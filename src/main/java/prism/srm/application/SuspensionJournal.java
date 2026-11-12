package prism.srm.application;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * The suspension journal of one session of Prism (LIFE-6, ADR-0011): a small file in the per-user
 * state folder that says which processes Prism holds, or may hold, suspended and why, so that a
 * session that ends without a controlled shutdown leaves a record the next session can show.
 *
 * <p><b>One file per session, and a lock.</b> Each session writes its own file, {@code
 * suspensions-<instance>.journal}, and holds an exclusive lock on {@code
 * suspensions-<instance>.lock} for as long as it runs. A lock that can be taken belongs to a
 * session that has ended: that is how a later session tells a journal it may recover from one a
 * running Prism still writes. The lock is taken before the journal is read and held until its
 * files are deleted, so two sessions starting together never recover the same journal.
 *
 * <p><b>Written durably, and checked.</b> The whole file is written to a temporary file in the same
 * folder, forced to the device, and moved over the journal in one step (tried three times, for a
 * scanner that holds the file a moment); then it is read back and compared byte by byte with what
 * was written. Only then does a write count. A write that fails in any of these steps leaves the
 * previous file as it was and puts the journal in a failed state, which stays until a new attempt
 * succeeds: the CPU limiter suspends nothing while the journal is failed (ADR-0011).
 *
 * <p><b>The form</b> is lines of text: a header, the writer, one line per entry with its fields as
 * {@code key=value} pairs (values URL-encoded), and an end line with the number of entries and a
 * CRC-32 of everything before it. A line that cannot be read is passed over; a file whose end does
 * not match is kept aside as {@code .corrupt-<time>} for whoever wants to see it, and what could be
 * read of it is still shown.
 */
public final class SuspensionJournal implements AutoCloseable {

    /** Where the journal stands: healthy, or failed with the reason. */
    public record Health(boolean healthy, Optional<String> problem) {

        public static final Health HEALTHY = new Health(true, Optional.empty());

        public Health {
            Objects.requireNonNull(problem, "problem");
            if (healthy == problem.isPresent()) {
                throw new IllegalArgumentException("Exactly a failed journal names its problem");
            }
        }

        static Health failed(String problem) {
            return new Health(false, Optional.of(problem));
        }
    }

    /** The journal of a session that has ended, its lock held by this session until it is done with it. */
    public static final class Dead {
        private final Path journal;
        private final Path lockFile;
        private final JournalFiles.Lock lock;
        private final List<JournalEntry> entries;
        private final boolean corrupt;

        Dead(Path journal, Path lockFile, JournalFiles.Lock lock, List<JournalEntry> entries, boolean corrupt) {
            this.journal = journal;
            this.lockFile = lockFile;
            this.lock = lock;
            this.entries = List.copyOf(entries);
            this.corrupt = corrupt;
        }

        public Path journal() {
            return journal;
        }

        /** The name of the ended session: its PID and start, as its files are named (milestone 10). */
        public String instance() {
            Path name = journal.getFileName();
            String file = name == null ? "" : name.toString();
            if (!file.startsWith(PREFIX) || !file.endsWith(EXTENSION)) {
                return file;
            }
            return file.substring(PREFIX.length(), file.length() - EXTENSION.length());
        }

        public List<JournalEntry> entries() {
            return entries;
        }

        /** Whether the file did not end as a journal of this version ends; what could be read is in {@link #entries()}. */
        public boolean corrupt() {
            return corrupt;
        }
    }

    /**
     * What one look at the journals of other sessions found: the journals of sessions that have ended,
     * each held by this session until it is done with it; and the journals whose writer has ended
     * but whose lock the system has not let go yet, which a later look may read (Windows lets the
     * locks of a killed process go "depending upon available system resources"). The journal of a
     * session that still runs is in neither.
     */
    public record Scan(List<Dead> dead, List<Path> waiting, boolean listFailed) {

        public Scan(List<Dead> dead, List<Path> waiting) {
            this(dead, waiting, false);
        }

        /**
         * How many journals of ended sessions are not read yet: those waiting for their lock, and one
         * more when the folder could not be listed, which is never "all read" (audit, D).
         */
        public int notReadYet() {
            return waiting.size() + (listFailed ? 1 : 0);
        }

