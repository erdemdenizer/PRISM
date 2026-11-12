package prism.srm.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitStatus;

/**
 * The feedback that steers the emulated CPU limiter's duty cycle (LIM-8, ADR-0004 §3): from the
 * use measured over each interval it decides which share of the next cycles the target runs. Pure
 * arithmetic, no clock and no thread: the limiter feeds it measurements and reads its decisions.
 *
 * <p><b>Units.</b> The limit and the measured use are shares of the whole machine, 0 to 1, as the
 * CPU column shows them (PROC-2): 100 % is every logical processor busy. The run share is the part
 * of each cycle the target is let run, {@link Tuning#minRunShare} to 1.
 *
 * <p><b>Why not "a 30 % limit runs 30 % of the time".</b> A target may use anything from a fraction
 * of one logical processor to all of them. So the controller estimates what the target would use
 * if it ran all the time, its demand: the measured use divided by the share of the interval it
 * actually ran. The run share that holds the target at the limit is the limit over the demand. A
 * single-threaded target on 8 logical processors demands at most 12.5 % of the machine and is never
 * suspended under a 30 % limit.
 *
 * <p><b>Stability.</b> The use is smoothed (an exponential average); a measurement within a dead
 * band around the limit changes nothing; one step moves the run share by at most a quarter of it
 * when throttling harder and half of it when letting go, so that a penalty is lifted faster than
 * it is applied. A run share of 1 means the target is not suspended at all.
 *
 * <p><b>What it reports</b> never claims more than was measured: throttling only while the last
 * intervals were at or below the limit (the limit is an upper bound, so the tolerance is one-sided);
 * above the target when they were not, because the limit lies below the lowest share Prism can hold
 * the target to (the floor of the duty cycle) or because the load varies faster than the limiter
 * follows; adjusting while the run share is still moving or nothing could be measured.
 */
final class DutyCycleController {

    /**
     * The constants of the duty cycle, fixed by measurement in milestone 9 (docs/PERFORMANCE.md).
     *
     * @param minCycle the shortest cycle: LIM-8 allows none below 100 ms; 200 ms keeps the accounting
     *     resolution of the system (15.6 ms on Windows) well below a phase
     * @param maxCycle the longest cycle
     * @param suspendCostFactor the cycle is at least this many times the time a suspend took, so that
     *     suspending costs at most a tenth of the cycle: on Windows a suspend lists every thread of the
     *     system, measured at about 110 ms on a desktop with 6,700 threads
     * @param minRunShare the smallest share of a cycle the target runs; below about 10 ms a phase is
     *     shorter than the system's accounting resolution
     * @param smoothing the weight of the newest measurement in the average
     * @param deadBandShare the dead band around the limit, as a share of the limit
     * @param deadBandFloor the dead band's least width, as a share of the machine
     * @param stepDown the largest decrease of the run share in one step, as a share of it
     * @param stepUp the largest increase of the run share in one step, as a share of it
     * @param intervalsToJudge how many intervals in a row decide a status
     */
    record Tuning(
            Duration minCycle,
            Duration maxCycle,
            double suspendCostFactor,
            double minRunShare,
            double smoothing,
            double deadBandShare,
            double deadBandFloor,
            double stepDown,
            double stepUp,
            int intervalsToJudge) {

        static final Tuning DEFAULTS =
                new Tuning(Duration.ofMillis(200), Duration.ofSeconds(2), 10, 0.05, 0.5, 0.1, 0.005, 0.25, 0.5, 3);

        Tuning {
            Objects.requireNonNull(minCycle, "minCycle");
            Objects.requireNonNull(maxCycle, "maxCycle");
            if (minCycle.toMillis() < 100 || maxCycle.compareTo(minCycle) < 0) {
                throw new IllegalArgumentException("A cycle is at least 100 ms (LIM-8): " + minCycle + ".." + maxCycle);
            }
            if (!(minRunShare > 0 && minRunShare < 1) || !(smoothing > 0 && smoothing <= 1) || intervalsToJudge < 1) {
                throw new IllegalArgumentException("Tuning out of range");
            }
        }

        /** The cycle that keeps suspending within its share of the time: the suspend time times the factor, within the bounds. */
        Duration cycleFor(long suspendNanos) {
            long nanos = (long) (Math.max(0, suspendNanos) * suspendCostFactor);
            return Duration.ofNanos(Math.clamp(nanos, minCycle.toNanos(), maxCycle.toNanos()));
        }
    }

    /** Below this measured run share an interval says nothing about demand; it is passed over. */
    static final double LEAST_RUN_SHARE_TO_JUDGE = 0.02;

    private final Tuning tuning;
    private final double limit;
    private double runShare = 1;
    private OptionalDouble smoothed = OptionalDouble.empty();
    private OptionalDouble lastUse = OptionalDouble.empty();
    private boolean moved;
    private int withinInARow;
    private int aboveAtFloorInARow;
    private int aboveInARow;
    private int unmeasuredInARow;

    /** @param limit the limit as a share of the whole machine, more than 0 and at most 1 */
    DutyCycleController(Tuning tuning, double limit) {
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        if (!(limit > 0 && limit <= 1)) {
            throw new IllegalArgumentException("A limit is a share of the machine above 0 and at most 1: " + limit);
        }
        this.limit = limit;
    }

