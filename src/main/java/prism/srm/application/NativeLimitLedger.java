package prism.srm.application;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ScopeProvenance;

/**
 * The native-limit ledger of one Prism session (milestone 10, plan §6.4, ADR-0012): which processes
 * this session may have left in a job with limits, so that a later session can say so after a
 * crash. One file, {@code native-limits-<instance>.ledger}, beside the suspension journal of the
 * same instance, whose lock tells a later session whether this one still runs.
 *
 * <p><b>Ordering and versions (plan D17).</b> What the ledger holds is the model in memory; every
 * change numbers it. The writer never writes a copy taken when a change was handed over: when it
 * runs it takes the model as it is then, and writes the whole file with that version, durably and
 * read back. So a write that is late, or whose wait ran out, lands with the newest model: it can
 * neither put an older record over a newer one nor bring back a record that was removed. The file
 * is deleted only once the newest version was written and the model is empty.
 *
 * <p><b>Waits (plan D12).</b> What must be on disk before Prism acts waits for its version, at most
 * {@link #WAIT}: a record before an assignment, a change that leaves a limit in force. A wait that
 * runs out fails the ledger for the session: no new native limit, nothing tightened; what is in
 * force stays. Clearing and removing never wait. The writes run on the executor given ({@code
 * srm-journal}), never on the caller's thread.
 */
public final class NativeLimitLedger {

    private static final Logger LOG = LoggerFactory.getLogger(NativeLimitLedger.class);

    /** The longest Prism waits for a record to be on disk before it acts on it. */
    public static final Duration WAIT = Duration.ofSeconds(2);

    static final String PREFIX = "native-limits-";
    static final String SUFFIX = ".ledger";
    /** Format 1 (milestone 10): every record a Job Object record. Still written whenever format 2 is not needed. */
    private static final String HEADER = "prism native limits 1";

    /**
     * Format 2 (milestone 12): each record also carries its memory boundary and, on Linux, its scope provenance.
     * Written only when some record needs them, so a ledger of Job Object records stays byte for byte format 1 and an
     * older Prism still reads it. An older Prism reads a format 2 file as damaged: it keeps it and holds its barrier.
     */
    private static final String HEADER_V2 = "prism native limits 2";

    /** The start of every header; a header of an unknown, later format is never interpreted. */
    private static final String HEADER_PREFIX = "prism native limits ";

    private static final int MOVE_ATTEMPTS = 3;

    private final Optional<Path> folder;
    private final String instance;
    private final JournalFiles files;
    private final Executor writes;
    private final Object guard = new Object();

    // Guarded by the guard.
    private final Map<ProcessIdentity, LimitRecord> model = new LinkedHashMap<>();
    private long version;
    private long written;
    private boolean draining;
    private boolean fileExists;
    private String problem = "";

    private volatile boolean failed;