        public Scan {
            dead = List.copyOf(dead);
            waiting = List.copyOf(waiting);
        }
    }

    static final String HEADER = "prism-suspension-journal 1";
    static final String EXTENSION = ".journal";
    static final int MOVE_ATTEMPTS = 3;
    private static final String PREFIX = "suspensions-";
    private static final Logger LOG = LoggerFactory.getLogger(SuspensionJournal.class);

    private final JournalFiles files;
    private final Path folder;
    private final String instance;
    private final Path file;
    private final Path lockFile;
    private final Path temporary;
    private final Sleeper sleeper;
    private final Predicate<String> writerRunning;
    private JournalFiles.Lock lock;
    private byte[] written;
    /** Guards the state below; private, so no caller can hold it from outside. */
    private final Object guard = new Object();

    private List<JournalEntry> lastEntries = List.of();
    private volatile Health health;

    /** Waits between attempts to move; a test does not wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleepMillis(long millis);
    }

    private SuspensionJournal(
            JournalFiles files, Path folder, String instance, Sleeper sleeper, Predicate<String> writerRunning) {
        this.files = files;
        this.writerRunning = writerRunning;
        this.folder = folder;
        this.instance = instance;
        this.file = journalFile(folder, instance);
        this.lockFile = lockFile(folder, instance);
        this.temporary = folder.resolve(PREFIX + instance + ".tmp");
        this.sleeper = sleeper;
    }

    /**
     * Opens the journal of this session: creates the folder, takes the lock, and writes an empty
     * journal, checked. Never throws: a journal that cannot be opened is returned failed, and the
     * session goes on without the features that need it.
     *
     * @param instance names this session, unique among the sessions that may share the folder
     */
    public static SuspensionJournal open(Path folder, String instance, JournalFiles files) {
        return open(folder, instance, files, SuspensionJournal::sleep);
    }

    static SuspensionJournal open(Path folder, String instance, JournalFiles files, Sleeper sleeper) {
        return open(folder, instance, files, sleeper, SuspensionJournal::writerRunning);
    }

    /** @param writerRunning whether the session of an instance name still runs; a test answers in its place */
    static SuspensionJournal open(
            Path folder, String instance, JournalFiles files, Sleeper sleeper, Predicate<String> writerRunning) {
        SuspensionJournal journal = new SuspensionJournal(files, folder, instance, sleeper, writerRunning);
        journal.start();
        return journal;
    }

    /**
     * Whether the session named by an instance name ({@link #instanceName}) still runs: its PID
     * names a live process that started at the time the name records. A PID that is gone, or that
     * now names a process started at another time, is a session that has ended. A name without a
     * start time is taken as running while its PID lives: nothing then shows it has ended.
     */
    static boolean writerRunning(String instance) {
        int dash = instance.indexOf('-');
        if (dash <= 0) {
            return true;
        }
        long pid;
        try {
            pid = Long.parseLong(instance.substring(0, dash));
        } catch (NumberFormatException notOurs) {
            return true;
        }
        Optional<ProcessHandle> process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (process.isEmpty()) {
            return false;
        }
        String started = instance.substring(dash + 1);
        long millis;
        try {
            millis = Long.parseLong(started);
        } catch (NumberFormatException noStartTime) {
            return true;
        }
        return process.get()
                .info()
                .startInstant()
                .map(start -> start.toEpochMilli() == millis)
                .orElse(true);
    }

    /** The journal file of a session, by its instance name (milestone 10, recovery of native limits). */
    static Path journalFile(Path folder, String instance) {
        return folder.resolve(PREFIX + instance + EXTENSION);
    }

    /** The lock file of a session, which it holds for as long as it runs (milestone 10). */
    static Path lockFile(Path folder, String instance) {
        return folder.resolve(PREFIX + instance + ".lock");
    }

    /** The name a session gets: its PID and start time, which do not repeat together, or a random one. */
    public static String instanceName(long pid, Optional<Instant> started) {
        return pid + "-"
                + started.map(time -> Long.toString(time.toEpochMilli()))
                        .orElseGet(() -> java.util.UUID.randomUUID().toString());
    }

