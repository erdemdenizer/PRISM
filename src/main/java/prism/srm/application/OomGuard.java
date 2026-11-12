package prism.srm.application;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.application.OomStateMachine.Transition;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveAction;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.Listed;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomCandidateOrder;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;
import prism.srm.domain.SuspensionReason;
import prism.srm.domain.VersionedSettings;
import prism.srm.platform.ProcessController;

/**
 * OOM Guard's interventions on Managed Processes (OOM-5 to OOM-12, LIM-9, ADR-0015).
 *
 * <p><b>Thread.</b> Everything below runs on the limiter thread ({@code srm-limiter}), the thread
 * that owns the managed registry: the limit service pushes the managed processes to it there, and the
 * monitors' samples and listings, the settings, a failed journal and the end of monitoring each queue
 * one evaluation, at most one waiting. Only {@link #stop()} and the queueing methods are called from
 * other threads; {@link #restorePriorities} runs on the shutdown thread once the limiter has ended.
 *
 * <p><b>Every new intervention passes one gate</b> right before its native call: the trigger fits the
 * intent (pressure: a sample; memory policy: a listing), the observation was made under the settings
 * now in force, the guard is not stopped, monitoring is on, the reading is fresh and neither missing,
 * after a gap, nor after monitoring ended, the reading itself (not only the state) still meets the
 * level and the settle interval has passed, the switch and the system allow the action, the process
 * is listed and not protected, and the incident ledger allows it. At most one native intervention
 * per evaluation. Releases need no gate and happen on every evaluation.
 *
 * <p><b>Suspension</b>: two logical claims (pressure, memory limit) on the one physical OOM Guard
 * hold of the suspension coordinator. The hold is declared in the journal before the first claim
 * suspends, and released only when the last claim goes. A release the platform leaves incomplete is
 * tried again on every evaluation.
 *
 * <p><b>Lowered priorities</b> are restored to the level they had, one per evaluation after the
 * resumes; never twice, never over a level the user set meanwhile, and a failure is an error.
 */
public final class OomGuard {

    private static final Logger LOG = LoggerFactory.getLogger(OomGuard.class);

    /** A candidate that is passed over with nothing to say about it. */
    private static final Optional<String> SKIP = Optional.of("");

    /**
     * The fixed numbers of the interventions (plan O13).
     *
     * @param settle how long OOM Guard waits after an emergency action before the next (OOM-10)
     * @param gap the oldest an observation may be for an action to rely on it
     * @param policyMarginPercent below the memory limit by this share, at least {@code policyMarginBytes}
     *     and at most a {@code policyMarginCapDivisor}th of the limit, the memory policy lets go
     */
    public record Tuning(
            Duration settle,
            Duration gap,
            int policyMarginPercent,
            long policyMarginBytes,
            int policyMarginCapDivisor) {

        public static final Tuning DEFAULTS = new Tuning(Duration.ofSeconds(5), Duration.ofSeconds(5), 5, 4L << 20, 2);

        public Tuning {
            Objects.requireNonNull(settle, "settle");
            Objects.requireNonNull(gap, "gap");
            if (settle.isNegative()
                    || gap.isNegative()
                    || policyMarginPercent < 0
                    || policyMarginPercent > 100
                    || policyMarginBytes < 0
                    || policyMarginCapDivisor < 2) {
                throw new IllegalArgumentException("Not a tuning of OOM Guard");
            }
        }
    }

    /** What queued an evaluation (ADR-0015 §2, item 0). */
    public enum Trigger {
        SAMPLE,
        LISTING,
        SETTINGS,
        MANAGED,
        JOURNAL,
        MONITOR_ENDED
    }

    /**
     * A Managed Process as the limit service hands it over.
     *
     * @param watchedLimit the memory limit OOM Guard watches as resident memory (LIM-9); empty when it
     *     watches none for the process
     * @param watchGeneration one more each time the user sets the watched limit again: a new watch,
     *     whose first listing above the limit counts as a crossing
     */
    public record Managed(ManagedProcess process, OptionalLong watchedLimit, long watchGeneration) {
        public Managed {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(watchedLimit, "watchedLimit");
            if (watchedLimit.isPresent() && !ManagedSettings.validMemoryBytes(watchedLimit.getAsLong())) {
                throw new IllegalArgumentException("Not a memory limit: " + watchedLimit);
            }
        }

        public ProcessIdentity identity() {
            return process.identity();
        }
    }

    /**
     * Where the memory policy of one process stands, for the Limits page.
     *
     * @param exceeded the last listing showed it above its limit
     * @param held OOM Guard holds an intervention on it for its limit
     * @param acted OOM Guard applied the emergency action for the last crossing
     */
    public record PolicyStatus(
            long limitBytes,
            OptionalLong residentBytes,
            boolean exceeded,
            boolean held,
            boolean acted,
            Optional<String> notNow) {
        public PolicyStatus {
            Objects.requireNonNull(residentBytes, "residentBytes");
            Objects.requireNonNull(notNow, "notNow");
        }
    }

    private enum Intent {
        LOWER_AT_CRITICAL,
        EMERGENCY,
        POLICY
    }

    /** What OOM Guard knows and holds of one Managed Process; limiter thread only. */
    private static final class Entry {
        ManagedProcess process;
        OptionalLong watchedLimit = OptionalLong.empty();
        long watchGeneration = -1;
        /** In the last listing; null when it was not listed. */
        Listed last;

        boolean armed;
        OptionalLong previousResident = OptionalLong.empty();
        int clearStreak;
        boolean policyCleared;
        boolean exceededPending;
        boolean policyActed;

        final EnumSet<Claim> suspendClaims = EnumSet.noneOf(Claim.class);
        OomHold.Physical physical = OomHold.Physical.NONE;
        final EnumSet<Claim> priorityClaims = EnumSet.noneOf(Claim.class);
        Optional<PriorityLevel> previousPriority = Optional.empty();
        boolean terminateRequested;
        Optional<String> failed = Optional.empty();
        Optional<String> notNow = Optional.empty();

        Entry(ManagedProcess process) {
            this.process = process;
        }

        ProcessIdentity identity() {
            return process.identity();
        }

        OomManagement oom() {
            return process.settings().oom();
        }

        String label() {
            return process.name() + " (PID " + identity().pid() + ")";
        }

        OptionalLong resident() {
            return last == null ? OptionalLong.empty() : last.residentBytes();
        }

        OomHold hold() {
            return new OomHold(
                    suspendClaims,
                    physical,
                    exceededPending,
                    priorityClaims,
                    previousPriority,
                    terminateRequested,
                    failed);
        }
    }

    private final ProcessController controller;
    private final ProtectionRules rules;
    private final SuspensionCoordinator coordinator;
    private final ActivityLog activity;
    private final LimiterScheduler scheduler;
    private final LongSupplier nanoClock;
    private final InstantSource clock;
    private final Supplier<VersionedSettings> settings;
    private final Consumer<OomView> views;
    private final Tuning tuning;
    private final OomSignalStatus signal;
    private final Restorability restorability;
    private final long settleNanos;
    private final long gapNanos;