    /**
     * @param folder where the ledger lives (the journal folder); empty for none, which refuses every
     *     native limit
     * @param writes the executor the file is written on ({@code srm-journal})
     */
    public NativeLimitLedger(Optional<Path> folder, String instance, JournalFiles files, Executor writes) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.instance = Objects.requireNonNull(instance, "instance");
        this.files = Objects.requireNonNull(files, "files");
        this.writes = Objects.requireNonNull(writes, "writes");
        if (folder.isEmpty()) {
            failed = true;
            problem = "Prism has no state folder";
        }
    }

    /** A ledger that records nothing: every native limit is refused (for systems and tests without one). */
    public static NativeLimitLedger none() {
        return new NativeLimitLedger(Optional.empty(), "none", new NioJournalFiles(), Runnable::run);
    }

    public String instance() {
        return instance;
    }

    /** Whether records can be made durable; false once a write failed or a wait ran out. */
    public boolean healthy() {
        return !failed;
    }

    public Optional<String> problem() {
        synchronized (guard) {
            return failed ? Optional.of(problem) : Optional.empty();
        }
    }

    public Optional<LimitRecord> find(ProcessIdentity identity) {
        synchronized (guard) {
            return Optional.ofNullable(model.get(identity));
        }
    }

    public List<LimitRecord> records() {
        synchronized (guard) {
            return List.copyOf(model.values());
        }
    }

    /**
     * Records and waits until the record is on disk, within {@link #WAIT}. False if it is not, and
     * then the record is taken back out of the model, so that no later write carries it (a write
     * that still lands with it only over-warns a later session).
     */
    public boolean putDurably(LimitRecord record) {
        if (failed) {
            return false;
        }
        long needed = change(record.identity(), record);
        if (await(needed, WAIT)) {
            return true;
        }
        synchronized (guard) {
            if (record.equals(model.get(record.identity()))) {
                model.remove(record.identity());
                version++;
            }
        }
        return false;
    }

    /** Records without waiting: what Prism learnt after it acted (applied, cleared, uncertain). */
    public void put(LimitRecord record) {
        change(record.identity(), record);
    }

    /** Removes the record of a process without waiting. */
    public void remove(ProcessIdentity identity) {
        change(identity, null);
    }

    private long change(ProcessIdentity identity, LimitRecord record) {
        long now;
        synchronized (guard) {
            LimitRecord before = record == null ? model.remove(identity) : model.put(identity, record);
            if (Objects.equals(before, record)) {
                return version;
            }
            now = ++version;
        }
        drainLater();
        return now;
    }

    private void drainLater() {
        if (failed || folder.isEmpty()) {
            return;
        }
        boolean start;
        synchronized (guard) {
            start = !draining;
            draining = true;
        }
        if (start) {
            try {
                writes.execute(this::drain);
            } catch (RejectedExecutionException closed) {
                synchronized (guard) {
                    draining = false;
                }
                fail("the ledger's thread takes no more work");
            }
        }
    }

    private void drain() {
        boolean finished = false;
        try {
            while (true) {
                long now;
                List<LimitRecord> snapshot;
                boolean exists;
                synchronized (guard) {
                    if (version <= written || failed) {
                        draining = false;
                        guard.notifyAll();
                        finished = true;
                        return;
                    }
                    // The model as it is now, not as it was handed over.
                    now = version;
                    snapshot = List.copyOf(model.values());
                    exists = fileExists;
                }
                boolean done = !snapshot.isEmpty() || exists ? write(now, snapshot) : true;
                synchronized (guard) {
                    if (done) {
                        written = Math.max(written, now);
                        fileExists = fileExists || !snapshot.isEmpty();
                    }
                    guard.notifyAll();
                }
                if (!done) {
                    return;
                }
            }
        } finally {
            if (!finished) {
                synchronized (guard) {
                    draining = false;
                    guard.notifyAll();
                }
            }
        }
    }

    private boolean write(long now, List<LimitRecord> snapshot) {
        Path dir = folder.orElseThrow();
        Path file = file(dir, instance);
        Path temporary = dir.resolve(PREFIX + instance + ".tmp");
        byte[] bytes = encode(now, snapshot);
        try {
            files.createFolder(dir);
            files.writeDurably(temporary, bytes);
            IOException lastMove = null;
            for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
                try {
                    files.moveAtomically(temporary, file);
                    lastMove = null;
                    break;
                } catch (IOException e) {
                    lastMove = e;
                }
            }
            if (lastMove != null) {
                deleteQuietly(temporary);
                return fail("the ledger could not be replaced: " + lastMove);
            }
            files.forceFolder(dir);
            if (!Arrays.equals(files.read(file), bytes)) {
                return fail("the ledger did not read back as it was written");
            }
            return true;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            return fail("the ledger could not be written: " + e);
        }
    }

    private boolean fail(String why) {
        synchronized (guard) {
            if (!failed) {
                failed = true;
                problem = why;
                LOG.error("The native-limit ledger failed: {}", why);
            }
            guard.notifyAll();
        }
        return false;
    }

    /**
     * Waits until the change numbered {@code needed} is on disk, within the bound. A wait that runs
     * out fails the ledger for the session (the late write may still land; it cannot do harm).
     */
    boolean await(long needed, Duration bound) {
        Waited result = waitFor(needed, bound);
        if (result == Waited.TIMED_OUT) {
            fail("the ledger did not answer within " + bound.toMillis() + " ms");
        }
        return result == Waited.WRITTEN && !failed;
    }

    /** Waits for the newest change within the bound and reports, failing nothing: for the shutdown and the exit. */
    public boolean awaitLatest(Duration bound) {
        long needed;
        synchronized (guard) {
            needed = version;
        }
        return waitFor(needed, bound) == Waited.WRITTEN;
    }

    private enum Waited {
        WRITTEN,
        TIMED_OUT,
        FAILED,
        INTERRUPTED
    }

    private Waited waitFor(long needed, Duration bound) {
        long deadline = System.nanoTime() + bound.toNanos();
        synchronized (guard) {
            while (written < needed) {
                if (failed) {
                    return Waited.FAILED;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return Waited.TIMED_OUT;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(guard, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Waited.INTERRUPTED;
                }
            }
            return Waited.WRITTEN;
        }
    }

    /**
     * The end of the session: once the newest model is on disk and empty, the file goes. A ledger
     * that still names a process, or whose last write did not land in time, stays for the next
     * start, which reads it as the ledger of an ended session.
     */
    public void close(Duration within) {
        if (folder.isEmpty()) {
            return;
        }
        boolean landed = awaitLatest(within);
        boolean empty;
        synchronized (guard) {
            empty = model.isEmpty();
        }
        if (landed && empty && !failed) {
            deleteQuietly(file(folder.get(), instance));
        } else if (!empty) {
            LOG.info(
                    "The native-limit ledger stays for the next start: it names {} process(es)",
                    records().size());
        }
    }

    // ----- Ledgers of ended sessions -----

    static Path file(Path folder, String instance) {
        return folder.resolve(PREFIX + instance + SUFFIX);
    }

    /**
     * What reading the ledger of an ended session came to (external audit item 1). Only an absent
     * file, or one read whole and found empty, says there is nothing; a damaged or unreadable one is
     * evidence that stays where it is.
     *
     * @param records what could be read; for a damaged file, possibly not all it holds
     * @param problem what went wrong, for a damaged or unreadable file
     */
    public record DeadLedger(Status status, List<LimitRecord> records, String problem) {
        public enum Status {
            /** No file: the session recorded no native limit. */
            ABSENT,
            /** Read whole and checked: no record. */
            EMPTY,
            /** Read whole and checked: these records. */
            VALID,
            /** Read, but it fails its check: the records listed may not be all it holds. */
            DAMAGED,
            /** It exists and could not be read. */
            UNREADABLE
        }

        public DeadLedger {
            Objects.requireNonNull(status, "status");
            records = List.copyOf(records);
            Objects.requireNonNull(problem, "problem");
        }

        /** Whether everything it holds is known: then, once settled, it may go. */
        public boolean whole() {
            return status == Status.ABSENT || status == Status.EMPTY || status == Status.VALID;
        }
    }

    /** Reads the ledger of an ended session, saying whether what was read is all it holds. */
    public static DeadLedger readDeadLedger(Path folder, String instance, JournalFiles files) {
        Path file = file(folder, instance);
        try {
            // Read at once: only the system saying the file is not there makes it absent (audit, A).
            Decoded decoded = decode(files.read(file));
            if (decoded.corrupt()) {
                return new DeadLedger(
                        DeadLedger.Status.DAMAGED,
                        decoded.records(),
                        "the file fails its check; " + decoded.records().size()
                                + " record(s) could be read, and it may hold more");
            }
            return new DeadLedger(
                    decoded.records().isEmpty() ? DeadLedger.Status.EMPTY : DeadLedger.Status.VALID,
                    decoded.records(),
                    "");
        } catch (NoSuchFileException notThere) {
            return new DeadLedger(DeadLedger.Status.ABSENT, List.of(), "");
        } catch (IOException | RuntimeException e) {
            return new DeadLedger(DeadLedger.Status.UNREADABLE, List.of(), e.toString());
        }
    }

    /** The records an ended session left, as far as they could be read: for tests and diagnosis. */
    public static List<LimitRecord> readDead(Path folder, String instance, JournalFiles files) {
        return readDeadLedger(folder, instance, files).records();
    }

    /** Deletes the ledger of an ended session once each of its records was settled or carried over. */
    public static void deleteDead(Path folder, String instance, JournalFiles files) {
        try {
            files.delete(file(folder, instance));
            files.delete(folder.resolve(PREFIX + instance + ".tmp"));
        } catch (IOException e) {
            LOG.warn("The native-limit ledger of {} could not be deleted: {}", instance, e.toString());
        }
    }

    /** The instances whose ledgers lie in the folder; a folder that cannot be listed is said so. */
    public static List<String> listInstances(Path folder, JournalFiles files) throws IOException {

        return files.list(folder).stream()
                .map(Path::getFileName)
                .filter(java.util.Objects::nonNull)
                .map(Path::toString)
                .filter(name -> name.startsWith(PREFIX) && name.endsWith(SUFFIX))
                .map(name -> name.substring(PREFIX.length(), name.length() - SUFFIX.length()))
                .toList();
    }

    private void deleteQuietly(Path path) {
        try {
            files.delete(path);
        } catch (IOException e) {
            LOG.debug("Could not delete {}: {}", path, e.toString());
        }
    }

    // ----- The format -----

    /** Whether a record needs format 2: a throttling memory boundary, or scope provenance. */
    static boolean needsFormat2(LimitRecord record) {
        return record.provenance().isPresent()
                || record.values().memoryBoundary() != MemoryBoundary.HARD
                || record.pendingTo()
                        .filter(to -> to.memoryBoundary() != MemoryBoundary.HARD)
                        .isPresent();
    }

    static byte[] encode(long version, List<LimitRecord> records) {
        boolean format2 = records.stream().anyMatch(NativeLimitLedger::needsFormat2);
        StringBuilder text = new StringBuilder(format2 ? HEADER_V2 : HEADER)
                .append('\n')
                .append("version ")
                .append(version)
                .append('\n');
        for (LimitRecord record : records) {
            text.append("record pid=")
                    .append(record.identity().pid())
                    .append(" start=")
                    .append(record.identity().startTime().toEpochMilli())
                    .append(" name=")
                    .append(URLEncoder.encode(record.name(), StandardCharsets.UTF_8))
                    .append(" state=")
                    .append(record.state().name())
                    .append(" cpu=")
                    .append(cpu(record.values()))
                    .append(" mem=")
                    .append(memory(record.values()))
                    .append(" tocpu=")
                    .append(record.pendingTo().map(NativeLimitLedger::cpu).orElse("."))
                    .append(" tomem=")
                    .append(record.pendingTo().map(NativeLimitLedger::memory).orElse("."))
                    .append(" flags=")
                    .append(
                            record.flags().isEmpty()
                                    ? "."
                                    : String.join(
                                            ",",
                                            record.flags().stream()
                                                    .map(Enum::name)
                                                    .sorted()
                                                    .toList()))
                    .append(" instance=")
                    .append(URLEncoder.encode(record.instance(), StandardCharsets.UTF_8));
            if (format2) {
                Optional<ScopeProvenance> scope = record.provenance();
                text.append(" memb=")
                        .append(boundary(record.values()))
                        .append(" tomemb=")
                        .append(record.pendingTo()
                                .map(NativeLimitLedger::boundary)
                                .orElse("."))
                        .append(" unit=")
                        .append(scope.map(s -> URLEncoder.encode(s.unit(), StandardCharsets.UTF_8))
                                .orElse("."))
                        .append(" inv=")
                        .append(scope.flatMap(ScopeProvenance::invocationId).orElse("."))
                        .append(" cg=")
                        .append(scope.flatMap(ScopeProvenance::cgroupPath)
                                .map(path -> URLEncoder.encode(path, StandardCharsets.UTF_8))
                                .orElse("."));
            }
            text.append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] end = ("end " + records.size() + " " + Long.toHexString(crc.getValue()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(body, body.length + end.length);
        System.arraycopy(end, 0, all, body.length, end.length);
        return all;
    }

    private static String boundary(EffectiveLimits limits) {
        return limits.memoryBoundary() == MemoryBoundary.THROTTLE ? "throttle" : "hard";
    }

    private static String cpu(EffectiveLimits limits) {
        return limits.cpuRate().isPresent() ? Integer.toString(limits.cpuRate().getAsInt()) : "-";
    }

    private static String memory(EffectiveLimits limits) {
        return limits.memoryBytes().isPresent()
                ? Long.toString(limits.memoryBytes().getAsLong())
                : "-";
    }

    record Decoded(long version, List<LimitRecord> records, boolean corrupt) {}

    static Decoded decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int format;
        if (text.startsWith(HEADER + "\n")) {
            format = 1;
        } else if (text.startsWith(HEADER_V2 + "\n")) {
            format = 2;
        } else if (text.startsWith(HEADER_PREFIX)) {
            // A later format this version does not know: nothing in it is interpreted, and it is kept as evidence.
            return new Decoded(-1, List.of(), true);
        } else {
            format = 0;
        }
        boolean corrupt = format == 0;
        int end = text.lastIndexOf("\nend ");
        String body = end < 0 ? text : text.substring(0, end + 1);
        long version = -1;
        List<LimitRecord> records = new ArrayList<>();
        if (end < 0) {
            corrupt = true;
        } else {
            String[] tail = text.substring(end + 1).strip().split(" ");
            CRC32 crc = new CRC32();
            crc.update(body.getBytes(StandardCharsets.UTF_8));
            long lines = body.lines().filter(line -> line.startsWith("record ")).count();
            if (tail.length != 3
                    || !tail[2].equals(Long.toHexString(crc.getValue()))
                    || !tail[1].equals(Long.toString(lines))) {
                corrupt = true;
            }
        }
        for (String line : body.lines().toList()) {
            if (line.startsWith("version ")) {
                try {
                    version = Long.parseLong(line.substring("version ".length()).strip());
                } catch (NumberFormatException e) {
                    corrupt = true;
                }
            } else if (line.startsWith("record ")) {
                Optional<LimitRecord> record = parse(line, format);
                if (record.isPresent()) {
                    records.add(record.get());
                } else {
                    corrupt = true;
                }
            }
        }
        return new Decoded(version, List.copyOf(records), corrupt);
    }

    /** The fields only format 2 has. */
    private static final List<String> FORMAT2_FIELDS = List.of("memb", "tomemb", "unit", "inv", "cg");

    /**
     * One record line. Format 1 must not carry format 2 fields; format 2 must carry them all, consistent with each
     * other; format 0 (a damaged header) takes either. A line that breaks a rule is not a record: the file is damaged.
     */
    private static Optional<LimitRecord> parse(String line, int format) {
        Map<String, String> fields = new HashMap<>();
        for (String part : line.substring("record ".length()).split(" ")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                fields.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        try {
            ProcessIdentity identity = new ProcessIdentity(
                    Long.parseLong(fields.get("pid")), Instant.ofEpochMilli(Long.parseLong(fields.get("start"))));
            LedgerState state = LedgerState.valueOf(fields.get("state"));
            EffectiveLimits values = limits(fields.get("cpu"), fields.get("mem"));
            Optional<EffectiveLimits> to = ".".equals(fields.get("tocpu"))
                    ? Optional.empty()
                    : Optional.of(limits(fields.get("tocpu"), fields.get("tomem")));
            Set<LimitRecord.Flag> flags = EnumSet.noneOf(LimitRecord.Flag.class);
            String flagText = fields.get("flags");
            if (flagText != null && !flagText.equals(".")) {
                for (String flag : flagText.split(",")) {
                    flags.add(LimitRecord.Flag.valueOf(flag));
                }
            }
            boolean hasFormat2 = FORMAT2_FIELDS.stream().anyMatch(fields::containsKey);
            if (format == 1 && hasFormat2) {
                return Optional.empty();
            }
            if (format == 2 && !FORMAT2_FIELDS.stream().allMatch(fields::containsKey)) {
                return Optional.empty();
            }
            Optional<ScopeProvenance> provenance = Optional.empty();
            if (hasFormat2) {
                if (!FORMAT2_FIELDS.stream().allMatch(fields::containsKey)) {
                    return Optional.empty();
                }
                Optional<MemoryBoundary> boundary = boundary(fields.get("memb"));
                if (boundary.isEmpty() || (values.memoryBytes().isEmpty() && boundary.get() != MemoryBoundary.HARD)) {
                    return Optional.empty();
                }
                values = values.withMemoryBoundary(boundary.get());
                String toBoundary = fields.get("tomemb");
                if (to.isPresent() == ".".equals(toBoundary)) {
                    return Optional.empty();
                }
                if (to.isPresent()) {
                    Optional<MemoryBoundary> pending = boundary(toBoundary);
                    if (pending.isEmpty()
                            || (to.get().memoryBytes().isEmpty() && pending.get() != MemoryBoundary.HARD)) {
                        return Optional.empty();
                    }
                    to = Optional.of(to.get().withMemoryBoundary(pending.get()));
                }
                String unit = fields.get("unit");
                String invocation = fields.get("inv");
                String cgroup = fields.get("cg");
                if (".".equals(unit)) {
                    if (!".".equals(invocation) || !".".equals(cgroup)) {
                        return Optional.empty();
                    }
                } else {
                    provenance = Optional.of(new ScopeProvenance(
                            URLDecoder.decode(unit, StandardCharsets.UTF_8),
                            ".".equals(invocation) ? Optional.empty() : Optional.of(invocation),
                            ".".equals(cgroup)
                                    ? Optional.empty()
                                    : Optional.of(URLDecoder.decode(cgroup, StandardCharsets.UTF_8))));
                }
            }
            return Optional.of(new LimitRecord(
                    identity,
                    URLDecoder.decode(Objects.requireNonNull(fields.get("name")), StandardCharsets.UTF_8),
                    values,
                    to,
                    flags,
                    state,
                    URLDecoder.decode(Objects.requireNonNull(fields.get("instance")), StandardCharsets.UTF_8),
                    provenance));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<MemoryBoundary> boundary(String text) {
        return switch (text) {
            case "hard" -> Optional.of(MemoryBoundary.HARD);
            case "throttle" -> Optional.of(MemoryBoundary.THROTTLE);
            default -> Optional.empty();
        };
    }

    private static EffectiveLimits limits(String cpu, String memory) {
        return EffectiveLimits.of(
                "-".equals(cpu) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(cpu)),
                "-".equals(memory) ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(memory)));
    }
}