    /** The JournalFiles of the running system. */
    public static JournalFiles onDisk() {
        return new NioJournalFiles();
    }

    private void start() {
        synchronized (guard) {
            try {
                files.createFolder(folder);
                Optional<JournalFiles.Lock> taken = files.tryLock(lockFile);
                if (taken.isEmpty()) {
                    health = Health.failed("the journal of this session is locked by another process");
                    return;
                }
                lock = taken.get();
                health = Health.HEALTHY;
                write(List.of());
            } catch (IOException | RuntimeException e) {
                fail("the journal could not be opened: " + e);
            }
        }
    }

    public Health health() {
        return health;
    }

    /** The entries as last written and read back; what the file holds while the journal is healthy. */
    public List<JournalEntry> entries() {
        synchronized (guard) {
            return List.copyOf(lastEntries);
        }
    }

    public Path file() {
        return file;
    }

    public String instance() {
        return instance;
    }

    /**
     * Replaces the journal with these entries, durably, and checks it. Returns whether that held;
     * on a failure the previous file stays as it was and the journal is failed from now on, until
     * {@link #retry()} succeeds.
     */
    public boolean write(List<JournalEntry> entries) {
        synchronized (guard) {
            lastEntries = List.copyOf(entries);
            if (!health.healthy()) {
                return false;
            }
            if (lock == null || !lock.valid() || !files.exists(lockFile)) {
                return fail("the lock of the journal is lost");
            }
            byte[] bytes = encode(instance, entries);
            try {
                files.writeDurably(temporary, bytes);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(temporary);
                return fail("the journal could not be written: " + e);
            }
            IOException lastMove = null;
            boolean moved = false;
            for (int attempt = 1; attempt <= MOVE_ATTEMPTS && !moved; attempt++) {
                try {
                    files.moveAtomically(temporary, file);
                    moved = true;
                } catch (IOException e) {
                    lastMove = e;
                    if (attempt < MOVE_ATTEMPTS) {
                        sleeper.sleepMillis(20);
                    }
                }
            }
            if (!moved) {
                deleteQuietly(temporary);
                return fail("the journal could not be replaced: " + lastMove);
            }
            files.forceFolder(folder);
            try {
                byte[] back = files.read(file);
                if (!Arrays.equals(back, bytes)) {
                    keepAside(file);
                    return fail("the journal did not read back as it was written");
                }
            } catch (IOException | RuntimeException e) {
                return fail("the journal could not be read back: " + e);
            }
            written = bytes;
            return true;
        }
    }

    /**
     * The check made while limits run: the lock is held, and the file is still what was written.
     * A failure is a failed journal.
     */
    public boolean verify() {
        synchronized (guard) {
            if (!health.healthy()) {
                return false;
            }
            if (lock == null || !lock.valid() || !files.exists(lockFile)) {
                return fail("the lock of the journal is lost");
            }
            try {
                if (!Arrays.equals(files.read(file), written)) {
                    keepAside(file);
                    return fail("the journal changed on disk");
                }
            } catch (IOException | RuntimeException e) {
                return fail("the journal could not be read: " + e);
            }
            return true;
        }
    }

    /**
     * Tries once more after a failure: takes the lock again if it was lost and nobody else holds
     * it, and writes the entries last given. Returns whether the journal is healthy now.
     */
    public boolean retry() {
        synchronized (guard) {
            if (health.healthy()) {
                return true;
            }
            try {
                files.createFolder(folder);
                if (lock == null || !lock.valid() || !files.exists(lockFile)) {
                    if (lock != null) {
                        lock.release();
                    }
                    Optional<JournalFiles.Lock> taken = files.tryLock(lockFile);
                    if (taken.isEmpty()) {
                        lock = null;
                        return false;
                    }
                    lock = taken.get();
                }
            } catch (IOException | RuntimeException e) {
                return false;
            }
            Health before = health;
            health = Health.HEALTHY;
            if (!write(lastEntries)) {
                LOG.warn(
                        "The suspension journal is still failed ({})",
                        before.problem().orElse(""));
                return false;
            }
            LOG.info("The suspension journal is written again");
            return true;
        }
    }