    // Any thread.
    private final AtomicReference<OomObservation> latest = new AtomicReference<>();
    private final AtomicReference<Map<ProcessIdentity, Listed>> pendingListing = new AtomicReference<>();
    private final AtomicReference<Set<Trigger>> pendingTriggers = new AtomicReference<>(Set.of());
    private final AtomicBoolean evaluatePending = new AtomicBoolean();
    private final AtomicBoolean journalSignal = new AtomicBoolean();
    private volatile boolean stopped;

    /**
     * The action lifecycle (ADR-0015 §2 and §6). A native intervention begins only under this lock,
     * and only while OOM Guard is not stopped and the settings in force are still those the gate
     * authorized it under. {@link #stop()} sets its flag under the lock, and the single writer of the
     * settings publishes them under it ({@link #publishSettings}). Once either returns, nothing begins
     * under what it replaced; one call that began before may still be in flight, and {@link
     * #awaitActionsEnded} waits for it within a bound. No lock is held during a native call.
     */
    private final Object actionLock = new Object();

    private int actionsInFlight;

    /** The version of the settings the gate last authorized an action under. The limiter thread only. */
    private long authorizedVersion = -1;

    /** A test seam: runs after the gate authorized an action, right before it is dispatched. */
    volatile Runnable beforeDispatch = () -> {};

    private volatile boolean monitorEnded;

    // The limiter thread only.
    private final Map<ProcessIdentity, Entry> entries = new LinkedHashMap<>();
    private final Set<ProcessIdentity> loweredAtCritical = new HashSet<>();
    private final Set<ProcessIdentity> emergencyActed = new HashSet<>();
    private final Set<ProcessIdentity> terminateSent = new HashSet<>();
    private final Set<ProcessIdentity> refused = new HashSet<>();
    private final Deque<OomActionRecord> actions = new ArrayDeque<>();
    private final Set<String> failuresLogged = new HashSet<>();
    /** Holds whose release was tried in this evaluation already: an incomplete one is tried again in the next. */
    private final Set<ProcessIdentity> releasedNow = new HashSet<>();

    private boolean incident;
    private boolean settleSet;
    private long settleUntil;
    private boolean journalDown;
    private Optional<String> notice = Optional.empty();
    private OomState shownState = OomState.NORMAL;
    private Optional<Instant> since = Optional.empty();

