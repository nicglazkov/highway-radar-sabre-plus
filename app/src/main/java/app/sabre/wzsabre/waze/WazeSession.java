package app.sabre.wzsabre.waze;

import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Waze "RT" protocol session: mints an anonymous account (register), logs in,
 * and runs alert queries. Single-slot, synchronous (runs on the caller's worker
 * thread). Ported from wzsabre 2.2 wazemo.WazeSession (fetch path only — no
 * reporting/keepalive/pooling).
 *
 * No backend or pre-shared credentials are needed: register() asks Waze itself for
 * a fresh username/password. Credentials + device can be injected (persisted across
 * runs) so we don't re-register on every fetch — Waze caps anonymous accounts per day.
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
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty register response");

        WazeProto.Batch batch = WazeProto.Batch.parseFrom(r.body);
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
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty login response");

        WazeProto.Batch batch = WazeProto.Batch.parseFrom(r.body);
        for (WazeProto.Element el : batch.getElementList()) {
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
        if (r.body.length == 0) throw new WazeExceptions.WazeOperationException("empty command response");
        lastRequestMs = SystemClock.elapsedRealtime();
        return WazeProto.Batch.parseFrom(r.body);
    }

    /** Register (if no creds) and log in (if no valid session). */
    private void ensureReady(double lon, double lat) throws Exception {
        if (credentials == null) register(lon, lat);
        if (!sessionValid())     login(lon, lat);
    }

    /** Full flow: ensure ready, handshake, then query the area around (lat,lon). */
    List<WazeAlert> fetchArea(double lat, double lon, double radiusMeters) throws Exception {
        ensureReady(lon, lat);
        command(WazeRtCodec.handshakePayload(lon, lat));   // SeeMe / SetMood / Location / MapDisplayed

        double latDelta = radiusMeters / WazeConstants.M_PER_DEG_LAT;
        double lonDelta = radiusMeters / WazeConstants.mPerDegLon(lat);
        WazeProto.Batch batch = command(WazeRtCodec.mapDisplayedCommand(
                lon - lonDelta, lat - latDelta, lon + lonDelta, lat + latDelta));
        return WazeRtCodec.parseAlerts(batch);
    }
}
