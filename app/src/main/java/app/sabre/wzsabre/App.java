package app.sabre.wzsabre;

import android.app.Application;

/**
 * Records the last uncaught exception (see {@link CrashLog}) before delegating to
 * the platform's default handler, so the settings diagnostics panel can show it.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            CrashLog.record(this, throwable);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
