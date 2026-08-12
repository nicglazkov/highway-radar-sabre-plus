package app.sabre.wzsabre.waze;

import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One Waze "RT" protocol session: mints an anonymous account (register), logs in,
 * and runs alert queries. Single-slot, synchronous (runs on the caller's worker
 * thread). Ported from wzsabre 2.2 wazemo.WazeSession (fetch path only: no
 * reporting/keepalive/pooling).
 *
 * No backend or pre-shared credentials are needed: register() asks Waze itself for
 * a fresh username/password. Credentials + device can be injected (persisted across
 * runs) so we don't re-register on every fetch, Waze caps anonymous accounts per day.
 */
final class WazeSession {
    private static final String TAG = "WazeRT";

    private final String region;
    private final DeviceIdentity device;
    private final WazeHttpClient http;

    private WazeCredentials credentials;   // from register() or injected
    private WazeSessionInfo session;       // from login()
    private int  seqCount = 1;
    private long lastRequestMs = 0L;

    WazeSession(String region) {
        this(region, DeviceIdentity.random(), null);
    }

    WazeSession(String region, DeviceIdentity device, WazeCredentials credentials) {
        this.region = region;
        this.device = device;
        this.credentials = credentials;
        this.http = new WazeHttpClient();
    }

    WazeCredentials getCredentials() { return credentials; }
    DeviceIdentity  getDevice()      { return device; }

    /** Server session id (0 if not logged in), lets the caller detect a re-login. */
    long currentServerSessionId() { return session != null ? session.serverSessionId : 0L; }

    /** Drop the logged-in session but KEEP credentials, so the next call re-logs in
     *  (with the in-memory account) instead of registering a brand-new one. */
    void invalidateSession() { session = null; }

