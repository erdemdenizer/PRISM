package prism.srm.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.application.SuspensionCoordinator.Change;
import prism.srm.application.SuspensionCoordinator.Outcome;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.ProcessSource;

/**
 * One emulated CPU limit at work (LIM-8, ADR-0004 §3, ADR-0011): stops and continues one process
 * in cycles, through the suspension coordinator for the reason {@code CPU_LIMITER}, measures its
 * processor time about once a second and lets the {@link DutyCycleController} decide the share of
 * each cycle it runs.
 *
 * <p><b>Thread.</b> Everything here runs on the limiter thread ({@link LimiterScheduler}): the steps
 * this schedules and the calls of the limit service. Only {@link #stopForShutdown} may come from
 * another thread.
 *
 * <p><b>Phases.</b> Running: the target runs; at the end of the phase a due measurement is taken
 * and, while the run share is below 1, the target is suspended. Stopped: the limiter holds it; at
 * the end it is released. Held by another: the process was found suspended for another reason (the
 * user's), and the limiter lets its own reason go at once so that the user's Resume continues it;
 * it looks once a second whether the other hold has ended. Paused: the process was found stopped by
 * another program; the limiter sends it nothing and tries again once a second (ADR-0011). After a
 * failed suspend that holds nothing, the next step, one cycle later, only looks whether the target
 * has exited and ends the limit (see {@link #FAILED_SUSPENDS_TO_END}); meanwhile the phase and the
 * state shown stay as they were and nothing is suspended.
 *
 * <p><b>Ending.</b> Every step runs in {@code try/finally}: a step that does not schedule the next
 * one or end the limit properly lets go of what it holds and ends the limit as failed. Only the
 * running thread's release undoes a stop the limiter made; at shutdown the run releases nothing,
 * the shutdown sequence releases every automatic reason after the limiter thread has stopped.
 *
 * <p><b>Cycle.</b> The cycle is ten times the time a suspend takes, within the tuning's bounds: on
 * Windows a suspend lists every thread of the system and takes about 110 ms on a desktop. The run
 * phase is shortened by that time, because the target runs while it is being suspended; what it
 * actually ran is measured from the clock, not planned.
 */
final class LimitRun {

    private static final Logger LOG = LoggerFactory.getLogger(LimitRun.class);

    /** How often the processor time is measured: about once a second, at the end of a run phase. */
    static final Duration INTERVAL = Duration.ofSeconds(1);

    /** After this many unreadable intervals in a row the limit ends; the target already runs in full after three. */
    static final int UNREADABLE_TO_END = 10;

    /**
     * A failed suspend that the platform classified as transient (one thread refused, everything
     * undone, nothing held) is tried again in the next cycle; after this many in a row the limit
     * ends. Any other failure ends the limit and is never tried again: a refusal or a failure of the
     * process as a whole, a journal failure, an identity that no longer matches (an exit), a stop
     * made elsewhere (a pause, not a failure), or a suspension that left anything held. A journal
     * failure, an exit and anything left held end it at once.
     *
     * <p>A failure that holds nothing (a refusal, or the last transient one) ends the limit a cycle
     * later: a thread of a process that is exiting may refuse a suspend before the system reports the
     * process as gone (the Windows runner of 2026-10-10: "SuspendThread failed: 5 (Access is
     * denied.)"; on the development machine 12 of 60 provoked exits, read as gone within 16 ms). The
     * processor time is read once more then; a target gone by then ends the limit as an exit, any
     * other ends it as the failure it was (ADR-0011, amended 2026-10-11). Nothing is suspended
     * meanwhile.
     */
    static final int FAILED_SUSPENDS_TO_END = 3;

    enum Phase {
        RUNNING,
        STOPPED,
        HELD_BY_OTHER,
        PAUSED,
        ENDED
    }

    /** Why a limit ended. */
    enum End {
        /** Removed, disabled or no longer managed: released, and the journal entry may go. */
        CANCELLED,
        /** The process exited, or its PID belongs to another process now. */
        EXITED,
        /** A suspend, a release or a reading failed: released what could be, and the entry may go. */
        FAILED,
        /** The journal failed: released what the limiter held; the entry stays as evidence. */
        JOURNAL,
        /** Prism shuts down: nothing released here; the shutdown sequence releases every limit. */
        SHUTDOWN
    }

