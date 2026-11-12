package prism.srm.platform.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the cgroup v2 and procfs files milestone 12 needs (S2), under roots that are {@code /sys/fs/cgroup} and
 * {@code /proc} on the running system and folders of a test's own in tests. It only reads: there is no method that
 * writes, creates or removes anything, and a cgroup path that could leave the root ({@code ..}, a relative path) is
 * refused before any file is opened. Every read answers with what it found ({@link ProcFs.Reading}).
 */
final class CgroupFiles {

    private final Path cgroupRoot;
    private final Path procRoot;

    CgroupFiles(Path cgroupRoot, Path procRoot) {
        this.cgroupRoot = Objects.requireNonNull(cgroupRoot, "cgroupRoot");
        this.procRoot = Objects.requireNonNull(procRoot, "procRoot");
    }

    /**
     * The files of the running system: the cgroup root is where {@code <procRoot>/self/mountinfo} says the cgroup v2
     * file system is mounted, so no mount point is assumed. Empty when it is not mounted or cannot be read.
     */
    static Optional<CgroupFiles> mounted(Path procRoot) {
        ProcFs.Reading<String> mount = parsed(procRoot.resolve("self").resolve("mountinfo"), CgroupV2::unifiedMount);
        return mount.value().map(point -> new CgroupFiles(Path.of(point), procRoot));
    }

    /** The mount point of the cgroup v2 file system, from {@code /proc/self/mountinfo}. */
    ProcFs.Reading<String> unifiedMount() {
        return parsed(procRoot.resolve("self").resolve("mountinfo"), CgroupV2::unifiedMount);
    }

    /** Whether a cgroup v1 hierarchy is mounted beside it. */
    ProcFs.Reading<Boolean> v1Mounted() {
        return parsed(procRoot.resolve("self").resolve("mountinfo"), text -> Optional.of(CgroupV2.v1Mounted(text)));
    }

    /** The unified-hierarchy cgroup path of a process. */
    ProcFs.Reading<String> processCgroup(int pid) {
        if (pid <= 0) {
            return ProcFs.Reading.not(ProcFs.Status.FAILED, "PID " + pid + " names no single process");
        }
        return parsed(procRoot.resolve(Integer.toString(pid)).resolve("cgroup"), CgroupV2::unifiedPath);
    }

    ProcFs.Reading<Set<String>> controllers(String cgroupPath) {
        return inCgroup(cgroupPath, "cgroup.controllers", CgroupV2::controllers);
    }

    ProcFs.Reading<Set<String>> subtreeControl(String cgroupPath) {
        return inCgroup(cgroupPath, "cgroup.subtree_control", CgroupV2::controllers);
    }

    ProcFs.Reading<CgroupV2.CpuMax> cpuMax(String cgroupPath) {
        return inCgroup(cgroupPath, "cpu.max", CgroupV2::cpuMax);
    }

    ProcFs.Reading<OptionalLong> memoryMax(String cgroupPath) {
        return inCgroup(cgroupPath, "memory.max", CgroupV2::memoryLimit);
    }

    ProcFs.Reading<OptionalLong> memoryHigh(String cgroupPath) {
        return inCgroup(cgroupPath, "memory.high", CgroupV2::memoryLimit);
    }

    ProcFs.Reading<List<Integer>> procs(String cgroupPath) {
        return inCgroup(cgroupPath, "cgroup.procs", CgroupV2::procs);
    }

    private <T> ProcFs.Reading<T> inCgroup(String cgroupPath, String file, Function<String, Optional<T>> parser) {
        if (!CgroupV2.validPath(cgroupPath)) {
            return ProcFs.Reading.not(ProcFs.Status.FAILED, "not a cgroup path: " + cgroupPath);
        }
        Path directory = cgroupPath.equals("/") ? cgroupRoot : cgroupRoot.resolve(cgroupPath.substring(1));
        Path target = directory.resolve(file).normalize();
        if (!target.startsWith(cgroupRoot.normalize())) {
            return ProcFs.Reading.not(ProcFs.Status.FAILED, "outside the cgroup root: " + cgroupPath);
        }
        return parsed(target, parser);
    }

    private static <T> ProcFs.Reading<T> parsed(Path file, Function<String, Optional<T>> parser) {
        String contents;
        try {
            contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException gone) {
            return ProcFs.Reading.not(ProcFs.Status.MISSING, file + " is not there");
        } catch (AccessDeniedException denied) {
            return ProcFs.Reading.not(ProcFs.Status.DENIED, file + " cannot be read");
        } catch (IOException failed) {
            return ProcFs.Reading.not(ProcFs.Status.FAILED, file + ": " + failed);
        }
        return parser.apply(contents)
                .map(ProcFs.Reading::found)
                .orElseGet(() -> ProcFs.Reading.not(ProcFs.Status.FAILED, file + " does not have the expected form"));
    }
}
