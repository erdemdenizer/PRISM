package prism.srm.platform.linux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import prism.srm.domain.ProcessIdentity;

/**
 * The test harness of the Linux native integration tests (milestone 12, S6, plan Rev 2.2 §8.2): it composes the scope
 * limiter from the real parts (libsystemd checked by {@link LibsystemdCheck}, the user bus, {@code /proc} and the cgroup
 * mount from mountinfo), and starts the disposable processes the tests limit. Those are started under the user manager
 * with {@code systemd-run --user --scope}, as CI-1 did, because the test JVM itself runs outside it on a GitHub runner;
 * that command is test infrastructure, never product code. Only identities the harness started are admitted, and the
 * consents are declared for exactly those (K16, plan §2.6).
 */
final class LinuxHarness {

    final long uid;
    final long bootSeconds;
    final CgroupFiles files;
    final Path cgroupRoot;
    final LibsystemdCheck.Outcome library;
    final SystemdCapabilityProbe.Report report;
    final String managerGroup;
    final SdBusApi api;
    /** The machine's online logical processors, as the limiter counts them. */
    final int processors = LinuxScopeLimiter.onlineProcessors(Path.of("/proc/stat"));

    private final ProcFs proc = new ProcFs(Path.of("/proc"));
    private final JnaLinux linux = new JnaLinux();
    private final List<Started> started = new ArrayList<>();

    private LinuxHarness(
            long uid,
            long bootSeconds,
            CgroupFiles files,
            Path cgroupRoot,
            LibsystemdCheck.Outcome library,
            SystemdCapabilityProbe.Report report) {
        this.uid = uid;
        this.bootSeconds = bootSeconds;
        this.files = files;
        this.cgroupRoot = cgroupRoot;
        this.library = library;
        this.report = report;
        this.managerGroup = report.manager()
                .flatMap(SystemdUserBus.Reading::value)
                .map(SystemdUserBus.ManagerFacts::controlGroup)
                .orElse("");
        this.api = library.api().orElse(null);
    }

    /** The harness, or why the system cannot run the native tests (the caller fails on it, never skips). */
    static LinuxHarness open() throws IOException {
        long uid = statusUid();
        long boot = bootTime();
        String mount = mountPoint().orElseThrow(() -> new IllegalStateException("cgroup v2 is not mounted"));
        CgroupFiles files = CgroupFiles.mounted(Path.of("/proc")).orElseThrow();
        LibsystemdCheck.Outcome library = LibsystemdCheck.onThisSystem();
        SystemdCapabilityProbe.Report report =
                new SystemdCapabilityProbe(library, SystemdUserBus::new, files, uid).run();
        return new LinuxHarness(uid, boot, files, Path.of(mount), library, report);
    }