    /**
     * The journals of sessions that have ended: every journal in the folder but this session's
     * whose lock can be taken. Each lock stays held until {@link #finish} or {@link #abandon} lets
     * go of it. Leftover temporary files of those sessions are deleted.
     */
    public List<Dead> deadJournals() {
        return scan().dead();
    }

    /**
     * Looks at the journals of other sessions (ADR-0011): takes the lock of each whose session has
     * ended and reads it; leaves alone the journal of a session that still runs; and reports, without
     * reading, one whose session has ended but whose lock is still held.
     */
    public Scan scan() {
        synchronized (guard) {
            List<Dead> dead = new ArrayList<>();
            List<Path> waiting = new ArrayList<>();
            List<Path> listed;
            try {
                listed = files.list(folder);
            } catch (IOException | RuntimeException e) {
                LOG.warn("The journals of earlier sessions could not be listed", e);
                return new Scan(dead, waiting, true);
            }
            for (Path candidate : listed) {
                Path fileName = candidate.getFileName();
                if (fileName == null) {
                    continue;
                }
                String name = fileName.toString();
                if (!name.startsWith(PREFIX) || !name.endsWith(EXTENSION) || candidate.equals(file)) {
                    continue;
                }
                String base = name.substring(0, name.length() - EXTENSION.length());
                Path otherLock = folder.resolve(base + ".lock");
                Optional<JournalFiles.Lock> taken;
                try {
                    taken = files.tryLock(otherLock);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("The lock of {} could not be taken; that journal is left alone", candidate, e);
                    continue;
                }
                if (taken.isEmpty()) {
                    // A session that still runs, or another one recovering it now: left alone. A
                    // writer that has ended with its lock not let go yet: to be looked at again.
                    String writer = base.substring(PREFIX.length());
                    if (!writerRunning.test(writer)) {
                        waiting.add(candidate);
                    }
                    continue;
                }
                deleteQuietly(folder.resolve(base + ".tmp"));
                try {
                    Decoded decoded = decode(files.read(candidate));
                    if (decoded.corrupt()) {
                        keepAside(candidate);
                    }
                    dead.add(new Dead(candidate, otherLock, taken.get(), decoded.entries(), decoded.corrupt()));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("The journal {} could not be read; it is left for a later session", candidate, e);
                    taken.get().release();
                }
            }
            return new Scan(dead, waiting);
        }
    }

    /** Done with a dead journal: its entries are carried over or closed. Deletes it and its lock. */
    public void finish(Dead dead) {
        deleteQuietly(dead.journal);
        dead.lock.release();
        deleteQuietly(dead.lockFile);
    }

    /** A dead journal that is to be tried again by a later session: its lock is let go, its files stay. */
    public void abandon(Dead dead) {
        dead.lock.release();
    }

    /**
     * The end of a controlled shutdown: the lock is let go. A journal that still holds entries, the
     * suspensions the user left in place, stays for the next session to show; an empty one is
     * deleted with its lock file.
     */
    @Override
    public void close() {
        synchronized (guard) {
            boolean empty = lastEntries.isEmpty() && health.healthy();
            if (empty) {
                deleteQuietly(file);
            }
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (empty) {
                deleteQuietly(lockFile);
            }
        }
    }

    private boolean fail(String problem) {
        if (health == null || health.healthy()) {
            LOG.error("The suspension journal failed: {}", problem);
        }
        health = Health.failed(problem);
        return false;
    }

