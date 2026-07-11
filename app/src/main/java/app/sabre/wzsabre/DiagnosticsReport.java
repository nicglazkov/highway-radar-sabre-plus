package app.sabre.wzsabre;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the user-facing, privacy-safe "Share diagnostics" text report. The report
 * is assembled from four independently toggleable sections (see {@link #SECTION_LABELS})
 * so the user decides exactly what leaves their device. Every section contains only
 * versions, settings, counts, category names, and error strings: no location, street
 * names, alert IDs, or personal data is ever included.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {}

    /**
     * The four categories the user can individually include or exclude. Index order
     * matches the booleans passed to {@link #build}.
     */
    public static final String[] SECTION_LABELS = {
        "App, device and settings (versions, toggles, no personal data)",
        "Highway Radar (its version, and whether it is requesting data)",
        "Alert counts and types (numbers and category names only)",
        "Activity and crashes (recent plugin events, never any location)"
    };

    private static final String HR_PKG = "com.highwayradar.app";
    private static final String[] SOURCES = {
        SabreResponseBuilder.SOURCE_CHP, SabreResponseBuilder.SOURCE_WAZE,
        SabreResponseBuilder.SOURCE_LCS, SabreResponseBuilder.SOURCE_FIRE,
        SabreResponseBuilder.SOURCE_CHAINS
    };

    public static String build(Context ctx, boolean appDevice, boolean hr,
                               boolean sources, boolean activity) {
        StringBuilder sb = new StringBuilder();
        sb.append("SABRE Plus diagnostics\n");
        sb.append("Contains no location, street names, or personal data.\n");

        if (appDevice) appDeviceSection(ctx, sb);
        if (hr)        highwayRadarSection(ctx, sb);
        if (sources)   sourcesSection(sb);
        if (activity)  activitySection(ctx, sb);

        return sb.toString();
    }

    private static void appDeviceSection(Context ctx, StringBuilder sb) {
        sb.append("\n[App]\n");
        sb.append("SABRE Plus ").append(BuildConfig.VERSION_NAME)
          .append(" (build ").append(BuildConfig.VERSION_CODE)
          .append(", ").append(BuildConfig.DEBUG ? "debug" : "release").append(")\n");
        sb.append("Package: ").append(ctx.getPackageName()).append('\n');
        long up = App.PROCESS_START_MS > 0 ? System.currentTimeMillis() - App.PROCESS_START_MS : -1;
        sb.append("App process uptime: ").append(up < 0 ? "unknown" : fmtDur(up)).append('\n');
        sb.append("Notifications enabled: ").append(bool(notificationsEnabled(ctx))).append('\n');
        sb.append("Battery-optimization exempt: ").append(bool(batteryExempt(ctx))).append('\n');

        sb.append("\n[Device]\n");
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("ABIs: ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("Locale: ").append(Locale.getDefault())
          .append(", timezone: ").append(TimeZone.getDefault().getID()).append('\n');

        settingsSection(ctx, sb);
    }

    private static void settingsSection(Context ctx, StringBuilder sb) {
        sb.append("\n[Settings]\n");
        try {
            ChpConfig cfg = ChpConfig.load(ctx);
            sb.append("Sources: Caltrans closures ").append(onOff(cfg.lcsEnabled))
              .append(", wildfires ").append(onOff(cfg.fireEnabled))
              .append(", chain controls ").append(onOff(cfg.chainsEnabled)).append('\n');
            sb.append("Update notifications: ").append(onOff(cfg.updateNotifyEnabled)).append('\n');
            sb.append("Wildfire minimum size: ").append(cfg.fireMinAcres).append("+ acres\n");
            sb.append("Incident age filter: ").append(cfg.maxAgeMinutes).append(" min\n");
            sb.append("Waze categories: police ").append(onOff(cfg.wazePolice))
              .append(", accidents ").append(onOff(cfg.wazeAccidents))
              .append(", hazards ").append(onOff(cfg.wazeHazards))
              .append(", jams ").append(onOff(cfg.wazeJams))
              .append(", closures ").append(onOff(cfg.wazeClosures)).append('\n');
            sb.append("CHP categories:\n");
            for (ChpCategory cat : ChpCategory.values()) {
                String override = cfg.getTypeOverride(cat);
                sb.append("  ").append(cat.name()).append(": ").append(onOff(cfg.isEnabled(cat)));
                if (override != null && !override.isEmpty()) sb.append(", shows as ").append(override);
                sb.append('\n');
            }
        } catch (Exception e) {
            sb.append("(settings unavailable: ").append(e.getClass().getSimpleName()).append(")\n");
        }
    }

    private static void highwayRadarSection(Context ctx, StringBuilder sb) {
        sb.append("\n[Highway Radar]\n");
        sb.append("Installed: ").append(hrVersion(ctx)).append('\n');
        sb.append("Plugin service running: ").append(bool(SabreService.RUNNING)).append('\n');
        long age = DebugLog.lastFetchAgeMs();
        sb.append("Last request from Highway Radar: ")
          .append(age < 0 ? "none this session" : fmtAge(age)).append('\n');
        sb.append("Requests this session: ").append(DebugLog.sessionFetchCount()).append('\n');
        long interval = DebugLog.lastFetchIntervalMs();
        sb.append("Recent request interval: ")
          .append(interval <= 0 ? "n/a" : (interval / 1000) + "s").append('\n');
        String fa = DebugLog.lastFetchAction();
        sb.append("Fetch action HR uses: ").append(fa == null ? "none received" : fa).append('\n');
        sb.append("Handshakes (discovery) this session: ").append(DebugLog.handshakeCount()).append('\n');
        sb.append("HR registration: ");
        if (DebugLog.sessionFetchCount() == 0 && DebugLog.handshakeCount() == 0) {
            sb.append("HR has not contacted the plugin yet this session\n");
        } else if (DebugLog.handshakeCount() > 0) {
            sb.append("HR re-detected SABRE Plus (registration is current)\n");
        } else {
            sb.append("HR is using a cached registration from a previously-installed plugin "
                    + "(it may still display an old name like \"WzSabre\"). Data still flows; "
                    + "fully restart Highway Radar to refresh the name.\n");
        }
        sb.append("We advertise to HR: id=app.sabre.wzsabre, version=").append(BuildConfig.VERSION_NAME)
          .append(", request_action=app.sabre.wzsabre.REQUEST, sources=[chp,waze,lcs,fire,chains]\n");
    }

    private static void sourcesSection(StringBuilder sb) {
        sb.append("\n[Alert sources]\n");
        for (String s : SOURCES) {
            SourceStatus.Entry e = SourceStatus.get(s);
            sb.append("  ").append(s).append(": ");
            if (e == null) {
                sb.append("no data yet\n");
            } else if (e.lastError != null) {
                sb.append(e.count).append(" items, ERROR: ").append(e.lastError).append('\n');
            } else {
                sb.append(e.count).append(" items");
                if (e.lastUpdateMs > 0) sb.append(", ok ").append(fmtAge(System.currentTimeMillis() - e.lastUpdateMs));
                sb.append('\n');
            }
        }
        String summary = DebugLog.lastFetchSummary();
        if (summary != null) sb.append("Last fetch: ").append(summary).append('\n');
        Map<String, Integer> types = DebugLog.lastFetchTypes();
        if (types.isEmpty()) {
            sb.append("Alert types sent to Highway Radar: (none captured yet)\n");
        } else {
            sb.append("Alert types sent to Highway Radar (category names only):\n");
            for (Map.Entry<String, Integer> t : types.entrySet()) {
                sb.append("  ").append(t.getKey()).append(" x").append(t.getValue()).append('\n');
            }
        }
    }

    private static void activitySection(Context ctx, StringBuilder sb) {
        sb.append("\n[Recent activity]\n");
        List<String> events = DebugLog.recentEvents();
        if (events.isEmpty()) {
            sb.append("  (none yet)\n");
        } else {
            for (String line : events) sb.append(line).append('\n');
        }
        String[] crash = CrashLog.readSummary(ctx);
        sb.append("\n[Last crash]\n");
        if (crash == null) {
            sb.append("none\n");
        } else {
            sb.append(crash[0]).append('\n').append(crash[1]).append('\n');
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String hrVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(HR_PKG, 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
            return "yes, v" + pi.versionName + " (build " + code + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    private static boolean notificationsEnabled(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) { return false; }
    }

    private static boolean batteryExempt(Context ctx) {
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) { return false; }
    }

    private static String bool(boolean b)  { return b ? "yes" : "no"; }
    private static String onOff(boolean b) { return b ? "on" : "off"; }

    private static String join(String[] arr) {
        if (arr == null || arr.length == 0) return "?";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < arr.length; i++) { if (i > 0) b.append(", "); b.append(arr[i]); }
        return b.toString();
    }

    private static String fmtAge(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
