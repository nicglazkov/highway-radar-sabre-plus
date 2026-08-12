package app.sabre.wzsabre;

/**
 * Logical categories for CHP log entries.
 * Each category has a default SABRE type and can be independently
 * enabled/disabled or remapped to a different type in ChpConfig.
 */
public enum ChpCategory {

    MAJOR_ACCIDENT(
            "Fatal and injury accidents",
            "1179, 1141, sig alerts, fatal collisions",
            "ACCIDENT_MAJOR"),

    MINOR_ACCIDENT(
            "Minor accidents",
            "Non-injury collisions, hit-and-run",
            "ACCIDENT_MINOR"),

    POLICE_ON_ROAD(
            "Officer on road",
            "Traffic control, construction/maintenance escort",
            "POLICE_VISIBLE"),

    CONGESTION(
            "Closures and congestion",
            "Road closures, traffic advisories",
            "HAZARD_ON_ROAD_CONGESTION"),

    DEBRIS(
            "Debris and road hazards",
            "Debris, vehicle fires, unrecognized incidents",
            "HAZARD_ON_ROAD_DEBRIS"),

    WEATHER(
            "Weather hazards",
            "Fog, wind, snow, ice, chain controls",
            null);   // null = keep natural per-type mapping (fog→FOG, snow→SLIPPERY, etc.)

    public final String label;
    public final String description;
    /** Default SABRE type; null means use the specific type returned by AlertMapper. */
    public final String defaultType;

    ChpCategory(String label, String description, String defaultType) {
        this.label       = label;
        this.description = description;
        this.defaultType = defaultType;
    }
}
