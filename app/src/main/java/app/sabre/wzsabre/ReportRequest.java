package app.sabre.wzsabre;

import org.json.JSONException;
import org.json.JSONObject;

/** A user-created report forwarded from HR (app.sabre.wzsabre.REPORT). */
public final class ReportRequest {
    public final double lat, lon, headingDeg, altitudeM;
    public final String type;
    public final boolean isOpposite;
    public final int timeDeltaS;

    private ReportRequest(double lat, double lon, double headingDeg, double altitudeM,
                          String type, boolean isOpposite, int timeDeltaS) {
        this.lat = lat; this.lon = lon; this.headingDeg = headingDeg; this.altitudeM = altitudeM;
        this.type = type; this.isOpposite = isOpposite; this.timeDeltaS = timeDeltaS;
    }

    public static ReportRequest fromJson(String data) throws JSONException {
        JSONObject o = new JSONObject(data);
        double lat = o.has("lat") ? o.getDouble("lat") : o.getDouble("latitude");
        double lon = o.has("lon") ? o.getDouble("lon") : o.getDouble("longitude");
        String type = o.getString("type");
        return new ReportRequest(lat, lon,
                o.optDouble("heading_deg", -720.0),
                o.optDouble("altitude_m", 0.0),
                type,
                o.optBoolean("is_opposite", false),
                o.optInt("time_delta_s", 0));
    }
}