    /**
     * Inspect a parsed response batch for in-band errors the server returns with an
     * HTTP 200: a {@code ServerError} element, or a {@code LoginError}. Without this,
     * a server that invalidates the session but replies 200 looks like success and
     * the session zombies: {@code lastRequestMs} keeps updating so it never idles
     * out, and no alerts are ever merged again. Mirrors the official's checkErrors.
     */
    static void checkErrors(WazeProto.Batch batch)
            throws WazeExceptions.AccountRejectedException,
                   WazeExceptions.SessionExpiredException,
                   WazeExceptions.WazeOperationException {
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasError()) {
                WazeProto.ServerError err = el.getError();
                int code = err.getCode();
                String desc = err.getDescription().toLowerCase(Locale.US);
                if (desc.contains("relogin") || desc.contains("unknown userid")
                        || desc.contains("secretkey missing") || desc.contains("secret key missing"))
                    throw new WazeExceptions.SessionExpiredException(
                            "server error: " + err.getDescription());
                if (code >= 400 && code < 500)
                    throw new WazeExceptions.AccountRejectedException(
                            "server error " + code + ": " + err.getDescription());
                if (code >= 500)
                    throw new WazeExceptions.WazeOperationException(
                            "server error " + code + ": " + err.getDescription());
                // Informational / unknown (incl. the proto default code 0), the
                // official ignores these; a normal 200 batch can legitimately carry
                // one, so do NOT fail the whole refresh over it.
                Log.w(TAG, "Ignoring non-fatal server error " + code + ": " + err.getDescription());
            }
            if (el.hasLoginError()) throwForLoginError(el.getLoginError().getErrorType());
        }
    }

    /**
     * Map a Waze auth-error type to an exception. Transient server-side problems
     * (INTERNAL_ISSUES / UNKNOWN) are operational, they must NOT nuke a good account
     * by triggering a re-register; only genuine credential/authorization failures do.
     */
    private static void throwForLoginError(WazeProto.LoginError.AuthErrorType t)
            throws WazeExceptions.AccountRejectedException, WazeExceptions.WazeOperationException {
        if (t == WazeProto.LoginError.AuthErrorType.INTERNAL_ISSUES
                || t == WazeProto.LoginError.AuthErrorType.UNKNOWN_ERROR)
            throw new WazeExceptions.WazeOperationException("login error: " + t);
        throw new WazeExceptions.AccountRejectedException("login error: " + t);
    }

    private String url(String path) { return "https://" + WazeConstants.rtHost(region) + path; }
    private String nextSeq() { return String.valueOf(seqCount++); }

    private boolean sessionValid() {
        return session != null
                && (SystemClock.elapsedRealtime() - lastRequestMs) < WazeConstants.SESSION_IDLE_TIMEOUT_MS;
    }

    // ── register ─────────────────────────────────────────────────────────────

    void register(double lon, double lat) throws Exception {
        String body = WazeRtCodec.buildClientInfoLine(device, lon, lat)
                + "\n" + WazeRtCodec.buildRegisterLine();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WazeConstants.APP_VERSION);          // bare "5.17.1.0"
        headers.put("x-waze-network-version", "3");
        headers.put("sequence-number", nextSeq());

        WazeHttpClient.HttpResult r = http.post(url(WazeConstants.PATH_STATIC),
                body.getBytes(StandardCharsets.UTF_8), headers);
        if (r.code >= 400) throw new WazeExceptions.WazeOperationException("register HTTP " + r.code);
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty register response");

        WazeProto.Batch batch = WazeProto.Batch.parseFrom(r.body);
        checkErrors(batch);
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasRegisterSuccessful()) {
                WazeProto.RegisterSuccessful rs = el.getRegisterSuccessful();
                String user = rs.getUsername(), pass = rs.getPassword();
                if (user == null || user.isEmpty()) throw new WazeExceptions.WazeOperationException("empty community from register");
                if (pass == null || pass.isEmpty()) throw new WazeExceptions.WazeOperationException("empty secret from register");
                credentials = new WazeCredentials(user, pass);
                Log.d(TAG, "Registered anonymous account: " + user);
                return;
            }
        }
        throw new WazeExceptions.WazeOperationException("register: no RegisterSuccessful element (" + r.body.length + "B)");
    }

    // ── login ────────────────────────────────────────────────────────────────

    void login(double lon, double lat) throws Exception {
        if (credentials == null) throw new WazeExceptions.WazeOperationException("login before register");
        http.clearCookies();

        String body = WazeRtCodec.buildClientInfoLine(device, lon, lat)
                + "\n" + WazeRtCodec.buildLoginLine(credentials.community, credentials.secret)
                + "\n" + WazeRtCodec.buildAdsLine();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "waze/" + WazeConstants.APP_VERSION);  // login uses the "waze/" prefix
        headers.put("cache-control", "no-cache");
        headers.put("sequence-number", nextSeq());
        headers.put("x-waze-network-version", "3");
        headers.put("x-waze-wait-timeout", WazeConstants.WAIT_TIMEOUT_LOGIN);

        WazeHttpClient.HttpResult r = http.post(url(WazeConstants.PATH_LOGIN),
                body.getBytes(StandardCharsets.UTF_8), headers);
        // A 4xx here means Waze no longer accepts these credentials (anonymous
        // accounts get purged): classify as AccountRejected so the caller clears
        // the persisted account and re-registers instead of failing forever.
        if (r.code >= 400 && r.code < 500)
            throw new WazeExceptions.AccountRejectedException("login HTTP " + r.code);
        if (r.code >= 500) throw new WazeExceptions.WazeOperationException("login HTTP " + r.code);
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty login response");

        WazeProto.Batch batch = WazeProto.Batch.parseFrom(r.body);
        checkErrors(batch);   // in-band LoginError → specific exception, not blanket reject
        for (WazeProto.Element el : batch.getElementList()) {
            // A login failure can arrive nested in the LoginResponse oneof (not just
            // as a top-level LoginError element), classify it too, so a transient
            // INTERNAL_ISSUES doesn't fall through to the blanket AccountRejected
            // below and needlessly re-register.
            if (el.hasLoginResponse() && el.getLoginResponse().hasLoginError())
                throwForLoginError(el.getLoginResponse().getLoginError().getErrorType());
            if (el.hasLoginResponse() && el.getLoginResponse().hasLoginSuccess()) {
                WazeProto.LoginSuccess s = el.getLoginResponse().getLoginSuccess();
                if (s.getServerSessionId() == 0) throw new WazeExceptions.WazeOperationException("zero serverSessionId");
                if (s.getSecretKey() == null || s.getSecretKey().isEmpty()) throw new WazeExceptions.WazeOperationException("empty secretKey");
                session = new WazeSessionInfo(s.getServerSessionId(), s.getSecretKey(),
                        String.valueOf(s.getGlobalUserId()));
                seqCount = 2;
                lastRequestMs = SystemClock.elapsedRealtime();
                Log.d(TAG, "Login OK: sessionId=" + s.getServerSessionId());
                return;
            }
        }
        throw new WazeExceptions.AccountRejectedException("login: no LoginSuccess element (" + r.body.length + "B)");
    }

    // ── command + query ──────────────────────────────────────────────────────

    private WazeProto.Batch command(String payload) throws Exception {
        if (session == null) throw new WazeExceptions.SessionExpiredException("command before login");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", WazeConstants.APP_VERSION);            // bare "5.17.1.0"
        headers.put("cache-control", "no-cache");
        headers.put("sequence-number", nextSeq());
        headers.put("x-waze-network-version", "3");
        headers.put("x-waze-wait-timeout", WazeConstants.WAIT_TIMEOUT_COMMAND);
        headers.put("uid", WazeRtCodec.buildUidHeader(session));

        WazeHttpClient.HttpResult r = http.post(url(WazeConstants.PATH_COMMAND),
                payload.getBytes(StandardCharsets.UTF_8), headers);
        // 4xx on a command = the server no longer honors this session → re-login.
        if (r.code >= 400 && r.code < 500) {
            session = null;
            throw new WazeExceptions.SessionExpiredException("command HTTP " + r.code);
        }
        if (r.code >= 500) throw new WazeExceptions.WazeOperationException("command HTTP " + r.code);
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty command response");
        WazeProto.Batch batch = WazeProto.Batch.parseFrom(r.body);
        // Check for an in-band error BEFORE marking the session healthy, so a zombie
        // session (HTTP 200 + "please relogin") doesn't keep refreshing lastRequestMs.
        try {
            checkErrors(batch);
        } catch (WazeExceptions.SessionExpiredException e) {
            session = null;   // force ensureReady to re-login next time
            throw e;
        }
        lastRequestMs = SystemClock.elapsedRealtime();
        return batch;
    }

    /** Register (if no creds) and log in (if no valid session). */
    private void ensureReady(double lon, double lat) throws Exception {
        if (credentials == null) register(lon, lat);
        if (!sessionValid())     login(lon, lat);
    }

    /**
     * Register (if no credentials) + log in (if no valid session) + run the
     * SeeMe/SetMood/Location/MapDisplayed handshake. Call once before a run of
     * {@link #queryBox} calls: mirrors the official, which handshakes per login,
     * not per query.
     */
    void prepareForArea(double lat, double lon) throws Exception {
        ensureReady(lon, lat);
        command(WazeRtCodec.handshakePayload(lon, lat));
    }

    /**
     * One MapDisplayed query for a bbox {@code [lonMin, latMin, lonMax, latMax]};
     * returns the raw batch so the caller can parse both added alerts and removed
     * ids from it (the RT response carries both).
     */
    WazeProto.Batch queryBox(double[] bbox) throws Exception {
        return command(WazeRtCodec.mapDisplayedCommand(bbox[0], bbox[1], bbox[2], bbox[3]));
    }

    /**
     * Submit a user report to Waze via the full road-snap command sequence
     * (recon D): handshake, an initial SeeMe+Location+MapDisplayed, a tile
     * fetch + nearest-segment snap, an At position update carrying the
     * (possibly absent) directional SegmentNodes, a short simulated-driving
     * camouflage sequence (only when a segment matched), the actual report,
     * and a trailing SeeMe close-out. Only the report POST's response batch
     * is parsed for the result; the rest are best-effort / fire-and-forget,
     * mirroring the official client.
     */
    ReportResult submitReport(app.sabre.wzsabre.ReportRequest r, long nowMs) throws Exception {
        prepareForArea(r.lat, r.lon);

        // Command A: SeeMe + Location + MapDisplayed around the report point.
        double[] boxA = WazeRtCodec.circleToBox(r.lon, r.lat);
        command(WazeRtCodec.seeMeCommand(1) + "\n" + WazeRtCodec.locationCommand(r.lon, r.lat)
                + "\n" + WazeRtCodec.mapDisplayedCommand(boxA[0], boxA[1], boxA[2], boxA[3]));

        List<RoadSegment> segs = fetchTileSegments(r.lat, r.lon);
        int heading = normalizeAngle360(Math.round(r.headingDeg));
        SegmentMatch m = RoadGeo.findMatchingSegment(
                new LatLon(r.lat, r.lon), r.headingDeg, segs, 15.0, 50.0);
        if (m != null) {
            Log.d(TAG, "Snapped to road " + m.segment.segmentId + (m.reverse ? " REV" : " FWD"));
        } else {
            Log.d(TAG, "No road match (" + segs.size() + " candidates)");
        }
        long fromNode = m != null ? m.fromNodeDirectional() : -1;
        long toNode   = m != null ? m.toNodeDirectional()   : -1;

        // Command B: At position update carrying the (possibly absent) snap result.
        double[] boxB = WazeRtCodec.circleToBox(r.lon, r.lat);
        command(WazeRtCodec.atCommand(r.lon, r.lat, heading, fromNode, toNode) + "\n"
                + WazeRtCodec.mapDisplayedCommand(boxB[0], boxB[1], boxB[2], boxB[3]));

        if (m != null) {
            simulateDriving(r.lat, r.lon, heading, fromNode, toNode);
        }

        // Command C: the actual report. SegmentNodes omitted (0/0) when no match.
        WazeProto.AddUserReportedAlertRequest req = WazeReportCodec.buildRequest(
                r, nowMs, m != null ? fromNode : 0, m != null ? toNode : 0);
        if (req == null) return ReportResult.fail("unreportable type: " + r.type);
        WazeProto.Batch batch = command(WazeRtCodec.reportPayload(req));

        // Command D: trailing SeeMe close-out (anti-abuse camouflage). Response
        // discarded; a failure here must not turn an already-accepted report into
        // a failure.
        try {
            command(WazeRtCodec.seeMeCommand(2));
        } catch (Exception ignore) {
            // best-effort, matches recon D step 9
        }

        String uuid = WazeReportCodec.reportUuidFrom(batch);
        int points = WazeReportCodec.reportPointsFrom(batch);
        if (uuid != null) {
            Log.d(TAG, "Report accepted: uuid=" + uuid + " pts=" + points);
            return ReportResult.ok(uuid, points);
        }
        Log.w(TAG, "No report response in batch");
        return ReportResult.fail("no report response");
    }

    /** Normalize a heading in degrees to [0, 360). */
    private static int normalizeAngle360(long deg) {
        int d = (int) (deg % 360);
        if (d < 0) d += 360;
        return d;
    }

    /**
     * Best-effort "driving" camouflage sent after the At update (recon D step 6):
     * three ~1s steps along {@code heading} at Waze's simulated driving speed
     * (13.4 m/s), each POSTing an At + MapDisplayed pair for the stepped
     * position. Mirrors the official client's simulateDriving; anti-abuse
     * camouflage, so it must run (not be skippable) whenever a segment matched,
     * but a failed step (network or sleep) must never abort the report itself.
     */
    private void simulateDriving(double lat, double lon, int heading, long fromNode, long toNode) {
        double rad = Math.toRadians(heading);
        for (int i = 0; i < 3; i++) {
            lat += (Math.cos(rad) * 13.4) / WazeConstants.M_PER_DEG_LAT;
            lon += (Math.sin(rad) * 13.4) / WazeConstants.mPerDegLon(lat);
            try {
                double[] box = WazeRtCodec.circleToBox(lon, lat);
                command(WazeRtCodec.atCommand(lon, lat, heading, fromNode, toNode) + "\n"
                        + WazeRtCodec.mapDisplayedCommand(box[0], box[1], box[2], box[3]));
            } catch (Exception e) {
                Log.w(TAG, "simulateDriving step " + i + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * GETs and decodes the WZDF road-graph tile covering (lat,lon), for road-snap
     * (Task R5). Requires a live session (uses {@code session.serverSessionId}/
     * {@code session.secretKey} in the tile URL, matching how the official
     * authenticates the tile GET). Best-effort: any failure (no session, HTTP
     * error, empty body, decode error) returns an empty list rather than throwing,
     * so a snap failure degrades to a position-only report instead of aborting it.
     */
    List<RoadSegment> fetchTileSegments(double lat, double lon) {
        if (session == null) {
            Log.w(TAG, "fetchTileSegments: no live session");
            return new ArrayList<>();
        }
        try {
            String tileHost = WazeConstants.tileHost(WazeProtocolSource.region(lat, lon));
            int tileId = WazeTileCodec.coordToTileId(lon, lat);
            String url = WazeTileCodec.buildTileUrl(tileHost, session.serverSessionId, session.secretKey, tileId);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", WazeConstants.APP_VERSION);   // bare "5.17.1.0"
            headers.put("if-modified-since", "Thu, 01 Jan 1970 00:00:00 GMT");

            WazeHttpClient.HttpResult r = http.get(url, headers);
            if (r.code >= 400 || r.body.length == 0) {
                Log.w(TAG, "fetchTileSegments: tile GET HTTP " + r.code + " (" + r.body.length + "B)");
                return new ArrayList<>();
            }
            return WazeTileParser.parse(r.body);
        } catch (Exception e) {
            Log.w(TAG, "fetchTileSegments failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Thumbs-up an existing Waze alert by its numeric id. */
    void confirmAlert(long id) throws Exception {
        prepareForArealess();
        command("ThumbsUp," + id);
        Log.d(TAG, "Confirmed alert " + id);
    }

    /** Remove/deny an existing Waze alert by its numeric id ("not there"). */
    void discardAlert(long id) throws Exception {
        prepareForArealess();
        command("ReportRmAlert," + id);
        Log.d(TAG, "Discarded alert " + id);
    }

    /** Ensure logged in for a bare command that needs no MapDisplayed handshake. */
    private void prepareForArealess() throws Exception {
        // confirm/discard carry lat/lon in the HR payload; callers that have them
        // should use prepareForArea. When absent, ensure the session is at least
        // registered+logged-in using the last known position is not available here,
        // so require the caller to have prepared. If no session, this throws, and the
        // caller (WazeReporter) will have called prepareForArea first.
        if (session == null) throw new WazeExceptions.SessionExpiredException("confirm/discard before login");
    }

    /** Full flow for a single box: prepare + one query. Used by the debug selfTest. */
    List<WazeAlert> fetchArea(double lat, double lon, double radiusMeters) throws Exception {
        prepareForArea(lat, lon);
        double latDelta = radiusMeters / WazeConstants.M_PER_DEG_LAT;
        double lonDelta = radiusMeters / WazeConstants.mPerDegLon(lat);
        WazeProto.Batch batch = queryBox(new double[]{
                lon - lonDelta, lat - latDelta, lon + lonDelta, lat + latDelta});
        return WazeRtCodec.parseAlerts(batch);
    }
}
