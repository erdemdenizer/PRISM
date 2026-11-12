package prism.srm.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.platform.ClearResult;
import prism.srm.platform.ResourceLimiter;

/**
 * The native limits earlier sessions recorded (LIFE-4, LIM-5, plan §8.4): each record is checked
 * with the limiter, which reads what it can and changes no job (audit of the recovery trust
 * boundary); everything
 * else; what may still be in force is said in the activity log and carried into this session's
 * ledger, so that it is said again until the process exits. Nothing is terminated, no job is made.
 *
 * <p>Called while the ended session's journal lock is held, before its files go (the dead-session
 * hook of the coordinator), and once at start-up for ledgers whose session left no lock file at all.
 */
public final class NativeRecovery implements EarlierLimits {

    private static final Logger LOG = LoggerFactory.getLogger(NativeRecovery.class);

    /** File times and process start times come from the same clock; this covers their rounding. */
    static final Duration SLACK = Duration.ofSeconds(2);

    /**
     * Ledgers of other sessions still on disk, not settled: their records name processes that existed
     * when the file was last written, so a process that started later cannot be among them.
     *
     * @param bound the newest last write among them; {@link Instant#MAX} when unknown
     */
    record Unsettled(Instant bound, String why) {}

    /** Until the first look, nothing is known: every process may be named (audit item 2). */
    private static final Unsettled NOT_LOOKED_YET =
            new Unsettled(Instant.MAX, "Prism has not read the native-limit ledgers of earlier sessions yet");

    private volatile Optional<Unsettled> unsettled = Optional.of(NOT_LOOKED_YET);

    private final ResourceLimiter limiter;
    private final NativeLimitLedger own;
    private final Optional<Path> folder;
    private final JournalFiles files;
    private final ActivityLog activity;
    private final List<RecoveredLimit> found = new ArrayList<>();
    private final Predicate<String> sessionRunning;

    /** Damaged ledgers already settled and said this session: kept, not looked at again until the next start. */
    private final java.util.Set<String> keptDamaged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public NativeRecovery(
            ResourceLimiter limiter,
            NativeLimitLedger own,
            Optional<Path> folder,
            JournalFiles files,
            ActivityLog activity) {
        this(limiter, own, folder, files, activity, SuspensionJournal::writerRunning);
    }

    /**
     * @param sessionRunning whether the Prism process an instance name records still runs; a session
     *     is treated as ended only when this says no and its lock can be taken (audit, C). A test
     *     answers in its place.
     */
    NativeRecovery(
            ResourceLimiter limiter,
            NativeLimitLedger own,
            Optional<Path> folder,
            JournalFiles files,
            ActivityLog activity,
            Predicate<String> sessionRunning) {
        this.sessionRunning = Objects.requireNonNull(sessionRunning, "sessionRunning");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.own = Objects.requireNonNull(own, "own");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.files = Objects.requireNonNull(files, "files");
        this.activity = Objects.requireNonNull(activity, "activity");
        if (folder.isEmpty()) {
            unsettled = Optional.empty();
        }
    }