    /**
     * @param settings the settings as published, with their version; read once per decision
     * @param views receives every view of OOM Guard, on the limiter thread; must return quickly
     * @param signal whether the memory readings are validated on this system (ADR-0015 §7)
     */
    public OomGuard(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            InstantSource clock,
            Supplier<VersionedSettings> settings,
            Consumer<OomView> views,
            Tuning tuning,
            OomSignalStatus signal) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.views = Objects.requireNonNull(views, "views");
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        this.signal = Objects.requireNonNull(signal, "signal");
        this.restorability = Objects.requireNonNull(controller.priorityRestorability(), "restorability");
        this.settleNanos = tuning.settle().toNanos();
        this.gapNanos = tuning.gap().toNanos();
    }

    /** Whether the memory readings are validated on this system. Any thread. */
    public OomSignalStatus signal() {
        return signal;
    }

    /**
     * Below this resident memory the memory policy counts a listing as clear of the limit: the limit
     * less a margin of {@code percent} of it, at least {@code marginBytes} and at most a
     * {@code capDivisor}th of it. Reachable for every valid limit, and without overflow.
     */
    public static long clearBelow(long limitBytes, Tuning tuning) {
        long margin = Math.min(
                Math.max(
                        OomStateMachine.floorFrac(limitBytes, tuning.policyMarginPercent()),
                        tuning.policyMarginBytes()),
                limitBytes / tuning.policyMarginCapDivisor());
        return limitBytes - margin;
    }

    // ----- Queued from other threads -----

    /** A new observation of the system monitor. Any thread; returns at once. */
    public void observed(OomObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (stopped) {
            return;
        }
        latest.set(observation);
        trigger(Trigger.SAMPLE);
    }

    /** What OOM Guard keeps of a new listing. Any thread; returns at once. No listing is kept beyond the next evaluation. */
    public void listed(Map<ProcessIdentity, Listed> listing) {
        Objects.requireNonNull(listing, "listing");
        if (stopped) {
            return;
        }
        pendingListing.set(Map.copyOf(listing));
        trigger(Trigger.LISTING);
    }

    /** The settings were published again. Starts no intervention by itself. Any thread. */
    public void settingsChanged() {
        trigger(Trigger.SETTINGS);
    }

    /**
     * Publishes new settings in order with the start of every native intervention: the publication
     * runs under the action lock, so an intervention the gate authorized under the settings it
     * replaces either began before it (and may still be in flight) or does not begin at all. Returns
     * what the publication returns. The single writer of the settings calls this, then {@link
     * #settingsChanged()}. Never waits for a native call.
     */
    public VersionedSettings publishSettings(Supplier<VersionedSettings> publication) {
        synchronized (actionLock) {
            return publication.get();
        }
    }

    /** The suspension journal failed (plan O14). Any thread; idempotent. */
    public void journalFailed(String problem) {
        journalSignal.set(true);
        trigger(Trigger.JOURNAL);
    }

    /** System monitoring ended for the session: OOM Guard reads nothing more and keeps what it holds (O5). */
    public void monitorEnded() {
        monitorEnded = true;
        trigger(Trigger.MONITOR_ENDED);
    }

    /** The user ends everything OOM Guard holds on the process. Any thread. */
    public void releaseHold(ProcessIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        try {
            scheduler.execute(() -> {
                try {
                    Entry entry = entries.get(identity);
                    if (entry != null) {
                        releaseEverything(entry, "at the user's request");
                    }
                } finally {
                    publish();
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // Shutting down: the shutdown releases every hold of OOM Guard.
        }
    }

    /**
     * From now on nothing new is done: every native intervention checks this right before it is made.
     * The last view says nothing is claimed; the coordinator releases the suspensions in the shutdown.
     * Any thread.
     */
    public void stop() {
        synchronized (actionLock) {
            stopped = true;
        }
        try {
            scheduler.execute(() -> {
                for (Entry entry : entries.values()) {
                    entry.suspendClaims.clear();
                    entry.priorityClaims.clear();
                }
                publish();
            });
        } catch (RejectedExecutionException shutDown) {
            // The limiter has ended already.
        }
    }

    /**
     * Waits, at most for the bound, until no intervention that began before {@link #stop()} is still
     * in flight. Returns whether none is. Never waits for one that has not begun: none can begin after
     * stop. Any thread; an interrupt ends the wait and is kept.
     */
    public boolean awaitActionsEnded(Duration within) {
        long end = System.nanoTime() + within.toNanos();
        synchronized (actionLock) {
            while (actionsInFlight > 0) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    actionLock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return actionsInFlight == 0;
                }
            }
            return true;
        }
    }

    /**
     * Begins a native intervention, or refuses it when OOM Guard has stopped since the gate passed.
     * Every true answer is followed by {@link #endAction()} in a finally block.
     */
    private boolean beginAction(Entry entry) {
        beforeDispatch.run();
        synchronized (actionLock) {
            if (stopped) {
                entry.notNow = notNow("stopped");
                return false;
            }
            // The settings may have been published since the gate: what it authorized is void then.
            if (settings.get().version() != authorizedVersion) {
                entry.notNow = notNow("settings changed; waiting for a sample");
                return false;
            }
            actionsInFlight++;
            return true;
        }
    }

    private void endAction() {
        synchronized (actionLock) {
            actionsInFlight--;
            actionLock.notifyAll();
        }
    }

    private void trigger(Trigger trigger) {
        if (stopped) {
            return;
        }
        pendingTriggers.updateAndGet(previous -> {
            EnumSet<Trigger> next = EnumSet.of(trigger);
            next.addAll(previous);
            return Collections.unmodifiableSet(next);
        });
        if (!evaluatePending.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(this::runEvaluation);
        } catch (RejectedExecutionException shutDown) {
            evaluatePending.set(false);
        }
    }

    // ----- Pushed by the limit service, on the limiter thread -----

    /**
     * The Managed Processes now, with the memory limit OOM Guard watches on each. A process no longer
     * there is forgotten: what OOM Guard held on it is released at once; the incident ledger keeps it.
     */
    public void managedChanged(List<Managed> managed) {
        Set<ProcessIdentity> seen = new HashSet<>();
        for (Managed process : managed) {
            seen.add(process.identity());
            Entry entry = entries.computeIfAbsent(process.identity(), unused -> new Entry(process.process()));
            entry.process = process.process();
            if (!entry.watchedLimit.equals(process.watchedLimit())
                    || entry.watchGeneration != process.watchGeneration()) {
                rewatch(entry, process.watchedLimit(), process.watchGeneration());
            }
        }
        for (Entry entry : List.copyOf(entries.values())) {
            if (!seen.contains(entry.identity())) {
                forget(entry);
            }
        }
        trigger(Trigger.MANAGED);
    }

    /** What the OOM settings of a process would be in effect now (OOM-8). Limiter thread. */
    public EffectiveAction effective(OomManagement oom) {
        return EffectiveAction.of(
                oom,
                settings.get().settings().switches(),
                controller.capabilities(),
                coordinator.journalHealthy() && !journalDown,
                restorability,
                signal);
    }

    /** Where the memory policy of the process stands; empty when its memory is not watched. Limiter thread. */
    public Optional<PolicyStatus> policyStatus(ProcessIdentity identity) {
        Entry entry = entries.get(identity);
        if (entry == null || entry.watchedLimit.isEmpty()) {
            return Optional.empty();
        }
        long limit = entry.watchedLimit.getAsLong();
        OptionalLong resident = entry.resident();
        return Optional.of(new PolicyStatus(
                limit,
                resident,
                resident.isPresent() && resident.getAsLong() > limit,
                entry.suspendClaims.contains(Claim.RESIDENT_POLICY)
                        || entry.priorityClaims.contains(Claim.RESIDENT_POLICY),
                entry.policyActed,
                entry.exceededPending ? entry.notNow : Optional.empty()));
    }

    private void rewatch(Entry entry, OptionalLong limit, long generation) {
        entry.watchedLimit = limit;
        entry.watchGeneration = generation;
        entry.armed = limit.isPresent();
        entry.previousResident = OptionalLong.empty();
        entry.clearStreak = 0;
        entry.policyCleared = false;
        entry.exceededPending = false;
        entry.policyActed = false;
    }

    // ----- At the shutdown -----

    /**
     * Restores, within the bound, every priority OOM Guard lowered (plan O7): on the shutdown thread,
     * once the limiter has ended. Logs what it left lowered. Returns whether nothing was left.
     */
    public boolean restorePriorities(Duration within) {
        long deadline = nanoClock.getAsLong() + within.toNanos();
        List<String> left = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.previousPriority.isEmpty()) {
                continue;
            }
            if (nanoClock.getAsLong() - deadline >= 0) {
                left.add(entry.label());
                continue;
            }
            entry.priorityClaims.clear();
            if (!restore(entry, "at shutdown")) {
                left.add(entry.label());
            }
        }
        if (!left.isEmpty()) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "OOM Guard left the priority of " + String.join(", ", left)
                            + " lowered at shutdown: it could not be restored in time or at all.");
        }
        return left.isEmpty();
    }

    // ----- One evaluation -----

    private void runEvaluation() {
        // Cleared first: a trigger that arrives while this runs queues the next evaluation.
        evaluatePending.set(false);
        Set<Trigger> triggers = pendingTriggers.getAndSet(Set.of());
        try {
            evaluate(triggers);
        } catch (RuntimeException failure) {
            if (failuresLogged.add(failure.getClass().getName())) {
                activity.record(
                        ActivitySeverity.ERROR,
                        "OOM Guard could not complete an evaluation: " + failure + ". It tries again with the next"
                                + " sample.",
                        failure);
            }
        } finally {
            publish();
        }
    }

    private void evaluate(Set<Trigger> triggers) {
        releasedNow.clear();
        Map<ProcessIdentity, Listed> listing = pendingListing.getAndSet(null);
        if (listing != null) {
            applyListing(listing);
        }
        OomObservation observation = latest.get();
        // One read of the settings for the evaluation; the gate reads them once more, right before a
        // native call (ADR-0015 section 2).
        OomSettings current = settings.get().settings();
        followIncident(observation);
        followJournal();
        for (Entry entry : entries.values()) {
            entry.notNow = Optional.empty();
        }
        notice = Optional.empty();
        if (stopped) {
            // No native call after stop: the shutdown releases the holds and restores the priorities.
            for (Entry entry : entries.values()) {
                if (entry.oom().managed() || entry.exceededPending) {
                    entry.notNow = notNow("stopped");
                }
            }
            return;
        }
        release(current);
        if (observation == null) {
            for (Entry entry : entries.values()) {
                if (entry.oom().managed() || entry.exceededPending) {
                    entry.notNow = notNow("no reading");
                }
            }
            return;
        }
        if (observation.kind() == Transition.Kind.GAP && triggers.contains(Trigger.SAMPLE)) {
            // After a gap, as after a sleep, the clock may have run on: no action for a settle interval.
            settleSet = true;
            settleUntil = nanoClock.getAsLong() + settleNanos;
        }
        boolean nativeDone = pressure(triggers, observation, current);
        policy(triggers, nativeDone, current);
    }

    /** The incident runs from CRITICAL or EMERGENCY until NORMAL or monitoring off (plan A7.5). */
    private void followIncident(OomObservation observation) {
        if (observation == null) {
            return;
        }
        if (!observation.monitoring() || observation.kind() == Transition.Kind.OFF) {
            endIncident();
            return;
        }
        switch (observation.state()) {
            case CRITICAL, EMERGENCY -> {
                if (!incident) {
                    incident = true;
                    // A new incident is not held back by the settle of the last one.
                    settleSet = false;
                }
            }
            case NORMAL -> endIncident();
            case WARNING -> {
                // E -> C -> W -> C is one incident: it ends at NORMAL.
            }
        }
    }

    private void endIncident() {
        incident = false;
        loweredAtCritical.clear();
        emergencyActed.clear();
    }

    private void followJournal() {
        boolean failedNow = journalSignal.getAndSet(false) || !coordinator.journalHealthy();
        if (failedNow && !journalDown) {
            journalDown = true;
            onJournalFailed();
        } else if (!failedNow) {
            journalDown = false;
        }
    }

    /**
     * Plan O14 (a), ADR-0015 §5: every suspension claim is released and the physical release is tried
     * at once. A release that is incomplete keeps its ownership and is tried again; the declaration is
     * dropped in memory only once released, and the file stays as evidence.
     */
    private void onJournalFailed() {
        for (Entry entry : entries.values()) {
            if (entry.physical != OomHold.Physical.SUSPENDED) {
                continue;
            }
            entry.suspendClaims.clear();
            releasePhysically(entry, "the suspension journal cannot be written");
        }
    }

    // ----- Releases -----

    /**
     * Lets go of every claim that is no longer justified: one physical resume per evaluation (OOM-11),
     * then, once none waits, one restored priority. A claim is taken away from a hold only together
     * with the resume, so that no suspension is ever held without a claim.
     */
    private void release(OomSettings now) {
        List<Entry> ordered = ordered(now);
        for (Entry entry : ordered) {
            if (entry.physical == OomHold.Physical.RELEASE_INCOMPLETE && !releasedNow.contains(entry.identity())) {
                releasePhysically(entry, "it is tried again");
            }
        }
        boolean resumed = false;
        boolean resumeWaits = false;
        for (Entry entry : ordered) {
            EnumSet<Claim> unjustified = EnumSet.noneOf(Claim.class);
            for (Claim claim : entry.suspendClaims) {
                if (!suspendJustified(entry, claim, now)) {
                    unjustified.add(claim);
                }
            }
            if (unjustified.isEmpty()) {
                continue;
            }
            if (unjustified.size() < entry.suspendClaims.size()) {
                entry.suspendClaims.removeAll(unjustified);
                record(
                        entry,
                        OomActionRecord.Kind.RESUME,
                        first(unjustified),
                        OperationResult.SUCCESS,
                        "OOM Guard no longer holds " + entry.label() + " for " + words(unjustified)
                                + "; it is still held for " + words(entry.suspendClaims) + ".",
                        ActivitySeverity.INFO);
            } else if (!resumed) {
                entry.suspendClaims.clear();
                releasePhysically(entry, "it is no longer needed");
                resumed = true;
            } else {
                resumeWaits = true;
            }
        }
        for (Entry entry : ordered) {
            EnumSet<Claim> unjustified = EnumSet.noneOf(Claim.class);
            for (Claim claim : entry.priorityClaims) {
                if (!priorityJustified(entry, claim, now)) {
                    unjustified.add(claim);
                }
            }
            if (unjustified.isEmpty()) {
                continue;
            }
            if (unjustified.size() < entry.priorityClaims.size()) {
                entry.priorityClaims.removeAll(unjustified);
            } else if (!resumed && !resumeWaits) {
                entry.priorityClaims.clear();
                restore(entry, "it is no longer needed");
                return;
            }
        }
    }

    private boolean suspendJustified(Entry entry, Claim claim, OomSettings now) {
        OomSwitches switches = now.switches();
        boolean allowed = switches.monitoring()
                && entry.oom().managed()
                && entry.oom().action() == EmergencyAction.SUSPEND
                && switches.autoSuspend();
        return switch (claim) {
            case PRESSURE -> allowed && incident;
            case RESIDENT_POLICY -> allowed && entry.watchedLimit.isPresent() && !entry.policyCleared;
        };
    }

    private boolean priorityJustified(Entry entry, Claim claim, OomSettings now) {
        OomSwitches switches = now.switches();
        boolean allowed = switches.monitoring() && entry.oom().managed() && switches.priorityReduction();
        return switch (claim) {
            case PRESSURE -> allowed && incident && entry.oom().action().atLeast(EmergencyAction.LOWER_PRIORITY);
            case RESIDENT_POLICY ->
                allowed
                        && entry.oom().action() == EmergencyAction.LOWER_PRIORITY
                        && entry.watchedLimit.isPresent()
                        && !entry.policyCleared;
        };
    }

    /** Releases the hold through the coordinator; the declaration goes once nothing is held. */
    private void releasePhysically(Entry entry, String why) {
        ProcessIdentity identity = entry.identity();
        releasedNow.add(identity);
        SuspensionCoordinator.Outcome outcome = coordinator.release(identity, SuspensionReason.OOM_GUARD);
        switch (outcome.change()) {
            case INCOMPLETE -> {
                boolean first = entry.physical != OomHold.Physical.RELEASE_INCOMPLETE;
                entry.physical = OomHold.Physical.RELEASE_INCOMPLETE;
                entry.failed = Optional.of("release incomplete: " + outcome.outstanding() + " thread(s) still held");
                if (first) {
                    record(
                            entry,
                            OomActionRecord.Kind.RESUME,
                            Claim.PRESSURE,
                            OperationResult.NATIVE_ERROR,
                            "OOM Guard could not resume every thread of " + entry.label() + " (" + outcome.outstanding()
                                    + " still held); it tries again.",
                            ActivitySeverity.ERROR);
                }
            }
            case NOT_HELD -> {
                boolean wasIncomplete = entry.physical == OomHold.Physical.RELEASE_INCOMPLETE;
                letGo(entry);
                if (!outcome.remaining().isEmpty()) {
                    entry.failed = Optional.of("the incomplete hold is the user's; resume it to let Prism finish");
                } else if (wasIncomplete) {
                    entry.failed = Optional.empty();
                }
            }
            case RESUMED, STILL_HELD, EXITED -> {
                boolean wasIncomplete = entry.physical == OomHold.Physical.RELEASE_INCOMPLETE;
                letGo(entry);
                entry.failed = Optional.empty();
                String text = switch (outcome.change()) {
                    case RESUMED -> "OOM Guard resumed " + entry.label() + ": " + why + ".";
                    case STILL_HELD ->
                        "OOM Guard let go of " + entry.label() + ": " + why + ". It stays suspended for "
                                + heldBy(outcome.remaining()) + ".";
                    default -> entry.label() + " had exited when OOM Guard let go of it.";
                };
                record(
                        entry,
                        OomActionRecord.Kind.RESUME,
                        Claim.PRESSURE,
                        outcome.outcome().result(),
                        wasIncomplete ? text + " The incomplete release is finished." : text,
                        ActivitySeverity.INFO);
            }
            default -> {
                // REFUSED and the suspend outcomes do not come back from a release.
                letGo(entry);
            }
        }
    }

    /** The coordinator holds nothing for OOM Guard any more: the declaration goes. */
    private void letGo(Entry entry) {
        entry.physical = OomHold.Physical.NONE;
        if (journalDown) {
            // The file keeps what was written, as evidence; nothing new is written for it (ADR-0011).
            coordinator.dropDeclaration(entry.identity(), SuspensionReason.OOM_GUARD);
        } else {
            coordinator.withdraw(entry.identity(), SuspensionReason.OOM_GUARD);
        }
    }

    /** Restores a lowered priority (OOM-11); returns whether it is restored or nothing is left to restore. */
    private boolean restore(Entry entry, String why) {
        if (entry.previousPriority.isEmpty()) {
            return true;
        }
        PriorityLevel previous = entry.previousPriority.get();
        entry.previousPriority = Optional.empty();
        Optional<PriorityLevel> listed = levelOf(entry.last);
        if (listed.isPresent() && listed.get() != PriorityLevel.LOW) {
            record(
                    entry,
                    OomActionRecord.Kind.RESTORE_PRIORITY,
                    Claim.PRESSURE,
                    OperationResult.SUCCESS,
                    "OOM Guard left the priority of " + entry.label() + " as it is: it was changed to "
                            + level(listed.get()) + " meanwhile.",
                    ActivitySeverity.INFO);
            return true;
        }
        ControlOutcome outcome = controller.changePriority(entry.identity(), previous);
        switch (outcome.result()) {
            case SUCCESS -> {
                record(
                        entry,
                        OomActionRecord.Kind.RESTORE_PRIORITY,
                        Claim.PRESSURE,
                        OperationResult.SUCCESS,
                        "OOM Guard restored the priority of " + entry.label() + " to " + level(previous) + ": " + why
                                + ".",
                        ActivitySeverity.INFO);
                return true;
            }
            case PROCESS_NOT_FOUND -> {
                record(
                        entry,
                        OomActionRecord.Kind.RESTORE_PRIORITY,
                        Claim.PRESSURE,
                        OperationResult.PROCESS_NOT_FOUND,
                        entry.label() + " had exited; its priority needs no restoring.",
                        ActivitySeverity.INFO);
                return true;
            }
            default -> {
                String detail = outcome.detail().orElse(outcome.result().name());
                entry.failed = Optional.of("priority could not be restored: " + detail);
                record(
                        entry,
                        OomActionRecord.Kind.RESTORE_PRIORITY,
                        Claim.PRESSURE,
                        outcome.result(),
                        "The priority of " + entry.label() + " could not be restored to " + level(previous) + ": "
                                + detail + ". It stays Low; OOM Guard does not try again.",
                        ActivitySeverity.ERROR);
                return false;
            }
        }
    }

    /** The user, the end of management or the exit: every claim goes at once; the ledger stays. */
    private void releaseEverything(Entry entry, String why) {
        entry.suspendClaims.clear();
        if (entry.physical != OomHold.Physical.NONE) {
            releasePhysically(entry, why);
        }
        entry.priorityClaims.clear();
        restore(entry, why);
        entry.exceededPending = false;
    }

    private void forget(Entry entry) {
        releaseEverything(entry, "it is no longer managed");
        if (entry.physical == OomHold.Physical.RELEASE_INCOMPLETE) {
            // The coordinator keeps the remainder as an incomplete suspension of its own, with its own entry
            // in the journal; the reason of OOM Guard goes with OOM Guard.
            letGo(entry);
            activity.record(
                    ActivitySeverity.WARNING,
                    "OOM Guard no longer manages " + entry.label() + ", but part of its suspension could not be"
                            + " undone. It stays shown as suspended by Prism; Resume on the Processes page releases"
                            + " the rest, and so does the shutdown.");
        }
        // A hold whose release is incomplete stays with the coordinator, which tries again at the user's
        // Resume and at shutdown; OOM Guard stops trying.
        entries.remove(entry.identity());
    }

    // ----- The listing and the memory policy -----

    private void applyListing(Map<ProcessIdentity, Listed> listing) {
        for (Entry entry : entries.values()) {
            entry.last = listing.get(entry.identity());
            if (entry.watchedLimit.isEmpty()
                    || entry.last == null
                    || entry.last.residentBytes().isEmpty()) {
                // Without a reading of the resident memory nothing crosses and nothing clears.
                continue;
            }
            long limit = entry.watchedLimit.getAsLong();
            long resident = entry.last.residentBytes().getAsLong();
            boolean above = resident > limit;
            boolean crossed = above
                    && (entry.armed
                            || (entry.previousResident.isPresent() && entry.previousResident.getAsLong() <= limit));
            entry.armed = false;
            entry.previousResident = OptionalLong.of(resident);
            if (crossed) {
                entry.exceededPending = true;
                entry.policyActed = false;
                record(
                        entry,
                        null,
                        Claim.RESIDENT_POLICY,
                        OperationResult.SUCCESS,
                        entry.label() + " uses " + bytes(resident) + " of resident memory, above its watched limit of "
                                + bytes(limit) + ".",
                        ActivitySeverity.WARNING);
            }
            entry.clearStreak = resident <= clearBelow(limit, tuning) ? entry.clearStreak + 1 : 0;
            entry.policyCleared = entry.clearStreak >= 2;
            if (entry.policyCleared) {
                entry.exceededPending = false;
            }
        }
    }

    private void policy(Set<Trigger> triggers, boolean nativeDone, OomSettings now) {
        boolean acted = nativeDone;
        for (Entry entry : ordered(now)) {
            if (!entry.exceededPending) {
                continue;
            }
            EffectiveAction effective = effective(entry, now);
            if (effective.action() == EmergencyAction.NOTIFY) {
                entry.notNow = effective.notInEffect().map(OomGuard::notNowText);
                continue;
            }
            if (acted) {
                entry.notNow = notNow("one action per evaluation");
                continue;
            }
            if (effective.action() == EmergencyAction.SUSPEND
                    && entry.physical == OomHold.Physical.RELEASE_INCOMPLETE) {
                entry.notNow = notNow("release incomplete");
                continue;
            }
            if (effective.action() == EmergencyAction.SUSPEND && entry.suspendClaims.isEmpty()) {
                Optional<String> incomplete = otherIncomplete(entry);
                if (incomplete.isPresent()) {
                    entry.notNow = incomplete;
                    continue;
                }
            }
            if (effective.action() == EmergencyAction.TERMINATE && terminateSent.contains(entry.identity())) {
                entry.exceededPending = false;
                entry.notNow = notNow("termination was requested in this session already");
                continue;
            }
            if (refused.contains(entry.identity())) {
                entry.notNow = notNow("refused: protected");
                continue;
            }
            if (effective.action() == EmergencyAction.LOWER_PRIORITY && entry.priorityClaims.isEmpty()) {
                // As under pressure: never from a level that is not known, nor from Low.
                Optional<String> ineligible = priorityIneligible(entry);
                if (ineligible.isPresent()) {
                    entry.notNow = ineligible;
                    continue;
                }
            }
            Optional<String> denied = authorize(triggers, Intent.POLICY, entry, effective.action());
            if (denied.isPresent()) {
                entry.notNow = denied;
                continue;
            }
            String cause = "OOM Guard: memory limit " + bytes(entry.watchedLimit.getAsLong()) + " exceeded";
            boolean madeNativeCall = switch (effective.action()) {
                case SUSPEND -> acquireSuspend(entry, Claim.RESIDENT_POLICY, cause);
                case LOWER_PRIORITY -> {
                    if (!entry.priorityClaims.isEmpty()) {
                        entry.priorityClaims.add(Claim.RESIDENT_POLICY);
                        yield false;
                    }
                    lower(entry, Claim.RESIDENT_POLICY);
                    yield true;
                }
                case TERMINATE -> {
                    terminate(entry, Claim.RESIDENT_POLICY);
                    yield true;
                }
                case NOTIFY -> false;
            };
            entry.exceededPending = false;
            entry.policyActed = true;
            acted |= madeNativeCall;
        }
    }

    // ----- Pressure -----

    /** CRITICAL: one lowering; EMERGENCY: one emergency action after the settle interval. Returns whether a native call was made. */
    private boolean pressure(Set<Trigger> triggers, OomObservation observation, OomSettings now) {
        Intent intent;
        if (observation.state() == OomState.EMERGENCY) {
            intent = Intent.EMERGENCY;
        } else if (observation.state() == OomState.CRITICAL) {
            intent = Intent.LOWER_AT_CRITICAL;
        } else {
            return false;
        }
        int managed = 0;
        int actedOn = 0;
        int notInEffect = 0;
        boolean anyEligible = false;
        for (Entry entry : ordered(now)) {
            if (!entry.oom().managed()) {
                continue;
            }
            managed++;
            EffectiveAction effective = effective(entry, now);
            Optional<String> ineligible;
            if (intent == Intent.LOWER_AT_CRITICAL) {
                ineligible = lowerIneligible(entry, effective, now);
            } else {
                ineligible = emergencyIneligible(entry, effective);
                if (emergencyActed.contains(entry.identity())) {
                    actedOn++;
                }
                if (effective.notInEffect().isPresent()) {
                    notInEffect++;
                }
            }
            if (ineligible.isPresent()) {
                if (!ineligible.get().isEmpty() && entry.notNow.isEmpty()) {
                    entry.notNow = ineligible;
                }
                continue;
            }
            anyEligible = true;
            EmergencyAction action =
                    intent == Intent.LOWER_AT_CRITICAL ? EmergencyAction.LOWER_PRIORITY : effective.action();
            Optional<String> denied = authorize(triggers, intent, entry, action);
            if (denied.isPresent()) {
                entry.notNow = denied;
                continue;
            }
            return act(entry, intent, action, observation);
        }
        if (intent == Intent.EMERGENCY && !anyEligible) {
            notice = Optional.of("No further candidates: " + managed + " under OOM management, " + actedOn
                    + " acted on in this incident, " + notInEffect + " with an action not in effect.");
        }
        return false;
    }

    /**
     * Why the process is not lowered at CRITICAL: empty when it may be, {@link #SKIP} when there is
     * nothing to say about it.
     */
    private Optional<String> lowerIneligible(Entry entry, EffectiveAction effective, OomSettings now) {
        if (!effective.lowersAtCritical()) {
            if (entry.oom().action().atLeast(EmergencyAction.LOWER_PRIORITY)) {
                return notNow(
                        EffectiveAction.whyNotLowering(now.switches(), controller.capabilities(), restorability, signal)
                                .orElse("not in effect"));
            }
            return SKIP;
        }
        if (loweredAtCritical.contains(entry.identity()) || !entry.priorityClaims.isEmpty()) {
            return SKIP;
        }
        return priorityIneligible(entry);
    }

    private Optional<String> priorityIneligible(Entry entry) {
        if (refused.contains(entry.identity())) {
            return notNow("refused: protected");
        }
        Optional<PriorityLevel> level = levelOf(entry.last);
        if (entry.last != null && level.isEmpty()) {
            return notNow("its priority level is not known");
        }
        if (level.filter(PriorityLevel.LOW::equals).isPresent()) {
            return notNow("already Low");
        }
        return Optional.empty();
    }

    /** Why the emergency action is not applied to the process: empty when it may be, as above. */
    private Optional<String> emergencyIneligible(Entry entry, EffectiveAction effective) {
        if (effective.action() == EmergencyAction.NOTIFY) {
            return effective.notInEffect().isPresent()
                    ? notNow(effective.notInEffect().get())
                    : SKIP;
        }
        ProcessIdentity identity = entry.identity();
        if (emergencyActed.contains(identity)) {
            return notNow("acted this incident");
        }
        if (refused.contains(identity)) {
            return notNow("refused: protected");
        }
        return switch (effective.action()) {
            case LOWER_PRIORITY ->
                loweredAtCritical.contains(identity) || !entry.priorityClaims.isEmpty()
                        ? notNow("already lowered")
                        : priorityIneligible(entry);
            case SUSPEND -> {
                if (entry.physical == OomHold.Physical.RELEASE_INCOMPLETE) {
                    yield notNow("release incomplete");
                }
                if (entry.suspendClaims.contains(Claim.PRESSURE)) {
                    yield SKIP;
                }
                yield otherIncomplete(entry);
            }
            case TERMINATE ->
                terminateSent.contains(identity)
                        ? notNow("termination was requested in this session already")
                        : Optional.empty();
            case NOTIFY -> SKIP;
        };
    }

    /**
     * Nothing is stacked on what is left of another suspension that could not be undone completely: the
     * coordinator tries that again at the user's Resume and at shutdown, and OOM Guard does not take it
     * over.
     */
    private Optional<String> otherIncomplete(Entry entry) {
        return entry.physical == OomHold.Physical.NONE && coordinator.incomplete(entry.identity())
                ? notNow("an incomplete suspension of it waits; resume it on the Processes page to let Prism finish")
                : Optional.empty();
    }

    private boolean act(Entry entry, Intent intent, EmergencyAction action, OomObservation observation) {
        ProcessIdentity identity = entry.identity();
        if (intent == Intent.LOWER_AT_CRITICAL) {
            loweredAtCritical.add(identity);
            lower(entry, Claim.PRESSURE);
            return true;
        }
        emergencyActed.add(identity);
        String cause = "OOM Guard: emergency, "
                + observation.reading().map(OomGuard::percent).orElse("reading unavailable") + " available";
        boolean madeNativeCall = switch (action) {
            case LOWER_PRIORITY -> {
                lower(entry, Claim.PRESSURE);
                yield true;
            }
            case SUSPEND -> acquireSuspend(entry, Claim.PRESSURE, cause);
            case TERMINATE -> {
                terminate(entry, Claim.PRESSURE);
                yield true;
            }
            case NOTIFY -> false;
        };
        if (madeNativeCall) {
            settleSet = true;
            settleUntil = nanoClock.getAsLong() + settleNanos;
        }
        return madeNativeCall;
    }

    // ----- The gate -----

    /** Every condition of a new intervention, in order, right before its native call; empty when all hold. */
    private Optional<String> authorize(Set<Trigger> triggers, Intent intent, Entry entry, EmergencyAction action) {
        // 0: the trigger fits the intent.
        if (intent == Intent.POLICY ? !triggers.contains(Trigger.LISTING) : !triggers.contains(Trigger.SAMPLE)) {
            return notNow(intent == Intent.POLICY ? "waiting for a listing" : "waiting for a sample");
        }
        OomObservation observation = latest.get();
        VersionedSettings snapshot = settings.get();
        if (observation == null) {
            return notNow("no reading");
        }
        // 0b: the observation was made under these settings.
        if (observation.settingsVersion() != snapshot.version()) {
            return notNow("settings changed; waiting for a sample");
        }
        // 1
        if (stopped) {
            return notNow("stopped");
        }
        OomSettings now = snapshot.settings();
        // 2
        if (!now.switches().monitoring()) {
            return notNow("monitoring is off");
        }
        long time = nanoClock.getAsLong();
        // 3
        if (time - observation.atNanos() > gapNanos) {
            return notNow("stale reading");
        }
        // 4
        if (monitorEnded) {
            return notNow("not monitoring");
        }
        switch (observation.kind()) {
            case HOLD -> {
                return notNow("no reading");
            }
            case GAP -> {
                return notNow("after a gap");
            }
            case OFF -> {
                return notNow("not monitoring");
            }
            default -> {
                // A reading taken under the settings in force.
            }
        }
        // 5: the reading itself, not only the state.
        if (intent == Intent.POLICY) {
            OptionalLong resident = entry.resident();
            if (!entry.exceededPending
                    || resident.isEmpty()
                    || resident.getAsLong() <= entry.watchedLimit.orElse(Long.MAX_VALUE)) {
                return notNow("below the limit");
            }
        } else {
            OomState level = intent == Intent.EMERGENCY ? OomState.EMERGENCY : OomState.CRITICAL;
            Optional<OomReading> reading = observation.reading();
            if (reading.isEmpty() || !OomStateMachine.meets(level, reading.get(), now.thresholds())) {
                return notNow("pressure relieved");
            }
            if (intent == Intent.EMERGENCY && settleSet && time - settleUntil < 0) {
                return notNow("settling");
            }
        }
        // 6: the switch, in the same snapshot, and the system.
        EffectiveAction effective = effective(entry, now);
        if (intent == Intent.LOWER_AT_CRITICAL) {
            if (!effective.lowersAtCritical()) {
                return notNow(
                        EffectiveAction.whyNotLowering(now.switches(), controller.capabilities(), restorability, signal)
                                .orElse("not in effect"));
            }
        } else if (effective.action() != action) {
            return notNow(effective.notInEffect().orElse("not in effect"));
        }
        // 7: listed, and not protected, by the facts read now.
        ProcessIdentity identity = entry.identity();
        if (entry.last == null) {
            return notNow("not listed");
        }
        if (rules.refusal(identity).isPresent()
                || rules.refusal(controller.protectionFacts(identity)).isPresent()) {
            refused.add(identity);
            record(
                    entry,
                    null,
                    intent == Intent.POLICY ? Claim.RESIDENT_POLICY : Claim.PRESSURE,
                    OperationResult.ACCESS_DENIED,
                    "OOM Guard refused to act on " + entry.label() + ": it is protected. It is not tried again in this"
                            + " session.",
                    ActivitySeverity.WARNING);
            return notNow("refused: protected");
        }
        // 8: the ledger was checked when the candidate was chosen; nothing else touched it since.
        if (stopped) {
            return notNow("stopped");
        }
        // The start of the action checks again, in order with publication, that these are still in force.
        authorizedVersion = snapshot.version();
        return Optional.empty();
    }

    // ----- The interventions -----

    /** Returns whether a native call was made. */
    private boolean acquireSuspend(Entry entry, Claim claim, String cause) {
        if (entry.suspendClaims.contains(claim)) {
            return false;
        }
        if (!entry.suspendClaims.isEmpty()) {
            entry.suspendClaims.add(claim);
            record(
                    entry,
                    OomActionRecord.Kind.SUSPEND,
                    claim,
                    OperationResult.SUCCESS,
                    "OOM Guard holds " + entry.label() + " for " + words(EnumSet.of(claim)) + " too.",
                    ActivitySeverity.INFO);
            return false;
        }
        ProcessIdentity identity = entry.identity();
        String name = entry.process.name();
        if (!beginAction(entry)) {
            return false;
        }
        boolean declared;
        SuspensionCoordinator.Outcome outcome = null;
        try {
            declared = coordinator.declare(identity, name, SuspensionReason.OOM_GUARD, cause);
            if (declared) {
                outcome = coordinator.suspend(identity, name, SuspensionReason.OOM_GUARD);
            }
        } finally {
            endAction();
        }
        if (!declared || outcome == null) {
            entry.failed = Optional.of("the suspension journal cannot be written");
            record(
                    entry,
                    OomActionRecord.Kind.SUSPEND,
                    claim,
                    OperationResult.NATIVE_ERROR,
                    "OOM Guard did not suspend " + entry.label() + ": the suspension journal cannot be written.",
                    ActivitySeverity.ERROR);
            return false;
        }
        String reason = words(EnumSet.of(claim));
        switch (outcome.change()) {
            case SUSPENDED, ALREADY_HELD -> {
                entry.physical = OomHold.Physical.SUSPENDED;
                entry.suspendClaims.add(claim);
                entry.failed = Optional.empty();
                record(
                        entry,
                        OomActionRecord.Kind.SUSPEND,
                        claim,
                        OperationResult.SUCCESS,
                        outcome.change() == SuspensionCoordinator.Change.SUSPENDED
                                ? "OOM Guard suspended " + entry.label() + ": " + reason + "."
                                : entry.label() + " was suspended already; OOM Guard holds it too: " + reason + ".",
                        ActivitySeverity.WARNING);
            }
            case INCOMPLETE -> {
                // Nothing is stacked on a half-made suspension: what was suspended is released at once.
                record(
                        entry,
                        OomActionRecord.Kind.SUSPEND,
                        claim,
                        outcome.outcome().result(),
                        "OOM Guard could not suspend " + entry.label() + " completely: "
                                + outcome.outcome().detail().orElse("part of it could not be suspended")
                                + ". What was suspended is released.",
                        ActivitySeverity.ERROR);
                entry.physical = OomHold.Physical.SUSPENDED;
                releasePhysically(entry, "the suspension was incomplete");
                if (entry.physical == OomHold.Physical.NONE && entry.failed.isEmpty()) {
                    entry.failed = Optional.of("the suspension was incomplete");
                }
            }
            default -> {
                coordinator.withdraw(identity, SuspensionReason.OOM_GUARD);
                String detail = outcome.outcome()
                        .detail()
                        .orElse(outcome.outcome().result().name());
                entry.failed = Optional.of(
                        outcome.change() == SuspensionCoordinator.Change.STOPPED_ELSEWHERE
                                ? "stopped by another program"
                                : "suspend failed: " + detail);
                record(
                        entry,
                        OomActionRecord.Kind.SUSPEND,
                        claim,
                        outcome.outcome().result(),
                        outcome.change() == SuspensionCoordinator.Change.STOPPED_ELSEWHERE
                                ? entry.label() + " is stopped by another program; OOM Guard left it as it is."
                                : "OOM Guard could not suspend " + entry.label() + ": " + detail
                                        + ". It is not tried again in this incident.",
                        outcome.change() == SuspensionCoordinator.Change.STOPPED_ELSEWHERE
                                ? ActivitySeverity.WARNING
                                : ActivitySeverity.ERROR);
            }
        }
        return true;
    }

    private void lower(Entry entry, Claim claim) {
        Optional<PriorityLevel> previous = levelOf(entry.last);
        if (previous.isEmpty()) {
            // Nothing to raise it back to: a guessed level would be a change Prism made and called a restore.
            entry.notNow = notNow("its priority level is not known");
            return;
        }
        if (!beginAction(entry)) {
            return;
        }
        ControlOutcome outcome;
        try {
            outcome = controller.changePriority(entry.identity(), PriorityLevel.LOW);
        } finally {
            endAction();
        }
        if (outcome.succeeded()) {
            entry.priorityClaims.add(claim);
            entry.previousPriority = previous;
            entry.failed = Optional.empty();
            record(
                    entry,
                    OomActionRecord.Kind.LOWER_PRIORITY,
                    claim,
                    OperationResult.SUCCESS,
                    "OOM Guard lowered the priority of " + entry.label() + " to Low (it was "
                            + level(entry.previousPriority.get()) + "): " + words(EnumSet.of(claim)) + ".",
                    ActivitySeverity.WARNING);
            return;
        }
        String detail = outcome.detail().orElse(outcome.result().name());
        entry.failed = Optional.of("lowering failed: " + detail);
        record(
                entry,
                OomActionRecord.Kind.LOWER_PRIORITY,
                claim,
                outcome.result(),
                "OOM Guard could not lower the priority of " + entry.label() + ": " + detail + ".",
                ActivitySeverity.ERROR);
    }

    private void terminate(Entry entry, Claim claim) {
        if (!beginAction(entry)) {
            return;
        }
        ControlOutcome outcome;
        try {
            terminateSent.add(entry.identity());
            entry.terminateRequested = true;
            outcome = controller.terminate(entry.identity());
        } finally {
            endAction();
        }
        if (outcome.succeeded()) {
            record(
                    entry,
                    OomActionRecord.Kind.TERMINATE,
                    claim,
                    OperationResult.SUCCESS,
                    "OOM Guard requested the forced termination of " + entry.label() + ": " + words(EnumSet.of(claim))
                            + ".",
                    ActivitySeverity.WARNING);
            return;
        }
        String detail = outcome.detail().orElse(outcome.result().name());
        entry.failed = Optional.of("Terminate failed: " + detail + "; not retried this session");
        record(
                entry,
                OomActionRecord.Kind.TERMINATE,
                claim,
                outcome.result(),
                "Terminate failed for " + entry.label() + ": " + detail + "; it is not tried again in this session.",
                ActivitySeverity.ERROR);
    }

    // ----- What is shown -----

    private EffectiveAction effective(Entry entry, OomSettings now) {
        return EffectiveAction.of(
                entry.oom(),
                now.switches(),
                controller.capabilities(),
                coordinator.journalHealthy() && !journalDown,
                restorability,
                signal);
    }

    private List<Entry> ordered(OomSettings now) {
        List<OomCandidateView> candidates = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            candidates.add(view(entry, now));
        }
        candidates.sort(OomCandidateOrder.ORDER);
        List<Entry> ordered = new ArrayList<>(candidates.size());
        for (OomCandidateView candidate : candidates) {
            ordered.add(entries.get(candidate.identity()));
        }
        return ordered;
    }

    private OomCandidateView view(Entry entry, OomSettings now) {
        return new OomCandidateView(
                entry.identity(),
                entry.process.name(),
                entry.oom(),
                effective(entry, now),
                entry.hold(),
                levelOf(entry.last),
                entry.resident(),
                entry.watchedLimit,
                entry.notNow);
    }

    private void publish() {
        try {
            views.accept(buildView());
        } catch (RuntimeException e) {
            if (failuresLogged.add("view|" + e.getClass().getName())) {
                LOG.warn("The view of OOM Guard could not be handed over", e);
            }
        }
    }

    private OomView buildView() {
        OomSettings now = settings.get().settings();
        OomObservation observation = latest.get();
        boolean monitoring = now.switches().monitoring();
        OomState state =
                monitoring && observation != null && observation.monitoring() ? observation.state() : OomState.NORMAL;
        if (state != shownState) {
            shownState = state;
            since = Optional.of(clock.instant());
        }
        if (!monitoring) {
            since = Optional.empty();
        }
        Optional<OomReading> reading = monitoring && observation != null ? observation.reading() : Optional.empty();
        Optional<Instant> readAt =
                reading.map(r -> clock.instant().minusNanos(Math.max(0, nanoClock.getAsLong() - r.atNanos())));
        List<OomCandidateView> candidates = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            candidates.add(view(entry, now));
        }
        candidates.sort(OomCandidateOrder.ORDER);
        Optional<String> shown = notice;
        if (shown.isEmpty()
                && reading.isPresent()
                && now.thresholds().emergencyFloorBytes() > 0
                && !OomStateMachine.floorUsable(
                        now.thresholds().emergencyFloorBytes(), reading.get().totalBytes())) {
            shown = Optional.of("The emergency floor is ignored: it is at or above half of the physical memory.");
        }
        OomSwitches all = new OomSwitches(true, true, true, true);
        OomView.Availability availability = new OomView.Availability(
                EffectiveAction.whyNotLowering(all, controller.capabilities(), restorability, signal),
                EffectiveAction.whyNotSuspending(all, controller.capabilities(), true, signal),
                EffectiveAction.whyNotTerminating(all, controller.capabilities(), signal),
                coordinator.journalHealthy() && !journalDown
                        ? Optional.empty()
                        : Optional.of(coordinator.journalProblem().orElse("the suspension journal cannot be written")));
        return new OomView(
                monitoring,
                state,
                reading,
                readAt,
                now,
                monitoring ? since : Optional.empty(),
                candidates,
                List.copyOf(actions),
                shown,
                monitorEnded,
                restorability,
                signal,
                availability);
    }

    private void record(
            Entry entry,
            OomActionRecord.Kind kind,
            Claim cause,
            OperationResult result,
            String text,
            ActivitySeverity severity) {
        activity.record(severity, text);
        if (kind == null) {
            return;
        }
        OomObservation observation = latest.get();
        actions.addFirst(new OomActionRecord(
                clock.instant(),
                observation == null ? OomState.NORMAL : observation.state(),
                cause,
                entry.identity(),
                entry.process.name(),
                kind,
                result,
                text));
        while (actions.size() > OomView.MOST_ACTIONS) {
            actions.removeLast();
        }
    }

    private Optional<PriorityLevel> levelOf(Listed listed) {
        if (listed == null || listed.priority().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                controller.listedPriorities().get(listed.priority().getAsInt()));
    }

    private static Optional<String> notNow(String why) {
        return Optional.of(notNowText(why));
    }

    private static String notNowText(String why) {
        return "not now: " + why;
    }

    private static Claim first(Set<Claim> claims) {
        return claims.iterator().next();
    }

    private static String words(Set<Claim> claims) {
        List<String> words = new ArrayList<>();
        for (Claim claim : claims) {
            words.add(claim == Claim.PRESSURE ? "memory pressure" : "its memory limit");
        }
        return String.join(" and ", words);
    }

    private static String heldBy(Set<SuspensionReason> reasons) {
        List<String> words = new ArrayList<>();
        for (SuspensionReason reason :
                reasons.stream().sorted(Comparator.naturalOrder()).toList()) {
            words.add(
                    switch (reason) {
                        case USER -> "you";
                        case CPU_LIMITER -> "its CPU limit";
                        case OOM_GUARD -> "OOM Guard";
                    });
        }
        return words.isEmpty() ? "another holder" : String.join(" and ", words);
    }

    private static String level(PriorityLevel level) {
        String name = level.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String percent(OomReading reading) {
        return String.format(Locale.ROOT, "%.1f %%", reading.availablePercent());
    }

    private static String bytes(long value) {
        return prism.srm.domain.EffectiveLimits.bytes(value);
    }
}
