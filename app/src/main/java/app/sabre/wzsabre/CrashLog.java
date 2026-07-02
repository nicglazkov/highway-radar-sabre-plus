package app.sabre.wzsabre;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Records the most recent uncaught exception to a private file so the settings
 * diagnostics panel can surface "last crash" — turns a vague "it crashed" report
 * into something actionable. Stays entirely on-device; never transmitted.
 */
public final class CrashLog {
    private CrashLog() {}

    private static final String FILE = "last_crash.txt";

    /** Persist a crash: line 1 = epoch millis, line 2 = summary, then the stack trace. */
    static void record(Context ctx, Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String content = System.currentTimeMillis() + "\n"
                    + t.toString() + "\n" + sw;
            try (FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Never let crash logging itself throw.
        }
    }

    /** @return {epochMillisString, summaryLine} of the last crash, or null if none. */
    static String[] readSummary(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String ts = r.readLine();
            String summary = r.readLine();
            if (ts == null || summary == null) return null;
            return new String[]{ts, summary};
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context ctx) {
        ctx.deleteFile(FILE);
    }
}
