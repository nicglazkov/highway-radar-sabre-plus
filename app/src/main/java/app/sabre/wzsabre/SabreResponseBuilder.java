package app.sabre.wzsabre;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the SABRE fetch-response JSON that Highway Radar deserializes into
 * SabreFetchResponse / SabreFetchResponseAlert (compatible with the wzsabre SABRE protocol).
 *
 * Extracted from SabreService so it can be exercised by JVM unit tests without
 * starting an Android service.
 */
public class SabreResponseBuilder {

    /**
     * SabreDiscoveryResponseSource IDs we declare in our HANDSHAKE.
     * alert_source MUST equal one of these; case-sensitive.
     * HR may use these IDs to look up source metadata (icon, color, etc.)
     * and crash with NPE/IOOB if the value is unknown.
     */
    public static final String SOURCE_CHP  = "chp";
    public static final String SOURCE_WAZE = "waze";
    public static final String SOURCE_LCS  = "lcs";

    /**
     * Highway Radar's package. Every reply broadcast is explicitly targeted at it
     * (Intent.setPackage) so the alert payload — a list of alerts centered on the
     * driver's location — is delivered only to HR and cannot be harvested by any
     * other app that registers a receiver for the (publicly known) response action.
     */
    public static final String HR_PACKAGE = "com.highwayradar.app";

    /**
     * heading_deg value for an alert with no known travel direction. The official
     * wzsabre sends -720.0 when an alert's magvar is unknown; HR treats this
     * out-of-range bearing as "directionless" and shows the alert regardless of
     * which way the driver is travelling. Sending 0.0 instead would claim the alert
     * faces due north, which can make HR direction-filter it away. CHP incidents
     * and LCS closures never carry a direction, so they use this.
     */
    public static final double HEADING_UNKNOWN = -720.0;

    /** Max alerts per response batch — matches the official wzsabre (200). */
    public static final int MAX_ALERTS_PER_BATCH = 200;

    // USER_ID_REGEX matches Waze alert IDs of the form "alert-<digits>/..."
    private static final Pattern USER_ID_PATTERN = Pattern.compile("alert-(\\d*)/.*");

