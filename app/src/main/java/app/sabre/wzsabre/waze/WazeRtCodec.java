package app.sabre.wzsabre.waze;

import android.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Encodes/decodes the Waze "RT" protocol wire format on top of the generated
 * {@link WazeProto} protobuf classes. Ported from wzsabre 2.2 wazemo.WazeProto +
 * WazeProtocolHelpersKt. (Named *Codec to avoid clashing with the generated
 * WazeProto outer class.)
 *
 * Body framing: each protobuf "line" is {@code "ProtoBase64," + base64(Batch{element})}
 * with NO_WRAP base64; multiple lines are joined with '\n'. Raw command lines
 * (SeeMe / Location / MapDisplayed) are sent verbatim, not protobuf-wrapped.
 */
final class WazeRtCodec {
    private WazeRtCodec() {}

    private static String protoBase64Line(WazeProto.Element element) {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder().addElement(element).build();
        return "ProtoBase64," + Base64.encodeToString(batch.toByteArray(), Base64.NO_WRAP);
    }

    // ── Request line builders ────────────────────────────────────────────────

    static String buildClientInfoLine(DeviceIdentity device, double lon, double lat) {
        // Position is jittered by up to +/-500m before encoding (matches official).
        double lonOffset = ((Math.random() - 0.5) * 1000.0) / (Math.cos(Math.toRadians(lat)) * 111320.0);
        double latOffset = ((Math.random() - 0.5) * 1000.0) / 110574.0;

        WazeProto.Coordinate pos = WazeProto.Coordinate.newBuilder()
                .setLonTimes1000000((int) Math.round((lon + lonOffset) * 1_000_000.0))
                .setLatTimes1000000((int) Math.round((lat + latOffset) * 1_000_000.0))
                .build();

        WazeProto.Display display = WazeProto.Display.newBuilder()
                .setType(WazeProto.Display.Type.BUILT_IN)
                .setWidth(device.screenW)
                .setHeight(device.screenH)
                .build();

        WazeProto.ClientInfo ci = WazeProto.ClientInfo.newBuilder()
                .setProtocol(WazeConstants.PROTOCOL_VERSION)
                .setClientVersion(WazeConstants.APP_VERSION)
                .setLastPosition(pos)
                .setManufacturer(device.manufacturer)
                .setModel(device.model)
                .setOsVersion(device.osVersion)
                .setLocale("en")
                .setInstallationId(device.installationId)
                .setDeviceType(WazeProto.DeviceType.ANDROID_DEVICE)
                .setAppType(WazeProto.AppType.WAZE)
                .addDisplay(display)
                .setOsLanguageId("en")
                .setSessionUuid(UUID.randomUUID().toString())
                .setCurrentTimeMillis(System.currentTimeMillis())
                .setAppFlavor(WazeProto.AppFlavor.ALPHA)
                .build();

        return protoBase64Line(WazeProto.Element.newBuilder().setClientInfo(ci).build());
    }

    static String buildRegisterLine() {
        return protoBase64Line(WazeProto.Element.newBuilder()
                .setRegister(WazeProto.Register.getDefaultInstance()).build());
    }

    static String buildLoginLine(String community, String secret) {
        WazeProto.PasswordCredential cred = WazeProto.PasswordCredential.newBuilder()
                .setUsername(community)
                .setPassword(secret)
                .build();
        WazeProto.LoginRequest lr = WazeProto.LoginRequest.newBuilder()
                .setPasswordCredential(cred)
                .setReason(WazeProto.LoginRequest.LoginReason.NORMAL)
                .build();
        return protoBase64Line(WazeProto.Element.newBuilder().setLoginRequest(lr).build());
    }

    static String buildAdsLine() {
        return protoBase64Line(WazeProto.Element.newBuilder()
                .setReportAdsSetting(WazeProto.ReportAdsSettings.getDefaultInstance()).build());
    }

    /** uid request header = base64(UID{id=serverSessionId, secretKey=secretKey}). */
    static String buildUidHeader(WazeSessionInfo session) {
        WazeProto.UID uid = WazeProto.UID.newBuilder()
                .setId(session.serverSessionId)
                .setSecretKey(session.secretKey)
                .build();
        return Base64.encodeToString(uid.toByteArray(), Base64.NO_WRAP);
    }

