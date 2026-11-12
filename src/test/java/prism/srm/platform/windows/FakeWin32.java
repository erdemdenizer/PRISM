package prism.srm.platform.windows;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A Windows that exists only in the test: processes, threads with suspend counts, top-level
 * windows, and every handle opened and closed. Nothing here calls the system, so a test can
 * suspend "threads" without stopping any real one.
 */
final class FakeWin32 implements Win32 {

    static final class Proc {
        long creation;
        int exitCode = STILL_ACTIVE;
        Optional<Boolean> critical = Optional.of(false);
        Optional<String> path = Optional.of("C:\\Apps\\demo.exe");
        int priorityClass = 0x20;
        /** When set, GetPriorityClass answers this instead of what was set. */
        Integer heldClass;

        int openError;
        /** Rights the process refuses with ERROR_ACCESS_DENIED, while it grants the others. */
        int deniedRights;

        int setPriorityError;
        boolean exitCodeFails;
        final List<Integer> threads = new ArrayList<>();
        /** Processor time, kernel and user together, in 100-nanosecond units; empty: GetProcessTimes fails. */
        OptionalLong processorTime = OptionalLong.of(0);
        /** What CheckRemoteDebuggerPresent answers; empty: it fails. */
        Optional<Boolean> debugger = Optional.of(false);
        /** The jobs the process is in (milestone 10). */
        final List<Job> jobs = new ArrayList<>();
    }

    /**
     * A Job Object of the fake system. Its name lives only while a handle is open, as on the real
     * one (Life Cycle of an Object); the job itself lives while it has a handle or a member.
     */
    static final class Job {
        final String name;
        int handles;
        final List<Integer> members = new ArrayList<>();
        int cpuFlags;
        int cpuRate;
        int limitFlags;
        long processMemoryLimit;
        /** Processes the job holds beyond the modelled members: children the fake does not model one by one. */
        int extraActive;
        /** SetInformationJobObject of the CPU rate fails with this; with {@link #setTakesEffectAnyway}, after taking effect. */
        int setCpuError;

        int setMemoryError;
        boolean setTakesEffectAnyway;
        /** Reads that still succeed before every read of the job fails; negative: never fails. */
        int readsBeforeFailure = -1;
        /** AssignProcessToJobObject fails with this; with {@link #assignTakesEffectAnyway}, after assigning. */
        int assignError;

        boolean assignTakesEffectAnyway;
        /** IsProcessInJob against this job fails. */
        boolean membershipUnreadable;
        /** The accounting cannot be read. */
        boolean accountingUnreadable;

        Job(String name) {
            this.name = name;
        }
    }

    static final class Thr {
        int owner;
        int suspendCount;
        boolean ended;
        int openError;
        int suspendError;
        int resumeError;
        /** GetProcessIdOfThread fails (returns 0). */
        boolean ownerUnreadable;
        /** GetExitCodeThread fails. */
        boolean exitCodeFails;
        /** The thread's creation time (a FILETIME); GetThreadTimes fails when it is negative. */
        long creation = 133_700_000_000_000_000L;

        int resumes;
        int suspends;
    }

    final Map<Integer, Proc> processes = new HashMap<>();
    final Map<Integer, Thr> threads = new HashMap<>();
    final List<Window> windows = new ArrayList<>();
    final Map<Long, Integer> postErrors = new HashMap<>();
    final List<Long> posted = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    /** Thread listings to return in turn before falling back to the process's threads. */
    final Deque<List<Integer>> listings = new ArrayDeque<>();
    /** What the process list says; empty means "could not be read". By default: whatever is in {@link #processes}. */
    Optional<Map<Integer, Boolean>> listed = Optional.of(Map.of());

    boolean listedFromProcesses = true;
    int threadListingError;
    /** When set, EnumWindows fails with this code. */
    int enumerateError;
    /** What GetLastError answers: the code of the last call that failed without returning one. */
    int lastError;
    /** Called after each thread is suspended: lets a test change the world part way. */
    Runnable afterSuspend = () -> {};
    /** Called when the windows are enumerated. */
    Runnable onEnumerateWindows = () -> {};
    /** Called when the threads are listed. */
    Runnable onListThreads = () -> {};

    /** Every job ever created, in order. */
    final List<Job> jobs = new ArrayList<>();
    /** The names that can be opened now. */
    final Map<String, Job> named = new HashMap<>();
    /** CreateJobObject fails with this code. */
    int createJobError;
    /** IsProcessInJob of this JVM. */
    Optional<Boolean> self = Optional.of(false);
    /** Called right after a CPU rate or memory limit was set: lets a test crash or change the world there. */
    Runnable afterSet = () -> {};
    /** Thrown by AssignProcessToJobObject after it took effect, when set: a RuntimeException or an Error. */
    Throwable assignThrows;
    /** Called right after an assignment took effect, before it returns. */
    Runnable afterAssign = () -> {};
    /** Every SetInformationJobObject of a CPU rate fails with this (Dynamic Fair Share Scheduling: 50). */
    int cpuRateErrorForAll;