    double runShare() {
        return runShare;
    }

    /** Whether the target is suspended at all: not while the run share is 1. */
    boolean throttles() {
        return runShare < 1;
    }

    OptionalDouble lastUse() {
        return lastUse;
    }

    /**
     * One interval measured.
     *
     * @param use the target's processor time over the interval, as a share of the machine
     * @param ranShare the part of the interval the target was let run, measured, not planned
     */
    void measured(double use, double ranShare) {
        unmeasuredInARow = 0;
        double clampedUse = Math.clamp(use, 0, 1);
        lastUse = OptionalDouble.of(clampedUse);
        double average = smoothed.isEmpty()
                ? clampedUse
                : tuning.smoothing() * clampedUse + (1 - tuning.smoothing()) * smoothed.getAsDouble();
        smoothed = OptionalDouble.of(average);
        double before = runShare;
        if (ranShare >= LEAST_RUN_SHARE_TO_JUDGE && Math.abs(average - limit) > deadBand()) {
            double demand = Math.clamp(average / Math.min(1, ranShare), 0, 1);
            double target = demand == 0 ? 1 : Math.clamp(limit / demand, tuning.minRunShare(), 1);
            double step = target > runShare ? tuning.stepUp() : tuning.stepDown();
            runShare = Math.clamp(
                    runShare + Math.clamp(target - runShare, -step * runShare, step * runShare),
                    tuning.minRunShare(),
                    1);
        }
        moved = Math.abs(runShare - before) > 1e-9;
        judge(clampedUse);
    }

    /**
     * An interval that could not be measured. After a few in a row the target is let run in full,
     * so that a limit Prism cannot see never keeps it throttled; the limiter decides when to give up.
     */
    void unmeasured() {
        unmeasuredInARow++;
        if (unmeasuredInARow >= tuning.intervalsToJudge()) {
            runShare = 1;
        }
        withinInARow = 0;
        aboveAtFloorInARow = 0;
        aboveInARow = 0;
    }

    int unmeasuredInARow() {
        return unmeasuredInARow;
    }

    /**
     * The target was held by someone else or stopped elsewhere: what was measured meanwhile is not
     * its demand. The next measurement starts the judgement afresh, at the run share it had.
     */
    void suspendJudgement() {
        withinInARow = 0;
        aboveAtFloorInARow = 0;
        aboveInARow = 0;
        moved = false;
    }

    private double deadBand() {
        return Math.max(tuning.deadBandShare() * limit, tuning.deadBandFloor());
    }

    /** The one-sided tolerance of the status: the limit is an upper bound. */
    private double tolerance() {
        return Math.max(0.1 * limit, 0.005);
    }

    private void judge(double use) {
        boolean within = use <= limit + tolerance();
        withinInARow = within ? withinInARow + 1 : 0;
        boolean atFloor = runShare <= tuning.minRunShare() + 1e-9;
        aboveAtFloorInARow = !within && atFloor ? aboveAtFloorInARow + 1 : 0;
        aboveInARow = !within && !atFloor && !moved ? aboveInARow + 1 : 0;
    }

    /** The status as measured so far, with the words that explain it and the lowest use reached when it is the floor. */
    record Judgement(LimitStatus status, Optional<String> detail, LimitNote note, OptionalDouble lowest) {}

    Judgement judgement() {
        if (unmeasuredInARow > 0) {
            return new Judgement(
                    LimitStatus.ADJUSTING,
                    Optional.of("not measured: the processor time could not be read; the process runs until it can"),
                    LimitNote.NOT_MEASURED,
                    OptionalDouble.empty());
        }
        if (lastUse.isEmpty()) {
            return new Judgement(
                    LimitStatus.ADJUSTING, Optional.of("measuring"), LimitNote.MEASURING, OptionalDouble.empty());
        }
        if (!throttles() && withinInARow > 0) {
            return new Judgement(
                    LimitStatus.ACTIVE_NOT_THROTTLING, Optional.empty(), LimitNote.NONE, OptionalDouble.empty());
        }
        if (aboveAtFloorInARow >= tuning.intervalsToJudge()) {
            return new Judgement(
                    LimitStatus.ACTIVE_ABOVE_TARGET,
                    Optional.of("floor: the limit is below the lowest share Prism can hold this process to"),
                    LimitNote.FLOOR,
                    lastUse);
        }
        if (aboveInARow >= tuning.intervalsToJudge()) {
            return new Judgement(
                    LimitStatus.ACTIVE_ABOVE_TARGET,
                    Optional.of("variable load: the use changes faster than the limiter follows"),
                    LimitNote.VARIABLE_LOAD,
                    OptionalDouble.empty());
        }
        if (withinInARow >= tuning.intervalsToJudge() && !moved) {
            return new Judgement(
                    LimitStatus.ACTIVE_THROTTLING, Optional.empty(), LimitNote.NONE, OptionalDouble.empty());
        }
        return new Judgement(LimitStatus.ADJUSTING, Optional.empty(), LimitNote.NONE, OptionalDouble.empty());
    }
}
