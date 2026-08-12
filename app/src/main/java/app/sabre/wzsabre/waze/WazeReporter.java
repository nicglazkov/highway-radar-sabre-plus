package app.sabre.wzsabre.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import app.sabre.wzsabre.ReportRequest;

/**
 * Owns a Waze RT session for the WRITE path (report/confirm/discard). Reuses the
 * SAME persisted anonymous account that {@link WazeProtocolSource} mints for the
 * read path (same SharedPreferences file/keys), so reporting doesn't register an
 * extra account, Waze caps anonymous accounts per day.
 *
 * <p>This is a READ-ONLY consumer of that shared account: it never registers its
 * own account (only {@link WazeProtocolSource}'s {@code canRegisterToday()}/
 * {@code recordRegistration()} accounting is allowed to mint one) and never deletes
 * the shared {@code community}/{@code secret} credentials (deleting them here would
 * force the read path to re-register too). If no account has been minted yet,
 * {@link #ensureSession} fails and the report is not sent to Waze; the HR echo pin
 * still shows via {@link app.sabre.wzsabre.UserReportStore}, and once the read path
 * mints an account on its next fetch, subsequent reports succeed.
 *
 * <p>This runs a separate {@link WazeSession} from the read path but shares the
 * account. A login on one session can log the other out server-side; both sessions
 * re-login on {@code SessionExpiredException}, so this self-heals. Not redesigned
 * to share a single session (see Task 8 brief).
 */
public final class WazeReporter {
    private static final String TAG = "WazeRT";
    private static final String PREFS = "waze_rt"; // must match WazeProtocolSource

    private final Context ctx;
    private WazeSession session;

    public WazeReporter(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Submit a new report. Runs on the caller's worker thread. Returns accepted. */
    public synchronized boolean submit(ReportRequest r) {
        try {
            WazeSession s = ensureSession(r.lat, r.lon);
            ReportResult res = s.submitReport(r, System.currentTimeMillis());
            if (!res.accepted) Log.w(TAG, "Report not accepted: " + res.error);
            return res.accepted;
        } catch (Exception e) {
            Log.w(TAG, "submit failed: " + e.getMessage());
            invalidateOnAuthError(e);
            return false;
        }
    }

    /** Thumbs-up an existing Waze alert. */
    public synchronized void confirm(double lat, double lon, long wazeId) {
        if (wazeId < 0) return;
        try {
            WazeSession s = ensureSession(lat, lon);
            s.prepareForArea(lat, lon);
            s.confirmAlert(wazeId);
        } catch (Exception e) {
            Log.w(TAG, "confirm failed: " + e.getMessage());
            invalidateOnAuthError(e);
        }
    }

    /** Report an existing Waze alert as not there. */
    public synchronized void discard(double lat, double lon, long wazeId) {
        if (wazeId < 0) return;
        try {
            WazeSession s = ensureSession(lat, lon);
            s.prepareForArea(lat, lon);
            s.discardAlert(wazeId);
        } catch (Exception e) {
            Log.w(TAG, "discard failed: " + e.getMessage());
            invalidateOnAuthError(e);
        }
    }

    private WazeSession ensureSession(double lat, double lon) throws Exception {
        if (session != null) return session;
        String region = WazeProtocolSource.region(lat, lon);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String community = p.getString("community", null);
        String secret = p.getString("secret", null);
        if (community == null || secret == null) {
            // No shared account minted yet. The write path must never register one
            // itself (only WazeProtocolSource's canRegisterToday()/recordRegistration()
            // accounting is allowed to do that) - so fail gracefully. The HR echo pin
            // still shows; the read path mints the account on its next fetch, after
            // which reports succeed.
            throw new WazeExceptions.WazeOperationException(
                    "no shared Waze account yet; report not sent to Waze");
        }
        WazeCredentials creds = new WazeCredentials(community, secret);
        DeviceIdentity dev = new DeviceIdentity(
                p.getString("dev_mfr", "Google"), p.getString("dev_model", "Pixel 8"),
                p.getString("dev_os", "15-SDK35"),
                p.getInt("dev_w", 1080), p.getInt("dev_h", 2400),
                p.getString("dev_iid", java.util.UUID.randomUUID().toString()));
        WazeSession s = new WazeSession(region, dev, creds);
        s.prepareForArea(lat, lon); // creds are non-null, so this only logs in + handshake, never registers
        session = s;
        return s;
    }

    private void invalidateOnAuthError(Exception e) {
        if (e instanceof WazeExceptions.AccountRejectedException) {
            // Drop only this reporter's in-memory session, not the shared credentials:
            // WazeProtocolSource (the read path) owns the shared account's lifecycle, so
            // deleting community/secret here would force the read path to re-register too.
            session = null;
        } else if (e instanceof WazeExceptions.SessionExpiredException && session != null) {
            session.invalidateSession();
        }
    }

    /** DEBUG-only: exercise the write path end to end. Logcat tag WazeRT. */
    public static void selfTest(Context ctx, double lat, double lon, String type) {
        try {
            ReportRequest r = ReportRequest.fromJson(
                "{\"lat\":" + lat + ",\"lon\":" + lon + ",\"type\":\"" + type + "\"}");
            boolean ok = new WazeReporter(ctx).submit(r);
            Log.d(TAG, "selfTest report accepted=" + ok);
        } catch (Exception e) { Log.w(TAG, "selfTest failed: " + e.getMessage()); }
    }
}
