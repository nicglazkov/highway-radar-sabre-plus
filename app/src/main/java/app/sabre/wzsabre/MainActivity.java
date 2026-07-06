package app.sabre.wzsabre;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Settings screen for the CHP alert feed.
 *
 * Category rows are inflated programmatically — one per ChpCategory — so
 * adding a new category requires no layout changes.
 *
 * All changes are saved to SharedPreferences immediately (no "Save" button
 * needed). SabreService reloads ChpConfig on every FETCH_REQUEST, so changes
 * take effect on the next HR map refresh without restarting anything.
 */
public class MainActivity extends Activity {

    private ChpConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = ChpConfig.load(this);

        // Start the foreground service so it's ready when HR sends FETCH_REQUEST.
        // We're in the foreground here, so the start is allowed.
        ForegroundServiceStarter.start(this, null, null);

        // Ask for the exemptions that keep the plugin reachable in the background.
        requestExemptions();

        updateServiceStatus();
        buildCategoryRows();
        buildLcsSwitch();
        buildFireSwitch();
        buildFireSizeSpinner();
        buildChainsSwitch();
        buildWazeFilters();
        buildUpdateNotifySwitch();
        buildAgeSpinner();
        buildDiagnostics();
        buildReportButton();
        checkForUpdate();
    }

    /**
     * Opens the GitHub bug-report issue form in the browser, pre-filling the plugin
     * and Android versions. Uses ACTION_VIEW (no permission needed).
     */
    private void buildReportButton() {
        findViewById(R.id.reportButton).setOnClickListener(v -> {
            String url = "https://github.com/nicglazkov/highway-radar-sabre-plus/issues/new"
                    + "?template=bug_report.yml"
                    + "&plugin-version=" + Uri.encode(BuildConfig.VERSION_NAME)
                    + "&android-version=" + Uri.encode(
                            "Android " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                // No browser / template unavailable — fall back to the issues list.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/nicglazkov/highway-radar-sabre-plus/issues")));
                } catch (Exception ignored) {}
            }
        });
    }

    private void buildFireSizeSpinner() {
        Spinner sp = findViewById(R.id.fireSizeSpinner);
        ArrayAdapter<String> ad = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, ChpConfig.FIRE_SIZE_LABELS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(ChpConfig.fireSizeToSpinnerIndex(config.fireMinAcres));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                config.fireMinAcres = ChpConfig.FIRE_SIZE_VALUES[pos];
                config.save(MainActivity.this);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void buildUpdateNotifySwitch() {
        Switch sw = findViewById(R.id.updateNotifySwitch);
        sw.setChecked(config.updateNotifyEnabled);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean isChecked) -> {
            config.updateNotifyEnabled = isChecked;
            config.save(this);
        });
    }

    // ── Waze category filters ───────────────────────────────────────────────────

    private void buildWazeFilters() {
        LinearLayout c = findViewById(R.id.wazeFilterContainer);
        addWazeToggle(c, "Police & cameras", config.wazePolice, v -> config.wazePolice = v);
        addWazeToggle(c, "Accidents",        config.wazeAccidents, v -> config.wazeAccidents = v);
        addWazeToggle(c, "Hazards",          config.wazeHazards, v -> config.wazeHazards = v);
        addWazeToggle(c, "Traffic jams",     config.wazeJams, v -> config.wazeJams = v);
        addWazeToggle(c, "Road closures",    config.wazeClosures, v -> config.wazeClosures = v);
    }

    private void addWazeToggle(LinearLayout container, String label, boolean checked,
                               Consumer<Boolean> setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 12, 0, 12);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14f);
        tv.setTextColor(0xFF212121);
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, isChecked) -> { setter.accept(isChecked); config.save(this); });
        row.addView(sw);
        container.addView(row);
    }

    // ── Update banner ─────────────────────────────────────────────────────────

    // Throttle GitHub checks and cache the decision across Activity re-creations
    // (rotation, quick reopen) so we don't hit the API on every onCreate.
    private static final long UPDATE_CHECK_THROTTLE_MS = 30 * 60_000L;
    private static volatile long lastUpdateCheckMs = 0L;
    private static volatile UpdateChecker.Result pendingUpdate; // non-null → show the card

    private void checkForUpdate() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastUpdateCheckMs != 0 && now - lastUpdateCheckMs < UPDATE_CHECK_THROTTLE_MS) {
            showUpdateCard(pendingUpdate);   // reuse the last decision (may be null)
            return;
        }
        lastUpdateCheckMs = now;
        new Thread(() -> {
            UpdateChecker.Result r = UpdateChecker.fetchLatest();
            UpdateChecker.Result showable = (r != null
                    && UpdateChecker.isNewer(r.latestVersion, BuildConfig.VERSION_NAME)) ? r : null;
            pendingUpdate = showable;
            runOnUiThread(() -> {
                // The thread outlives a destroyed Activity (up to ~12s); don't touch
                // its views if it's gone.
                if (isFinishing() || isDestroyed()) return;
                showUpdateCard(showable);
            });
        }).start();
    }

    private void showUpdateCard(UpdateChecker.Result r) {
        View card = findViewById(R.id.updateCard);
        if (r == null) { card.setVisibility(View.GONE); return; }
        ((TextView) findViewById(R.id.updateText))
                .setText("Update available: v" + r.latestVersion + " (you have v"
                        + BuildConfig.VERSION_NAME + ")");
        findViewById(R.id.updateButton).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.htmlUrl)));
            } catch (Exception ignored) {}
        });
        card.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sources refresh in the background after the service starts, so re-render the
        // diagnostics each time the screen is shown to pick up fresh numbers.
        buildDiagnostics();
    }

    // ── Diagnostics panel ─────────────────────────────────────────────────────

    private void buildDiagnostics() {
        LinearLayout container = findViewById(R.id.diagnosticsContainer);
        container.removeAllViews();
        String[][] sources = {
                {SabreResponseBuilder.SOURCE_CHP,  "CHP incidents"},
                {SabreResponseBuilder.SOURCE_WAZE, "Waze alerts"},
                {SabreResponseBuilder.SOURCE_LCS,  "Caltrans closures"},
                {SabreResponseBuilder.SOURCE_FIRE, "Wildfires"},
                {SabreResponseBuilder.SOURCE_CHAINS, "Chain controls"},
        };
        for (String[] s : sources) {
            SourceStatus.Entry e = SourceStatus.get(s[0]);
            TextView row = new TextView(this);
            row.setTextSize(13f);
            row.setPadding(0, 6, 0, 6);
            row.setText(formatStatus(s[1], e));
            row.setTextColor(e != null && e.lastError != null ? 0xFFD32F2F : 0xFF424242);
            container.addView(row);
        }

        // Last crash (if any) — tap to clear.
        String[] crash = CrashLog.readSummary(this);
        if (crash != null) {
            TextView cr = new TextView(this);
            cr.setTextSize(13f);
            cr.setPadding(0, 6, 0, 6);
            cr.setTextColor(0xFFD32F2F);
            long whenMs = 0L;
            try { whenMs = Long.parseLong(crash[0]); } catch (NumberFormatException ignored) {}
            cr.setText("Last crash: " + (whenMs > 0 ? ago(whenMs) : "?") + ", " + crash[1] + "  (tap to clear)");
            cr.setOnClickListener(v -> { CrashLog.clear(this); buildDiagnostics(); });
            container.addView(cr);
        }

        // Refresh: poke the service (prewarms/refreshes stale sources) and re-render.
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> {
            ForegroundServiceStarter.start(this, null, null);
            v.postDelayed(this::buildDiagnostics, 1500);
        });
        container.addView(refresh);
    }

    private static String formatStatus(String label, SourceStatus.Entry e) {
        if (e == null || !e.ran) return label + ": not fetched yet";
        StringBuilder sb = new StringBuilder(label).append(": ");
        if (e.lastUpdateMs > 0) sb.append(e.count).append(" · ").append(ago(e.lastUpdateMs));
        else sb.append("no data yet");
        if (e.lastError != null) sb.append(" · error: ").append(e.lastError);
        return sb.toString();
    }

    /** Human "N ago" for a wall-clock timestamp. */
    private static String ago(long whenMs) {
        long s = Math.max(0, (System.currentTimeMillis() - whenMs) / 1000L);
        if (s < 10)   return "just now";
        if (s < 60)   return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    private void buildLcsSwitch() {
        Switch sw = findViewById(R.id.lcsSwitch);
        sw.setChecked(config.lcsEnabled);
        sw.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
            config.lcsEnabled = isChecked;
            config.save(this);
        });
    }

    private void buildFireSwitch() {
        Switch sw = findViewById(R.id.fireSwitch);
        sw.setChecked(config.fireEnabled);
        sw.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
            config.fireEnabled = isChecked;
            config.save(this);
        });
    }

    private void buildChainsSwitch() {
        Switch sw = findViewById(R.id.chainsSwitch);
        sw.setChecked(config.chainsEnabled);
        sw.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
            config.chainsEnabled = isChecked;
            config.save(this);
        });
    }

    /**
     * Requests the runtime permissions/exemptions the plugin needs to stay
     * reachable while Highway Radar is in use:
     *   - POST_NOTIFICATIONS (Android 13+) so the foreground-service notification shows.
     *   - Battery-optimization exemption so Android won't freeze/Doze the process,
     *     which would otherwise block the service from starting on FETCH_REQUEST.
     * Both are guarded so the user is only prompted until granted.
     */
    private void requestExemptions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                    // Some OEM builds don't expose the direct request screen; ignore.
                }
            }
        }
    }

    // ── Service status header ─────────────────────────────────────────────────

    private void updateServiceStatus() {
        TextView tv = findViewById(R.id.serviceStatus);
        tv.setText("Plugin ready · runs while Highway Radar is open");
    }

    // ── Category rows ─────────────────────────────────────────────────────────

    private void buildCategoryRows() {
        LinearLayout container = findViewById(R.id.categoryContainer);
        ChpCategory[] categories = ChpCategory.values();

        for (int i = 0; i < categories.length; i++) {
            ChpCategory cat = categories[i];
            View row = getLayoutInflater().inflate(R.layout.row_category, container, false);

            // Label + description
            ((TextView) row.findViewById(R.id.catLabel)).setText(cat.label);
            ((TextView) row.findViewById(R.id.catDescription)).setText(cat.description);

            // Hide divider on last row
            if (i == categories.length - 1) {
                row.findViewById(R.id.divider).setVisibility(View.GONE);
            }

            // Type spinner
            Spinner typeSpinner = row.findViewById(R.id.typeSpinner);
            ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, ChpConfig.TYPE_LABELS);
            typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            typeSpinner.setAdapter(typeAdapter);
            typeSpinner.setSelection(ChpConfig.typeToSpinnerIndex(config.getTypeOverride(cat)));

            // Enable/disable controls
            View typeRow = row.findViewById(R.id.typeRow);
            Switch sw = row.findViewById(R.id.catSwitch);

            boolean enabled = config.isEnabled(cat);
            sw.setChecked(enabled);
            typeRow.setVisibility(enabled ? View.VISIBLE : View.GONE);
            setRowAlpha(row, enabled);

            // Switch listener — save immediately
            sw.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
                config.setEnabled(cat, isChecked);
                config.save(this);
                typeRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                setRowAlpha(row, isChecked);
            });

            // Spinner listener — save immediately
            typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    config.setTypeOverride(cat, ChpConfig.TYPE_VALUES[pos]);
                    config.save(MainActivity.this);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            container.addView(row);
        }
    }

    private static void setRowAlpha(View row, boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        row.findViewById(R.id.catLabel).setAlpha(alpha);
        row.findViewById(R.id.catDescription).setAlpha(alpha);
    }

    // ── Incident age spinner ──────────────────────────────────────────────────

    private void buildAgeSpinner() {
        Spinner spinner = findViewById(R.id.ageSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, ChpConfig.AGE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(ChpConfig.ageToSpinnerIndex(config.maxAgeMinutes));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                config.maxAgeMinutes = ChpConfig.AGE_VALUES_MINUTES[pos];
                config.save(MainActivity.this);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
