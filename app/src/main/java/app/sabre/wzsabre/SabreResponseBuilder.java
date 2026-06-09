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

    // USER_ID_REGEX matches Waze alert IDs of the form "alert-<digits>/..."
    private static final Pattern USER_ID_PATTERN = Pattern.compile("alert-(\\d*)/.*");

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
        JSONObject root = new JSONObject();
        root.put("request_id",    requestId);
        root.put("error_message", JSONObject.NULL);

        JSONObject responseData = new JSONObject();
        responseData.put("n_batches", 1);
        responseData.put("batch_id",  0);

        JSONArray alertsArray = new JSONArray();
        for (SabreAlert a : alerts) {
            alertsArray.put(buildAlert(a));
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
        obj.put("confirm_ts",    JSONObject.NULL);          // nullable Int
        obj.put("confirm_count", 0);
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
