package prism.srm.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.MemorySample;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;

/**
 * The memory pressure states of OOM Guard (OOM-1 to OOM-4, OOM-13): a pure function of the samples it
 * is given, the monotonic time of each and the settings in force at each. It acts on nothing, logs
 * nothing, keeps no settings and knows no thread; it is called from one thread only, the system
 * monitor's, once for every sample.
 *
 * <p><b>Up.</b> A more severe state is entered when two consecutive samples meet its threshold: at or
 * below its share of the total, or, for EMERGENCY, below the absolute floor. Consecutive means the
 * previous sample was present, read under the same thresholds and monitoring switch, and no more than
 * the gap before. A ramp may enter one level per sample, and NORMAL may go straight to EMERGENCY.
 *
 * <p><b>Down.</b> A state is left one level at a time, after the reading has stayed above its
 * threshold plus the margin (and, for EMERGENCY, above the floor plus the margin) for the dwell time,
 * counted from the first such sample; any sample that does not, and any missing sample, starts the
 * dwell again.
 *
 * <p><b>What resets.</b> A missing sample holds the state and forgets the previous sample (HOLD). A
 * sample more than the gap after the previous one counts as the first again (GAP): after a sleep the
 * Windows monotonic clock has run on and a dwell would otherwise look complete. A change of the
 * thresholds or of the monitoring switch starts a new epoch: the previous sample no longer counts and
 * the dwell starts again, the state is kept. Monitoring off is NORMAL (OFF).
 *
 * <p><b>Arithmetic.</b> Integer only and without overflow for every reading a {@link MemorySample}
 * allows: {@link #floorFrac} divides before it multiplies. Time is compared by differences, which
 * tolerate the wrap of {@code System.nanoTime()}.
 */
public final class OomStateMachine {

    /**
     * The fixed numbers of the hysteresis (plan O13: tested constants, not settings).
     *
     * @param marginPoints how many percentage points above its threshold a state is left (OOM-4)
     * @param dwell how long the reading must stay above that before the state is left (OOM-4)
     * @param gap the longest pause between two samples that still counts as consecutive
     */
    public record Tuning(int marginPoints, Duration dwell, Duration gap) {
        public static final Tuning DEFAULTS = new Tuning(3, Duration.ofSeconds(10), Duration.ofSeconds(5));

        public Tuning {
            Objects.requireNonNull(dwell, "dwell");
            Objects.requireNonNull(gap, "gap");
            if (marginPoints < 0 || marginPoints > 100 - OomThresholds.MOST_PERCENT) {
                throw new IllegalArgumentException("A margin from 0 to 10 points: " + marginPoints);
            }
            if (dwell.isNegative() || gap.isNegative() || gap.isZero()) {
                throw new IllegalArgumentException("A dwell of at least zero and a positive gap");
            }
        }
    }

    /**
     * What one sample changed.
     *
     * @param reading the sample as read; empty for HOLD and OFF
     * @param epoch the epoch of the settings it was judged under
     */
    public record Transition(Kind kind, OomState from, OomState to, Optional<OomReading> reading, long epoch) {

        /** What happened. */
        public enum Kind {
            /** Nothing changed. */
            NONE,
            /** The sample was unavailable: the state holds and no new action starts. */
            HOLD,
            /** The sample came after a gap: it counts as the first again. */
            GAP,
            /** Monitoring is off. */
            OFF,
            /** A more severe state was entered. */
            ENTERED,
            /** The state went one level down. */
            LEFT
        }

        public Transition {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(reading, "reading");
        }
    }

    /** The settings that make two samples comparable: a change of them starts a new epoch. */
    private record Relevant(OomThresholds thresholds, boolean monitoring) {}

    private final Tuning tuning;
    private final long gapNanos;
    private final long dwellNanos;

    private OomState state = OomState.NORMAL;
    private Relevant relevant;
    private long epoch;
    /** The previous sample, in the current epoch; null when there is none to pair with. */
    private OomReading previous;
    /** When the dwell toward the level below began; meaningful only while dwelling. */
    private long dwellStart;

    private boolean dwelling;

    public OomStateMachine(Tuning tuning) {
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        this.gapNanos = tuning.gap().toNanos();
        this.dwellNanos = tuning.dwell().toNanos();
    }

    public OomState state() {
        return state;
    }