    /** What stands against running the native tests here; empty when nothing does. */
    List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (!library.available()) {
            missing.add("libsystemd: " + library.detail());
        }
        if (managerGroup.isEmpty()) {
            missing.add("no systemd user manager reachable: "
                    + report.manager().map(SystemdUserBus.Reading::detail).orElse("not asked"));
        }
        if (!report.readiness().cpu().ready()) {
            missing.add("cpu: " + report.readiness().cpu().reasons());
        }
        if (!report.readiness().memoryThrottle().ready()) {
            missing.add("memory: " + report.readiness().memoryThrottle().reasons());
        }
        return missing;
    }

    LinuxScopeLimiter limiter(Set<ProcessIdentity> admitted, Set<ProcessIdentity> hardMemory) {
        return new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(admitted),
                NativeConsent.harness(admitted, hardMemory),
                linux,
                proc,
                () -> bootSeconds,
                Optional.of(files),
                Optional.of(new SystemdScopeManager(api)),
                managerGroup,
                uid,
                Optional.of(report.readiness()),
                report.findings(),
                () -> processors,
                duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()),
                System::nanoTime));
    }

    SystemdUserBus bus() {
        return new SystemdUserBus(api);
    }

    /**
     * What the user manager answers to a scope whose only descriptor names a process that has exited and been reaped
     * (test review M7, T-L3/T-L10): the harness starts a short process, opens its pidfd, ends and reaps it, then asks
     * for the scope directly. Returns the answer and the unit's name.
     */
    Map.Entry<ScopeManager.Start, String> startWithReapedProcess() throws IOException, InterruptedException {
        Started sleeper = start(true, "ready", List.of("bash", "-c", "echo ready; exec sleep 60"));
        String unit = ScopeNames.of(sleeper.identity).orElseThrow();
        LinuxNative.Call opened = linux.pidfdOpen((int) sleeper.identity.pid());
        if (opened.failed()) {
            throw new IllegalStateException("pidfd_open failed: errno " + opened.errno());
        }
        int fd = (int) opened.value();
        try {
            sleeper.process.destroyForcibly();
            if (!sleeper.process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the sleeper did not end");
            }
            return Map.entry(new SystemdScopeManager(api).startScope(unit, fd, "Prism test: reaped process"), unit);
        } finally {
            linux.close(fd);
        }
    }

    /** A process the harness started, with its pipes. */
    static final class Started {
        final Process process;
        final ProcessIdentity identity;
        final ConcurrentLinkedQueue<String> lines;
        final Writer stdin;
        /** The thread that copies the process's output into {@link #lines}; it ends at end of stream. */
        final Thread reader;

        Started(Process process, ProcessIdentity identity, ConcurrentLinkedQueue<String> lines, Thread reader) {
            this.process = process;
            this.identity = identity;
            this.lines = lines;
            this.reader = reader;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        void send(String line) throws IOException {
            stdin.write(line + "\n");
            stdin.flush();
        }

        boolean await(String prefix, Duration bound) throws InterruptedException {
            long deadline = System.nanoTime() + bound.toNanos();
            while (System.nanoTime() < deadline) {
                if (lines.stream().anyMatch(l -> l.startsWith(prefix))) {
                    return true;
                }
                // The process can end before the reader has handed over its last lines: only an ended reader means
                // that no more output will come.
                if (!process.isAlive() && !reader.isAlive()) {
                    return lines.stream().anyMatch(l -> l.startsWith(prefix));
                }
                Thread.sleep(20);
            }
            return false;
        }
    }

    /** Starts a command inside a transient scope of the user manager (or, with {@code inUserManager} false, directly). */
    Started start(boolean inUserManager, String readyPrefix, List<String> command)
            throws IOException, InterruptedException {
        List<String> full = new ArrayList<>();
        if (inUserManager) {
            full.addAll(List.of(
                    "systemd-run",
                    "--user",
                    "--scope",
                    "--quiet",
                    "--collect",
                    "--unit=prism-it-" + Long.toHexString(System.nanoTime()) + ".scope",
                    "--"));
        }
        full.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(full).redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        Thread reader = new Thread(
                () -> {
                    try (BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            lines.add(line);
                            System.out.println("  [" + process.pid() + "] " + line);
                        }
                    } catch (IOException ended) {
                        // The process ended.
                    }
                },
                "harness-output-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        Started s = new Started(process, identityOf((int) process.pid()), lines, reader);
        started.add(s);
        if (!s.await(readyPrefix, Duration.ofSeconds(30))) {
            process.destroyForcibly();
            throw new IllegalStateException("did not start: " + full + " " + lines);
        }
        return s;
    }

    String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * The class path of the demo workload (main classes) and of the test helpers (test classes), from where the classes
     * were loaded: surefire may run the tests from a manifest-only jar.
     */
    String testClasses() {
        try {
            String main = Path.of(prism.srm.demo.DemoWorkload.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
            String test = Path.of(LateAllocator.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
            return main + java.io.File.pathSeparator + test;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The identity of a live process, computed as the limiter computes it. */
    ProcessIdentity identityOf(int pid) {
        ProcFs.Reading<ProcessIdentity> read =
                ProcessIdentityReader.read(proc, pid, bootSeconds, linux.clockTicksPerSecond());
        return read.value()
                .orElseThrow(() -> new IllegalStateException("no identity for " + pid + ": " + read.detail()));
    }

    /** A cgroup file of a group, as text; empty when it cannot be read. */
    Optional<String> cgroupFile(String group, String file) {
        try {
            return Optional.of(
                    Files.readString(cgroupRoot.resolve(group.substring(1)).resolve(file), StandardCharsets.UTF_8)
                            .strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a group holds no CPU quota: {@code cpu.max} reads {@code max}, or the cpu controller is not enabled for it
     * (systemd turns it off for a unit once no CPU property needs it, and then the file does not exist).
     */
    boolean noCpuQuota(String group) {
        boolean cpuEnabled = cgroupFile(group, "cgroup.controllers")
                .map(c -> List.of(c.split(" ")).contains("cpu"))
                .orElse(true);
        return cpuEnabled
                ? cgroupFile(group, "cpu.max").filter(v -> v.startsWith("max ")).isPresent()
                : cgroupFile(group, "cpu.max").isEmpty();
    }

    /** A counter of a key-value cgroup file ({@code cpu.stat}, {@code memory.events}). */
    long counter(String group, String file, String key) {
        return cgroupFile(group, file).stream()
                .flatMap(String::lines)
                .map(l -> l.split(" "))
                .filter(p -> p.length == 2 && p[0].equals(key))
                .mapToLong(p -> Long.parseLong(p[1]))
                .findFirst()
                .orElse(-1);
    }

    /** The cores a group used over a window, from {@code cpu.stat usage_usec}. */
    double cores(String group, Duration window) throws InterruptedException {
        long before = counter(group, "cpu.stat", "usage_usec");
        long start = System.nanoTime();
        Thread.sleep(window.toMillis());
        long after = counter(group, "cpu.stat", "usage_usec");
        double seconds = (System.nanoTime() - start) / 1e9;
        return (after - before) / 1e6 / seconds;
    }

    static long openDescriptors() throws IOException {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.count();
        }
    }

    /** How many of this JVM's descriptors are of a kind: {@code anon_inode:[pidfd]} or {@code socket:} (proc(5)). */
    static long descriptorsOf(String prefix) throws IOException {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.filter(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString().startsWith(prefix);
                        } catch (IOException gone) {
                            return false;
                        }
                    })
                    .count();
        }
    }

    /**
     * The {@code some} stall time of a group's memory pressure in microseconds ({@code memory.pressure}, kernel
     * Documentation/accounting/psi.rst: "some avg10=... total=N"); -1 when it cannot be read.
     */
    long memoryPressureSome(String group) {
        return cgroupFile(group, "memory.pressure").stream()
                .flatMap(String::lines)
                .filter(l -> l.startsWith("some "))
                .flatMap(l -> java.util.Arrays.stream(l.split(" ")))
                .filter(f -> f.startsWith("total="))
                .mapToLong(f -> Long.parseLong(f.substring(6)))
                .findFirst()
                .orElse(-1);
    }

    /** The machine's swap in KiB ({@code SwapTotal} of {@code /proc/meminfo}, proc(5)); -1 when it cannot be read. */
    long swapTotalKib() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/meminfo"), StandardCharsets.US_ASCII)) {
            return lines.filter(l -> l.startsWith("SwapTotal:"))
                    .map(l -> l.substring(10).trim().split(" ")[0])
                    .mapToLong(Long::parseLong)
                    .findFirst()
                    .orElse(-1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** This JVM's whole class path (with JNA), for a second JVM that composes the harness itself. */
    String fullClassPath() {
        return System.getProperty("java.class.path");
    }

    /** Kills every process the harness started (test cleanup of its own disposable processes). */
    void killAll() {
        for (Started s : started) {
            s.process.descendants().forEach(ProcessHandle::destroyForcibly);
            s.process.destroyForcibly();
        }
        started.clear();
    }

    private static long statusUid() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
            if (line.startsWith("Uid:")) {
                return Long.parseLong(line.substring(4).strip().split("\\s+")[0]);
            }
        }
        throw new IOException("no Uid line");
    }

    private static long bootTime() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/stat"), StandardCharsets.UTF_8)) {
            if (line.startsWith("btime ")) {
                return Long.parseLong(line.substring(6).strip());
            }
        }
        throw new IOException("no btime line");
    }

    private static Optional<String> mountPoint() throws IOException {
        return CgroupV2.unifiedMount(Files.readString(Path.of("/proc/self/mountinfo"), StandardCharsets.UTF_8));
    }
}
