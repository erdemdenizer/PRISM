package prism.srm.domain;

/**
 * Where the more severe states begin (OOM-3): the share of physical memory still available, in
 * whole percent, and an absolute floor below which EMERGENCY begins whatever the share.
 *
 * @param emergencyFloorBytes EMERGENCY begins when less than this many bytes are available; zero
 *     turns the floor off
 */
public record OomThresholds(int warningPercent, int criticalPercent, int emergencyPercent, long emergencyFloorBytes) {

    /** The highest threshold a user may set. */
    public static final int MOST_PERCENT = 90;

    /** The defaults of OOM-3: 15 %, 8 %, 5 % and 256 MiB. */
    public static final OomThresholds DEFAULTS = new OomThresholds(15, 8, 5, 256L << 20);

    public OomThresholds {
        if (!valid(warningPercent, criticalPercent, emergencyPercent, emergencyFloorBytes)) {
            throw new IllegalArgumentException("Expected 1 <= emergency < critical < warning <= " + MOST_PERCENT
                    + " and a floor of at least 0: " + warningPercent + ", " + criticalPercent + ", "
                    + emergencyPercent + ", " + emergencyFloorBytes);
        }
    }

    /** Whether these numbers may be thresholds. */
    public static boolean valid(int warningPercent, int criticalPercent, int emergencyPercent, long floorBytes) {
        return emergencyPercent >= 1
                && emergencyPercent < criticalPercent
                && criticalPercent < warningPercent
                && warningPercent <= MOST_PERCENT
                && floorBytes >= 0;
    }

    /** The share at or below which the state begins; NORMAL has none. */
    public int percent(OomState state) {
        return switch (state) {
            case NORMAL -> throw new IllegalArgumentException("NORMAL has no threshold");
            case WARNING -> warningPercent;
            case CRITICAL -> criticalPercent;
            case EMERGENCY -> emergencyPercent;
        };
    }
}
