package app.sabre.wzsabre;

import android.content.SharedPreferences;

/**
 * User configuration for the CHP alert feed.
 * Persisted in SharedPreferences; read by CHPSource on every fetch.
 *
 * Per-category settings
 * ─────────────────────
 * Each ChpCategory has two controls:
 *   - enabled      : if false, alerts in this category are dropped entirely
 *   - typeOverride : if non-null, replaces the default SABRE type for that
 *                    category; if null, uses the category's defaultType
 *                    (or the per-alert natural type for WEATHER).
 *
 * Max incident age
 * ────────────────
 * maxAgeMinutes = 0  → no limit (keep all incidents)
 * maxAgeMinutes > 0  → drop incidents whose LogTime is older than this
 */
public class ChpConfig {

    // ── Prefs key prefix ─────────────────────────────────────────────────────
    private static final String PREFS_NAME    = "chp_config";
    private static final String KEY_ENABLED   = "_enabled";
    private static final String KEY_OVERRIDE  = "_type_override";
    private static final String KEY_MAX_AGE   = "max_age_minutes";

    // ── Per-category state ───────────────────────────────────────────────────
    private final boolean[] enabled      = new boolean[ChpCategory.values().length];
    private final String[]  typeOverride = new String[ChpCategory.values().length];

    // ── Age filter ───────────────────────────────────────────────────────────
    public int maxAgeMinutes;

    // ── Caltrans LCS lane/road closures ──────────────────────────────────────
    public boolean lcsEnabled;
    private static final String KEY_LCS_ENABLED = "lcs_enabled";

    // ── Wildfires (WFIGS) ─────────────────────────────────────────────────────
    public boolean fireEnabled;
    private static final String KEY_FIRE_ENABLED = "fire_enabled";

    // ── Constructor (defaults) ───────────────────────────────────────────────
    private ChpConfig() {
        for (ChpCategory cat : ChpCategory.values()) {
            enabled[cat.ordinal()]      = true;
            typeOverride[cat.ordinal()] = null;  // null = keep default
        }
        maxAgeMinutes = 60;  // 1 hour default
        lcsEnabled = true;
        fireEnabled = true;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────
    public boolean isEnabled(ChpCategory cat) {
        return enabled[cat.ordinal()];
    }

    public void setEnabled(ChpCategory cat, boolean value) {
        enabled[cat.ordinal()] = value;
    }

    /** null = use category's defaultType (or natural per-alert type for WEATHER) */
    public String getTypeOverride(ChpCategory cat) {
        return typeOverride[cat.ordinal()];
    }

    public void setTypeOverride(ChpCategory cat, String type) {
        typeOverride[cat.ordinal()] = type;
    }

    /**
     * Resolve the final SABRE type for an alert, applying override if set.
     * @param cat          the alert's category
     * @param naturalType  the type AlertMapper chose (may be null)
     * @return SABRE type string to send, or null to drop the alert
     */
    public String resolveType(ChpCategory cat, String naturalType) {
        if (!isEnabled(cat)) return null;
        String override = getTypeOverride(cat);
        if (override != null) return override;
        // No override: use category defaultType, or the natural type for WEATHER
        return (cat.defaultType != null) ? cat.defaultType : naturalType;
    }

    // ── Factory methods ───────────────────────────────────────────────────────
    /** All categories enabled, max age 60 min — for use in unit tests. */
    public static ChpConfig loadDefaults() {
        return new ChpConfig();
    }

    // ── SharedPreferences I/O ─────────────────────────────────────────────────
    public static ChpConfig load(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME,
                android.content.Context.MODE_PRIVATE);
        ChpConfig cfg = new ChpConfig();
        for (ChpCategory cat : ChpCategory.values()) {
            cfg.enabled[cat.ordinal()]      = prefs.getBoolean(cat.name() + KEY_ENABLED, true);
            // Validate persisted overrides: an unknown SABRE type string (corrupt or
            // left over from an older version) would crash Highway Radar's renderer.
            cfg.typeOverride[cat.ordinal()] =
                    validOverrideOrNull(prefs.getString(cat.name() + KEY_OVERRIDE, null));
        }
        cfg.maxAgeMinutes = prefs.getInt(KEY_MAX_AGE, 60);
        cfg.lcsEnabled    = prefs.getBoolean(KEY_LCS_ENABLED, true);
        cfg.fireEnabled   = prefs.getBoolean(KEY_FIRE_ENABLED, true);
        return cfg;
    }

    public void save(android.content.Context context) {
        SharedPreferences.Editor ed = context.getSharedPreferences(PREFS_NAME,
                android.content.Context.MODE_PRIVATE).edit();
        for (ChpCategory cat : ChpCategory.values()) {
            ed.putBoolean(cat.name() + KEY_ENABLED, enabled[cat.ordinal()]);
            if (typeOverride[cat.ordinal()] != null) {
                ed.putString(cat.name() + KEY_OVERRIDE, typeOverride[cat.ordinal()]);
            } else {
                ed.remove(cat.name() + KEY_OVERRIDE);
            }
        }
        ed.putInt(KEY_MAX_AGE, maxAgeMinutes);
        ed.putBoolean(KEY_LCS_ENABLED, lcsEnabled);
        ed.putBoolean(KEY_FIRE_ENABLED, fireEnabled);
        ed.apply();
    }

    // ── Display type options for the UI spinner ───────────────────────────────

    /** Human-readable labels for the type picker. Index 0 = "Keep Default". */
    public static final String[] TYPE_LABELS = {
        "Keep Default",
        "Accident (Major)",
        "Accident (Minor)",
        "Police Visible",
        "Road Closure",
        "Road Debris",
        "Slippery Road",
    };

    /** SABRE type values matching TYPE_LABELS. null = keep default. */
    public static final String[] TYPE_VALUES = {
        null,
        "ACCIDENT_MAJOR",
        "ACCIDENT_MINOR",
        "POLICE_VISIBLE",
        "HAZARD_ON_ROAD_CONGESTION",
        "HAZARD_ON_ROAD_DEBRIS",
        "HAZARD_ON_ROAD_SLIPPERY",
    };

    /** Returns the override if it is one of the selectable SABRE types, else null. */
    public static String validOverrideOrNull(String type) {
        if (type == null) return null;
        for (int i = 1; i < TYPE_VALUES.length; i++) {
            if (type.equals(TYPE_VALUES[i])) return type;
        }
        return null;
    }

    /** Returns the spinner index for a given SABRE type string (or null → 0). */
    public static int typeToSpinnerIndex(String type) {
        if (type == null) return 0;
        for (int i = 1; i < TYPE_VALUES.length; i++) {
            if (type.equals(TYPE_VALUES[i])) return i;
        }
        return 0;
    }

    // ── Age option helpers ────────────────────────────────────────────────────

    public static final int[]    AGE_VALUES_MINUTES = { 0, 30, 60, 120, 240, 480 };
    public static final String[] AGE_LABELS         = {
        "No limit", "30 minutes", "1 hour", "2 hours", "4 hours", "8 hours"
    };

    public static int ageToSpinnerIndex(int minutes) {
        for (int i = 0; i < AGE_VALUES_MINUTES.length; i++) {
            if (AGE_VALUES_MINUTES[i] == minutes) return i;
        }
        return 2; // default to 1 hour
    }
}
