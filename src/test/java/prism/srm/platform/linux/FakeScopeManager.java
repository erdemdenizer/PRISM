package prism.srm.platform.linux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A systemd user manager for the scope limiter's tests: units it creates move the process the descriptor refers to (and
 * only that one) into a cgroup folder of the test's own, written in the kernel's file formats; limits become {@code
 * cpu.max}, {@code memory.high} and {@code memory.max} as systemd writes them. Every way an answer can go wrong is
 * scripted here. It counts calls, and fails a test that overlaps two calls (the limiter must make one at a time).
 */
final class FakeScopeManager implements ScopeManager {

    static final String MANAGER = "/user.slice/user-1000.slice/user@1000.service";

    /** One unit of the fake manager. */
    static final class Unit {
        final String name;
        final String group;
        String invocationId;
        String activeState = "active";
        String subState = "running";
        String oomPolicy = "continue";
        /** Reads before the queued start job runs (inactive, dead, no cgroup); then the process moves. */
        int queuedPolls;

        int queuedPid = -1;

        Unit(String name, String group, String invocationId) {
            this.name = name;
            this.group = group;
            this.invocationId = invocationId;
        }
    }

    final FakeLinux linux;
    final Path cgroupRoot;
    final Path procCgroups;
    final int processors;
    final Map<String, Unit> units = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    final List<String> events;
    final Map<String, SetStatus> setAnswer = new HashMap<>();
    /** Properties whose value systemd takes but does not write (a read-back then shows the old value). */
    final Set<String> setIgnored = new LinkedHashSet<>();

    /**
     * systemd answers SetUnitProperties before it writes the cgroup: with this set, a property reaches the files only at
     * the next {@link #settle()} (the limiter's pause calls it).
     */
    boolean lagging;

    private final List<Runnable> pending = new ArrayList<>();
    StartStatus startAnswer = StartStatus.STARTED;
    boolean moveOnStart = true;
    boolean unitVanishesOnStart;
    int pollsUntilActive;
    /**
     * systemd answers StartTransientUnit once the job is queued and runs jobs at idle priority: until then the unit reads
     * inactive/dead with no cgroup. With this above zero, the job runs at that many reads of the unit.
     */
    int queuedPolls;

    boolean unitReadFails;
    Runnable afterStart = () -> {};
    /** Runs inside every read of a unit (a manager that is slow or hangs). */
    Runnable onUnitRead = () -> {};
    /** Runs inside every SetUnitProperties (a slow manager). */
    Runnable onSet = () -> {};
    /** The invocation id every SetProperties was addressed to, in order. */
    final List<String> setInvocations = new ArrayList<>();
    /** Runs when a start request arrives, before the manager looks at the descriptor. */
    Runnable beforeStart = () -> {};

    private final AtomicInteger counter = new AtomicInteger(1);
    private final AtomicBoolean inside = new AtomicBoolean();

    FakeScopeManager(FakeLinux linux, Path cgroupRoot, Path procCgroups, int processors, List<String> events) {
        this.linux = linux;
        this.cgroupRoot = cgroupRoot;
        this.procCgroups = procCgroups;
        this.processors = processors;
        this.events = events;
    }

    private void enter(String call) {
        if (!inside.compareAndSet(false, true)) {
            throw new IllegalStateException("two manager calls at once: " + call);
        }
        calls.add(call);
        events.add(call);
    }

    private void leave() {
        inside.set(false);
    }

    /** Places a process in a cgroup, as the kernel shows it in {@code /proc/<pid>/cgroup}. */
    void placeProcess(int pid, String group) {
        write(procCgroups.resolve(Integer.toString(pid)).resolve("cgroup"), "0::" + group + "\n");
    }

    /** A unit that exists already: a scope of Prism's name made by someone else, or a leftover. */
    Unit addUnit(String name, String invocationId, int... members) {
        Unit unit = new Unit(name, MANAGER + "/app.slice/" + name, invocationId);
        units.put(name, unit);
        writeLimits(unit.group, "max 100000", "max", "max");
        writeMembers(unit.group, members);
        for (int pid : members) {
            placeProcess(pid, unit.group);
        }
        return unit;
    }