    private final Map<Long, Job> jobHandles = new HashMap<>();
    /** The rights each job handle was opened with; SetInformationJobObject needs JOB_OBJECT_SET_ATTRIBUTES. */
    private final Map<Long, Integer> jobRights = new HashMap<>();

    private final Map<Long, String> open = new LinkedHashMap<>();
    private final List<String> doubleCloses = new ArrayList<>();
    private long next = 1000;

    static long filetime(Instant moment) {
        return (moment.toEpochMilli() + 11_644_473_600_000L) * 10_000L + 1234;
    }

    Proc process(int pid, Instant start) {
        Proc proc = new Proc();
        proc.creation = filetime(start);
        processes.put(pid, proc);
        return proc;
    }

    Thr thread(int tid, int owner) {
        Thr thread = new Thr();
        thread.owner = owner;
        threads.put(tid, thread);
        Proc proc = processes.get(owner);
        if (proc != null) {
            proc.threads.add(tid);
        }
        return thread;
    }

    /** The handles still open, each as "process pid" or "thread tid". */
    List<String> openHandles() {
        return List.copyOf(open.values());
    }

    List<String> doubleCloses() {
        return List.copyOf(doubleCloses);
    }

    @Override
    public Opened openProcess(int rights, int pid) {
        calls.add("OpenProcess " + pid + " 0x" + Integer.toHexString(rights));
        Proc proc = processes.get(pid);
        if (proc == null) {
            return Opened.failed(87);
        }
        if (proc.openError != 0) {
            return Opened.failed(proc.openError);
        }
        if ((rights & proc.deniedRights) != 0) {
            return Opened.failed(5);
        }
        long handle = next++;
        open.put(handle, "process " + pid);
        return Opened.of(handle);
    }

    @Override
    public Opened openThread(int rights, int threadId) {
        calls.add("OpenThread " + threadId);
        Thr thread = threads.get(threadId);
        if (thread == null) {
            return Opened.failed(87);
        }
        if (thread.openError != 0) {
            return Opened.failed(thread.openError);
        }
        long handle = next++;
        open.put(handle, "thread " + threadId);
        return Opened.of(handle);
    }

    /** A handle closed twice, or never opened, fails the test at once: on the real system it could close another object's handle. */
    @Override
    public void close(long handle) {
        String what = open.remove(handle);
        if (what == null) {
            doubleCloses.add(Long.toString(handle));
            throw new IllegalStateException("CloseHandle on a handle that is not open: " + handle);
        }
        Job job = jobHandles.remove(handle);
        jobRights.remove(handle);
        if (job != null) {
            calls.add("CloseHandle job");
            job.handles--;
            if (job.handles == 0 && job.name != null) {
                // The name goes with the last handle; the job lives on while it has members.
                named.remove(job.name, job);
            }
        }
    }

    /** The open job handles, by name ("job <name>" or "job (anonymous)"). */
    List<String> openJobHandles() {
        return open.values().stream().filter(what -> what.startsWith("job ")).toList();
    }

    /** Makes the process exit. */
    void exit(int pid) {
        processes.get(pid).exitCode = 0;
    }

    @Override
    public Created createJob(String name) {
        calls.add("CreateJobObject " + name);
        if (createJobError != 0) {
            return new Created(0, false, createJobError);
        }
        Job job = name == null ? null : named.get(name);
        boolean existed = job != null;
        if (job == null) {
            job = new Job(name);
            jobs.add(job);
            if (name != null) {
                named.put(name, job);
            }
        }
        return new Created(openJobHandle(job, 0x1F001F), existed, 0);
    }

    @Override
    public Opened openJob(int rights, String name) {
        calls.add("OpenJobObject 0x" + Integer.toHexString(rights) + " " + name);
        Job job = named.get(name);
        if (job == null) {
            return Opened.failed(2);
        }
        return Opened.of(openJobHandle(job, rights));
    }

    private long openJobHandle(Job job, int rights) {
        long handle = next++;
        job.handles++;
        jobHandles.put(handle, job);
        jobRights.put(handle, rights);
        open.put(handle, "job " + (job.name == null ? "(anonymous)" : job.name));
        return handle;
    }

