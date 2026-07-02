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

    // ── Chain controls (Caltrans CC) ──────────────────────────────────────────
    public boolean chainsEnabled;
    private static final String KEY_CHAINS_ENABLED = "chains_enabled";

    // ── Update notification ────────────────────────────────────────────────────
    /** When false, the once-per-version update notification is suppressed (the
     *  in-app banner still shows). */
    public boolean updateNotifyEnabled;
    private static final String KEY_UPDATE_NOTIFY = "update_notify_enabled";

    // ── Wildfire noise control ─────────────────────────────────────────────────
    /** Hide wildfires smaller than this many acres (0 = show all). */
    public int fireMinAcres;
    private static final String KEY_FIRE_MIN_ACRES = "fire_min_acres";

    // ── Waze category filters (coarse) ─────────────────────────────────────────
    // Each toggles a whole class of Waze alerts. Default all on.
    public boolean wazePolice, wazeAccidents, wazeHazards, wazeJams, wazeClosures;
    private static final String KEY_WAZE_POLICE    = "waze_police";
    private static final String KEY_WAZE_ACCIDENTS = "waze_accidents";
    private static final String KEY_WAZE_HAZARDS   = "waze_hazards";
    private static final String KEY_WAZE_JAMS      = "waze_jams";
    private static final String KEY_WAZE_CLOSURES  = "waze_closures";

    /** @return whether a Waze coarse category ("police"/"accidents"/... from
     *  {@link AlertMapper#wazeCategory}) is currently enabled. */
    public boolean isWazeCategoryEnabled(String category) {
        switch (category) {
            case "police":    return wazePolice;
            case "accidents": return wazeAccidents;
            case "jams":      return wazeJams;
            case "closures":  return wazeClosures;
            default:          return wazeHazards;   // hazards + anything else
        }
    }

    // ── Constructor (defaults) ───────────────────────────────────────────────
    private ChpConfig() {
        for (ChpCategory cat : ChpCategory.values()) {
            enabled[cat.ordinal()]      = true;
            typeOverride[cat.ordinal()] = null;  // null = keep default
        }
        maxAgeMinutes = 60;  // 1 hour default
        lcsEnabled = true;
        fireEnabled = true;
        chainsEnabled = true;
        updateNotifyEnabled = true;
        fireMinAcres = 0;
        wazePolice = wazeAccidents = wazeHazards = wazeJams = wazeClosures = true;
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
        cfg.chainsEnabled = prefs.getBoolean(KEY_CHAINS_ENABLED, true);
        cfg.updateNotifyEnabled = prefs.getBoolean(KEY_UPDATE_NOTIFY, true);
        cfg.fireMinAcres  = Math.max(0, prefs.getInt(KEY_FIRE_MIN_ACRES, 0));
        cfg.wazePolice    = prefs.getBoolean(KEY_WAZE_POLICE, true);
        cfg.wazeAccidents = prefs.getBoolean(KEY_WAZE_ACCIDENTS, true);
        cfg.wazeHazards   = prefs.getBoolean(KEY_WAZE_HAZARDS, true);
        cfg.wazeJams      = prefs.getBoolean(KEY_WAZE_JAMS, true);
        cfg.wazeClosures  = prefs.getBoolean(KEY_WAZE_CLOSURES, true);
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
        ed.putBoolean(KEY_CHAINS_ENABLED, chainsEnabled);
        ed.putBoolean(KEY_UPDATE_NOTIFY, updateNotifyEnabled);
        ed.putInt(KEY_FIRE_MIN_ACRES, fireMinAcres);
        ed.putBoolean(KEY_WAZE_POLICE, wazePolice);
        ed.putBoolean(KEY_WAZE_ACCIDENTS, wazeAccidents);
        ed.putBoolean(KEY_WAZE_HAZARDS, wazeHazards);
        ed.putBoolean(KEY_WAZE_JAMS, wazeJams);
        ed.putBoolean(KEY_WAZE_CLOSURES, wazeClosures);
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

    // ── Wildfire min-size option helpers ────────────────────────────────────────

    public static final int[]    FIRE_SIZE_VALUES = { 0, 10, 100, 1000 };
    public static final String[] FIRE_SIZE_LABELS = {
        "All sizes", "10+ acres", "100+ acres", "1000+ acres"
    };

    public static int fireSizeToSpinnerIndex(int acres) {
        for (int i = 0; i < FIRE_SIZE_VALUES.length; i++) {
            if (FIRE_SIZE_VALUES[i] == acres) return i;
        }
        return 0; // default to all sizes
    }
}