    public long epoch() {
        return epoch;
    }

    public Tuning tuning() {
        return tuning;
    }

    /** Judges one sample, taken at the given monotonic time under the given settings. */
    public Transition observe(Optional<MemorySample> sample, long nowNanos, OomSettings settings) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(settings, "settings");
        OomSwitches switches = settings.switches();
        Relevant now = new Relevant(settings.thresholds(), switches.monitoring());
        if (!now.equals(relevant)) {
            epoch++;
            relevant = now;
            previous = null;
            dwelling = false;
        }
        OomState from = state;
        if (!switches.monitoring()) {
            state = OomState.NORMAL;
            previous = null;
            dwelling = false;
            return new Transition(Transition.Kind.OFF, from, state, Optional.empty(), epoch);
        }
        if (sample.isEmpty()) {
            // A reading that is not there proves nothing: no streak, no dwell.
            previous = null;
            dwelling = false;
            return new Transition(Transition.Kind.HOLD, from, state, Optional.empty(), epoch);
        }
        MemorySample memory = sample.get();
        OomReading reading = new OomReading(memory.availableBytes(), memory.totalBytes(), nowNanos);
        OomThresholds thresholds = settings.thresholds();
        if (previous != null && nowNanos - previous.atNanos() > gapNanos) {
            dwelling = false;
            previous = reading;
            return new Transition(Transition.Kind.GAP, from, state, Optional.of(reading), epoch);
        }
        boolean consecutive = previous != null;
        OomState entered = null;
        if (consecutive) {
            for (OomState level = OomState.EMERGENCY; level.above(state); level = level.below()) {
                if (meets(level, reading, thresholds) && meets(level, previous, thresholds)) {
                    entered = level;
                    break;
                }
            }
        }
        if (entered != null) {
            state = entered;
            dwelling = false;
            previous = reading;
            return new Transition(Transition.Kind.ENTERED, from, state, Optional.of(reading), epoch);
        }
        previous = reading;
        if (state != OomState.NORMAL && clear(state, reading, thresholds, tuning.marginPoints())) {
            if (!dwelling) {
                dwelling = true;
                dwellStart = nowNanos;
            } else if (nowNanos - dwellStart >= dwellNanos) {
                state = state.below();
                dwelling = false;
                return new Transition(Transition.Kind.LEFT, from, state, Optional.of(reading), epoch);
            }
        } else {
            dwelling = false;
        }
        return new Transition(Transition.Kind.NONE, from, state, Optional.of(reading), epoch);
    }

    // ----- The arithmetic, shared with the gate of OOM Guard -----

    /**
     * {@code floor(total × percent / 100)} without overflow for every total up to {@code Long.MAX_VALUE}
     * and every percent from 0 to 100: the total is divided first, and the remainder's share is added.
     */
    public static long floorFrac(long total, int percent) {
        if (total < 0 || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Expected a total of at least 0 and a percent from 0 to 100");
        }
        return (total / 100) * percent + ((total % 100) * percent) / 100;
    }

    /** Whether the floor counts for this total: it is above zero and below half the total. */
    public static boolean floorUsable(long floorBytes, long totalBytes) {
        return floorBytes > 0 && floorBytes < totalBytes - floorBytes;
    }

    /** Whether the reading meets the threshold of a more severe state (OOM-3). */
    public static boolean meets(OomState level, OomReading reading, OomThresholds thresholds) {
        long total = reading.totalBytes();
        long available = reading.availableBytes();
        if (available <= floorFrac(total, thresholds.percent(level))) {
            return true;
        }
        return level == OomState.EMERGENCY
                && floorUsable(thresholds.emergencyFloorBytes(), total)
                && available < thresholds.emergencyFloorBytes();
    }

    /** Whether the reading is clear of the state: above its threshold plus the margin (OOM-4). */
    public static boolean clear(OomState state, OomReading reading, OomThresholds thresholds, int marginPoints) {
        long total = reading.totalBytes();
        long available = reading.availableBytes();
        if (available <= floorFrac(total, thresholds.percent(state) + marginPoints)) {
            return false;
        }
        long floor = thresholds.emergencyFloorBytes();
        return state != OomState.EMERGENCY
                || !floorUsable(floor, total)
                || available > floor + floorFrac(total, marginPoints);
    }
}