    @Override
    public Optional<String> mayStillCap(ProcessIdentity identity) {
        // The file state first, the found records second: a record is found before its file goes, and
        // the file goes before the state is refreshed, so no order of events falls between the two
        // (native-api review of the audit fixes).
        Optional<Unsettled> open = unsettled;
        synchronized (this) {
            for (RecoveredLimit each : found) {
                if (each.blocksAnotherLimiter() && each.record().identity().equals(identity)) {
                    return Optional.of("an earlier Prism session may still cap it natively ("
                            + each.record().describe() + ")");
                }
            }
        }
        if (open.isPresent()
                && (open.get().bound().equals(Instant.MAX)
                        || !identity.startTime().isAfter(open.get().bound().plus(SLACK)))) {
            return Optional.of(open.get().why());
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> pending() {
        return unsettled.map(Unsettled::why);
    }

    /**
     * Looks at which ledgers of other sessions are still on disk, unsettled: one waiting for its
     * session's lock, one that could not be read, one that is damaged, one whose records could not be
     * carried over. Never on the limiter thread: it lists the folder. A folder that cannot be listed
     * leaves every process that started until now possibly named.
     */
    public synchronized void refresh() {
        if (folder.isEmpty()) {
            unsettled = Optional.empty();
            return;
        }
        List<String> others;
        try {
            others = NativeLimitLedger.listInstances(folder.get(), files).stream()
                    .filter(instance -> !instance.equals(own.instance()))
                    .toList();
        } catch (IOException e) {
            unsettled = Optional.of(
                    new Unsettled(Instant.now(), "the folder of the native-limit ledgers cannot be read (" + e + ")"));
            return;
        }
        if (others.isEmpty()) {
            unsettled = Optional.empty();
            return;
        }
        Instant bound = Instant.MIN;
        List<String> present = new ArrayList<>();
        for (String instance : others) {
            JournalFiles.FileState state = files.state(NativeLimitLedger.file(folder.get(), instance));
            if (state.presence() == JournalFiles.FileState.Presence.ABSENT) {
                // The system says it is gone since the listing (settled, or a clean close): nothing to hold.
                continue;
            }
            present.add(instance);
            // A file whose state or time cannot be told could have been written just now (audit, B).
            Instant written = state.modified().orElseGet(Instant::now);
            if (written.isAfter(bound)) {
                bound = written;
            }
        }
        if (present.isEmpty()) {
            unsettled = Optional.empty();
            return;
        }
        others = present;
        unsettled = Optional.of(new Unsettled(
                bound,
                others.size() + " native-limit ledger(s) of earlier Prism sessions are not settled yet ("
                        + String.join(", ", others) + ")"));
    }

    /**
     * The look at start-up: the orphans, and whatever goes wrong, a barrier that follows what is on
     * disk. A failure of the look leaves no "not read yet" state for the session where no ledger is
     * there; one of the refresh too leaves every process that ran until now blocked, and is said.
     */
    public void lookAtStartUp() {
        try {
            lookAtOrphans();
        } catch (RuntimeException e) {
            activity.record(
                    ActivitySeverity.WARNING, "The native-limit ledgers of earlier sessions could not be read.", e);
            try {
                refresh();
            } catch (RuntimeException alsoFailed) {
                LOG.warn("The native-limit ledgers of earlier sessions could not be looked at again", alsoFailed);
                unsettled = Optional.of(
                        new Unsettled(Instant.now(), "the native-limit ledgers of earlier sessions could not be read"));
            }
        }
    }

    /** What was found so far, for the limit service. */
    public synchronized List<RecoveredLimit> found() {
        return List.copyOf(found);
    }

    /**
     * Looks at the ledger of one ended session. Its file goes once every record was settled or
     * carried over durably; otherwise it stays for the next start, which reads it as an orphan.
     */
    public List<RecoveredLimit> lookAt(String instance) {
        if (folder.isEmpty() || instance.equals(own.instance())) {
            return List.of();
        }
        try {
            if (sessionRunning.test(instance)) {
                // The lock alone is not proof: the Prism process the name records still runs (audit, C).
                LOG.warn("The native-limit ledger of {} is left alone: that Prism process still runs", instance);
                return List.of();
            }
            return settle(instance);
        } finally {
            refresh();
        }
    }

    private List<RecoveredLimit> settle(String instance) {
        NativeLimitLedger.DeadLedger ledger = NativeLimitLedger.readDeadLedger(folder.get(), instance, files);
        switch (ledger.status()) {
            case ABSENT -> {
                return List.of();
            }
            case EMPTY -> {
                NativeLimitLedger.deleteDead(folder.get(), instance, files);
                return List.of();
            }
            case UNREADABLE -> {
                // Evidence, not an empty ledger (audit item 1): it stays, and the next start reads it.
                activity.record(
                        ActivitySeverity.WARNING,
                        "The native-limit ledger of an earlier Prism session could not be read ("
                                + ledger.problem() + "). It is kept and read again at the next start; until then"
                                + " no emulated limit is offered for processes that ran while it was written.");
                return List.of();
            }
            case DAMAGED, VALID -> {
                // A damaged ledger's records are looked at as far as they could be read.
            }
        }
        List<RecoveredLimit> checked = new ArrayList<>();
        boolean carried = false;
        for (LimitRecord record : ledger.records()) {
            RecoveredLimit result;
            try {
                result = limiter.recover(record);
            } catch (RuntimeException e) {
                LOG.warn(
                        "The native limit of {} recorded by an earlier session could not be checked", record.name(), e);
                result = new RecoveredLimit(record, RecoveredLimit.Verdict.FAILED, e.toString());
            }
            result = settle(result);
            checked.add(result);
            say(result);
            if (result.blocksAnotherLimiter() || result.retainsProvenance()) {
                // Said again by every start until the process exits (the identity rule), and for a proven scope until
                // its
                // limits are cleared and read back or it has no processes left (provenance retention, plan Rev 2.2
                // §4.5).
                own.put(result.record().carriedInto(own.instance()));
                carried = true;
            }
        }
        // Known before any file goes, so that nothing is ever between "on disk" and "found".
        synchronized (this) {
            found.addAll(checked);
        }
        boolean kept = !carried || own.awaitLatest(NativeLimitLedger.WAIT);
        if (!ledger.whole()) {
            keptDamaged.add(instance);
            activity.record(
                    ActivitySeverity.WARNING,
                    "The native-limit ledger of an earlier Prism session is damaged: " + ledger.problem()
                            + ". The file is kept as it is; until it is settled, no emulated limit is offered for"
                            + " processes that ran while it was written.");
        } else if (kept) {
            NativeLimitLedger.deleteDead(folder.get(), instance, files);
        } else {
            LOG.warn(
                    "The native-limit ledger of {} stays for the next start: its records could not be carried over",
                    instance);
        }
        return List.copyOf(checked);
    }

    /**
     * The ledgers of sessions whose journal has gone, read as ended only on proof (audit, C): this
     * session takes the session's lock, which a running session holds as long as it runs, and the
     * Prism process its name records does not run. A lock that is held, or cannot be taken, or a
     * process that runs, leaves the ledger alone and unsettled. A ledger with a journal beside it is
     * left to the suspension journal's recovery, which reads it under that journal's lock.
     */
    public List<RecoveredLimit> lookAtOrphans() {
        if (folder.isEmpty()) {
            return List.of();
        }
        List<RecoveredLimit> all = new ArrayList<>();
        List<String> instances;
        try {
            instances = NativeLimitLedger.listInstances(folder.get(), files);
        } catch (IOException e) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "The native-limit ledgers of earlier Prism sessions cannot be listed (" + e + "). No emulated"
                            + " limit is offered for processes that started before now until they can be read.");
            refresh();
            return all;
        }
        for (String instance : instances) {
            if (instance.equals(own.instance()) || keptDamaged.contains(instance)) {
                continue;
            }
            JournalFiles.FileState journal = files.state(SuspensionJournal.journalFile(folder.get(), instance));
            if (journal.presence() != JournalFiles.FileState.Presence.ABSENT) {
                // Present, or not known to be gone: that session's journal recovery reads it (or a later look).
                continue;
            }
            Path lockFile = SuspensionJournal.lockFile(folder.get(), instance);
            Optional<JournalFiles.Lock> lock;
            try {
                lock = files.tryLock(lockFile);
            } catch (IOException | RuntimeException e) {
                LOG.warn("The lock of {} could not be taken; its native-limit ledger is left alone", instance, e);
                continue;
            }
            if (lock.isEmpty()) {
                // Held: a session that runs, or another one recovering it now. Not this session's to read.
                continue;
            }
            try {
                all.addAll(lookAt(instance));
            } finally {
                lock.get().release();
                if (files.state(NativeLimitLedger.file(folder.get(), instance)).presence()
                        == JournalFiles.FileState.Presence.ABSENT) {
                    deleteQuietly(lockFile);
                }
            }
        }
        refresh();
        return all;
    }