    void writeMembers(String group, int... pids) {
        String text = java.util.Arrays.stream(pids).mapToObj(Integer::toString).collect(Collectors.joining("\n"));
        write(dir(group).resolve("cgroup.procs"), pids.length == 0 ? "" : text + "\n");
    }

    List<Integer> members(String group) {
        try {
            return Files.readAllLines(dir(group).resolve("cgroup.procs"), StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank())
                    .map(Integer::parseInt)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the cgroup files a lagging systemd has not written yet. */
    void settle() {
        List<Runnable> now = List.copyOf(pending);
        pending.clear();
        now.forEach(Runnable::run);
    }

    void writeLimits(String group, String cpuMax, String memoryHigh, String memoryMax) {
        write(dir(group).resolve("cgroup.controllers"), "cpu memory pids\n");
        write(dir(group).resolve("cpu.max"), cpuMax + "\n");
        write(dir(group).resolve("memory.high"), memoryHigh + "\n");
        write(dir(group).resolve("memory.max"), memoryMax + "\n");
    }

    String read(String group, String file) {
        try {
            return Files.readString(dir(group).resolve(file), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir(String group) {
        return cgroupRoot.resolve(group.substring(1));
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Start startScope(String unit, int pidfd, String description) {
        enter("start " + unit);
        try {
            beforeStart.run();
            if (startAnswer != StartStatus.STARTED && startAnswer != StartStatus.UNKNOWN) {
                return new Start(startAnswer, "scripted " + startAnswer);
            }
            if (units.containsKey(unit)) {
                return new Start(StartStatus.UNIT_EXISTS, "Unit " + unit + " already exists");
            }
            boolean live = linux.refersToLiveProcess(pidfd);
            int pid = linux.pidOf(pidfd);
            if (startAnswer == StartStatus.STARTED && !live) {
                // The pidfd refers to an exited, reaped process: systemd 255 refuses and creates no unit (CI run
                // 38799770641, T-L10).
                return new Start(
                        StartStatus.REFUSED,
                        "StartTransientUnit: org.freedesktop.DBus.Error.UnixProcessIdUnknown"
                                + " (Failed to set unit properties: No such process)");
            }
            Unit made = new Unit(
                    unit, MANAGER + "/app.slice/" + unit, HexFormat.of().formatHex(new byte[] {
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, (byte) counter.getAndIncrement()
                    }));
            made.activeState = pollsUntilActive > 0 ? "activating" : "active";
            if (!unitVanishesOnStart) {
                units.put(unit, made);
            }
            writeLimits(made.group, "max 100000", "max", "max");
            if (queuedPolls > 0) {
                made.activeState = "inactive";
                made.subState = "dead";
                made.queuedPolls = queuedPolls;
                made.queuedPid = moveOnStart && live ? pid : -1;
                writeMembers(made.group);
            } else if (moveOnStart && live) {
                writeMembers(made.group, pid);
                placeProcess(pid, made.group);
            } else {
                writeMembers(made.group);
            }
            afterStart.run();
            return new Start(startAnswer, startAnswer == StartStatus.UNKNOWN ? "no reply" : "job");
        } finally {
            leave();
        }
    }

    @Override
    public SystemdUserBus.Reading<SystemdUserBus.UnitFacts> unit(String unit) {
        enter("unit " + unit);
        try {
            onUnitRead.run();
            if (unitReadFails) {
                return SystemdUserBus.Reading.not(SystemdUserBus.Status.TIMEOUT, "scripted timeout");
            }
            Unit found = units.get(unit);
            if (found == null) {
                return SystemdUserBus.Reading.not(SystemdUserBus.Status.NO_SUCH_UNIT, "NoSuchUnit " + unit);
            }
            if (found.activeState.equals("activating") && pollsUntilActive-- <= 1) {
                found.activeState = "active";
            }
            if (found.queuedPolls > 0 && --found.queuedPolls == 0) {
                found.activeState = "active";
                found.subState = "running";
                if (found.queuedPid >= 0) {
                    writeMembers(found.group, found.queuedPid);
                    placeProcess(found.queuedPid, found.group);
                }
            }
            boolean empty = members(found.group).isEmpty();
            return SystemdUserBus.Reading.ok(new SystemdUserBus.UnitFacts(
                    "/org/freedesktop/systemd1/unit/" + unit.replace("-", "_2d").replace(".", "_2e"),
                    unit,
                    found.activeState,
                    found.subState,
                    empty && found.activeState.equals("inactive") ? Optional.empty() : Optional.of(found.group),
                    Optional.of(found.invocationId)));
        } finally {
            leave();
        }
    }

    @Override
    public SystemdUserBus.Reading<String> scopeProperty(String unit, String property) {
        enter("property " + unit + " " + property);
        try {
            Unit found = units.get(unit);
            if (found == null) {
                return SystemdUserBus.Reading.not(SystemdUserBus.Status.NO_SUCH_UNIT, "NoSuchUnit " + unit);
            }
            return property.equals("OOMPolicy")
                    ? SystemdUserBus.Reading.ok(found.oomPolicy)
                    : SystemdUserBus.Reading.not(SystemdUserBus.Status.FAILED, "unknown property");
        } finally {
            leave();
        }
    }

    @Override
    public LimitSet setLimit(String unit, String invocationId, String property, long value) {
        enter("set " + unit + " " + property + " " + (value == INFINITY ? "infinity" : Long.toString(value)));
        try {
            setInvocations.add(invocationId);
            onSet.run();
            // systemd resolves the unit by its invocation id: a unit that took the name later is not it.
            Unit found = units.get(unit);
            if (found == null || !found.invocationId.equals(invocationId)) {
                return new LimitSet(
                        SetStatus.NO_SUCH_UNIT,
                        "org.freedesktop.systemd1.NoUnitForInvocationID: no unit with the invocation id "
                                + invocationId);
            }
            SetStatus answer = setAnswer.getOrDefault(property, SetStatus.SET);
            if (answer == SetStatus.REFUSED || answer == SetStatus.NO_SUCH_UNIT) {
                return new LimitSet(answer, "scripted " + answer);
            }
            if (!setIgnored.contains(property)) {
                if (lagging) {
                    pending.add(() -> apply(found.group, property, value));
                } else {
                    apply(found.group, property, value);
                }
            }
            return new LimitSet(answer, answer == SetStatus.UNKNOWN ? "no reply" : "done");
        } finally {
            leave();
        }
    }

    private void apply(String group, String property, long value) {
        switch (property) {
            case "CPUQuotaPerSecUSec" ->
                write(
                        dir(group).resolve("cpu.max"),
                        (value == INFINITY ? "max" : Long.toString(value * CpuQuota.PERIOD_USEC / 1_000_000L)) + " "
                                + CpuQuota.PERIOD_USEC + "\n");
            case "MemoryHigh" -> write(dir(group).resolve("memory.high"), (value == INFINITY ? "max" : value) + "\n");
            case "MemoryMax" -> write(dir(group).resolve("memory.max"), (value == INFINITY ? "max" : value) + "\n");
            default -> throw new IllegalArgumentException(property);
        }
    }

    /** The process exits: gone from proc and from its cgroup. */
    void exit(FakeProc proc, int pid) {
        proc.remove(pid);
        for (Unit unit : units.values()) {
            List<Integer> left = new ArrayList<>(members(unit.group));
            if (left.remove(Integer.valueOf(pid))) {
                writeMembers(
                        unit.group, left.stream().mapToInt(Integer::intValue).toArray());
            }
        }
    }

    long setCalls() {
        return calls.stream().filter(c -> c.startsWith("set ")).count();
    }
}
