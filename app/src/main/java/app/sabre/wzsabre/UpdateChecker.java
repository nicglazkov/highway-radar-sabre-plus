package app.sabre.wzsabre;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Checks GitHub Releases for a newer version. Since the app is sideloaded (no Play
 * Store auto-update), this powers both the in-app "update available" card and a
 * once-per-version notification. No auth needed — the public releases endpoint is
 * used within GitHub's unauthenticated rate limit.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    static final String RELEASES_API =
            "https://api.github.com/repos/nicglazkov/highway-radar-sabre-plus/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/nicglazkov/highway-radar-sabre-plus/releases/latest";

    public static final class Result {
        public final String latestVersion;   // normalized, e.g. "1.6"
        public final String htmlUrl;
        Result(String latestVersion, String htmlUrl) {
            this.latestVersion = latestVersion;
            this.htmlUrl = htmlUrl;
        }
    }

    /** Blocking network call — returns null on any failure. Must run off the main thread. */
    public static Result fetchLatest() {
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL(RELEASES_API).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "highway-radar-sabre-plus-app");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            }
            JSONObject o = new JSONObject(sb.toString());
            String tag = o.optString("tag_name", "");
            if (tag.isEmpty()) return null;
            String htmlUrl = o.optString("html_url", RELEASES_PAGE);
            return new Result(stripV(tag), htmlUrl);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String stripV(String t) {
        if (t == null) return null;
        return (t.startsWith("v") || t.startsWith("V")) ? t.substring(1) : t;
    }

    /** True if {@code latest} is a strictly higher version than {@code current} (numeric, dot-separated). */
    public static boolean isNewer(String latest, String current) {
        if (latest == null) return false;
        int[] a = parse(latest), b = parse(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        String[] parts = stripV(v).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