    @Override
    public int assignToJob(long job, long process) {
        Job target = jobOf(job);
        int pid = pidOf(process);
        Proc proc = processes.get(pid);
        calls.add("AssignProcessToJobObject " + pid);
        if (target.assignError != 0) {
            if (target.assignTakesEffectAnyway) {
                join(target, pid, proc);
            }
            return target.assignError;
        }
        if (proc.exitCode != STILL_ACTIVE || !proc.jobs.isEmpty()) {
            return 5;
        }
        join(target, pid, proc);
        afterAssign.run();
        if (assignThrows instanceof RuntimeException unchecked) {
            throw unchecked;
        }
        if (assignThrows instanceof Error error) {
            throw error;
        }
        return 0;
    }

    private static void join(Job job, int pid, Proc proc) {
        job.members.add(pid);
        proc.jobs.add(job);
    }

    @Override
    public Optional<Boolean> inJob(long process, OptionalLong job) {
        Proc proc = processes.get(pidOf(process));
        calls.add("IsProcessInJob " + pidOf(process) + (job.isPresent() ? " job" : " any"));
        if (job.isPresent()) {
            Job target = jobOf(job.getAsLong());
            if (target.membershipUnreadable) {
                lastError = 5;
                return Optional.empty();
            }
            return Optional.of(proc.jobs.contains(target));
        }
        return Optional.of(!proc.jobs.isEmpty());
    }

    @Override
    public Optional<Boolean> selfInJob() {
        return self;
    }

    @Override
    public int setCpuRate(long job, int flags, int rate) {
        Job target = jobOf(job);
        calls.add("SetCpuRate 0x" + Integer.toHexString(flags) + " " + rate);
        if ((jobRights.getOrDefault(job, 0) & 0x2) == 0) {
            return 5;
        }
        if (cpuRateErrorForAll != 0) {
            return cpuRateErrorForAll;
        }
        if (target.setCpuError != 0 && !target.setTakesEffectAnyway) {
            return target.setCpuError;
        }
        if (flags != 0 && rate == 0) {
            return 87;
        }
        target.cpuFlags = flags;
        target.cpuRate = flags == 0 ? 0 : rate;
        afterSet.run();
        return target.setCpuError;
    }

    @Override
    public Optional<CpuRate> cpuRate(long job) {
        Job target = jobOf(job);
        if (!readable(target)) {
            lastError = 5;
            return Optional.empty();
        }
        return Optional.of(new CpuRate(target.cpuFlags, target.cpuRate));
    }

    @Override
    public Optional<ExtendedLimits> extendedLimits(long job) {
        Job target = jobOf(job);
        if (!readable(target)) {
            lastError = 5;
            return Optional.empty();
        }
        return Optional.of(new ExtendedLimits(target.limitFlags, target.processMemoryLimit));
    }

    private static boolean readable(Job job) {
        if (job.readsBeforeFailure == 0) {
            return false;
        }
        if (job.readsBeforeFailure > 0) {
            job.readsBeforeFailure--;
        }
        return true;
    }

    @Override
    public int setExtendedLimits(long job, int limitFlags, long processMemoryLimit) {
        Job target = jobOf(job);
        calls.add("SetExtendedLimits 0x" + Integer.toHexString(limitFlags) + " " + processMemoryLimit);
        if ((jobRights.getOrDefault(job, 0) & 0x2) == 0) {
            return 5;
        }
        if (target.setMemoryError != 0 && !target.setTakesEffectAnyway) {
            return target.setMemoryError;
        }
        target.limitFlags = limitFlags;
        target.processMemoryLimit = processMemoryLimit;
        afterSet.run();
        return target.setMemoryError;
    }

    @Override
    public OptionalInt activeProcesses(long job) {
        Job target = jobOf(job);
        if (target.accountingUnreadable) {
            return OptionalInt.empty();
        }
        long alive = target.members.stream()
                .filter(pid -> processes.get(pid).exitCode == STILL_ACTIVE)
                .count();
        return OptionalInt.of((int) alive + target.extraActive);
    }

    private Job jobOf(long handle) {
        Job job = jobHandles.get(handle);
        if (job == null) {
            throw new IllegalStateException("Not an open job handle: " + handle);
        }
        return job;
    }

    @Override
    public OptionalInt processExitCode(long process) {
        Proc proc = processes.get(pidOf(process));
        if (proc.exitCodeFails) {
            lastError = 6;
            return OptionalInt.empty();
        }
        return OptionalInt.of(proc.exitCode);
    }

    @Override
    public OptionalInt threadExitCode(long thread) {
        Thr thr = threads.get(tidOf(thread));
        if (thr.exitCodeFails) {
            lastError = 6;
            return OptionalInt.empty();
        }
        return OptionalInt.of(thr.ended ? 0 : STILL_ACTIVE);
    }

    @Override
    public OptionalLong creationTime(long process) {
        return OptionalLong.of(processes.get(pidOf(process)).creation);
    }