    /** A copy of a file that did not read as written, for whoever wants to see what was there. */
    private void keepAside(Path damaged) {
        try {
            byte[] bytes = files.read(damaged);
            files.writeDurably(
                    damaged.resolveSibling(damaged.getFileName() + ".corrupt-" + System.currentTimeMillis()), bytes);
        } catch (IOException | RuntimeException e) {
            LOG.warn("A damaged journal could not be kept aside: {}", damaged, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            files.delete(path);
        } catch (IOException | RuntimeException e) {
            LOG.debug("{} could not be deleted", path, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----- The form of the file -----

    static byte[] encode(String writer, List<JournalEntry> entries) {
        StringBuilder text = new StringBuilder();
        text.append(HEADER).append('\n');
        text.append("writer ").append(encoded(writer)).append('\n');
        for (JournalEntry entry : entries) {
            text.append("entry")
                    .append(" pid=")
                    .append(entry.identity().pid())
                    .append(" start=")
                    .append(entry.identity().startTime())
                    .append(" name=")
                    .append(encoded(entry.name()))
                    .append(" reasons=")
                    .append(
                            entry.reasons().isEmpty()
                                    ? "-"
                                    : String.join(
                                            ",",
                                            entry.reasons().stream()
                                                    .map(Enum::name)
                                                    .sorted()
                                                    .toList()))
                    .append(" memento=")
                    .append(entry.memento().map(SuspensionJournal::encoded).orElse("-"))
                    .append(" cause=")
                    .append(entry.cause().map(SuspensionJournal::encoded).orElse("-"))
                    .append(" writer=")
                    .append(encoded(entry.writer()))
                    .append(" created=")
                    .append(entry.created().toEpochMilli())
                    .append(" updated=")
                    .append(entry.updated().toEpochMilli())
                    .append(" incomplete=")
                    .append(entry.incomplete() ? 1 : 0)
                    .append(" carried=")
                    .append(entry.carried() ? 1 : 0)
                    .append('\n');
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] end = ("end " + entries.size() + " " + Long.toHexString(crc.getValue()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(body, body.length + end.length);
        System.arraycopy(end, 0, all, body.length, end.length);
        return all;
    }

    /** What a file held, and whether it ended as a journal of this version ends. */
    record Decoded(List<JournalEntry> entries, boolean corrupt) {}

    static Decoded decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<JournalEntry> entries = new ArrayList<>();
        boolean corrupt = !text.startsWith(HEADER + "\n");
        int end = text.lastIndexOf("\nend ");
        String body = end < 0 ? text : text.substring(0, end + 1);
        if (end < 0) {
            corrupt = true;
        } else {
            String[] tail = text.substring(end + 1).strip().split(" ");
            CRC32 crc = new CRC32();
            crc.update(body.getBytes(StandardCharsets.UTF_8));
            int lines =
                    (int) body.lines().filter(line -> line.startsWith("entry ")).count();
            if (tail.length != 3
                    || !tail[2].equals(Long.toHexString(crc.getValue()))
                    || !tail[1].equals(Integer.toString(lines))) {
                corrupt = true;
            }
        }
        for (String line : body.lines().toList()) {
            if (!line.startsWith("entry ")) {
                continue;
            }
            Optional<JournalEntry> entry = parseEntry(line);
            if (entry.isPresent()) {
                entries.add(entry.get());
            } else {
                corrupt = true;
            }
        }
        return new Decoded(entries, corrupt);
    }

    private static Optional<JournalEntry> parseEntry(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String part : line.substring("entry ".length()).split(" ")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                return Optional.empty();
            }
            fields.put(part.substring(0, equals), part.substring(equals + 1));
        }
        try {
            // The start time exactly as the identity holds it: identities compare to the last unit.
            Instant start = Instant.parse(fields.get("start"));
            Set<SuspensionReason> reasons = EnumSet.noneOf(SuspensionReason.class);
            String listed = fields.get("reasons");
            if (!"-".equals(listed)) {
                for (String reason : listed.split(",")) {
                    reasons.add(SuspensionReason.valueOf(reason));
                }
            }
            long pid = Long.parseLong(fields.get("pid"));
            if (pid <= 0 || pid > Integer.MAX_VALUE) {
                // No system Prism runs on gives such a PID: the line is not one Prism wrote.
                return Optional.empty();
            }
            return Optional.of(new JournalEntry(
                    new ProcessIdentity(pid, start),
                    decoded(fields.get("name")),
                    reasons,
                    optional(fields.get("memento")),
                    optional(fields.get("cause")),
                    decoded(fields.get("writer")),
                    Instant.ofEpochMilli(Long.parseLong(fields.get("created"))),
                    Instant.ofEpochMilli(Long.parseLong(fields.get("updated"))),
                    "1".equals(fields.get("incomplete")),
                    "1".equals(fields.get("carried"))));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<String> optional(String value) {
        return value == null || "-".equals(value) ? Optional.empty() : Optional.of(decoded(value));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decoded(String value) {
        return URLDecoder.decode(Objects.requireNonNull(value, "field"), StandardCharsets.UTF_8);
    }
}
