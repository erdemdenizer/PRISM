package prism.srm.platform.linux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ResourceLimiter;

/**
 * The real Linux scope limiter over a fake systemd and fake proc and cgroup files, for tests outside this package that
 * need the limiter's own R table and clearing rules with the application's ledger and recovery (test review S8). Each
 * {@link #limiter} is one Prism session; they share the one system.
 */
public final class ScopeSystemFixture {

    private static final long BASE_TICKS = 5_000;

    private final FakeProc proc;
    private final FakeLinux linux;
    private final CgroupFiles files;
    private final FakeScopeManager systemd;
    private final long[] clock = {0};
    private final List<LinuxScopeLimiter> sessions = new ArrayList<>();

    public ScopeSystemFixture(Path temp) {
        proc = new FakeProc(temp.resolve("proc"));
        linux = new FakeLinux(proc);
        Path cgroups = temp.resolve("cgroup");
        Path procCgroups = temp.resolve("proc-cgroup");
        files = new CgroupFiles(cgroups, procCgroups);
        systemd = new FakeScopeManager(linux, cgroups, procCgroups, 4, new CopyOnWriteArrayList<>());
    }

    /** A process of the user's session, in a scope its launcher made. */
    public ProcessIdentity start(int pid, long tickOffset) {
        proc.process(pid, "demo" + pid, BASE_TICKS + tickOffset);
        systemd.placeProcess(pid, FakeScopeManager.MANAGER + "/app.slice/app-launcher-" + pid + ".scope");
        return new ProcessIdentity(pid, FakeProc.start(BASE_TICKS + tickOffset));
    }

    /** A process the given one started: it is in the same cgroup, and a member of it. */
    public ProcessIdentity child(int parent, int pid, long tickOffset) {
        proc.process(pid, "child" + pid, BASE_TICKS + tickOffset);
        String group = files.processCgroup(parent).value().orElseThrow();
        systemd.placeProcess(pid, group);
        List<Integer> members = new ArrayList<>(systemd.members(group));
        members.add(pid);
        systemd.writeMembers(group, members.stream().mapToInt(Integer::intValue).toArray());
        return new ProcessIdentity(pid, FakeProc.start(BASE_TICKS + tickOffset));
    }

    public void exit(int pid) {
        systemd.exit(proc, pid);
    }

    /** A new session's limiter: it admits {@code admitted} (none: a harness that only recovers). */
    public ResourceLimiter session(Set<ProcessIdentity> admitted) {
        Set<ProcessIdentity> policy = admitted.isEmpty() ? Set.of(new ProcessIdentity(1, FakeProc.start(1))) : admitted;
        LinuxScopeLimiter limiter = new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(policy),
                NativeConsent.harness(policy, Set.of()),
                linux,
                new ProcFs(proc.root),
                () -> FakeProc.BOOT_TIME_SECONDS,
                Optional.of(files),
                Optional.of(systemd),
                FakeScopeManager.MANAGER,
                FakeProc.OWNER_UID,
                Optional.of(LinuxNativeReadiness.classify(new LinuxNativeReadiness.Facts(
                        Optional.of("/sys/fs/cgroup"),
                        Optional.of(false),
                        Optional.of("255.4-1ubuntu8.18"),
                        Optional.of(Set.of("cpu", "memory", "pids")),
                        Optional.of(true)))),
                List.of(),
                () -> 4,
                duration -> {
                    clock[0] += duration.toNanos();
                    systemd.settle();
                },
                () -> clock[0]));
        sessions.add(limiter);
        return limiter;
    }

    /** The session crashed: its descriptors go with its process; nothing else changes. */
    public void crash(ResourceLimiter session) {
        for (int fd : ((LinuxScopeLimiter) session).heldDescriptors()) {
            linux.close(fd);
        }
    }

    /** The {@code cpu.max} of the scope the process is in. */
    public String cpuMax(int pid) {
        return systemd.read(files.processCgroup(pid).value().orElseThrow(), "cpu.max");
    }

    public boolean inPrismScope(int pid) {
        return ScopeNames.enclosingPrismScope(files.processCgroup(pid).value().orElse(""))
                .isPresent();
    }

    /** The manager refuses or takes every limit property from now on. */
    public void refuseLimits(boolean refuse) {
        for (String property : List.of("CPUQuotaPerSecUSec", "MemoryHigh", "MemoryMax")) {
            if (refuse) {
                systemd.setAnswer.put(property, ScopeManager.SetStatus.REFUSED);
            } else {
                systemd.setAnswer.remove(property);
            }
        }
    }

    public long setCalls() {
        return systemd.setCalls();
    }

    public int unitCount() {
        return systemd.units.size();
    }
}
