package app.sabre.wzsabre;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;
import java.util.Map;

/**
 * Builds the user-facing, privacy-safe "Share diagnostics" text report. The report
 * is assembled from four independently toggleable sections (see {@link #SECTION_LABELS})
 * so the user decides exactly what leaves their device. Every section contains only
 * versions, counts, category names, and error strings: no location, street names,
 * alert IDs, or personal data is ever included.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {}

    /**
     * The four categories the user can individually include or exclude. Index order
     * matches the booleans passed to {@link #build}.
     */
    public static final String[] SECTION_LABELS = {
        "App and device (SABRE Plus version, Android version, phone model)",
        "Highway Radar (its version, and whether it is requesting data)",
        "Alert counts and types (numbers and category names only)",
        "Activity and crashes (recent plugin events, never any location)"
    };

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

        if (appDevice) {
            sb.append("\n[App and device]\n");
            sb.append("SABRE Plus ").append(BuildConfig.VERSION_NAME)
              .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n");
            sb.append("Android ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
              .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        }

        if (hr) {
            sb.append("\n[Highway Radar]\n");
            sb.append("Installed: ").append(hrVersion(ctx)).append('\n');
            sb.append("Plugin service running: ").append(SabreService.RUNNING ? "yes" : "no").append('\n');
            long age = DebugLog.lastFetchAgeMs();
            sb.append("Last request from Highway Radar: ")
              .append(age < 0 ? "none this session" : (age / 1000) + "s ago").append('\n');
        }

        if (sources) {
            sb.append("\n[Alert sources]\n");
            for (String s : SOURCES) {
                SourceStatus.Entry e = SourceStatus.get(s);
                sb.append("  ").append(s).append(": ");
                if (e == null) {
                    sb.append("no data yet\n");
                } else if (e.lastError != null) {
                    sb.append(e.count).append(" items, error: ").append(e.lastError).append('\n');
                } else {
                    sb.append(e.count).append(" items");
                    if (e.lastUpdateMs > 0) {
                        sb.append(", ok ").append((System.currentTimeMillis() - e.lastUpdateMs) / 1000).append("s ago");
                    }
                    sb.append('\n');
                }
            }
            String summary = DebugLog.lastFetchSummary();
            if (summary != null) sb.append("  last ").append(summary).append('\n');
            Map<String, Integer> types = DebugLog.lastFetchTypes();
            if (!types.isEmpty()) {
                sb.append("Alert types sent to Highway Radar (category names only):\n");
                for (Map.Entry<String, Integer> t : types.entrySet()) {
                    sb.append("  ").append(t.getKey()).append(" x").append(t.getValue()).append('\n');
                }
            }
        }

        if (activity) {
            sb.append("\n[Recent activity]\n");
            List<String> events = DebugLog.recentEvents();
            if (events.isEmpty()) {
                sb.append("  (none yet)\n");
            } else {
                for (String line : events) sb.append(line).append('\n');
            }
            String[] crash = CrashLog.readSummary(ctx);
            sb.append("Last crash: ").append(crash == null ? "none" : crash[1]).append('\n');
        }

        return sb.toString();
    }

    private static String hrVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo("com.highwayradar.app", 0);
            return "yes, v" + pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }
}