    private void deleteQuietly(Path path) {
        try {
            files.delete(path);
        } catch (IOException e) {
            LOG.debug("Could not delete {}: {}", path, e.toString());
        }
    }

    /**
     * Where the limiter clears earlier sessions' limits ({@link ResourceLimiter#recoveryClears}), a scope whose ownership
     * recovery proved is cleared now; the limiter proves the ownership again before it changes anything. Only a clearing
     * read back as none ends the record; anything else keeps it, as {@code CLEAR_FAILED}, which blocks and is retried at
     * the next start. A limiter that does not clear (Job Objects) is never asked.
     */
    private RecoveredLimit settle(RecoveredLimit result) {
        if (!limiter.recoveryClears() || !result.ownershipProven()) {
            return result;
        }
        ClearResult cleared;
        try {
            cleared = limiter.clearOwned(result.record());
        } catch (RuntimeException e) {
            LOG.warn(
                    "The limits an earlier session left on {} could not be cleared",
                    result.record().name(),
                    e);
            return new RecoveredLimit(result.record(), RecoveredLimit.Verdict.CLEAR_FAILED, e.toString());
        }
        String detail = cleared.outcome().detail().orElse(cleared.result().name());
        return switch (cleared.result()) {
            case CLEARED -> new RecoveredLimit(result.record(), RecoveredLimit.Verdict.CLEARED, "read back as none");
            case UNSUPPORTED -> result;
            case NOT_DONE ->
                cleared.outcome().result() == prism.srm.domain.OperationResult.PROCESS_NOT_FOUND
                        ? new RecoveredLimit(result.record(), RecoveredLimit.Verdict.GONE, detail)
                        : new RecoveredLimit(result.record(), RecoveredLimit.Verdict.CLEAR_FAILED, detail);
            case PARTIAL, UNVERIFIED, NOT_OWNED ->
                new RecoveredLimit(result.record(), RecoveredLimit.Verdict.CLEAR_FAILED, detail);
        };
    }

