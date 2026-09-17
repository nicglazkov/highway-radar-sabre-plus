package app.sabre.wzsabre;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Translates the plugin's internal SABRE alert types into the vocabulary the
 * current Highway Radar actually draws.
 *
 * <p>Highway Radar 3.2 (update 7) first requires the type to start with POLICE,
 * HAZARD or ACCIDENT, then for HAZARD/ACCIDENT does an exact match against a
 * short list and silently drops everything else (confirmed on the live app on
 * 2026-09-16 with a synthetic two-column injection:
 * congestion, debris, slippery, generic on-road and weather pins never appeared,
 * while the six native hazard names next to them did). POLICE is prefix-only, so
 * any suffix renders.
 *
 * <p>Internal types stay semantic everywhere else (settings overrides, dedupe,
 * diagnostics); only the wire type written by {@link SabreResponseBuilder} is
 * translated, so an HR update that widens the list needs a change here only.
 */
public final class HrTypes {
    private HrTypes() {}

    /** Hazard type strings Highway Radar 3.2 matches exactly. */
    private static final Set<String> HR_NATIVE_HAZARDS = new HashSet<>(Arrays.asList(
            "HAZARD_ON_ROAD_OBJECT",
            "HAZARD_ON_ROAD_POT_HOLE",
            "HAZARD_ON_ROAD_CAR_STOPPED",
            "HAZARD_ON_ROAD_ROAD_KILL",
            "HAZARD_ON_SHOULDER_CAR_STOPPED",
            "HAZARD_ON_SHOULDER_ANIMALS"));

    /** Generic pin for any hazard Highway Radar has no closer word for. */
    static final String GENERIC_HAZARD = "HAZARD_ON_ROAD_OBJECT";

    /**
     * The type string to put on the wire for {@code sabreType}. Police and accident
     * types, and hazards Highway Radar already knows, pass through. Debris becomes
     * the "object on road" pin, congestion (closures, jams) becomes the "vehicle
     * stopped on road" pin, and every other hazard becomes the generic pin. Types
     * without a renderable prefix are returned unchanged (HR drops them by design).
     */
    public static String toHrType(String sabreType) {
        if (sabreType == null) return null;
        String u = sabreType.toUpperCase(Locale.US);
        if (!u.startsWith("HAZARD")) return sabreType;          // POLICE*, ACCIDENT*, and non-renderable
        if (HR_NATIVE_HAZARDS.contains(u)) return sabreType;
        if (u.equals("HAZARD_ON_ROAD_CONGESTION")) return "HAZARD_ON_ROAD_CAR_STOPPED";
        return GENERIC_HAZARD;                                   // DEBRIS, SLIPPERY, weather, fire, rest
    }
}