    /** Told on the limiter thread. Never calls back into the run. */
    interface Listener {
        /** The state the user is shown may have changed. */
        void changed(LimitRun run);

        /** The limit ended; the run does nothing more. */
        void ended(LimitRun run, End end, Optional<String> reason);
    }

    /** What the user is shown of a limit at work; shares of the whole machine, 0 to 1. */
    record State(
            LimitStatus status,
            Optional<String> detail,
            LimitNote note,
            OptionalDouble measured,
            OptionalDouble lowest) {
        State {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(note, "note");
            Objects.requireNonNull(measured, "measured");
            Objects.requireNonNull(lowest, "lowest");
        }
    }

    private final ProcessIdentity identity;
    private final String name;
    private final SuspensionCoordinator coordinator;
    private final ProcessSource source;
    private final LimiterScheduler scheduler;
    private final LongSupplier nanoClock;
    private final int processors;
    private final DutyCycleController.Tuning tuning;
    private final DutyCycleController duty;
    private final Listener listener;

    private Phase phase = Phase.RUNNING;
    private volatile Future<?> next;
    private volatile boolean shuttingDown;

    /** When the current interval of measurement began, and how long the target ran within it. */
    private long intervalStart;

    private long ranNanos;
    /** When the target was last let run. */
    private long runningSince;
    /** The processor time at the start of the interval, if it could be read. */
    private OptionalLong baselineMillis = OptionalLong.empty();

    private int unreadableInARow;
    private int failedSuspendsInARow;
    /** The failure of a suspend that held nothing, while the next step looks whether the target has exited. */
    private Optional<String> failureToConfirm = Optional.empty();
    /** The time a suspend takes, smoothed; empty until one was made. */
    private OptionalLong suspendNanos = OptionalLong.empty();

    /**
     * @param limit the limit as a share of the whole machine, above 0 and at most 1
     * @param processors the logical processors the shares are measured against (PROC-2)
     */
    LimitRun(
            ProcessIdentity identity,
            String name,
            double limit,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            DutyCycleController.Tuning tuning,
            Listener listener) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.name = Objects.requireNonNull(name, "name");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.source = Objects.requireNonNull(source, "source");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (processors < 1) {
            throw new IllegalArgumentException("At least one logical processor: " + processors);
        }
        this.processors = processors;
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        this.duty = new DutyCycleController(tuning, limit);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    ProcessIdentity identity() {
        return identity;
    }

    Phase phase() {
        return phase;
    }

    /** The share of each cycle the target runs now; 1 while it is not suspended at all. */
    double runShare() {
        return duty.runShare();
    }

    /** The cycle in use: from the measured time of a suspend, the shortest one until a suspend was made. */
    Duration cycle() {
        return suspendNanos.isPresent() ? tuning.cycleFor(suspendNanos.getAsLong()) : tuning.minCycle();
    }

    State state() {
        return switch (phase) {
            case HELD_BY_OTHER ->
                new State(
                        LimitStatus.HELD_BY_OTHER,
                        Optional.of(
                                coordinator.heldFor(identity).contains(SuspensionReason.OOM_GUARD)
                                        ? "suspended by OOM Guard: the limit goes on when it releases the process"
                                        : "suspended by you: the limit goes on when you resume it"),
                        LimitNote.NONE,
                        OptionalDouble.empty(),
                        OptionalDouble.empty());
            case PAUSED ->
                new State(
                        LimitStatus.PAUSED_EXTERNAL_STOP,
                        Optional.of("stopped by another program: Prism waits and does not continue it"),
                        LimitNote.NONE,
                        OptionalDouble.empty(),
                        OptionalDouble.empty());
            default -> {
                DutyCycleController.Judgement judged = duty.judgement();
                yield new State(judged.status(), judged.detail(), judged.note(), duty.lastUse(), judged.lowest());
            }
        };
    }

    /** Starts the limit: the first interval lets the target run in full and measures what it uses. */
    void start() {
        long now = nanoClock.getAsLong();
        restartInterval(now);
        runningSince = now;
        if (phase != Phase.ENDED) {
            schedule(INTERVAL);
        }
    }

