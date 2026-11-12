package prism.srm.platform.linux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A proc file system written into a folder of the test's own, in the form proc_pid_stat(5) gives:
 * {@code /stat} with its {@code btime} line, and per process {@code /<pid>/stat} and {@code
 * /<pid>/task/<tid>/stat}. Every change rewrites the files, so what {@link ProcFs} reads is a real
 * file in the real format.
 */
final class FakeProc {

    static final long BOOT_TIME_SECONDS = 1_790_000_000L;
    static final long TICKS_PER_SECOND = 100;
    /** The user every fake process runs as unless a test says otherwise. */
    static final long OWNER_UID = 1000;

    /** The start time the process listing computes for a start of the given ticks after boot. */
    static Instant start(long ticks) {
        return Instant.ofEpochMilli(BOOT_TIME_SECONDS * 1000 + ticks * 1000 / TICKS_PER_SECOND);
    }

    private static final class Proc {
        String name;
        char state = 'S';
        long flags;
        long startTicks;
        /** Real, effective, saved set and file system user IDs. */
        long[] uids = {OWNER_UID, OWNER_UID, OWNER_UID, OWNER_UID};

        final Map<Integer, Integer> threadNice = new LinkedHashMap<>();
    }

    final Path root;
    private final Map<Integer, Proc> processes = new HashMap<>();

    FakeProc(Path root) {
        this.root = root;
        write(root.resolve("stat"), "cpu  1 2 3 4\nbtime " + BOOT_TIME_SECONDS + "\nprocesses 100\n");
    }

    /** A process with its main thread and the further threads given, all at nice 0, sleeping. */
    FakeProc process(int pid, String name, long startTicks, int... moreThreads) {
        Proc proc = new Proc();
        proc.name = name;
        proc.startTicks = startTicks;
        proc.threadNice.put(pid, 0);
        for (int tid : moreThreads) {
            proc.threadNice.put(tid, 0);
        }
        processes.put(pid, proc);
        render(pid);
        return this;
    }

    boolean exists(int pid) {
        return processes.containsKey(pid);
    }

    long startTicks(int pid) {
        return processes.get(pid).startTicks;
    }

    char state(int pid) {
        return processes.get(pid).state;
    }

    void state(int pid, char state) {
        processes.get(pid).state = state;
        render(pid);
    }

    /** The process's real, effective, saved set and file system user IDs. */
    void uids(int pid, long real, long effective, long saved, long fs) {
        processes.get(pid).uids = new long[] {real, effective, saved, fs};
        render(pid);
    }

    void flags(int pid, long flags) {
        processes.get(pid).flags = flags;
        render(pid);
    }

    /** The process of the thread, or 0 when no process has it. */
    int processOf(int tid) {
        return processes.entrySet().stream()
                .filter(entry -> entry.getValue().threadNice.containsKey(tid))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(0);
    }

    int nice(int pid, int tid) {
        return processes.get(pid).threadNice.get(tid);
    }

    void setNice(int tid, int nice) {
        int pid = processOf(tid);
        processes.get(pid).threadNice.put(tid, nice);
        render(pid);
    }

    void addThread(int pid, int tid, int nice) {
        processes.get(pid).threadNice.put(tid, nice);
        render(pid);
    }

    void removeThread(int pid, int tid) {
        processes.get(pid).threadNice.remove(tid);
        render(pid);
    }

    /** The stat file of the process no longer has its form; the process itself is unchanged. */
    void corrupt(int pid) {
        write(root.resolve(Integer.toString(pid)).resolve("stat"), "4242 (half");
    }

    /** The stat file of one thread no longer has its form, while the thread still runs and is listed. */
    void corruptThread(int pid, int tid) {
        write(
                root.resolve(Integer.toString(pid))
                        .resolve("task")
                        .resolve(Integer.toString(tid))
                        .resolve("stat"),
                "6002 (half");
    }

    /** The process has gone for the system calls while its files are still being read. */
    void forget(int pid) {
        processes.remove(pid);
    }

    /** The process has gone and was collected: its folder is no more. */
    void remove(int pid) {
        processes.remove(pid);
        delete(root.resolve(Integer.toString(pid)));
    }

    private void render(int pid) {
        Proc proc = processes.get(pid);
        Path folder = root.resolve(Integer.toString(pid));
        delete(folder);
        write(folder.resolve("stat"), stat(pid, proc, proc.threadNice.getOrDefault(pid, 0)));
        write(
                folder.resolve("status"),
                String.format(
                        Locale.ROOT,
                        "Name:\t%s\nState:\t%c\nUid:\t%d\t%d\t%d\t%d\nGid:\t1000\t1000\t1000\t1000\n",
                        proc.name,
                        proc.state,
                        proc.uids[0],
                        proc.uids[1],
                        proc.uids[2],
                        proc.uids[3]));
        for (Map.Entry<Integer, Integer> thread : proc.threadNice.entrySet()) {
            write(
                    folder.resolve("task")
                            .resolve(Integer.toString(thread.getKey()))
                            .resolve("stat"),
                    stat(thread.getKey(), proc, thread.getValue()));
        }
    }

    /** Fields 1 to 24 of proc_pid_stat(5); field 18 is the nice value plus 20, as for a process that is not real-time. */
    private static String stat(int id, Proc proc, int nice) {
        return String.format(
                Locale.ROOT,
                "%d (%s) %c 1 %d %d 0 -1 %d 0 0 0 0 5 3 0 0 %d %d %d 0 %d 1000000 250\n",
                id,
                proc.name,
                proc.state,
                id,
                id,
                proc.flags,
                20 + nice,
                nice,
                proc.threadNice.size(),
                proc.startTicks);
    }

    private static void write(Path file, String contents) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
