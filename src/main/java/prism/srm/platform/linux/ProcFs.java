package prism.srm.platform.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads what process control needs from the proc file system, under a root that is {@code /proc}
 * on the running system and a folder of a test's own making in tests. Plain file reads: no native
 * call. A process can exit between any two reads, so every read answers with what it found.
 * https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html
 * https://man7.org/linux/man-pages/man5/proc_pid_task.5.html
 */
final class ProcFs {

    /** What a read found. */
    enum Status {
        FOUND,
        /** The file is not there: the process (or thread) has gone. */
        MISSING,
        /** The system refused the read, as under {@code hidepid}. */
        DENIED,
        /** The read failed otherwise, or the contents were not what the file holds. */
        FAILED
    }

    /** One read: what it found and, when it found it, the value. */
    record Reading<T>(Status status, Optional<T> value, String detail) {

        Reading {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(detail, "detail");
        }

        static <T> Reading<T> found(T value) {
            return new Reading<>(Status.FOUND, Optional.of(value), "");
        }

        static <T> Reading<T> not(Status status, String detail) {
            return new Reading<>(status, Optional.empty(), detail);
        }

        T get() {
            return value.orElseThrow();
        }
    }

    private final Path root;

    ProcFs(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    Reading<ProcStat> stat(int pid) {
        return parse(root.resolve(Integer.toString(pid)).resolve("stat"));
    }

    Reading<ProcStat> threadStat(int pid, int tid) {
        return parse(root.resolve(Integer.toString(pid))
                .resolve("task")
                .resolve(Integer.toString(tid))
                .resolve("stat"));
    }

    /** The thread IDs of the process, in ascending order, from the names of the folders in {@code /proc/<pid>/task}. */
    Reading<List<Integer>> taskIds(int pid) {
        Path task = root.resolve(Integer.toString(pid)).resolve("task");
        List<Integer> ids = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(task)) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                String name = fileName == null ? "" : fileName.toString();
                if (!name.isEmpty() && name.chars().allMatch(Character::isDigit)) {
                    ids.add(Integer.parseInt(name));
                }
            }
        } catch (NoSuchFileException gone) {
            return Reading.not(Status.MISSING, task + " is not there");
        } catch (AccessDeniedException denied) {
            return Reading.not(Status.DENIED, task + " cannot be read");
        } catch (IOException | NumberFormatException failed) {
            return Reading.not(Status.FAILED, task + ": " + failed);
        }
        ids.sort(null);
        return Reading.found(List.copyOf(ids));
    }

    /** The text of {@code /proc/<pid>/cgroup}, as the kernel wrote it. */
    Reading<String> cgroupText(int pid) {
        if (pid <= 0) {
            return Reading.not(Status.FAILED, "PID " + pid + " names no single process");
        }
        Path file = root.resolve(Integer.toString(pid)).resolve("cgroup");
        try {
            return Reading.found(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException gone) {
            return Reading.not(Status.MISSING, file + " is not there");
        } catch (AccessDeniedException denied) {
            return Reading.not(Status.DENIED, file + " cannot be read");
        } catch (IOException failed) {
            return Reading.not(Status.FAILED, file + ": " + failed);
        }
    }

    /**
     * The real, effective, saved set and file system user IDs of the process, from the {@code Uid:} line of
     * {@code /proc/<pid>/status} (proc_pid_status(5): "Uid, Gid: Real, effective, saved set, and filesystem UIDs").
     */
    Reading<List<Long>> uids(int pid) {
        Path file = root.resolve(Integer.toString(pid)).resolve("status");
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException gone) {
            return Reading.not(Status.MISSING, file + " is not there");
        } catch (AccessDeniedException denied) {
            return Reading.not(Status.DENIED, file + " cannot be read");
        } catch (IOException failed) {
            return Reading.not(Status.FAILED, file + ": " + failed);
        }
        for (String line : lines) {
            if (line.startsWith("Uid:")) {
                String[] fields = line.substring(4).strip().split("\\s+");
                if (fields.length != 4) {
                    break;
                }
                try {
                    List<Long> ids = new ArrayList<>();
                    for (String field : fields) {
                        ids.add(Long.parseLong(field));
                    }
                    return Reading.found(List.copyOf(ids));
                } catch (NumberFormatException malformed) {
                    break;
                }
            }
        }
        return Reading.not(Status.FAILED, file + " has no Uid line of four IDs");
    }

    private static Reading<ProcStat> parse(Path file) {
        String contents;
        try {
            // The name may hold any bytes; a malformed one is replaced, which no comparison relies on.
            contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException gone) {
            return Reading.not(Status.MISSING, file + " is not there");
        } catch (AccessDeniedException denied) {
            return Reading.not(Status.DENIED, file + " cannot be read");
        } catch (IOException failed) {
            return Reading.not(Status.FAILED, file + ": " + failed);
        }
        return ProcStat.parse(contents)
                .map(Reading::found)
                .orElseGet(() -> Reading.not(Status.FAILED, file + " does not have the form of a stat file"));
    }
}