    /** Ends the limit on the user's request: what the limiter holds is released. On the limiter thread. */
    void cancel() {
        finish(End.CANCELLED, Optional.empty());
    }

    /** Ends the limit because the journal failed: what the limiter holds is released, nothing is withdrawn. */
    void stopForJournal(Optional<String> problem) {
        finish(End.JOURNAL, problem);
    }

    /**
     * Prism shuts down: the next step ends the limit without touching the process, and a pending
     * step is called off. Any thread; the shutdown sequence releases what is held.
     */
    void stopForShutdown() {
        shuttingDown = true;
        Future<?> pending = next;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void step() {
        boolean settled = false;
        try {
            advance();
            settled = true;
        } catch (RuntimeException e) {
            LOG.error("The CPU limit of {} (PID {}) failed", name, identity.pid(), e);
        } finally {
            if (!settled && phase != Phase.ENDED) {
                // Not even an Error leaves the process stopped: the release runs, the Error goes on.
                finish(End.FAILED, Optional.of("an unexpected error ended the limit; the Logs page has the details"));
            }
        }
    }

    private void advance() {
        if (phase == Phase.ENDED) {
            return;
        }
        if (shuttingDown) {
            endHere(End.SHUTDOWN, Optional.empty());
            return;
        }
        if (!coordinator.journalHealthy()) {
            finish(End.JOURNAL, coordinator.journalProblem());
            return;
        }
        if (failureToConfirm.isPresent()) {
            confirmFailure(failureToConfirm.get());
            return;
        }
        long now = nanoClock.getAsLong();
        switch (phase) {
            case RUNNING -> afterRunning(now);
            case STOPPED -> afterStopped();
            case HELD_BY_OTHER -> whileHeldByOther(now);
            case PAUSED -> whilePaused(now);
            case ENDED -> {
                // Nothing to do.
            }
        }
    }

    private void afterRunning(long now) {
        if (!coordinator.heldFor(identity).isEmpty()) {
            // Suspended for another reason, the user's: what it uses now is not its demand.
            heldByAnother();
            return;
        }
        ranNanos += now - runningSince;
        runningSince = now;
        if (now - intervalStart >= INTERVAL.toNanos()) {
            measure(now);
            if (phase == Phase.ENDED) {
                return;
            }
        }
        if (!duty.throttles()) {
            // A run share of 1 suspends nothing (LIM-8): the next look is the next measurement.
            schedule(INTERVAL);
            return;
        }
        suspendNow();
    }

    private void suspendNow() {
        long before = nanoClock.getAsLong();
        Outcome outcome = coordinator.suspend(identity, name, SuspensionReason.CPU_LIMITER);
        long after = nanoClock.getAsLong();
        switch (outcome.change()) {
            case SUSPENDED -> {
                failedSuspendsInARow = 0;
                learnSuspendTime(after - before);
                if (phase == Phase.RUNNING) {
                    ranNanos += after - runningSince;
                }
                phase = Phase.STOPPED;
                schedule(stopPhase());
            }
            case ALREADY_HELD -> {
                // Held for another reason as well: the limiter's own goes at once, so that the
                // other holder's release continues the process.
                Outcome let = coordinator.release(identity, SuspensionReason.CPU_LIMITER);
                if (let.change() == Change.INCOMPLETE) {
                    finish(End.FAILED, let.outcome().detail());
                    return;
                }
                heldByAnother();
            }
            case STOPPED_ELSEWHERE -> {
                boolean was = phase == Phase.PAUSED;
                phase = Phase.PAUSED;
                duty.suspendJudgement();
                schedule(INTERVAL);
                if (!was) {
                    LOG.info("{} (PID {}) is stopped by another program; its CPU limit waits", name, identity.pid());
                    listener.changed(this);
                }
            }
            case REFUSED -> endHere(End.SHUTDOWN, Optional.empty());
            case FAILED -> {
                if (!coordinator.journalHealthy()) {
                    // The journal failed between the check of this step and the suspend: refused for it.
                    endHere(End.JOURNAL, coordinator.journalProblem());
                } else if (outcome.outcome().result() == OperationResult.PROCESS_NOT_FOUND) {
                    endHere(End.EXITED, Optional.empty());
                } else if (!coordinator.heldFor(identity).isEmpty()) {
                    endHere(End.FAILED, Optional.of(describe(outcome)));
                } else if (!outcome.retryable() || ++failedSuspendsInARow >= FAILED_SUSPENDS_TO_END) {
                    // Nothing is held: whether the target is exiting is looked at once more before it is a failure.
                    LOG.info(
                            "A suspend for the CPU limit of {} (PID {}) failed, holding nothing; whether it is exiting"
                                    + " is looked at once more: {}",
                            name,
                            identity.pid(),
                            describe(outcome));
                    failureToConfirm = Optional.of(describe(outcome));
                    runningSince = after;
                    schedule(cycle());
                } else {
                    // Nothing is held: the target runs one more cycle, and the next suspend is tried then.
                    LOG.info(
                            "A suspend for the CPU limit of {} (PID {}) failed, holding nothing: {}",
                            name,
                            identity.pid(),
                            describe(outcome));
                    runningSince = after;
                    schedule(cycle());
                }
            }
            case INCOMPLETE -> {
                // The coordinator keeps what could not be undone and shows it; one more try to let it go.
                coordinator.release(identity, SuspensionReason.CPU_LIMITER);
                endHere(End.FAILED, Optional.of(describe(outcome)));
            }
            case RESUMED, STILL_HELD, NOT_HELD, EXITED ->
                endHere(End.FAILED, Optional.of("unexpected answer to a suspend: " + outcome.change()));
        }
    }

    /** The look after a failed suspend that held nothing: an exit, or the failure it was. */
    private void confirmFailure(String failure) {
        failureToConfirm = Optional.empty();
        if (source.cpuTime(identity) instanceof CpuTimeReading.Gone) {
            endHere(End.EXITED, Optional.empty());
        } else {
            finish(End.FAILED, Optional.of(failure));
        }
    }

    private void afterStopped() {
        Outcome outcome = coordinator.release(identity, SuspensionReason.CPU_LIMITER);
        long after = nanoClock.getAsLong();
        switch (outcome.change()) {
            case RESUMED -> runAgain(after);
            case NOT_HELD -> {
                if (outcome.remaining().isEmpty()) {
                    runAgain(after);
                } else {
                    heldByAnother();
                }
            }
            case STILL_HELD -> heldByAnother();
            case EXITED -> endHere(End.EXITED, Optional.empty());
            case INCOMPLETE -> endHere(End.FAILED, Optional.of(describe(outcome)));
            case SUSPENDED, ALREADY_HELD, REFUSED, FAILED, STOPPED_ELSEWHERE ->
                endHere(End.FAILED, Optional.of("unexpected answer to a release: " + outcome.change()));
        }
    }

    private void runAgain(long now) {
        phase = Phase.RUNNING;
        runningSince = now;
        schedule(runPhase());
    }

    private void heldByAnother() {
        boolean was = phase == Phase.HELD_BY_OTHER;
        phase = Phase.HELD_BY_OTHER;
        duty.suspendJudgement();
        schedule(INTERVAL);
        if (!was) {
            listener.changed(this);
        }
    }

    private void whileHeldByOther(long now) {
        if (!coordinator.heldFor(identity).isEmpty()) {
            schedule(INTERVAL);
            return;
        }
        // The other hold has ended: limiting goes on at the run share it had, measured afresh.
        restartInterval(now);
        if (phase == Phase.ENDED) {
            return;
        }
        runAgain(now);
        listener.changed(this);
    }

    private void whilePaused(long now) {
        restartInterval(now);
        if (phase == Phase.ENDED) {
            return;
        }
        runningSince = nanoClock.getAsLong();
        suspendNow();
        if (phase == Phase.STOPPED) {
            listener.changed(this);
        }
    }

    /** Takes one measurement at the end of an interval and gives it to the duty cycle. */
    private void measure(long now) {
        long elapsed = now - intervalStart;
        double ranShare = elapsed > 0 ? Math.min(1, ranNanos / (double) elapsed) : 0;
        CpuTimeReading reading = source.cpuTime(identity);
        switch (reading) {
            case CpuTimeReading.Gone _ -> {
                endHere(End.EXITED, Optional.empty());
                return;
            }
            case CpuTimeReading.Unreadable unreadable -> {
                unreadableInARow++;
                baselineMillis = OptionalLong.empty();
                duty.unmeasured();
                if (unreadableInARow >= UNREADABLE_TO_END) {
                    endHere(End.FAILED, Optional.of("the processor time could not be read: " + unreadable.reason()));
                    return;
                }
            }
            case CpuTimeReading.Read read -> {
                unreadableInARow = 0;
                long stale = 2 * Math.max(INTERVAL.toNanos(), cycle().toNanos());
                if (baselineMillis.isPresent() && elapsed > 0 && elapsed <= stale) {
                    long used = read.cpuMillis() - baselineMillis.getAsLong();
                    // A counter that went back (MON-10) says nothing about this interval.
                    if (used >= 0) {
                        double use = used * 1_000_000.0 / ((double) elapsed * processors);
                        duty.measured(use, ranShare);
                    }
                }
                baselineMillis = OptionalLong.of(read.cpuMillis());
            }
        }
        intervalStart = now;
        ranNanos = 0;
        listener.changed(this);
    }

    /** Starts a new interval of measurement now: the processor time it starts from, and nothing run yet. */
    private void restartInterval(long now) {
        intervalStart = now;
        ranNanos = 0;
        runningSince = now;
        CpuTimeReading reading = source.cpuTime(identity);
        switch (reading) {
            case CpuTimeReading.Gone _ -> endHere(End.EXITED, Optional.empty());
            case CpuTimeReading.Unreadable _ -> baselineMillis = OptionalLong.empty();
            case CpuTimeReading.Read read -> baselineMillis = OptionalLong.of(read.cpuMillis());
        }
    }

    private void learnSuspendTime(long nanos) {
        long taken = Math.max(0, nanos);
        suspendNanos = OptionalLong.of(suspendNanos.isEmpty() ? taken : (suspendNanos.getAsLong() + taken) / 2);
    }

    /** The run phase as scheduled: its share of the cycle, less the time the next suspend takes. */
    private Duration runPhase() {
        long cycle = cycle().toNanos();
        long run = Math.round(duty.runShare() * cycle) - suspendNanos.orElse(0);
        return Duration.ofNanos(Math.max(0, run));
    }

    private Duration stopPhase() {
        long cycle = cycle().toNanos();
        return Duration.ofNanos(Math.max(0, cycle - Math.round(duty.runShare() * cycle)));
    }

    private void schedule(Duration delay) {
        try {
            next = scheduler.schedule(this::step, delay);
        } catch (RejectedExecutionException shutDown) {
            // The limiter thread is shut down: the shutdown sequence releases what is held.
            endHere(End.SHUTDOWN, Optional.empty());
        }
    }

    /** Ends the limit, first releasing the limiter's hold where it has one. */
    private void finish(End end, Optional<String> reason) {
        if (phase == Phase.ENDED) {
            return;
        }
        try {
            // Also outside the stop phase: a suspend that threw after it took hold leaves the
            // coordinator holding the process for the limiter while the run still says running.
            boolean holding =
                    phase == Phase.STOPPED || coordinator.heldFor(identity).contains(SuspensionReason.CPU_LIMITER);
            if (holding) {
                Outcome released = coordinator.release(identity, SuspensionReason.CPU_LIMITER);
                if (released.change() == Change.INCOMPLETE) {
                    LOG.error(
                            "The CPU limit of {} (PID {}) could not release every thread: {}",
                            name,
                            identity.pid(),
                            describe(released));
                }
            }
        } finally {
            endHere(end, reason);
        }
    }

    /** Ends the limit as it stands: nothing is released here. */
    private void endHere(End end, Optional<String> reason) {
        if (phase == Phase.ENDED) {
            return;
        }
        phase = Phase.ENDED;
        Future<?> pending = next;
        if (pending != null) {
            pending.cancel(false);
        }
        LOG.info(
                "The CPU limit of {} (PID {}) ended: {}{}",
                name,
                identity.pid(),
                end,
                reason.map(text -> " (" + text + ")").orElse(""));
        listener.ended(this, end, reason);
    }

    private static String describe(Outcome outcome) {
        return outcome.outcome().detail().orElse(outcome.outcome().result().name());
    }
}