    /**
     * Alert type strings HR's renderer accepts. This is the union of (a) the
     * canonical SABRE types our CHP/LCS sources emit and (b) the full Waze alert
     * type + subtype vocabulary, which the official wzsabre passes through raw to
     * the same HR app — so HR is known to handle every string here. An alert whose
     * type is outside this set can only be a bug in our own mapping, so {@link
     * #build} drops it rather than risk HR's renderer on an unexpected string.
     *
     * <p>The Waze entries are the exact enum names the official's ALERT_TYPE_NAMES /
     * ALERT_SUBTYPE_NAMES maps produce (see WazeRtCodec.typeName/subTypeName).
     */
    private static final java.util.Set<String> VALID_TYPES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    // ── canonical SABRE types emitted by the CHP and LCS sources ──
                    "POLICE_VISIBLE", "POLICE_HIDDEN",
                    "ACCIDENT_MAJOR", "ACCIDENT_MINOR",
                    "HAZARD_ON_ROAD_DEBRIS", "HAZARD_ON_ROAD_CONGESTION",
                    "HAZARD_ON_ROAD_SLIPPERY", "HAZARD_ON_ROAD_POT_HOLE",
                    "HAZARD_WEATHER_FOG", "HAZARD_WEATHER_RAIN", "HAZARD_WEATHER_SNOW",
                    "HAZARD_WEATHER_WIND", "HAZARD_WEATHER_STORM", "HAZARD_WEATHER_HAIL",
                    // ── Waze alert type names (subtype empty → type passed through) ──
                    "CHIT_CHAT", "POLICE", "ACCIDENT", "JAM", "TRAFFIC_INFO", "HAZARD",
                    "MISC", "CONSTRUCTION", "PARKING", "DYNAMIC", "CAMERA",
                    "ROAD_CLOSED", "SYSTEM_ROAD_CLOSED", "UNKNOWN_ALERT", "SOS",
                    "CRASH_PRONE", "TURN_CLOSED", "NEW_BAD_WEATHER", "NEW_LANE_CLOSED",
                    "PERMANENT_HAZARD", "PERSONAL_SAFETY", "UNKNOWN",
                    // ── Waze alert subtype names ──
                    "POLICE_HIDING", "POLICE_WITH_MOBILE_CAMERA",
                    "JAM_MODERATE_TRAFFIC", "JAM_HEAVY_TRAFFIC",
                    "JAM_STAND_STILL_TRAFFIC", "JAM_LIGHT_TRAFFIC",
                    "HAZARD_ON_ROAD", "HAZARD_ON_SHOULDER", "HAZARD_WEATHER",
                    "HAZARD_ON_ROAD_OBJECT", "HAZARD_ON_ROAD_ROAD_KILL",
                    "HAZARD_ON_SHOULDER_CAR_STOPPED", "HAZARD_ON_SHOULDER_ANIMALS",
                    "HAZARD_ON_SHOULDER_MISSING_SIGN",
                    "HAZARD_WEATHER_HEAVY_RAIN", "HAZARD_WEATHER_HEAVY_SNOW",
                    "HAZARD_WEATHER_FLOOD", "HAZARD_WEATHER_MONSOON",
                    "HAZARD_WEATHER_TORNADO", "HAZARD_WEATHER_HEAT_WAVE",
                    "HAZARD_WEATHER_HURRICANE", "HAZARD_WEATHER_FREEZING_RAIN",
                    "HAZARD_ON_ROAD_LANE_CLOSED", "HAZARD_ON_ROAD_OIL",
                    "HAZARD_ON_ROAD_ICE", "HAZARD_ON_ROAD_CONSTRUCTION",
                    "HAZARD_ON_ROAD_CAR_STOPPED", "HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT",
                    "HAZARD_ON_ROAD_EMERGENCY_VEHICLE",
                    "ROAD_CLOSED_HAZARD", "ROAD_CLOSED_CONSTRUCTION", "ROAD_CLOSED_EVENT",
                    "SOS_FLAT_TIRE", "SOS_NO_FUEL", "SOS_MEDICAL_HELP",
                    "SOS_MECHANICAL_PROBLEM", "SOS_OTHER", "SOS_BATTERY_ISSUE",
                    "CRASH_PRONE_SHORT_ALERT_LENGTH", "CRASH_PRONE_LONG_ALERT_LENGTH",
                    "TURN_CLOSED_EVENT", "BAD_WEATHER_DEFAULT", "BAD_WEATHER_SLIPPERY_ROAD",
                    "LANE_CLOSURE_BLOCKED_LANES", "LANE_CLOSURE_LEFT_LANE",
                    "LANE_CLOSURE_RIGHT_LANE", "LANE_CLOSURE_CENTER_LANE",
                    "PERMANENT_HAZARD_SPEED_BUMP", "PERMANENT_HAZARD_TOPES",
                    "PERMANENT_HAZARD_TOLL_BOOTH", "PERMANENT_HAZARD_DANGEROUS_CURVE",
                    "PERMANENT_HAZARD_DANGEROUS_INTERSECTION",
                    "PERMANENT_HAZARD_DANGEROUS_SPLIT", "PERMANENT_HAZARD_DANGEROUS_MERGE",
                    "PERMANENT_HAZARD_SCHOOL_ZONE",
                    "DEFAULT_PERSONAL_SAFETY", "DEFAULT_CAMERA"));

    public static boolean isValidType(String type) {
        return type != null && VALID_TYPES.contains(type);
    }

    /**
     * Build the full response JSON string for a FETCH_REQUEST.
     *
     * Schema (all fields required by HR's kotlinx.serialization):
     * <pre>
     * {
     *   "request_id": String,        // required, non-null
     *   "error_message": null,       // nullable String — present but null
     *   "response": {
     *     "n_batches": Int,           // required
     *     "batch_id":  Int,           // required
     *     "alerts": [                 // required list (may be empty)
     *       {
     *         "alert_source":  String,   // required; must be SOURCE_CHP or SOURCE_WAZE
     *         "alert_id":      String,   // required
     *         "user_id":       String,   // required, non-null (digits or "0")
     *         "type":          String,   // required SABRE type constant
     *         "lat":           Double,   // required
     *         "lon":           Double,   // required
     *         "heading_deg":   Double,   // required
     *         "street_name":   String?,  // nullable String — present (may be null)
     *         "report_ts":     Int,      // required; must fit in Int (not Long)
     *         "confirm_ts":    null,     // nullable Int — present but null
     *         "confirm_count": Int       // required; 0
     *       }
     *     ]
     *   }
     * }
     * </pre>
     */
    public static String build(String requestId, List<SabreAlert> alerts) throws JSONException {
        return build(requestId, alerts, 1, 0);
    }

    /**
     * Build one batch of a multi-batch response. HR's wire format carries
     * {@code n_batches} (total) and {@code batch_id} (0-based); the official
     * wzsabre splits a fetch response into batches of {@value #MAX_ALERTS_PER_BATCH}
     * alerts, each sent as a separate broadcast. {@code batchAlerts} is the slice
     * for this batch.
     */
    public static String build(String requestId, List<SabreAlert> batchAlerts,
                               int nBatches, int batchId) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("request_id",    requestId);
        root.put("error_message", JSONObject.NULL);

        JSONObject responseData = new JSONObject();
        responseData.put("n_batches", nBatches);
        responseData.put("batch_id",  batchId);
        List<SabreAlert> alerts = batchAlerts;

        // One malformed alert must never take down the whole response (HR would
        // get nothing and show "plugin not responding") — drop it and keep the rest.
        JSONArray alertsArray = new JSONArray();
        for (SabreAlert a : alerts) {
            if (a == null || !isValidType(a.type)) continue;
            try {
                alertsArray.put(buildAlert(a));
            } catch (RuntimeException ignored) {
                // invalid coords / report_ts — skip this alert only
            }
        }
        responseData.put("alerts", alertsArray);
        root.put("response", responseData);

        return root.toString();
    }

    /** Build one alert object. Validates types at construction time. */
    public static JSONObject buildAlert(SabreAlert a) throws JSONException {
        if (a.reportTs > Integer.MAX_VALUE || a.reportTs < 0) {
            throw new IllegalArgumentException(
                "report_ts " + a.reportTs + " does not fit in Int (max " + Integer.MAX_VALUE + ")");
        }
        if (Double.isNaN(a.lat) || Double.isInfinite(a.lat)) {
            throw new IllegalArgumentException("lat is NaN/Infinite for alert " + a.alertId);
        }
        if (Double.isNaN(a.lon) || Double.isInfinite(a.lon)) {
            throw new IllegalArgumentException("lon is NaN/Infinite for alert " + a.alertId);
        }

        JSONObject obj = new JSONObject();
        obj.put("alert_source",  a.alertSource);           // must be SOURCE_CHP or SOURCE_WAZE
        obj.put("alert_id",      a.alertId);
        obj.put("user_id",       extractUserId(a.alertId)); // required non-null String
        obj.put("type",          a.type);
        obj.put("lat",           a.lat);
        obj.put("lon",           a.lon);
        obj.put("heading_deg",   a.headingDeg);
        obj.put("street_name",   a.streetName != null ? a.streetName : JSONObject.NULL);
        obj.put("report_ts",     (int) a.reportTs);
        // confirm_ts is a nullable Int (epoch seconds); present but null unless the
        // alert has been crowd-confirmed and the timestamp fits in Int.
        boolean confirmTsFits = a.confirmTs != null
                && a.confirmTs >= 0 && a.confirmTs <= Integer.MAX_VALUE;
        obj.put("confirm_ts",    confirmTsFits ? (int) (long) a.confirmTs : JSONObject.NULL);
        obj.put("confirm_count", Math.max(0, a.confirmCount));
        return obj;
    }

    /**
     * Extracts a numeric user_id from a Waze alert ID.
     * Waze IDs look like "alert-1234567890/abcdef" → user_id = "1234567890".
     * Our alertId prefix is "waze_", stripped before matching.
     * Returns "0" if no numeric user ID found (CHP alerts, anonymous Waze alerts).
     */
    public static String extractUserId(String alertId) {
        if (alertId == null) return "0";
        String id = alertId.startsWith("waze_") ? alertId.substring(5) : alertId;
        Matcher m = USER_ID_PATTERN.matcher(id);
        if (m.find()) {
            String uid = m.group(1);
            return (uid != null && !uid.isEmpty()) ? uid : "0";
        }
        return "0";
    }
}