    private void say(RecoveredLimit result) {
        LimitRecord record = result.record();
        String who = record.name() + " (PID " + record.identity().pid() + ")";
        String limits = record.describe();
        switch (result.verdict()) {
            case HELD_ELSEWHERE ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + " may still be limited (" + limits + "): " + result.detail()
                                + ". The limit lasts until the program holding the job clears it, or the process exits;"
                                + " Prism starts no second limiter for it.");
            case NOTHING_TO_CLEAR, NOT_IN_JOB, GONE ->
                activity.record(
                        ActivitySeverity.INFO,
                        "A native limit an earlier session recorded for " + who + " is not in force: " + result.detail()
                                + ".");
            case GONE_CHILDREN_UNKNOWN ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + ", limited by an earlier session (" + limits
                                + "), has exited. Prism cannot prove that the limit"
                                + " never took effect: the processes it started may still be limited until they exit, and Prism"
                                + " cannot reach that job.");
            case UNREACHABLE, ACCESS_DENIED, VALUES_MISMATCH, FAILED ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + " may still be limited by an earlier Prism session (" + limits + "): " + result.detail()
                                + ". The limit lasts until the process exits; Prism starts no second limiter for it.");
            // Linux (milestone 12): what a limiter that does not clear reports. Unreachable in the K16 product, which
            // reads no ledger; the wording is listed in the M12 risk register (D8).
            case OWNED_LIMITED ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + " may still be limited by a systemd scope an earlier Prism session created (" + limits
                                + "): " + result.detail() + ". This version does not clear it; the limit lasts until"
                                + " the processes in the scope exit, and Prism starts no second limiter for it.");
            case OWNED_CHILDREN_ONLY ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + ", limited by a systemd scope an earlier Prism session created (" + limits
                                + "), has exited: " + result.detail() + ". The processes it started are still in the"
                                + " scope and may be limited until they exit; this version does not clear it.");
            case CLEARED ->
                activity.record(
                        ActivitySeverity.INFO,
                        "The limits an earlier session left on " + who + " were cleared: " + result.detail() + ".");
            case CLEAR_FAILED, UNPROVEN_SCOPE, FOREIGN_MEMBER ->
                activity.record(
                        ActivitySeverity.WARNING,
                        who + " may still be limited (" + limits + "): " + result.detail()
                                + ". Prism changes nothing it cannot prove it created, and starts no second limiter"
                                + " for it.");
        }
    }
}
