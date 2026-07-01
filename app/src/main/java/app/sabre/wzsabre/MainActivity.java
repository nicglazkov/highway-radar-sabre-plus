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
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

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
        buildAgeSpinner();
    }

    private void buildLcsSwitch() {
        Switch sw = findViewById(R.id.lcsSwitch);
        sw.setChecked(config.lcsEnabled);
        sw.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
            config.lcsEnabled = isChecked;
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
