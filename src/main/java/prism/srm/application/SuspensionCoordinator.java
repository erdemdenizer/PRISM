package prism.srm.application;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryState;
import prism.srm.domain.SuspendedProcess;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.ProcessController;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * The one owner of the suspensions Prism holds (CTL-9, ADR-0004, ADR-0011). It is the only caller of
 * the controller's suspend, resume and adopt. Per process identity it records why the process is
 * suspended (by the user, by the CPU limiter, by OOM Guard) and resumes it only when no reason is
 * left, so that one owner never wakes a process another still holds.
 *
 * <p><b>What it never loses.</b> A suspension stays recorded as long as Prism may still hold any
 * part of it on a live process. A resume that leaves part of it in place, or a suspend whose
 * rollback did, is kept as <em>incomplete</em>: it still shows as suspended by Prism, and the next
 * Resume tries the rest.
 *
 * <p><b>The journal</b> (ADR-0011). What Prism holds, or may hold, is written to the suspension
 * journal before Prism suspends for it, so that a session that ends without a controlled shutdown
 * leaves a record: the user's suspension before it is made, with the units it holds once they are
 * known; a CPU limit when it starts ({@link #declare}), not on every cycle of its duty cycle; a
 * suspension left incomplete. A suspension for an automatic reason (the limiter, OOM Guard) is
 * refused unless that reason is recorded and the journal is healthy: Prism suspends nothing of its
 * own that the next session could not find. The user's suspension goes ahead when the journal fails,
 * and says that it was not recorded.
 *
 * <p><b>What is shown.</b> The processes the user is shown as suspended by Prism, and offered to
 * resume when monitoring has stopped, are those held for the user or OOM Guard, or incomplete. A
 * process the CPU limiter alone holds, which it does for a fraction of a second at a time, is shown
 * by its limit instead.
 *
 * <p><b>After a crash</b> ({@link #recover}). The journals of sessions that ended without a
 * controlled shutdown are read at start-up. An entry is a claim, not proof that a stop is still
 * Prism's: nothing is resumed by itself. A process that has exited or runs is closed; every other
 * entry is listed for the user, who may ask to resume it ({@link #resumeRecovered}), which checks
 * again through the controller and resumes it only if the check holds, or dismiss it.
 *
 * <p><b>Exit.</b> A process missing from one listing has not necessarily exited: a listing can
 * miss a live process. A held process missing from a listing is therefore checked against the
 * system, off the monitoring thread, on the executor given for that, and only a confirmed exit or a
 * PID taken over by another process drops it. One check waits per process at most.
 *
 * <p><b>Shutdown.</b> After {@link #refuseFurtherSuspensions()} nothing new is suspended.
 * {@link #releaseAutomaticReasons} releases what the limiter and OOM Guard hold, {@link
 * #finalizeForShutdown} closes everything and leaves what the user suspended suspended, and in the
 * journal (LIFE-1, LIFE-2). Both wait for the lock no longer than they are told.
 *
 * <p>Threads: suspend, release, the checks and recovery run on the control executor or the
 * limiter's; the shutdown steps on the shutdown thread. {@link #listed} runs on the process
 * monitoring thread and only reads a published copy and queues checks. Native calls are made while
 * the lock is held, so all of it is one sequence.
 */
public final class SuspensionCoordinator {

    /** What a suspend or a release changed. */
    public enum Change {
        /** Prism suspended the process now. */
        SUSPENDED,
        /** Prism already held it; the reason was added. Nothing was done to the process. */
        ALREADY_HELD,
        /** The last reason went and the process was resumed. */
        RESUMED,
        /** The reason went, but another keeps the process suspended. */
        STILL_HELD,
        /** Prism holds no suspension of the process for that reason. */
        NOT_HELD,
        /** Part of a suspension Prism made could not be undone and is still held. */
        INCOMPLETE,
        /** The process had exited when the last reason went: nothing was left to resume, and Prism let go of it. */
        EXITED,
        /** Prism is shutting down and suspends nothing new. */
        REFUSED,
        /** The suspend failed and nothing is held. */
        FAILED,
        /** The process is stopped already, by someone else: nothing was done and nothing is held. */
        STOPPED_ELSEWHERE
    }

    /**
     * @param remaining the reasons Prism still holds the process for
     * @param outstanding the threads Prism still holds suspended in an incomplete suspension
     * @param recorded whether the journal holds what this did; false when the journal failed and the
     *     user's suspension went ahead without it
     * @param retryable a failed suspend that the platform classified as transient and rolled back
     *     completely, after which Prism holds nothing of the process: the same request may be made
     *     again. Never for a refusal, a journal failure, a process stopped elsewhere, an identity that
     *     no longer matches, or anything left held.
     */
    public record Outcome(
            ControlOutcome outcome,
            Change change,
            Set<SuspensionReason> remaining,
            int outstanding,
            boolean recorded,
            boolean retryable) {
        public Outcome {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(change, "change");
            remaining = Set.copyOf(remaining);
            if (retryable && (change != Change.FAILED || outstanding != 0 || !remaining.isEmpty())) {
                throw new IllegalArgumentException("Only a failure that holds nothing is retryable: " + change);
            }
        }

        public Outcome(
                ControlOutcome outcome,
                Change change,
                Set<SuspensionReason> remaining,
                int outstanding,
                boolean recorded) {
            this(outcome, change, remaining, outstanding, recorded, false);
        }

        public Outcome(ControlOutcome outcome, Change change, Set<SuspensionReason> remaining, int outstanding) {
            this(outcome, change, remaining, outstanding, true, false);
        }

        Outcome unrecorded() {
            // Not retryable: a journal that failed is never a reason to try again.
            return new Outcome(outcome, change, remaining, outstanding, false, false);
        }
    }

    /** What came of a request about a process an earlier session may have left suspended. */
    public record RecoveryOutcome(ControlOutcome outcome, Optional<AdoptResult.Verdict> verdict, boolean closed) {
        public RecoveryOutcome {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(verdict, "verdict");
        }
    }

    /** After this many checks in a row that could not decide, the log says so. */
    static final int UNDECIDED_CHECKS_REPORTED = 3;

    private static final Logger LOG = LoggerFactory.getLogger(SuspensionCoordinator.class);

    /** One suspension Prism holds. */
    private static final class Held {
        final Suspension token;
        final String name;
        final EnumSet<SuspensionReason> reasons;
        boolean incomplete;
        int undecidedChecks;

        Held(Suspension token, String name, EnumSet<SuspensionReason> reasons, boolean incomplete) {
            this.token = token;
            this.name = name;
            this.reasons = reasons;
            this.incomplete = incomplete;
        }
    }

    /** What the journal says of one process: declared reasons, and where it came from. */
    private static final class Declared {
        String name;
        final EnumSet<SuspensionReason> reasons = EnumSet.noneOf(SuspensionReason.class);
        /** Why each automatic reason was declared, for the journal entry; one per reason (milestone 11). */
        final Map<SuspensionReason, String> causes = new java.util.EnumMap<>(SuspensionReason.class);

        final Instant created;
        /** The change that wrote the latest reason; the reason holds once the writer has written it. */
        long durableAt = Long.MAX_VALUE;

        Declared(String name, Instant created) {
            this.name = name;
            this.created = created;
        }
    }

    private final ProcessController controller;
    private final ActivityLog activity;
    private final Executor checks;
    private final InstantSource clock;
    private final Optional<SuspensionJournal> journal;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<ProcessIdentity, Held> held = new HashMap<>();
    /** Reasons recorded ahead of a suspension: a CPU limit in force, the user's suspension being made. */
    private final Map<ProcessIdentity, Declared> declared = new HashMap<>();
    /** The journal entries as last written, by identity, to keep their creation times. */
    private final Map<ProcessIdentity, JournalEntry> written = new HashMap<>();
    /** Processes an earlier session may have left suspended, in the order they were found. */
    private final Map<ProcessIdentity, RecoveredSuspension> recovered = new LinkedHashMap<>();

    private final Map<ProcessIdentity, JournalEntry> recoveredEntries = new HashMap<>();
    /** Entries the user dismissed in this session: a journal read later does not list them again. */
    private final Set<ProcessIdentity> dismissed = new HashSet<>();

    private final Set<ProcessIdentity> checksPending = ConcurrentHashMap.newKeySet();
    private volatile Set<ProcessIdentity> heldView = Set.of();
    private List<SuspendedProcess> lastPublished = List.of();
    private Consumer<List<SuspendedProcess>> heldListener = processes -> {};
    private Consumer<List<RecoveredSuspension>> recoveredListener = processes -> {};
    private volatile Consumer<String> journalListener = problem -> {};
    /** Whether the failure of the journal was reported; set by any thread, without the lock. */
    private final java.util.concurrent.atomic.AtomicBoolean journalFailureReported =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile boolean refusing;
    /** Writes the journal outside the lock; present with the journal. */
    private final Optional<JournalWriter> writer;
    /**
     * The number of the last change of {@link #written}; changed under the lock. Atomic, like
     * {@link #dirty} is volatile, because static analysis does not see the lock.
     */
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
    /** Whether {@link #written} changed since it was last handed to the writer; guarded by the lock. */
    private volatile boolean dirty;

    /** Told the instance name of each ended session whose journal is read (milestone 10: its ledger is read too). */
    private volatile Consumer<String> deadSession = instance -> {};

    /** A coordinator without a journal: the user's suspensions only; every automatic one is refused. */
    public SuspensionCoordinator(ProcessController controller, ActivityLog activity, Executor checks) {
        this(controller, activity, checks, InstantSource.system(), Optional.empty());
    }

    /**
     * A coordinator that writes its journal on the calling thread, once the lock is let go: for
     * tests, where what was written is to be read back at once.
     *
     * @param checks runs the checks of held processes missing from a listing; the control executor
     * @param journal the journal of this session; empty where none could be opened at all
     */
    public SuspensionCoordinator(
            ProcessController controller,
            ActivityLog activity,
            Executor checks,
            InstantSource clock,
            Optional<SuspensionJournal> journal) {
        this(controller, activity, checks, clock, journal, Runnable::run);
    }

    /**
     * @param journalWrites the thread the journal is written on ({@code srm-journal}): never the
     *     caller's, so that a disk that stalls holds up no release (milestone 9 review, item 3)
     */
    public SuspensionCoordinator(
            ProcessController controller,
            ActivityLog activity,
            Executor checks,
            InstantSource clock,
            Optional<SuspensionJournal> journal,
            Executor journalWrites) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.checks = Objects.requireNonNull(checks, "checks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.journal = Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(journalWrites, "journalWrites");
        this.writer = journal.map(open -> new JournalWriter(open, journalWrites, this::writeFailed));
    }

    /** The longest a write-ahead waits for the journal; a journal slower than this is failed. */
    static final Duration JOURNAL_WAIT = Duration.ofSeconds(2);

    /**
     * Sets who is told the processes the user is to see as suspended by Prism, with their names,
     * each time that changes, and at once. Called on the thread that made the change, while the
     * coordinator is locked: it must return quickly and must not call back. A {@code
     * RuntimeException} it throws is logged and changes nothing here.
     */
    public void setHeldListener(Consumer<List<SuspendedProcess>> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            heldListener = listener;
            lastPublished = null;
            publish();
        } finally {
            unlockAndWrite();
        }
    }

    /** Sets who is told the processes an earlier session may have left suspended, at once and on each change; as above. */
    public void setRecoveredListener(Consumer<List<RecoveredSuspension>> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            recoveredListener = listener;
            publishRecovered();
        } finally {
            unlockAndWrite();
        }
    }

    /**
     * Sets who is told, once, that the journal failed, with the problem: the limit service, which
     * stops every limit then. Called while the coordinator is locked: it must hand the news on and
     * return, and must not call back.
     */
    public void setJournalListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            journalListener = listener;
        } finally {
            unlockAndWrite();
        }
    }

    /**
     * Holds the process suspended for the reason, suspending it if Prism does not already. The
     * user's suspension is written to the journal before the process is touched, and waited for
     * outside the lock, within {@link #JOURNAL_WAIT}.
     */
    public Outcome suspend(ProcessIdentity identity, String name, SuspensionReason reason) {
        boolean preDeclared = false;
        long ahead = 0;
        if (reason == SuspensionReason.USER && journal.isPresent()) {
            lock.lock();
            try {
                // Whatever Prism holds now: by the time the suspension runs, under a second hold of
                // the lock, another thread may have released the process (milestone 9 review). The
                // entry is written before the process is touched, so a crash right after the
                // suspension still leaves the record; held already, it costs one write.
                if (!refusing) {
                    declaredOf(identity, name).reasons.add(SuspensionReason.USER);
                    sync(identity);
                    ahead = version.get();
                    preDeclared = true;
                }
            } finally {
                unlockAndWrite();
            }
        }
        boolean recorded = !preDeclared || awaitWritten(ahead);
        Outcome outcome;
        long after;
        lock.lock();
        try {
            outcome = suspendLocked(identity, name, reason, preDeclared);
            after = version.get();
        } finally {
            unlockAndWrite();
        }
        if (reason == SuspensionReason.USER && journal.isPresent() && outcome.change() != Change.REFUSED) {
            recorded = recorded && awaitWritten(after);
            if (!recorded) {
                return outcome.unrecorded();
            }
        }
        return outcome;
    }

    private Outcome suspendLocked(ProcessIdentity identity, String name, SuspensionReason reason, boolean preDeclared) {
        if (refusing) {
            if (preDeclared) {
                undeclare(identity, SuspensionReason.USER);
                sync(identity);
            }
            return new Outcome(
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "suspensions are refused: shutting down"),
                    Change.REFUSED,
                    Set.of(),
                    0);
        }
        if (reason.automatic() && !recordedAhead(identity, reason)) {
            return new Outcome(
                    ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            "not suspended: the journal does not hold the " + reason
                                    + " entry that would let a later session find it"),
                    Change.FAILED,
                    Set.of(),
                    0);
        }
        Held current = held.get(identity);
        if (current != null && current.incomplete) {
            // What is left of an earlier suspension is released first; nothing is stacked on it.
            ControlOutcome retry = controller.resume(current.token);
            if (current.token.outstanding() > 0) {
                if (preDeclared) {
                    undeclare(identity, SuspensionReason.USER);
                }
                sync(identity);
                return new Outcome(retry, Change.INCOMPLETE, current.reasons, current.token.outstanding());
            }
            forget(identity);
            current = null;
        }
        if (current != null) {
            current.reasons.add(reason);
            if (preDeclared) {
                undeclare(identity, SuspensionReason.USER);
            }
            sync(identity);
            publish();
            return new Outcome(
                    ControlOutcome.success("already held for " + current.reasons),
                    Change.ALREADY_HELD,
                    current.reasons,
                    0);
        }
        SuspendResult result;
        try {
            result = controller.suspend(identity);
        } finally {
            if (preDeclared) {
                undeclare(identity, SuspensionReason.USER);
            }
        }
        Outcome outcome;
        if (result.outcome().succeeded()) {
            keep(identity, new Held(result.suspension().orElseThrow(), name, EnumSet.of(reason), false));
            outcome = new Outcome(result.outcome(), Change.SUSPENDED, EnumSet.of(reason), 0);
        } else if (result.stoppedElsewhere()) {
            outcome = new Outcome(result.outcome(), Change.STOPPED_ELSEWHERE, Set.of(), 0);
        } else if (result.suspension().isPresent()) {
            Suspension remainder = result.suspension().get();
            keep(identity, new Held(remainder, name, EnumSet.noneOf(SuspensionReason.class), true));
            outcome = new Outcome(result.outcome(), Change.INCOMPLETE, Set.of(), remainder.outstanding());
        } else {
            // Retryable only when the platform says so and Prism holds nothing of the process now.
            boolean retryable = result.transientFailure() && !held.containsKey(identity);
            outcome = new Outcome(result.outcome(), Change.FAILED, Set.of(), 0, true, retryable);
        }
        sync(identity);
        return outcome;
    }

    /** Gives up the reason; resumes the process when it was the last one. */
    public Outcome release(ProcessIdentity identity, SuspensionReason reason) {
        lock.lock();
        try {
            Outcome outcome = releaseLocked(identity, reason);
            sync(identity);
            return outcome;
        } finally {
            unlockAndWrite();
        }
    }

    private Outcome releaseLocked(ProcessIdentity identity, SuspensionReason reason) {
        Held current = held.get(identity);
        if (current == null) {
            return new Outcome(
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "Prism holds no suspension of this process"),
                    Change.NOT_HELD,
                    Set.of(),
                    0);
        }
        boolean had = current.reasons.remove(reason);
        if (!current.reasons.isEmpty()) {
            publish();
            return had
                    ? new Outcome(
                            ControlOutcome.success("still held for " + current.reasons),
                            Change.STILL_HELD,
                            current.reasons,
                            0)
                    : new Outcome(
                            ControlOutcome.of(
                                    OperationResult.INVALID_ARGUMENT, "held for " + current.reasons + " only"),
                            Change.NOT_HELD,
                            current.reasons,
                            0);
        }
        return resumeLocked(identity, current);
    }

    /** No reason is left: undoes the suspension, or keeps what could not be undone as incomplete. */
    private Outcome resumeLocked(ProcessIdentity identity, Held current) {
        ControlOutcome resumed;
        try {
            resumed = controller.resume(current.token);
        } catch (RuntimeException unexpected) {
            // What the token still holds is not known: it is kept as incomplete, shown, and retried.
            current.incomplete = true;
            publish();
            throw unexpected;
        }
        int outstanding = current.token.outstanding();
        if (outstanding == 0) {
            forget(identity);
            return new Outcome(
                    resumed,
                    resumed.result() == OperationResult.PROCESS_NOT_FOUND ? Change.EXITED : Change.RESUMED,
                    Set.of(),
                    0);
        }
        current.incomplete = true;
        publish();
        return new Outcome(
                resumed.succeeded()
                        ? ControlOutcome.of(OperationResult.NATIVE_ERROR, outstanding + " thread(s) still held")
                        : resumed,
                Change.INCOMPLETE,
                Set.of(),
                outstanding);
    }

    // ----- The journal -----

    /**
     * Records, ahead, that Prism may hold the process for an automatic reason: a CPU limit that
     * starts. Returns whether the journal holds it durably; only then may that reason suspend the
     * process (ADR-0011). Without a journal it never does.
     */
    public boolean declare(ProcessIdentity identity, String name, SuspensionReason reason, String cause) {
        if (!reason.automatic()) {
            throw new IllegalArgumentException("Only an automatic reason is declared ahead: " + reason);
        }
        if (journal.isEmpty()) {
            return false;
        }
        long needed;
        lock.lock();
        try {
            Declared entry = declaredOf(identity, name);
            entry.reasons.add(reason);
            entry.causes.put(reason, cause);
            sync(identity);
            needed = version.get();
            entry.durableAt = needed;
        } finally {
            unlockAndWrite();
        }
        // Waited for outside the lock: a slow disk holds up this limit, and no release.
        if (awaitWritten(needed)) {
            return true;
        }
        lock.lock();
        try {
            // Not written: nothing may suspend for it, and no later write may carry it.
            undeclare(identity, reason);
            refreshWritten(identity);
        } finally {
            unlockAndWrite();
        }
        return false;
    }

    /** Waits, without the lock, until the change is written; false if it was not, within the bound. */
    private boolean awaitWritten(long needed) {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("The journal is never waited for under the coordinator's lock");
        }
        return writer.isPresent() && writer.get().await(needed, JOURNAL_WAIT);
    }

    /** Lets go of the lock, then hands what changed to the writer: no journal I/O under the lock. */
    private void unlockAndWrite() {
        Optional<JournalWriter.Batch> batch = Optional.empty();
        if (dirty && writer.isPresent() && lock.getHoldCount() == 1) {
            List<JournalEntry> entries = new ArrayList<>(written.values());
            entries.sort(Comparator.comparingLong(
                    (JournalEntry entry) -> entry.identity().pid()));
            batch = Optional.of(new JournalWriter.Batch(version.get(), entries));
            dirty = false;
        }
        lock.unlock();
        batch.ifPresent(b -> writer.get().submit(b));
    }

    /**
     * Told by the writer when a write fails or the journal stalls, on the thread that found it: the
     * writing thread, or one whose wait ran out. Without the lock, which an action in a native call
     * may hold: reporting a failure never waits (milestone 9 review).
     */
    private void writeFailed() {
        journalFailed();
    }

    /**
     * Forgets a declared automatic reason without writing the journal: for a limit that ended because
     * the journal failed, or whose declaration was not written. What is on disk stays as evidence; a
     * later successful write no longer carries it.
     */
    public void dropDeclaration(ProcessIdentity identity, SuspensionReason reason) {
        lock.lock();
        try {
            undeclare(identity, reason);
            refreshWritten(identity);
        } finally {
            unlockAndWrite();
        }
    }

    /** What the next write says of the process, brought in line with the state, without writing. */
    private void refreshWritten(ProcessIdentity identity) {
        Optional<JournalEntry> now = entryOf(identity);
        if (now.isPresent()) {
            written.put(identity, now.get());
        } else {
            written.remove(identity);
        }
    }

    /** The automatic reason is no longer in force: the entry loses it. Returns whether the journal holds that. */
    public boolean withdraw(ProcessIdentity identity, SuspensionReason reason) {
        lock.lock();
        try {
            undeclare(identity, reason);
            return sync(identity);
        } finally {
            unlockAndWrite();
        }
    }

    /** Whether the journal is there and healthy: the condition for any automatic suspension. */
    public boolean journalHealthy() {
        return journal.isPresent()
                && journal.get().health().healthy()
                && writer.map(open -> !open.stalled()).orElse(false);
    }

    /** Why the journal is failed, when it is; empty while it is healthy. */
    public Optional<String> journalProblem() {
        if (journal.isEmpty()) {
            return Optional.of("no journal could be opened");
        }
        Optional<String> stalled = writer.flatMap(JournalWriter::stallReason);
        return stalled.isPresent() ? stalled : journal.get().health().problem();
    }

    /**
     * Checks that the journal is still what was written and its lock held; a failure fails it and
     * tells the journal listener. For the limiter, about once a second while limits are in force.
     */
    public boolean verifyJournal() {
        if (journal.isEmpty()) {
            return false;
        }
        boolean healthy =
                writer.get().call(() -> journal.get().verify(), JOURNAL_WAIT).orElse(false)
                        && !writer.get().stalled();
        if (!healthy) {
            lock.lock();
            try {
                journalFailed();
            } finally {
                unlockAndWrite();
            }
        }
        return healthy;
    }

    /** Tries the journal once more after a failure; for a new limit the user asks for. */
    public boolean retryJournal() {
        if (journal.isEmpty() || writer.get().stalled()) {
            // A journal that stopped answering is not tried again in this session.
            return false;
        }
        boolean reopened =
                writer.get().call(() -> journal.get().retry(), JOURNAL_WAIT).orElse(false);
        if (!reopened) {
            return false;
        }
        long needed;
        lock.lock();
        try {
            // Written with what this session holds now: not with what it held when it failed.
            writeJournal();
            needed = version.get();
        } finally {
            unlockAndWrite();
        }
        boolean healthy = awaitWritten(needed);
        if (healthy) {
            lock.lock();
            try {
                journalFailureReported.set(false);
                activity.record(ActivitySeverity.INFO, "The suspension journal is written again.");
            } finally {
                unlockAndWrite();
            }
        }
        return healthy;
    }

    /**
     * The end of a controlled shutdown: waits within the bound for what was handed to the writer,
     * then closes the journal on the writing thread. An empty journal is deleted.
     */
    public void closeJournal(Duration within) {
        if (journal.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + within.toNanos();
        long needed;
        // Bounded: an action stuck in a native call may hold the lock to the end.
        if (!tryLock(left(deadline))) {
            LOG.warn("The suspension journal was not closed: an action still held the coordinator");
            return;
        }
        try {
            needed = version.get();
        } finally {
            unlockAndWrite();
        }
        // Closed once what was handed over is written, healthy or not (the journal keeps a file that
        // failed); a slow disk at the end does not fail the journal of the next start.
        if (!writer.get().awaitQuietly(needed, left(deadline))) {
            LOG.warn("The suspension journal was not closed: what was handed to it is not written");
            return;
        }
        writer.get()
                .call(
                        () -> {
                            journal.get().close();
                            return true;
                        },
                        left(deadline));
    }

    /** Whether the reason is in the journal, healthy, for the process. */
    private boolean recordedAhead(ProcessIdentity identity, SuspensionReason reason) {
        Declared entry = declared.get(identity);
        return entry != null
                && entry.reasons.contains(reason)
                && journalHealthy()
                && writer.get().written() >= entry.durableAt;
    }

    private Declared declaredOf(ProcessIdentity identity, String name) {
        Declared entry = declared.computeIfAbsent(identity, unused -> new Declared(name, clock.instant()));
        entry.name = name;
        return entry;
    }

    private void undeclare(ProcessIdentity identity, SuspensionReason reason) {
        Declared entry = declared.get(identity);
        if (entry != null) {
            entry.reasons.remove(reason);
            entry.causes.remove(reason);
            if (entry.reasons.isEmpty()) {
                declared.remove(identity);
            }
        }
    }

    /**
     * Brings the journal entry of the process in line with what is held and declared, and writes
     * the journal if it changed. Returns whether the journal holds it.
     */
    private boolean sync(ProcessIdentity identity) {
        if (journal.isEmpty()) {
            return false;
        }
        Optional<JournalEntry> now = entryOf(identity);
        JournalEntry before = written.get(identity);
        if (now.isEmpty() && before == null) {
            return journalHealthy();
        }
        if (now.isPresent() && before != null && sameContent(now.get(), before)) {
            return journalHealthy();
        }
        if (now.isPresent()) {
            written.put(identity, now.get());
        } else {
            written.remove(identity);
        }
        return writeJournal();
    }

    /** What the journal should say of the process now; empty when nothing is to be said. */
    private Optional<JournalEntry> entryOf(ProcessIdentity identity) {
        Held current = held.get(identity);
        Declared ahead = declared.get(identity);
        JournalEntry recoveredEntry = recoveredEntries.get(identity);
        EnumSet<SuspensionReason> reasons = EnumSet.noneOf(SuspensionReason.class);
        if (ahead != null) {
            reasons.addAll(ahead.reasons);
        }
        boolean incomplete = current != null && current.incomplete;
        if (current != null) {
            // The limiter's own holds come and go each cycle: its declared limit is what is written.
            for (SuspensionReason reason : current.reasons) {
                if (!reason.automatic()) {
                    reasons.add(reason);
                }
            }
        }
        if (reasons.isEmpty() && !incomplete) {
            return Optional.ofNullable(recoveredEntry);
        }
        // OOM Guard holds for minutes, not a fraction of a second: its threads are recorded like the user's,
        // so that a hold a crash leaves can be resumed from the next session's list (milestone 11, F33).
        boolean unitsMatter = incomplete
                || (current != null
                        && (current.reasons.contains(SuspensionReason.USER)
                                || current.reasons.contains(SuspensionReason.OOM_GUARD)));
        Optional<String> memento = unitsMatter ? current.token.memento() : Optional.empty();
        String name = current != null ? current.name : ahead.name;
        JournalEntry previous = written.get(identity);
        Instant created = previous != null ? previous.created() : ahead != null ? ahead.created : clock.instant();
        return Optional.of(new JournalEntry(
                identity,
                name,
                reasons,
                memento,
                ahead == null || ahead.causes.isEmpty()
                        ? Optional.empty()
                        : Optional.of(String.join("; ", ahead.causes.values())),
                journal.map(SuspensionJournal::instance).orElse("none"),
                created,
                clock.instant(),
                incomplete,
                false));
    }

    private static boolean sameContent(JournalEntry a, JournalEntry b) {
        return a.identity().equals(b.identity())
                && a.name().equals(b.name())
                && a.reasons().equals(b.reasons())
                && a.memento().equals(b.memento())
                && a.cause().equals(b.cause())
                && a.incomplete() == b.incomplete()
                && a.carried() == b.carried();
    }

    /**
     * Marks {@link #written} as changed: the writer gets it once the lock is let go. No I/O here.
     * Returns whether the journal is healthy now; whether this change reaches the disk is known only
     * to whoever waits for it.
     */
    private boolean writeJournal() {
        if (journal.isEmpty()) {
            return false;
        }
        version.incrementAndGet();
        dirty = true;
        return journalHealthy();
    }

    private void journalFailed() {
        if (!journalFailureReported.compareAndSet(false, true)) {
            return;
        }
        String problem = journalProblem().orElse("unknown");
        activity.record(
                ActivitySeverity.ERROR,
                "The suspension journal cannot be written (" + problem + "). Prism suspends nothing by itself"
                        + " until it can, and any CPU limit at work is stopped.");
        try {
            journalListener.accept(problem);
        } catch (RuntimeException e) {
            LOG.warn("The listener for the journal failed", e);
        }
    }

    // ----- What is held, for the user -----

    /** The processes the user is shown as suspended by Prism, incomplete ones included. Any thread; never blocks. */
    public Set<ProcessIdentity> held() {
        return heldView;
    }

    /**
     * Whether Prism keeps an incomplete suspension of the process: threads it could not resume,
     * retried on the user's Resume and at shutdown (milestone 7). Read only; the barrier of
     * milestone 10 starts no native CPU cap over it (plan D15).
     */
    public boolean incomplete(ProcessIdentity identity) {
        lock.lock();
        try {
            Held current = held.get(identity);
            return current != null && current.incomplete;
        } finally {
            unlockAndWrite();
        }
    }

    /**
     * Told the name of every ended session whose journal this session reads, while its lock is held
     * and before its files are deleted (milestone 10: its native-limit ledger is read then). Must not
     * call back into the coordinator.
     */
    public void setDeadSessionListener(Consumer<String> listener) {
        this.deadSession = Objects.requireNonNull(listener, "listener");
    }

    /** Why Prism holds the process now, the limiter's momentary hold included; empty when it holds none. */
    public Set<SuspensionReason> heldFor(ProcessIdentity identity) {
        lock.lock();
        try {
            Held current = held.get(identity);
            return current == null ? Set.of() : Set.copyOf(current.reasons);
        } finally {
            unlockAndWrite();
        }
    }

    /**
     * Takes a new listing on the monitoring thread and returns it as the user is to see it: a
     * process Prism holds for the user or OOM Guard is marked {@code SUSPENDED_BY_PRISM}, and a held
     * process the listing misses gets a check ({@link #listed}). Asks the system nothing.
     */
    public List<ProcessSnapshot> observe(List<ProcessSnapshot> listing) {
        Set<ProcessIdentity> current = heldView;
        if (current.isEmpty()) {
            return listing;
        }
        Set<ProcessIdentity> identities = new HashSet<>(listing.size() * 2);
        for (ProcessSnapshot process : listing) {
            identities.add(process.identity());
        }
        listed(identities);
        return SuspendedByPrism.overlay(listing, heldView);
    }

    /**
     * Told the identities of each new listing, on the monitoring thread. Queues a check for every
     * shown held process the listing misses that is not waiting for a check already. A process the
     * limiter alone holds is checked by the limiter, which reads it every second.
     */
    public void listed(Set<ProcessIdentity> listing) {
        Set<ProcessIdentity> current = heldView;
        if (current.isEmpty() || refusing) {
            return;
        }
        for (ProcessIdentity identity : current) {
            if (listing.contains(identity) || !checksPending.add(identity)) {
                continue;
            }
            try {
                checks.execute(() -> check(identity));
            } catch (RejectedExecutionException full) {
                // The next listing that misses it queues the check again.
                checksPending.remove(identity);
            }
        }
    }

    /** Whether a check of the identity is waiting or running; for tests. */
    boolean checkPending(ProcessIdentity identity) {
        return checksPending.contains(identity);
    }

    /** Checks whether a held process that a listing missed has really gone. */
    void check(ProcessIdentity identity) {
        lock.lock();
        try {
            Held current = held.get(identity);
            if (current == null) {
                return;
            }
            ControlOutcome liveness = controller.liveness(identity);
            if (liveness.result() == OperationResult.PROCESS_NOT_FOUND) {
                // Its threads have ended with it: nothing is left to resume.
                current.token.close();
                forget(identity);
                sync(identity);
                activity.record(
                        ActivitySeverity.INFO,
                        current.name + " (PID " + identity.pid() + ") exited while Prism held it suspended.");
            } else if (liveness.succeeded()) {
                current.undecidedChecks = 0;
            } else if (++current.undecidedChecks == UNDECIDED_CHECKS_REPORTED) {
                activity.record(
                        ActivitySeverity.WARNING,
                        "Whether " + current.name + " (PID " + identity.pid() + "), suspended by Prism, still runs"
                                + " could not be checked: "
                                + liveness.detail().orElse(liveness.result().name())
                                + ". Prism keeps its suspension.");
            }
        } catch (RuntimeException e) {
            LOG.warn("Checking a suspended process failed; its suspension is kept", e);
        } finally {
            unlockAndWrite();
            checksPending.remove(identity);
        }
    }

    // ----- After a crash -----

    /**
     * Takes the entries of the journals of sessions that ended without a controlled shutdown, at
     * start-up, before anything else is held. Resumes nothing: closes the entries whose process has
     * exited, belongs to another start time, or runs, and lists every other one for the user,
     * written into this session's journal so that it survives another crash. Returns whether that
     * write held; if not, the earlier journals must be kept for a later session.
     */
    public boolean recover(List<JournalEntry> entries) {
        boolean anyListed;
        int newlyListed = 0;
        long needed;
        lock.lock();
        try {
            for (JournalEntry entry : entries) {
                if (dismissed.contains(entry.identity()) || recovered.containsKey(entry.identity())) {
                    // Dismissed by the user in this session, or listed already: once is enough.
                    LOG.info("Already dismissed or listed in this session: {}", entry.describe());
                    continue;
                }
                if (held.containsKey(entry.identity())) {
                    // This session holds the process: its own journal records it.
                    LOG.info("Held by this session; the earlier entry is closed: {}", entry.describe());
                    continue;
                }
                Optional<RecoveredSuspension> classified;
                try {
                    classified = classify(entry);
                } catch (RuntimeException unchecked) {
                    // One entry that cannot be checked must not keep Prism from starting, nor the others from being
                    // listed.
                    LOG.warn("An entry of an earlier session could not be checked: {}", entry.describe(), unchecked);
                    classified = Optional.of(
                            manual(entry, "It could not be checked (" + unchecked + "). Prism changes nothing."));
                }
                if (classified.isPresent()) {
                    newlyListed++;
                }
                classified.ifPresent(listed -> {
                    recovered.put(entry.identity(), listed);
                    JournalEntry carried = new JournalEntry(
                            entry.identity(),
                            entry.name(),
                            entry.reasons(),
                            entry.memento(),
                            entry.cause(),
                            entry.writer(),
                            entry.created(),
                            entry.updated(),
                            entry.incomplete(),
                            true);
                    recoveredEntries.put(entry.identity(), carried);
                    written.put(entry.identity(), carried);
                    LOG.warn("Possibly left suspended by an earlier session: {}", entry.describe());
                });
            }
            anyListed = !recovered.isEmpty();
            if (anyListed) {
                writeJournal();
            }
            if (newlyListed > 0) {
                activity.record(
                        ActivitySeverity.WARNING,
                        newlyListed + " process(es) may have been left suspended by an earlier session of Prism."
                                + " The Processes page lists them; Prism resumes none of them by itself.");
            }
            needed = version.get();
            publishRecovered();
        } finally {
            unlockAndWrite();
        }
        return !anyListed || awaitWritten(needed);
    }

    /**
     * Reads the journals of earlier sessions that ended without a controlled shutdown and lists what
     * they recorded (ADR-0011): at start-up, before anything is suspended, and again later while a
     * journal waits for its lock. Their files go only once this session's journal holds the listed
     * entries; otherwise, and whatever goes wrong meanwhile, they stay for a later start and their
     * locks are let go. Nothing is resumed; the journal of a session that still runs is not read.
     * Not once the shutdown has begun.
     *
     * @return how many journals of ended sessions could not be read yet because their locks are still
     *     held: a later call may read them
     */
    public int recoverEarlierSessions() {
        if (journal.isEmpty() || refusing) {
            return 0;
        }
        SuspensionJournal own = journal.get();
        SuspensionJournal.Scan scan = own.scan();
        for (Path waiting : scan.waiting()) {
            LOG.warn("The journal of an earlier session has no free lock yet; it is read later: {}", waiting);
        }
        List<SuspensionJournal.Dead> dead = scan.dead();
        if (dead.isEmpty()) {
            return scan.notReadYet();
        }
        boolean saved = false;
        try {
            for (SuspensionJournal.Dead journalOf : dead) {
                try {
                    // Milestone 10: the native limits the ended session recorded are looked at while its
                    // lock is held, before its files go (plan section 6.4).
                    deadSession.accept(journalOf.instance());
                } catch (RuntimeException e) {
                    LOG.warn("The native limits of an earlier session could not be looked at", e);
                }
                if (journalOf.corrupt()) {
                    activity.record(
                            ActivitySeverity.WARNING,
                            "A suspension journal of an earlier session was damaged; what could be read is listed. A"
                                    + " copy is kept beside it: " + journalOf.journal());
                }
            }
            saved = recover(dead.stream()
                    .flatMap(journalOf -> journalOf.entries().stream())
                    .toList());
        } finally {
            for (SuspensionJournal.Dead journalOf : dead) {
                if (saved) {
                    own.finish(journalOf);
                } else {
                    own.abandon(journalOf);
                }
            }
        }
        if (!saved) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "The suspension journals of earlier sessions are kept for the next start: this session's"
                            + " journal could not take their entries.");
        }
        return scan.notReadYet();
    }

    /** What the user is shown of one entry, or empty when the entry is closed. */
    private Optional<RecoveredSuspension> classify(JournalEntry entry) {
        ProcessIdentity identity = entry.identity();
        ControlOutcome alive = controller.liveness(identity);
        if (alive.result() == OperationResult.PROCESS_NOT_FOUND) {
            activity.record(
                    ActivitySeverity.INFO,
                    "An earlier session of Prism recorded " + entry.name() + " (PID " + identity.pid()
                            + ") as suspended; it has exited. Nothing was done.");
            return Optional.empty();
        }
        if (!alive.succeeded()) {
            return Optional.of(manual(
                    entry,
                    "Whether it still runs could not be checked: "
                            + alive.detail().orElse(alive.result().name())
                            + ". Prism changes nothing; resume it outside Prism if it is stuck."));
        }
        StopState state = controller.stopState(identity);
        if (state == StopState.RUNNING) {
            activity.record(
                    ActivitySeverity.INFO,
                    "An earlier session of Prism recorded " + entry.name() + " (PID " + identity.pid()
                            + ") as suspended; it runs. Nothing was done.");
            return Optional.empty();
        }
        if (state == StopState.TRACED) {
            return Optional.of(manual(
                    entry, "It is stopped under a debugger, which is never Prism's to resume. Prism changes nothing."));
        }
        Capability resume = controller.capabilities().getOrDefault(ControlAction.RESUME, Capability.UNAVAILABLE);
        if (!resume.available()) {
            return Optional.of(manual(
                    entry, "Resume is not available on this system. Prism changes nothing; resume it outside Prism."));
        }
        boolean threadsAreUnits = resume.mechanism().orElseThrow() == ControlMechanism.PER_THREAD_SUSPENSION;
        if (threadsAreUnits && entry.memento().isEmpty()) {
            return Optional.of(manual(
                    entry,
                    "Prism did not record which threads it suspended, and a suspension count does not show whose"
                            + " it is. Prism changes nothing. If the process is stuck, resume it outside Prism,"
                            + " for example in Resource Monitor."));
        }
        String explanation = state == StopState.STOPPED
                ? "It is stopped. That does not prove the stop is Prism's: another program may have stopped it."
                : "Whether it is still suspended is checked when you ask to resume it.";
        return Optional.of(new RecoveredSuspension(
                identity,
                entry.displayName(),
                entry.reasons(),
                RecoveryState.POSSIBLY_SUSPENDED,
                explanation,
                true,
                entry.describe()));
    }

    private static RecoveredSuspension manual(JournalEntry entry, String explanation) {
        return new RecoveredSuspension(
                entry.identity(),
                entry.displayName(),
                entry.reasons(),
                RecoveryState.MANUAL_INTERVENTION,
                explanation,
                false,
                entry.describe());
    }

    /** The processes an earlier session may have left suspended, as listed now. */
    public List<RecoveredSuspension> recovered() {
        lock.lock();
        try {
            return List.copyOf(recovered.values());
        } finally {
            unlockAndWrite();
        }
    }

    /**
     * Resumes a listed process on the user's request, after the controller has checked it again
     * (ADR-0011): taken over and resumed only if the check holds; otherwise nothing is changed and
     * the entry says why.
     */
    public RecoveryOutcome resumeRecovered(ProcessIdentity identity) {
        lock.lock();
        try {
            RecoveredSuspension entry = recovered.get(identity);
            if (entry == null) {
                return new RecoveryOutcome(
                        ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no such entry"), Optional.empty(), true);
            }
            if (held.containsKey(identity)) {
                return new RecoveryOutcome(
                        ControlOutcome.of(
                                OperationResult.INVALID_ARGUMENT,
                                "Prism holds it in this session; Resume in the table releases that"),
                        Optional.empty(),
                        false);
            }
            if (!entry.resumable()) {
                return new RecoveryOutcome(
                        ControlOutcome.of(OperationResult.UNSUPPORTED, "Prism does not resume this entry"),
                        Optional.empty(),
                        false);
            }
            if (refusing) {
                return new RecoveryOutcome(
                        ControlOutcome.of(OperationResult.UNSUPPORTED, "shutting down"), Optional.empty(), false);
            }
            JournalEntry journalEntry = recoveredEntries.get(identity);
            AdoptResult adopted = controller.adopt(identity, journalEntry.memento());
            switch (adopted.verdict()) {
                case ADOPTED -> {
                    Held taken = new Held(
                            adopted.suspension().orElseThrow(),
                            entry.name(),
                            EnumSet.noneOf(SuspensionReason.class),
                            false);
                    held.put(identity, taken);
                    Outcome resumed = resumeLocked(identity, taken);
                    closeRecovered(identity);
                    sync(identity);
                    LOG.info(
                            "On the user's request Prism took over and released {}: {}",
                            entry.evidence(),
                            resumed.change());
                    return new RecoveryOutcome(resumed.outcome(), Optional.of(AdoptResult.Verdict.ADOPTED), true);
                }
                case NOT_SUSPENDED, GONE -> {
                    closeRecovered(identity);
                    sync(identity);
                    LOG.info("Closed without a change, {}: {}", adopted.verdict(), entry.evidence());
                    return new RecoveryOutcome(adopted.outcome(), Optional.of(adopted.verdict()), true);
                }
                case REFUSED -> {
                    if (adopted.suspension().isPresent()) {
                        // A check of Prism's own that could not be undone: kept, so it is not forgotten.
                        keep(
                                identity,
                                new Held(
                                        adopted.suspension().get(),
                                        entry.name(),
                                        EnumSet.noneOf(SuspensionReason.class),
                                        true));
                    }
                    recovered.put(
                            identity,
                            entry.withExplanation(
                                    "Not resumed: " + adopted.outcome().detail().orElse("the check did not hold")
                                            + ". Prism changed nothing.",
                                    true));
                    publishRecovered();
                    sync(identity);
                    LOG.warn(
                            "Not resumed ({}): {}",
                            adopted.outcome().detail().orElse(adopted.verdict().name()),
                            entry.evidence());
                    return new RecoveryOutcome(adopted.outcome(), Optional.of(adopted.verdict()), false);
                }
            }
            throw new IllegalStateException("Unknown verdict " + adopted.verdict());
        } finally {
            unlockAndWrite();
        }
    }

    /** Closes a listed entry without touching the process, and logs it with everything it held. */
    public boolean dismissRecovered(ProcessIdentity identity) {
        lock.lock();
        try {
            RecoveredSuspension entry = recovered.get(identity);
            if (entry == null) {
                return false;
            }
            LOG.info("Dismissed by the user, process left as it was: {}", entry.evidence());
            dismissed.add(identity);
            closeRecovered(identity);
            sync(identity);
            return true;
        } finally {
            unlockAndWrite();
        }
    }

    private void closeRecovered(ProcessIdentity identity) {
        recovered.remove(identity);
        recoveredEntries.remove(identity);
        publishRecovered();
    }

    // ----- Shutdown -----

    /** From now on nothing new is suspended; releasing still works. */
    public void refuseFurtherSuspensions() {
        refusing = true;
    }

    /**
     * Releases every reason that is Prism's own (the limiter's and OOM Guard's), resuming a process
     * that has no reason left, and takes the automatic reasons out of the journal. A suspension the
     * user asked for stays. Returns false if the lock could not be had within the time given;
     * nothing was done then.
     */
    public boolean releaseAutomaticReasons(Duration within) {
        if (!tryLock(within)) {
            activity.record(
                    ActivitySeverity.WARNING, "Automatic suspensions were not released: an action still held them.");
            return false;
        }
        try {
            releaseAutomaticLocked();
            return true;
        } finally {
            unlockAndWrite();
        }
    }

    private void releaseAutomaticLocked() {
        boolean changed = false;
        for (ProcessIdentity identity : List.copyOf(held.keySet())) {
            Held current = held.get(identity);
            boolean removed = current.reasons.removeIf(SuspensionReason::automatic);
            if (removed && current.reasons.isEmpty()) {
                Outcome outcome;
                try {
                    outcome = resumeLocked(identity, current);
                } catch (RuntimeException unexpected) {
                    // One process that cannot be released does not keep the others suspended.
                    activity.record(
                            ActivitySeverity.ERROR,
                            current.name + " (PID " + identity.pid() + ") could not be resumed: " + unexpected,
                            unexpected);
                    changed = true;
                    continue;
                }
                String target = current.name + " (PID " + identity.pid() + ") ";
                switch (outcome.change()) {
                    case RESUMED ->
                        activity.record(ActivitySeverity.INFO, target + "was resumed: Prism no longer holds it.");
                    case EXITED ->
                        activity.record(
                                ActivitySeverity.INFO, target + "has exited; Prism no longer holds it suspended.");
                    default ->
                        activity.record(
                                ActivitySeverity.ERROR,
                                target + "could not be resumed completely: "
                                        + outcome.outcome().detail().orElse(""));
                }
            }
            changed |= removed;
        }
        for (ProcessIdentity identity : List.copyOf(declared.keySet())) {
            Declared entry = declared.get(identity);
            entry.causes.keySet().removeIf(SuspensionReason::automatic);
            changed |= entry.reasons.removeIf(SuspensionReason::automatic);
            if (entry.reasons.isEmpty()) {
                declared.remove(identity);
            }
        }
        if (changed) {
            Set<ProcessIdentity> touched = new HashSet<>(written.keySet());
            touched.addAll(held.keySet());
            for (ProcessIdentity identity : touched) {
                sync(identity);
            }
        }
        publish();
    }

    /**
     * For the shutdown hook, which runs when the JVM exits without the controlled shutdown: refuses
     * further suspensions, releases the automatic reasons, and tries once more what an incomplete
     * suspension still holds, without letting go of anything. Safe after a completed shutdown, when
     * nothing is held. Returns whether anything was held.
     */
    public boolean releaseForExit(Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        refuseFurtherSuspensions();
        if (!tryLock(left(deadline))) {
            return true;
        }
        try {
            if (held.isEmpty() && declared.isEmpty()) {
                return false;
            }
        } finally {
            unlockAndWrite();
        }
        releaseAutomaticReasons(left(deadline));
        if (!tryLock(left(deadline))) {
            return true;
        }
        try {
            for (ProcessIdentity identity : List.copyOf(held.keySet())) {
                Held current = held.get(identity);
                if (current.incomplete) {
                    controller.resume(current.token);
                    if (current.token.outstanding() == 0) {
                        forget(identity);
                    }
                    sync(identity);
                }
            }
        } finally {
            unlockAndWrite();
        }
        // What was released leaves the journal if the disk answers in time; otherwise the next
        // start finds the processes running and closes their entries.
        if (writer.isPresent() && tryLock(left(deadline))) {
            long needed;
            try {
                needed = version.get();
            } finally {
                unlockAndWrite();
            }
            writer.get().awaitQuietly(needed, left(deadline));
        }
        return true;
    }

    /**
     * The last step of a shutdown: tries once more what an incomplete suspension still holds,
     * then lets go of every suspension without resuming it. What the user suspended stays suspended
     * (LIFE-2), stays in the journal for the next session to show, and the log names it. Returns
     * false if the lock could not be had in time; nothing is let go of then, and the exit hook still
     * finds it.
     *
     * <p>An automatic reason found here means that {@link #releaseAutomaticReasons} failed or ran out
     * of time (LIFE-3). It is released first: letting go of it as it stands would leave frozen a
     * process nobody asked to suspend. A resume that throws there leaves the hold incomplete: it is
     * tried once more below, and if it still holds threads the journal keeps them and the log says
     * so. If that second try throws too, nothing is let go of, and the exit hook still finds it.
     */
    public boolean finalizeForShutdown(Duration within) {
        if (!tryLock(within)) {
            activity.record(ActivitySeverity.WARNING, "Suspensions were not finalized: an action still held them.");
            return false;
        }
        try {
            releaseAutomaticLocked();
            List<String> leftForTheUser = new ArrayList<>();
            List<String> stillHeld = new ArrayList<>();
            for (Iterator<Map.Entry<ProcessIdentity, Held>> it = held.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<ProcessIdentity, Held> entry = it.next();
                Held current = entry.getValue();
                String label = current.name + " (PID " + entry.getKey().pid() + ")";
                if (current.incomplete) {
                    controller.resume(current.token);
                    if (current.token.outstanding() > 0) {
                        stillHeld.add(label);
                    } else {
                        current.incomplete = false;
                    }
                    // The journal keeps what is left, with the threads that are left, and drops what is
                    // released now (ADR-0011).
                    sync(entry.getKey());
                } else if (current.reasons.contains(SuspensionReason.USER)) {
                    leftForTheUser.add(label);
                }
                // The journal keeps the entry as it was last written: the next session lists it.
                current.token.close();
                it.remove();
            }
            publish();
            if (!leftForTheUser.isEmpty()) {
                activity.record(
                        ActivitySeverity.WARNING,
                        "Left suspended, as the user asked: " + String.join(", ", leftForTheUser)
                                + ". They stay suspended after Prism closes.");
            }
            if (!stillHeld.isEmpty()) {
                activity.record(
                        ActivitySeverity.ERROR,
                        "Threads Prism suspended could not all be released: " + String.join(", ", stillHeld) + ".");
            }
            return true;
        } finally {
            unlockAndWrite();
        }
    }

    /** What is left of a bound that ends at the deadline, on {@link System#nanoTime()}; never negative. */
    private static Duration left(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private boolean tryLock(Duration within) {
        try {
            return lock.tryLock(Math.max(0, within.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void keep(ProcessIdentity identity, Held entry) {
        held.put(identity, entry);
        publish();
    }

    private void forget(ProcessIdentity identity) {
        held.remove(identity);
        publish();
    }

    /** A hold the user is to see: the user's, OOM Guard's, or one Prism could not finish undoing. */
    private static boolean shown(Held current) {
        return current.incomplete
                || current.reasons.contains(SuspensionReason.USER)
                || current.reasons.contains(SuspensionReason.OOM_GUARD);
    }

    private void publish() {
        Set<ProcessIdentity> visible = new HashSet<>();
        List<SuspendedProcess> processes = new ArrayList<>();
        for (Map.Entry<ProcessIdentity, Held> entry : held.entrySet()) {
            if (shown(entry.getValue())) {
                visible.add(entry.getKey());
                processes.add(new SuspendedProcess(entry.getKey(), entry.getValue().name));
            }
        }
        heldView = Set.copyOf(visible);
        processes.sort(Comparator.comparing(SuspendedProcess::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(process -> process.identity().pid()));
        List<SuspendedProcess> now = List.copyOf(processes);
        if (now.equals(lastPublished)) {
            return;
        }
        lastPublished = now;
        try {
            heldListener.accept(now);
        } catch (RuntimeException e) {
            LOG.warn("The processes Prism holds suspended could not be handed over", e);
        }
    }

    private void publishRecovered() {
        try {
            recoveredListener.accept(List.copyOf(recovered.values()));
        } catch (RuntimeException e) {
            LOG.warn("The processes an earlier session may have left suspended could not be handed over", e);
        }
    }

    /** For tests: the reasons Prism holds the identity for. */
    Collection<SuspensionReason> reasons(ProcessIdentity identity) {
        return heldFor(identity);
    }
}
