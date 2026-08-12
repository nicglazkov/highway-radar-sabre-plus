package app.sabre.wzsabre;

import org.json.JSONException;
import org.json.JSONObject;

/** A confirm (thumbs-up) or discard (not-there) forwarded from HR. */
public final class ConfirmDiscardRequest {
    public final double lat, lon;
    public final String alertId;
    public final boolean test;

    private ConfirmDiscardRequest(double lat, double lon, String alertId, boolean test) {
        this.lat = lat; this.lon = lon; this.alertId = alertId; this.test = test;
    }

    public static ConfirmDiscardRequest fromJson(String data) throws JSONException {
        JSONObject o = new JSONObject(data);
        double lat = o.has("lat") ? o.getDouble("lat") : o.getDouble("latitude");
        double lon = o.has("lon") ? o.getDouble("lon") : o.getDouble("longitude");
        return new ConfirmDiscardRequest(lat, lon, o.optString("alert_id", null),
                o.optBoolean("test", false));
    }

    /** Numeric Waze alert id embedded in alertId ("alert-<id>/<uuid>"), or -1. */
    public long wazeAlertId() {
        if (alertId == null) return -1L;
        String s = alertId.startsWith("alert-") ? alertId.substring("alert-".length()) : alertId;
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1L; }
    }
}