    // ── Raw command-line builders (sent verbatim as the /command body) ───────

    private static String f6(double v) { return String.format(Locale.US, "%.6f", v); }

    /** bbox = [lonMin, latMin, lonMax, latMax]. */
    static String mapDisplayedCommand(double lonMin, double latMin, double lonMax, double latMax) {
        double midLon = (lonMin + lonMax) / 2.0;
        double midLat = (latMin + latMax) / 2.0;
        return "MapDisplayed,"
                + f6(lonMin) + "," + f6(latMax) + "," + f6(lonMax) + "," + f6(latMax) + ","
                + f6(lonMax) + "," + f6(latMin) + "," + f6(lonMin) + "," + f6(latMin) + ","
                + f6(midLon) + "," + f6(midLat) + ",67186,"
                + f6(lonMin) + "," + f6(latMax) + "," + f6(lonMax) + "," + f6(latMax) + ","
                + f6(lonMax) + "," + f6(latMin) + "," + f6(lonMin) + "," + f6(latMin);
    }

    static String seeMeCommand()  { return "SeeMe,1,2,T,T,T,1,-1,1,7"; }
    static String setMoodCommand(){ return "SetMood,1"; }
    static String locationCommand(double lon, double lat) {
        return "Location," + lon + "," + lat;
    }

    /** Handshake = SeeMe + SetMood + Location + MapDisplayed, newline-joined, one POST. */
    static String handshakePayload(double lon, double lat) {
        double[] b = circleToBox(lon, lat);
        return seeMeCommand() + "\n" + setMoodCommand() + "\n" + locationCommand(lon, lat)
                + "\n" + mapDisplayedCommand(b[0], b[1], b[2], b[3]);
    }

    /** Default ~0.018deg lon x 0.015deg lat box around a point (matches official). */
    static double[] circleToBox(double lon, double lat) {
        return new double[]{ lon - 0.018, lat - 0.015, lon + 0.018, lat + 0.015 };
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    static List<WazeAlert> parseAlerts(WazeProto.Batch batch) {
        List<WazeAlert> out = new ArrayList<>();
        for (WazeProto.Element el : batch.getElementList()) {
            if (!el.hasAddAlertAction()) continue;
            WazeProto.AddAlertAction aaa = el.getAddAlertAction();
            if (!aaa.hasRealtimeAlert()) continue;
            WazeProto.RealtimeAlert ra = aaa.getRealtimeAlert();
            if (!ra.hasAlertInfo()) continue;
            WazeProto.AlertInfo info = ra.getAlertInfo();
            if (!info.hasPosition()) continue;
            WazeProto.Coordinate c = info.getPosition();

            // Longitude is an unsigned-32-bit micro-degree value; map to signed range.
            long lonRaw = ((long) c.getLonTimes1000000()) & 0xFFFFFFFFL;
            if (lonRaw >= 0x80000000L) lonRaw -= 0x100000000L;
            double lon = lonRaw / 1_000_000.0;
            double lat = c.getLatTimes1000000() / 1_000_000.0;

            String type    = info.getType().name();
            String subtype = info.getSubType().name();
            int    magvar  = info.getAzymuth();

            long reportTime = 0L;
            Integer thumbs = null;
            String street = null, city = null;
            if (ra.hasAlertReportingInfo()) {
                WazeProto.AlertReportingInfo ri = ra.getAlertReportingInfo();
                reportTime = ri.getReportTime();
                if (ri.getThumbsUpCount() > 0) thumbs = ri.getThumbsUpCount();
                String s = ri.getAlertAddress().getStreet();
                String ci2 = ri.getAlertAddress().getCity();
                if (s != null && !s.isEmpty())  street = s;
                if (ci2 != null && !ci2.isEmpty()) city = ci2;
            }
            long pubMillis = reportTime > 0 ? reportTime * 1000L : System.currentTimeMillis();

            out.add(new WazeAlert(ra.getAlertUuid(), ra.getId(), type, subtype,
                    lon, lat, magvar, pubMillis, thumbs, street, city));
        }
        return out;
    }
}