    @Override
    public OptionalLong processorTime(long process) {
        calls.add("GetProcessTimes " + pidOf(process));
        OptionalLong time = processes.get(pidOf(process)).processorTime;
        if (time.isEmpty()) {
            lastError = 5;
        }
        return time;
    }

    @Override
    public OptionalLong threadCreationTime(long thread) {
        calls.add("GetThreadTimes " + tidOf(thread));
        Thr thr = threads.get(tidOf(thread));
        if (thr.creation < 0) {
            lastError = 6;
            return OptionalLong.empty();
        }
        return OptionalLong.of(thr.creation);
    }

    @Override
    public Optional<Boolean> debuggerPresent(long process) {
        calls.add("CheckRemoteDebuggerPresent " + pidOf(process));
        Optional<Boolean> present = processes.get(pidOf(process)).debugger;
        if (present.isEmpty()) {
            lastError = 5;
        }
        return present;
    }

    @Override
    public Optional<Boolean> critical(long process) {
        return processes.get(pidOf(process)).critical;
    }

    @Override
    public Optional<String> imagePath(long process) {
        return processes.get(pidOf(process)).path;
    }

    @Override
    public int setPriorityClass(long process, int priorityClass) {
        Proc proc = processes.get(pidOf(process));
        calls.add("SetPriorityClass 0x" + Integer.toHexString(priorityClass));
        if (proc.setPriorityError != 0) {
            return proc.setPriorityError;
        }
        proc.priorityClass = priorityClass;
        return 0;
    }

    @Override
    public OptionalInt priorityClass(long process) {
        Proc proc = processes.get(pidOf(process));
        if (proc.heldClass != null) {
            if (proc.heldClass == 0) {
                lastError = 6;
                return OptionalInt.empty();
            }
            return OptionalInt.of(proc.heldClass);
        }
        return OptionalInt.of(proc.priorityClass);
    }

    @Override
    public ThreadListing threadsOf(int pid) {
        calls.add("Toolhelp threads");
        onListThreads.run();
        if (threadListingError != 0) {
            return ThreadListing.failed(threadListingError);
        }
        if (!listings.isEmpty()) {
            return ThreadListing.of(listings.poll());
        }
        Proc proc = processes.get(pid);
        return ThreadListing.of(proc == null ? List.of() : proc.threads);
    }

    @Override
    public Optional<Boolean> processListed(int pid) {
        calls.add("Toolhelp processes");
        if (listedFromProcesses) {
            return Optional.of(processes.containsKey(pid) && processes.get(pid).openError != 87);
        }
        return listed.map(map -> map.getOrDefault(pid, false));
    }

    @Override
    public int processIdOfThread(long thread) {
        Thr thr = threads.get(tidOf(thread));
        if (thr.ownerUnreadable) {
            lastError = 6;
            return 0;
        }
        return thr.owner;
    }

    @Override
    public Counted suspendThread(long thread) {
        int tid = tidOf(thread);
        Thr thr = threads.get(tid);
        calls.add("SuspendThread " + tid);
        if (thr.suspendError != 0) {
            return new Counted(-1, thr.suspendError);
        }
        thr.suspends++;
        int previous = thr.suspendCount++;
        afterSuspend.run();
        return new Counted(previous, 0);
    }

    @Override
    public Counted resumeThread(long thread) {
        int tid = tidOf(thread);
        Thr thr = threads.get(tid);
        calls.add("ResumeThread " + tid);
        thr.resumes++;
        if (thr.resumeError != 0) {
            return new Counted(-1, thr.resumeError);
        }
        int previous = thr.suspendCount;
        if (previous > 0) {
            thr.suspendCount--;
        }
        return new Counted(previous, 0);
    }

    @Override
    public Optional<List<Window>> topLevelWindows() {
        calls.add("EnumWindows");
        onEnumerateWindows.run();
        if (enumerateError != 0) {
            lastError = enumerateError;
            return Optional.empty();
        }
        return Optional.of(List.copyOf(windows));
    }

    @Override
    public int lastError() {
        return lastError;
    }

    @Override
    public int postClose(long window) {
        calls.add("PostMessage " + window);
        int error = postErrors.getOrDefault(window, 0);
        if (error == 0) {
            posted.add(window);
        }
        return error;
    }

    @Override
    public String describe(int error) {
        return "error " + error;
    }

    private int pidOf(long handle) {
        String what = open.get(handle);
        if (what == null || !what.startsWith("process ")) {
            throw new IllegalStateException("Not an open process handle: " + handle + " (" + what + ")");
        }
        return Integer.parseInt(what.substring("process ".length()));
    }

    private int tidOf(long handle) {
        String what = open.get(handle);
        if (what == null || !what.startsWith("thread ")) {
            throw new IllegalStateException("Not an open thread handle: " + handle + " (" + what + ")");
        }
        return Integer.parseInt(what.substring("thread ".length()));
    }
}
