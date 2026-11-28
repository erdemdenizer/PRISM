package prism.srm.platform.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.NativeConsentKind;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ClearResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;
import prism.srm.platform.ResourceLimiter;

/**
 * Native CPU and memory limits on Linux through systemd transient scopes (milestone 12, S3B/S4, plan Rev 2.2, ADR-0016
 * draft). One scope per process, {@code prism-srm-limit-<pid>-<startMillis>.scope}, created by the user manager over a
 * pidfd only; limits are runtime unit properties, set one kind per call and verified by reading {@code cpu.max},
 * {@code memory.high} and {@code memory.max}. PRISM never writes a cgroup file and never stops, kills, abandons or
 * attaches to a unit.
 *
 * <p><b>Who may be adopted.</b> Nothing is asked of the system before the {@link
 * LinuxNativeReadiness.EligibilityPolicy} admits the identity and the adoption consent is given (and, for {@code
 * memory.max}, the separate hard-memory consent): by the {@link NativeConsent} the limiter was made with, or by the user
 * for the call ({@link NativeConsentKind}). A consent not given is answered {@code CONSENT_REQUIRED}, with the statements
 * of {@link NativeConsent} to show, before anything is asked of the system. The product's limiter ({@link #desktop},
 * since the product owner lifted K16 on 2026-11-28) is made with the desktop policy and with no consent of its own: it
 * admits only a desktop application's own scope in the user manager's {@code app.slice}, never PRISM or the processes
 * it runs under, and only once the user consented for that request in the consent dialog. A limiter made with {@link
 * LinuxNativeReadiness.EligibilityPolicy#none()} admits no process and never opens a bus connection; a test harness
 * makes one that admits the disposable processes it created. {@link #sessionBarrier} keeps every desktop session
 * service from an emulated limit (owner decision 2 of 2026-11-28).
 *
 * <p><b>Adoption (§2.5).</b> A pidfd is opened and the identity (PID and start time) checked after it; the scope is
 * created with that descriptor; the job is awaited for a bounded time; then the unit's {@code ControlGroup} and
 * {@code InvocationID} are read, and the process is shown to be in exactly that cgroup, alive and the same process. Only
 * then is the ownership evidence recorded durably and are limits set. An answer that may or may not have taken effect
 * keeps the hold {@link NativeHold.State#UNCERTAIN} with its descriptor: no second limiter starts and nothing is assumed
 * undone. There is no PID-based adoption: a manager that refuses {@code PIDFDs} makes native limits unavailable for
 * the session.
 *
 * <p><b>Recovery (§4.3, §4.5).</b> {@link #recover} classifies and changes nothing; {@link #clearOwned} clears only a
 * scope whose ownership it proves again at that moment (the record's unit and invocation id equal the live unit's, and
 * the recorded process is its member, or has exited and other processes remain). A recorded process that runs outside
 * its scope proves nothing. A scope with PRISM's name but no proof is never changed. {@link #scopeBarrier} keeps every process inside a PRISM scope this limiter does not hold from a second
 * limiter, native or emulated, whatever the ledger says.
 *
 * <p>One operation at a time under one lock; every system call is bounded. Never on the JavaFX Application Thread. The
 * limit service calls {@link #apply}, {@link #change} and {@link #forget} on its native-limit worker, one at a time, and
 * reads {@link #hold} on its limiter thread without the lock (risk D6), so a read never waits for an adoption; the lock
 * serializes that worker, recovery and the release.
 */
public final class LinuxScopeLimiter implements ResourceLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(LinuxScopeLimiter.class);

    static final String NOT_NAMED = LinuxNativeReadiness.NOT_NAMED;
    /** Why a limiter whose policy admits no process ({@code EligibilityPolicy.none()}) offers nothing. */
    static final String ADMITS_NONE = "this native limiter admits no process";
    /**
     * How long making the product's limiter waits for the read-only probe of the user manager ({@link #desktop}). The
     * probe normally answers within milliseconds; each of its bus calls is bounded to 2 s on its own.
     */
    static final Duration PROBE_BOUND = Duration.ofSeconds(5);

    static final String CPU_QUOTA = "CPUQuotaPerSecUSec";
    static final String MEMORY_HIGH = "MemoryHigh";
    static final String MEMORY_MAX = "MemoryMax";
    static final Duration START_BOUND = Duration.ofSeconds(2);
    static final Duration POLL = Duration.ofMillis(50);
    /**
     * How long a read-back waits for the cgroup files to show what was set: systemd answers SetProperties before it
     * writes the cgroup (it queues the cgroup realization), so the first read can still show the old value.
     */
    static final Duration SETTLE_BOUND = Duration.ofSeconds(1);

    /** A bounded sleep; a seam so that tests do not wait. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * A scope the limiter holds for a process. Changed only under {@link #lock}; {@link #view} is also read without it
     * (risk D6), so it is replaced whole, never changed in part.
     */
    private static final class Hold {
        final String unit;
        int fd;
        Optional<ScopeProvenance> provenance = Optional.empty();
        /** The ownership evidence is on disk (A6); until then no limit is set in the scope. */
        boolean recorded;

        /**
         * A hard memory limit the user consented to reads back in force on this scope (LIM-6): a later change of this
         * process in this session needs no new consent, as it needs no new adoption consent. Written under the lock,
         * read by {@link #consentNeeded}.
         */
        volatile boolean hardMemory;

        /** Whether the process is known to be in the scope and what the scope is known to hold, as one value. */
        volatile NativeHold view = new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty());

        Hold(String unit, int fd) {
            this.unit = unit;
            this.fd = fd;
        }

        /** Under the lock. */
        void set(NativeHold.State state, Optional<EffectiveLimits> inForce) {
            view = new NativeHold(state, inForce);
        }

        /** Under the lock: what the scope holds, its state unchanged. */
        void inForce(Optional<EffectiveLimits> inForce) {
            view = new NativeHold(view.state(), inForce);
        }

        Optional<EffectiveLimits> inForce() {
            return view.inForce();
        }
    }

    /** What a series of SetProperties calls left: the problems, and the values that may still be written. */
    private static final class Sent {
        /** The invocation of the scope every property goes to (never the name: see {@link ScopeManager#setLimit}). */
        final String invocation;

        /** Past this {@link #nanoTime}, nothing more is sent (a bounded release); empty: no bound of its own. */
        final OptionalLong deadline;

        final List<String> problems = new ArrayList<>();
        /** A send found no unit with the invocation id: the scope PRISM made is gone (nothing was written). */
        boolean gone;

        /** Per property, a value systemd accepted or that got no answer: it may be written after the read-back. */
        final Map<String, Long> landing = new LinkedHashMap<>();

        Sent(String invocation) {
            this(invocation, OptionalLong.empty());
        }

        Sent(String invocation, OptionalLong deadline) {
            this.invocation = invocation;
            this.deadline = deadline;
        }

        String why(String otherwise) {
            return problems.isEmpty() ? otherwise : String.join("; ", problems);
        }
    }

    private final LinuxNativeReadiness.EligibilityPolicy policy;
    private final NativeConsent consent;
    private final LinuxNative calls;
    private final ProcFs proc;
    private final LongSupplier bootSeconds;
    private final Optional<CgroupFiles> files;
    private final Optional<ScopeManager> scopes;
    private final String managerGroup;
    private final long uid;
    private final Optional<LinuxNativeReadiness.SystemReadiness> readiness;
    private final List<String> limitations;
    private final IntSupplier processors;
    private final Pause pause;
    private final LongSupplier nanoTime;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ProcessIdentity, Hold> holds = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile Optional<String> adoptionRefused = Optional.empty();
    private volatile long ticks;

    /** Everything the limiter is made of; package-private so that tests and the harness compose it. */
    record Parts(
            LinuxNativeReadiness.EligibilityPolicy policy,
            NativeConsent consent,
            LinuxNative calls,
            ProcFs proc,
            LongSupplier bootSeconds,
            Optional<CgroupFiles> files,
            Optional<ScopeManager> scopes,
            String managerGroup,
            long uid,
            Optional<LinuxNativeReadiness.SystemReadiness> readiness,
            List<String> limitations,
            IntSupplier processors,
            Pause pause,
            LongSupplier nanoTime) {}

    LinuxScopeLimiter(Parts parts) {
        this.policy = Objects.requireNonNull(parts.policy(), "policy");
        this.consent = Objects.requireNonNull(parts.consent(), "consent");
        this.calls = Objects.requireNonNull(parts.calls(), "calls");
        this.proc = Objects.requireNonNull(parts.proc(), "proc");
        this.bootSeconds = Objects.requireNonNull(parts.bootSeconds(), "bootSeconds");
        this.files = Objects.requireNonNull(parts.files(), "files");
        this.scopes = Objects.requireNonNull(parts.scopes(), "scopes");
        this.managerGroup = Objects.requireNonNull(parts.managerGroup(), "managerGroup");
        this.uid = parts.uid();
        this.readiness = Objects.requireNonNull(parts.readiness(), "readiness");
        this.limitations = List.copyOf(parts.limitations());
        this.processors = Objects.requireNonNull(parts.processors(), "processors");
        this.pause = Objects.requireNonNull(parts.pause(), "pause");
        this.nanoTime = Objects.requireNonNull(parts.nanoTime(), "nanoTime");
    }

    /** The read-only probe of the user manager the product's limiter is made from; a seam for tests. */
    @FunctionalInterface
    interface Probe {
        SystemdCapabilityProbe.Report run(CgroupFiles files, long uid);
    }

    /**
     * The product's limiter on Linux since the product owner lifted K16 on 2026-11-28 (ADR-0016, amendment of that
     * date; docs/evidence/k16-desktop-validation-2026-11-28.md). It is made with the desktop policy ({@link
     * LinuxNativeReadiness.EligibilityPolicy#desktop()}) and with no consent of its own ({@link NativeConsent#none()}):
     * the user gives the adoption consent, and the hard-memory one, for each request in the consent dialog. It uses the
     * real {@code /proc}, the cgroup v2 files and the systemd user manager over libsystemd's sd-bus ({@link
     * SystemdScopeManager}). What the system can provide comes from the read-only {@link SystemdCapabilityProbe}, as
     * the test harness takes it: the readiness, the manager's control group, and PRISM's own user ID. Where anything is
     * missing (no cgroup v2, no libsystemd, no reachable user manager, a cgroup v1 hierarchy too, a controller not
     * delegated, systemd older than 255, the probe's sources disagreeing, no answer within {@link #PROBE_BOUND}) every
     * native kind is {@code UNAVAILABLE} with the reason and the limit service offers the emulated CPU limit as it does
     * where there is no native limit.
     *
     * <p>Making it queries the system: it reads {@code /proc} and the cgroup mount, loads libsystemd where the checks of
     * {@link LibsystemdCheck} pass, and the probe opens the user bus (one short connection per inspection, read-only
     * methods only, each call bounded to 2 s; the SASL authentication before them only by libsystemd's own timeout,
     * risk D1). The probe runs on a thread of its own, {@code srm-linux-probe}, which is waited for at most {@link
     * #PROBE_BOUND}; a probe that has not answered by then ends by its own bounds, and its answer is not used. Never call
     * this on the JavaFX Application Thread: {@code HostPlatform.create} runs on {@code srm-startup}.
     */
    public static LinuxScopeLimiter desktop(LongSupplier bootSeconds) {
        Path procRoot = Path.of("/proc");
        ProcFs proc = new ProcFs(procRoot);
        LibsystemdCheck.Outcome library = LibsystemdCheck.onThisSystem();
        LinuxScopeLimiter limiter = new LinuxScopeLimiter(desktopParts(
                new DesktopSystem(
                        new JnaLinux(),
                        proc,
                        bootSeconds,
                        CgroupFiles.mounted(procRoot),
                        library.api().map(SystemdScopeManager::new),
                        ownUid(proc),
                        () -> onlineProcessors(procRoot.resolve("stat")),
                        duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()),
                        System::nanoTime),
                (files, uid) -> new SystemdCapabilityProbe(library, SystemdUserBus::new, files, uid).run(),
                PROBE_BOUND));
        LOG.info("Linux native limits: {}", limiter.summary());
        return limiter;
    }

    /**
     * What {@link #desktop} gathers from the running system before it probes the user manager.
     *
     * @param scopes the state-changing side of the user manager, where libsystemd could be bound
     * @param uid PRISM's own real user ID, -1 when it could not be read
     */
    record DesktopSystem(
            LinuxNative calls,
            ProcFs proc,
            LongSupplier bootSeconds,
            Optional<CgroupFiles> files,
            Optional<ScopeManager> scopes,
            long uid,
            IntSupplier processors,
            Pause pause,
            LongSupplier nanoTime) {}

    /**
     * The parts of the product's limiter: always the desktop policy and no consent of the limiter's own, so that only
     * the user's consent for a request admits an adoption. The probe runs only where the cgroup v2 files and PRISM's
     * user ID are there, bounded by {@code bound}. The scope manager is kept only when the probe reached the user
     * manager and its sources agree; otherwise there is none, and recovery classifies an earlier session's record as
     * failed without a bus call. A finding of the probe (the manager's control group not the user's, {@code app.slice}
     * outside it, PRISM's unit and {@code /proc/self/cgroup} disagreeing) makes every kind unavailable: the paths PRISM
     * would decide eligibility from are then not the manager's.
     */
    static Parts desktopParts(DesktopSystem system, Probe probe, Duration bound) {
        List<String> missing = new ArrayList<>();
        Optional<SystemdCapabilityProbe.Report> report = Optional.empty();
        if (system.files().isEmpty()) {
            missing.add("the cgroup v2 file system is not mounted");
        } else if (system.uid() < 0) {
            missing.add("PRISM's own user ID could not be read");
        } else {
            CgroupFiles files = system.files().get();
            report = probed(() -> probe.run(files, system.uid()), bound, missing);
        }
        Optional<LinuxNativeReadiness.SystemReadiness> readiness = Optional.empty();
        String manager = "";
        if (report.isPresent()) {
            if (report.get().findings().isEmpty()) {
                readiness = Optional.of(report.get().readiness());
                manager = report.get()
                        .manager()
                        .flatMap(SystemdUserBus.Reading::value)
                        .map(SystemdUserBus.ManagerFacts::controlGroup)
                        .orElse("");
                for (LinuxNativeReadiness.Readiness kind :
                        List.of(readiness.get().cpu(), readiness.get().memoryThrottle())) {
                    kind.reasons().stream().filter(r -> !missing.contains(r)).forEach(missing::add);
                }
            } else {
                missing.addAll(report.get().findings());
            }
        }
        Optional<ScopeManager> scopes = manager.isEmpty() ? Optional.empty() : system.scopes();
        return new Parts(
                LinuxNativeReadiness.EligibilityPolicy.desktop(),
                NativeConsent.none(),
                system.calls(),
                system.proc(),
                system.bootSeconds(),
                system.files(),
                scopes,
                manager,
                system.uid(),
                readiness,
                missing.isEmpty()
                        ? List.of()
                        : List.of("Linux native limits are unavailable: " + String.join("; ", missing)),
                system.processors(),
                system.pause(),
                system.nanoTime());
    }

    /**
     * The probe's report, run on {@code srm-linux-probe} and waited for at most {@code bound}; empty, with why in {@code
     * missing}, when it did not answer in time, failed, or the wait was interrupted (the interrupt is kept). A probe left
     * running ends by the bounds of its own calls; it changes nothing, and its answer is dropped.
     */
    static Optional<SystemdCapabilityProbe.Report> probed(
            java.util.function.Supplier<SystemdCapabilityProbe.Report> probe, Duration bound, List<String> missing) {
        CompletableFuture<SystemdCapabilityProbe.Report> done = new CompletableFuture<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        done.complete(probe.get());
                    } catch (RuntimeException failed) {
                        done.completeExceptionally(failed);
                    } catch (Error fatal) {
                        // Said as what it is, not as a late answer; the Error goes on to the thread's handler.
                        done.completeExceptionally(fatal);
                        throw fatal;
                    }
                },
                "srm-linux-probe");
        worker.setDaemon(true);
        worker.start();
        try {
            return Optional.of(done.get(nanosOf(bound), TimeUnit.NANOSECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            missing.add("the systemd user manager was not checked: PRISM was interrupted while it waited");
        } catch (TimeoutException late) {
            missing.add("the systemd user manager did not answer within "
                    + (bound.toMillis() % 1000 == 0 ? bound.toSeconds() + " s" : bound.toMillis() + " ms"));
        } catch (ExecutionException failed) {
            LOG.warn("Checking the systemd user manager failed", failed.getCause());
            missing.add("the systemd user manager could not be checked: " + failed.getCause());
        }
        return Optional.empty();
    }

    /** PRISM's own real user ID, from {@code /proc/self/status} through its PID; -1 when it cannot be read. */
    static long ownUid(ProcFs proc) {
        return proc.uids((int) ProcessHandle.current().pid())
                .value()
                .filter(ids -> !ids.isEmpty())
                .map(ids -> ids.get(0))
                .orElse(-1L);
    }

    /** One line for the log: what each kind is here, or why it is unavailable. */
    String summary() {
        Map<LimitKind, LimitCapability> found = capabilities();
        LimitCapability cpu = found.get(LimitKind.CPU);
        LimitCapability memory = found.get(LimitKind.MEMORY);
        return "CPU " + described(cpu) + "; memory " + described(memory)
                + (cpu.available() || memory.available()
                        ? "; each process needs the user's consent before PRISM moves it into a scope"
                        : "");
    }

    private static String described(LimitCapability capability) {
        return capability.available()
                ? capability.level() + " through a systemd scope"
                : "unavailable (" + capability.reason().orElse("no reason given") + ")";
    }

    /**
     * The machine's online logical processors: the per-processor {@code cpuN} lines of {@code /proc/stat}, which the
     * kernel writes for each online processor (proc(5), fs/proc/stat.c), not {@code Runtime.availableProcessors()},
     * which counts only the processors this JVM may run on. 0 when unreadable.
     */
    static int onlineProcessors(Path procStat) {
        try {
            int count = 0;
            for (String line : Files.readAllLines(procStat, StandardCharsets.US_ASCII)) {
                if (line.length() > 3 && line.startsWith("cpu") && Character.isDigit(line.charAt(3))) {
                    count++;
                }
            }
            return count;
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    // ----- What can be had -----

    private boolean admitsNone() {
        return policy.admitsNone();
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilities() {
        if (admitsNone()) {
            LimitCapability no = LimitCapability.unavailable(ADMITS_NONE);
            return Map.of(LimitKind.CPU, no, LimitKind.MEMORY, no);
        }
        Map<LimitKind, LimitCapability> found = new LinkedHashMap<>();
        found.put(LimitKind.CPU, systemCapability(LimitKind.CPU));
        found.put(LimitKind.MEMORY, systemCapability(LimitKind.MEMORY));
        return Map.copyOf(found);
    }

    private LimitCapability systemCapability(LimitKind kind) {
        if (adoptionRefused.isPresent()) {
            return LimitCapability.unavailable(adoptionRefused.get());
        }
        if (readiness.isEmpty() || scopes.isEmpty() || files.isEmpty()) {
            return LimitCapability.unavailable(
                    limitations.isEmpty()
                            ? "the systemd user manager could not be checked"
                            : String.join("; ", limitations));
        }
        LinuxNativeReadiness.Readiness ready =
                kind == LimitKind.CPU ? readiness.get().cpu() : readiness.get().memoryThrottle();
        if (!ready.ready()) {
            return LimitCapability.unavailable(String.join("; ", ready.reasons()));
        }
        return kind == LimitKind.CPU
                ? LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.SYSTEMD_SCOPE)
                : LimitCapability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.SYSTEMD_SCOPE);
    }

    @Override
    public List<String> limitations() {
        return limitations;
    }

    @Override
    public Optional<Boolean> selfInJob() {
        return Optional.empty();
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilitiesFor(ProcessIdentity target) {
        Optional<String> refused = eligibility(target);
        if (refused.isPresent()) {
            LimitCapability no = LimitCapability.unavailable(refused.get());
            return Map.of(LimitKind.CPU, no, LimitKind.MEMORY, no);
        }
        return capabilities();
    }

    /** Why the process may not be adopted; asked before anything else, without any bus call. */
    private Optional<String> eligibility(ProcessIdentity target) {
        Optional<String> policyRefusal = policy.refusal(target);
        if (policyRefusal.isPresent()) {
            return policyRefusal;
        }
        if (files.isEmpty()) {
            return Optional.of("the cgroup v2 file system is not mounted");
        }
        Optional<String> gone = identityProblem(target);
        if (gone.isPresent()) {
            return gone;
        }
        Optional<String> user = otherUser(target);
        if (user.isPresent()) {
            return user;
        }
        return cgroupProblem(target);
    }

    /**
     * Why the process's cgroup rules it out: unreadable, in a scope of PRISM's name, or outside the user's own
     * manager. Asked in the eligibility, and again once the pidfd pins the process (risk D2).
     */
    private Optional<String> cgroupProblem(ProcessIdentity target) {
        ProcFs.Reading<String> path = files.orElseThrow().processCgroup((int) target.pid());
        return LinuxNativeReadiness.eligibility(policy, target, path.value(), uid)
                .or(() -> path.value()
                        .filter(p -> !p.startsWith(managerGroup + "/"))
                        .map(p -> "it runs outside your session manager (in " + p + ")"));
    }

    /**
     * Why the process does not run wholly as this user: every one of its real, effective, saved set and file system
     * user IDs must be PRISM's own. A set-user-ID program started in the session, or one that changed its IDs, is not
     * the user's to limit; an unreadable status refuses. This is PRISM's policy (cgroup v2 itself checks only write access
     * to the common ancestor's {@code cgroup.procs}); it is checked again after the pidfd is open, and cannot see a change
     * of IDs after that.
     */
    private Optional<String> otherUser(ProcessIdentity target) {
        ProcFs.Reading<List<Long>> ids = proc.uids((int) target.pid());
        if (ids.value().isEmpty()) {
            return Optional.of("its user IDs could not be read: " + ids.detail());
        }
        if (ids.value().get().stream().anyMatch(id -> id != uid)) {
            return Optional.of("it does not run wholly as your user (user IDs "
                    + ids.value().get() + ")");
        }
        // Risk D8: a set-group-ID program runs with a group the user did not start it with, and its four group IDs then
        // differ unless the program made them equal itself (setresgid); that case is not caught here.
        ProcFs.Reading<List<Long>> groups = proc.gids((int) target.pid());
        if (groups.value().isEmpty()) {
            return Optional.of("its group IDs could not be read: " + groups.detail());
        }
        if (groups.value().get().stream().distinct().count() != 1) {
            return Optional.of(
                    "it runs with a set-group-ID (group IDs " + groups.value().get() + ")");
        }
        return Optional.empty();
    }

    private static final String NOT_SIGNALLABLE =
            "the process may no longer be signalled by your user (it runs as another user now)";

    /** What signal 0 through a descriptor says about the process it names. */
    private enum Liveness {
        RUNS,
        /**
         * ESRCH: the process has terminated and been waited on; or it has terminated and is a zombie, which signal 0
         * still reaches but which is no longer in its cgroup (risk D8).
         */
        EXITED,
        /**
         * Any other failure, EPERM in practice (kill(2) rules: its real and saved set-user-IDs are no longer the user's,
         * as after setuid(0), or a security module refuses): it may run, and is not provably the user's.
         */
        NOT_SIGNALLABLE
    }

    /**
     * Signal 0 through the descriptor ({@code pidfd_send_signal}(2), https://man7.org/linux/man-pages/man2/pidfd_send_signal.2.html):
     * only ESRCH means the process has exited; EPERM means it runs but this caller may not signal it. A set-user-ID exec
     * keeps the real user ID and so still passes; the user IDs are compared separately ({@link #otherUser}).
     */
    private Liveness liveness(ProcessIdentity target, int fd) {
        LinuxNative.Call checked = calls.pidfdSendSignal(fd, 0);
        if (!checked.failed()) {
            return terminatedZombie(target) ? Liveness.EXITED : Liveness.RUNS;
        }
        return checked.errno() == LinuxErrors.ESRCH ? Liveness.EXITED : Liveness.NOT_SIGNALLABLE;
    }

    /**
     * Whether the process has terminated and waits to be reaped, which signal 0 still reaches (risk D8). The state in
     * {@code /proc/<pid>/stat} is the thread-group leader's, and a leader that exits before its other threads is a
     * zombie while they still run (kernel/exit.c, delay_group_leader; pidfd_poll in kernel/fork.c says so): so 'Z'
     * counts only with a single thread left. A zombie's PID is not reused before it is reaped (pidfd_open(2)), but it
     * can be reaped between the signal and these reads, so the start time read must still be the target's.
     */
    private boolean terminatedZombie(ProcessIdentity target) {
        ProcFs.Reading<ProcStat> stat = proc.stat((int) target.pid());
        if (stat.value().filter(read -> read.state() == 'Z').isEmpty()) {
            return false;
        }
        ProcFs.Reading<Integer> threads = proc.threads((int) target.pid());
        return threads.value().filter(count -> count == 1).isPresent()
                && identityProblem(target).isEmpty();
    }

    /** Before an adoption: empty when the descriptor's process runs and may be signalled; otherwise the refusal. */
    private Optional<ApplyResult> notSignallable(ProcessIdentity target, int fd) {
        return switch (liveness(target, fd)) {
            case RUNS -> Optional.empty();
            case EXITED ->
                Optional.of(
                        ApplyResult.notAssigned(fail(OperationResult.PROCESS_NOT_FOUND, "the process is gone"), false));
            case NOT_SIGNALLABLE ->
                Optional.of(ApplyResult.notAssigned(fail(OperationResult.ACCESS_DENIED, NOT_SIGNALLABLE), false));
        };
    }

    /** Why a hold's scope is not, at this moment, PRISM's invocation with the target in it. */
    private record Outside(Kind kind, String why) {
        enum Kind {
            /** The unit is gone or is another invocation, or the target is not in it: PRISM's hold no longer holds. */
            NOT_OURS,
            /** The target has exited; the scope may still be PRISM's. */
            EXITED,
            /**
             * The scope is still the invocation PRISM made with its recorded cgroup (checked first), and the process its
             * descriptor names still exists, but it is no longer the user's: signal 0 is refused (EPERM, not ESRCH) or its
             * user or group IDs are not the user's now. No limit is set or tightened on it; its scope's limits may still
             * be cleared, which signals nothing and only loosens (risk D4).
             */
            NOT_THE_USERS,
            /** Something could not be read; nothing is known to have changed. */
            UNREADABLE
        }
    }

    /**
     * Why the hold's scope is not, at this moment, the invocation PRISM made with the target in it: the unit is gone, has
     * another invocation id or cgroup, cannot be read, or the target (alive by its descriptor, the same identity) is not
     * in exactly that cgroup and listed in its {@code cgroup.procs}. Empty when it is. The files are read first and the
     * descriptor and identity checked after them, so the reads describe the live process the descriptor names. A read,
     * not a lock: the process may move right after it (the callers check again after their sends).
     */
    private Optional<Outside> outsideItsScope(ProcessIdentity target, Hold hold) {
        if (hold.provenance.isEmpty()) {
            return Optional.of(new Outside(Outside.Kind.NOT_OURS, "whether the process is in its scope is unknown"));
        }
        ScopeProvenance proven = hold.provenance.get();
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> facts =
                scopes.orElseThrow().unit(hold.unit);
        if (facts.status() == SystemdUserBus.Status.NO_SUCH_UNIT) {
            return Optional.of(new Outside(Outside.Kind.NOT_OURS, "the scope PRISM made is gone"));
        }
        if (facts.value().isEmpty()) {
            return Optional.of(new Outside(Outside.Kind.UNREADABLE, "the scope could not be read: " + facts.detail()));
        }
        if (!facts.value().get().invocationId().equals(proven.invocationId())
                || !facts.value().get().controlGroup().equals(proven.cgroupPath())) {
            return Optional.of(new Outside(Outside.Kind.NOT_OURS, "another unit has the scope's name now"));
        }
        String group = proven.cgroupPath().orElseThrow();
        ProcFs.Reading<String> path = files.orElseThrow().processCgroup((int) target.pid());
        ProcFs.Reading<List<Integer>> members = files.orElseThrow().procs(group);
        switch (liveness(target, hold.fd)) {
            case EXITED -> {
                return Optional.of(new Outside(Outside.Kind.EXITED, "the process has exited"));
            }
            case NOT_SIGNALLABLE -> {
                return Optional.of(new Outside(Outside.Kind.NOT_THE_USERS, NOT_SIGNALLABLE));
            }
            case RUNS -> {
                // Checked below: the identity and the membership.
            }
        }
        if (identityProblem(target).isPresent()) {
            return Optional.of(new Outside(Outside.Kind.EXITED, "the PID names another process now"));
        }
        // The user IDs again (final review): a set-user-ID program run after the adoption keeps the real user ID, so
        // signal 0 still works, but the process is no longer the user's to limit. Alive by its descriptor, so the PID
        // still names it.
        ProcFs.Reading<List<Long>> ids = proc.uids((int) target.pid());
        if (ids.value().isEmpty()) {
            return Optional.of(new Outside(Outside.Kind.UNREADABLE, "its user IDs could not be read: " + ids.detail()));
        }
        Optional<String> user = otherUser(target);
        if (user.isPresent()) {
            return Optional.of(new Outside(Outside.Kind.NOT_THE_USERS, user.get()));
        }
        if (path.value().isEmpty() || members.value().isEmpty()) {
            return Optional.of(
                    new Outside(Outside.Kind.UNREADABLE, "whether the process is in its scope could not be read"));
        }
        if (!path.value().get().equals(group) || !members.value().get().contains((int) target.pid())) {
            return Optional.of(new Outside(
                    Outside.Kind.NOT_OURS, "the process is no longer in its scope (moved by another program)"));
        }
        return Optional.empty();
    }

    /** Empty when the PID still names exactly this process; otherwise why not. */
    private Optional<String> identityProblem(ProcessIdentity target) {
        if (target.pid() < 1 || target.pid() > CgroupV2.MOST_PID) {
            return Optional.of("PID " + target.pid() + " names no process");
        }
        ProcFs.Reading<ProcStat> stat = proc.stat((int) target.pid());
        if (stat.status() != ProcFs.Status.FOUND) {
            return Optional.of("the process is gone");
        }
        if (!ProcessIdentityReader.matches(target, stat.value().orElseThrow(), bootSeconds.getAsLong(), ticks())) {
            return Optional.of("its PID belongs to another process now");
        }
        return Optional.empty();
    }

    private long ticks() {
        if (ticks <= 0) {
            ticks = calls.clockTicksPerSecond();
        }
        return ticks;
    }

    @Override
    public Optional<NativeHold> hold(ProcessIdentity target) {
        // Without the lock (risk D6): an adoption holds it for seconds, and the limit service reads the holds
        // meanwhile.
        return Optional.ofNullable(holds.get(target)).map(h -> h.view);
    }

    // ----- Adoption -----

    /**
     * Refused: a scope limiter sets a limit only after the ownership evidence is on disk (plan Rev 2.2 A6), so it is
     * applied only with a place to record it. Nothing is asked of the system.
     */
    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec) {
        return ApplyResult.notAssigned(
                fail(OperationResult.INVALID_ARGUMENT, "no place to record the scope's ownership was given"), false);
    }

    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec, OwnershipRecorder ownership) {
        return apply(target, spec, ownership, Set.of());
    }

    /**
     * An adoption with these limits. The consents are admitted when the limiter was made with them or when {@code
     * granted} holds them for this call; one missing is answered {@link ApplyResult.Phase#CONSENT_REQUIRED} once the
     * process is eligible and the system could provide the limits, before anything is asked of the system.
     */
    @Override
    public ApplyResult apply(
            ProcessIdentity target, LimitSpec spec, OwnershipRecorder ownership, Set<NativeConsentKind> granted) {
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(granted, "granted");
        lock.lock();
        try {
            if (closed) {
                return ApplyResult.notAssigned(fail(OperationResult.NATIVE_ERROR, "PRISM is closing"), false);
            }
            if (holds.containsKey(target)) {
                return ApplyResult.notAssigned(
                        fail(OperationResult.INVALID_ARGUMENT, "PRISM already holds a scope for this process"), false);
            }
            if (spec.none()) {
                return ApplyResult.notAssigned(fail(OperationResult.INVALID_ARGUMENT, "no limit was asked for"), false);
            }
            Optional<String> refused = eligibility(target);
            if (refused.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.UNSUPPORTED, refused.get()), true);
            }
            // What the system cannot provide is refused before a consent is asked: the user is never asked to agree to
            // a move that would not be made.
            Optional<String> system = kindsUnavailable(spec);
            if (system.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.UNSUPPORTED, system.get()), true);
            }
            Optional<NativeConsentOffer> missing = missingConsent(target, spec, granted, true, false);
            if (missing.isPresent()) {
                return ApplyResult.consentRequired(consentRefusal(missing.get()), missing.get());
            }
            return adopt(target, spec, ownership, hard(spec) && hardGranted(target, granted));
        } finally {
            lock.unlock();
        }
    }

    private static boolean hard(LimitSpec spec) {
        return spec.memoryBytes().isPresent() && spec.memoryBoundary() == MemoryBoundary.HARD;
    }

    // ----- Consent (LIM-6, ADR-0016 decisions 4 and 6) -----

    /**
     * Read without the lock and without asking anything of the system: a process the limiter holds needs no adoption
     * consent again, only the hard-memory one for a hard memory limit its scope has not had; any other process needs
     * the adoption consent once it is eligible and the system could provide the limits. Empty where it would be refused
     * for another reason first, which {@link #apply} and {@link #change} say themselves.
     */
    @Override
    public Optional<NativeConsentOffer> consentNeeded(
            ProcessIdentity target, LimitSpec spec, Set<NativeConsentKind> granted) {
        Objects.requireNonNull(granted, "granted");
        if (closed || spec.none() || admitsNone()) {
            return Optional.empty();
        }
        Hold hold = holds.get(target);
        if (hold != null) {
            // As change() does: what the system cannot provide is refused before a consent is asked.
            return kindsUnavailable(spec).isPresent()
                    ? Optional.empty()
                    : missingConsent(target, spec, granted, false, hold.hardMemory);
        }
        if (eligibility(target).isPresent() || kindsUnavailable(spec).isPresent()) {
            return Optional.empty();
        }
        return missingConsent(target, spec, granted, true, false);
    }

    /**
     * The consents the operation needs that neither the limiter's own {@link NativeConsent} nor {@code granted} gives,
     * with their fixed statements; empty when none is missing.
     *
     * @param adopting whether the operation moves the process into a scope
     * @param heldHard whether the user consented to a hard memory limit on the scope the limiter holds
     */
    private Optional<NativeConsentOffer> missingConsent(
            ProcessIdentity target,
            LimitSpec spec,
            Set<NativeConsentKind> granted,
            boolean adopting,
            boolean heldHard) {
        Set<NativeConsentKind> kinds = EnumSet.noneOf(NativeConsentKind.class);
        List<String> statements = new ArrayList<>();
        if (adopting && !consent.adoption(target) && !granted.contains(NativeConsentKind.ADOPTION)) {
            kinds.add(NativeConsentKind.ADOPTION);
            statements.addAll(NativeConsent.ADOPTION_STATEMENTS);
        }
        if (hard(spec) && !heldHard && !hardGranted(target, granted)) {
            kinds.add(NativeConsentKind.HARD_MEMORY);
            statements.add(NativeConsent.HARD_MEMORY_STATEMENT);
        }
        return kinds.isEmpty() ? Optional.empty() : Optional.of(new NativeConsentOffer(kinds, statements));
    }

    /**
     * Under the lock: the scope keeps the user's hard-memory consent only once a hard memory limit given with it reads
     * back in force, so a consent for a limit that was not set (refused, not sent, {@code OOMPolicy} not {@code
     * continue}) is not kept for later changes (native-API review of 2026-11-28).
     */
    private static void rememberHardConsent(
            Hold hold, boolean hardConsent, LimitSpec spec, Optional<EffectiveLimits> observed) {
        if (hardConsent
                && hard(spec)
                && observed.filter(limits ->
                                limits.memoryBytes().isPresent() && limits.memoryBoundary() == MemoryBoundary.HARD)
                        .isPresent()) {
            hold.hardMemory = true;
        }
    }

    private boolean hardGranted(ProcessIdentity target, Set<NativeConsentKind> granted) {
        return consent.hardMemory(target) || granted.contains(NativeConsentKind.HARD_MEMORY);
    }

    /** The outcome of a refusal for a consent not given, for the log. */
    private static ControlOutcome consentRefusal(NativeConsentOffer missing) {
        List<String> why = new ArrayList<>();
        if (missing.adoption()) {
            why.add("no consent to moving this process into a scope");
        }
        if (missing.hardMemory()) {
            why.add("a hard memory limit needs its own consent");
        }
        return fail(OperationResult.INVALID_ARGUMENT, String.join("; ", why));
    }

    private Optional<String> kindsUnavailable(LimitSpec spec) {
        if (spec.cpuPercent().isPresent() && !systemCapability(LimitKind.CPU).available()) {
            return systemCapability(LimitKind.CPU).reason();
        }
        if (spec.memoryBytes().isPresent()) {
            LimitCapability memory = systemCapability(LimitKind.MEMORY);
            if (!memory.available()) {
                return memory.reason();
            }
            if (hard(spec) && !readiness.orElseThrow().memoryHard().ready()) {
                return Optional.of(
                        String.join("; ", readiness.get().memoryHard().reasons()));
            }
        }
        return Optional.empty();
    }

    /** Steps A2 to A9; under the lock. */
    private ApplyResult adopt(
            ProcessIdentity target, LimitSpec spec, OwnershipRecorder ownership, boolean hardConsent) {
        ScopeManager manager = scopes.orElseThrow();
        CgroupFiles cgroups = files.orElseThrow();
        String unit = ScopeNames.of(target).orElse("");
        if (unit.isEmpty()) {
            return ApplyResult.notAssigned(
                    fail(OperationResult.INVALID_ARGUMENT, "no scope name for this identity"), false);
        }
        LinuxNative.Call opened = calls.pidfdOpen((int) target.pid());
        if (opened.failed()) {
            return opened.errno() == LinuxErrors.ESRCH
                    ? ApplyResult.notAssigned(fail(OperationResult.PROCESS_NOT_FOUND, "the process is gone"), false)
                    : ApplyResult.notAssigned(LinuxErrors.outcome("pidfd_open", opened.errno()), true);
        }
        int fd = (int) opened.value();
        boolean kept = false;
        try {
            // A2: the descriptor names whatever process had the PID when it was opened; the identity checked after it
            // proves that this is the process asked for (a later process with the PID started later).
            Optional<String> other = identityProblem(target);
            if (other.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.PROCESS_NOT_FOUND, other.get()), false);
            }
            Optional<ApplyResult> notAlive = notSignallable(target, fd);
            if (notAlive.isPresent()) {
                return notAlive.get();
            }
            // The user IDs again, then signal 0: the status read describes the live process the descriptor names.
            Optional<String> user = otherUser(target);
            if (user.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.ACCESS_DENIED, user.get()), false);
            }
            // Risk D2: the cgroup again, read before the last liveness and identity checks, so that they
            // prove the read was the target's. Before the open it could have described another process
            // with the PID. systemd's user manager falls back to PID 1's AttachProcessesToUnit by PID when
            // it lacks the privilege, so a process a privileged actor moved out of the user's own manager
            // must not be asked for. The window between these checks and the start request stays (D2).
            Optional<String> moved = cgroupProblem(target);
            if (moved.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.UNSUPPORTED, moved.get()), true);
            }
            notAlive = notSignallable(target, fd);
            if (notAlive.isPresent()) {
                return notAlive.get();
            }
            Optional<String> replaced = identityProblem(target);
            if (replaced.isPresent()) {
                return ApplyResult.notAssigned(fail(OperationResult.PROCESS_NOT_FOUND, replaced.get()), false);
            }
            Hold hold = new Hold(unit, fd);
            holds.put(target, hold);
            kept = true;
            ScopeManager.Start start = manager.startScope(unit, fd, description(target));
            switch (start.status()) {
                case STARTED -> {
                    // A5 follows.
                }
                case UNKNOWN -> {
                    LOG.warn("Creating the scope {} got no answer: {}", unit, start.detail());
                    return new ApplyResult(
                            ApplyResult.Phase.UNCERTAIN,
                            fail(
                                    OperationResult.NATIVE_ERROR,
                                    "whether the scope was created is unknown: " + start.detail()),
                            Optional.empty(),
                            false);
                }
                case PIDFDS_REFUSED -> {
                    adoptionRefused = Optional.of(
                            "this systemd user manager does not accept a process by pidfd; PRISM never moves one by PID");
                    return refusedStart(target, OperationResult.UNSUPPORTED, start, true);
                }
                case ACCESS_DENIED -> {
                    return refusedStart(target, OperationResult.ACCESS_DENIED, start, true);
                }
                case UNIT_EXISTS -> {
                    return refusedStart(target, OperationResult.UNSUPPORTED, start, false);
                }
                default -> {
                    // A process that exited meanwhile (the manager answers UnixProcessIdUnknown) or may no longer be
                    // signalled is said as such by refusedStart.
                    return refusedStart(target, OperationResult.NATIVE_ERROR, start, true);
                }
            }
            // A5: the start job, bounded; then the unit, the membership and the identity.
            SystemdUserBus.Reading<SystemdUserBus.UnitFacts> facts = awaitActive(manager, unit);
            if (facts.status() == SystemdUserBus.Status.NO_SUCH_UNIT) {
                holds.remove(target);
                kept = false;
                Liveness now = liveness(target, fd);
                if (identityProblem(target).isPresent() || now == Liveness.EXITED) {
                    return new ApplyResult(
                            ApplyResult.Phase.ASSIGNED_RELEASED,
                            fail(OperationResult.PROCESS_NOT_FOUND, "the process exited while it was being moved"),
                            Optional.of(EffectiveLimits.NONE),
                            false);
                }
                if (now == Liveness.NOT_SIGNALLABLE) {
                    // Risk D5: said as before the start, with no emulated fallback, which could not signal it either.
                    return ApplyResult.notAssigned(fail(OperationResult.ACCESS_DENIED, NOT_SIGNALLABLE), false);
                }
                // The unit does not exist, so the process is in no scope of PRISM's: the system shows nothing happened.
                return ApplyResult.notAssigned(
                        fail(OperationResult.NATIVE_ERROR, "the scope's start failed: " + facts.detail()), true);
            }
            Optional<ScopeProvenance> proven = facts.value().flatMap(f -> membership(target, fd, unit, f, cgroups));
            if (proven.isEmpty()) {
                if (liveness(target, fd) == Liveness.EXITED) {
                    // Exited after the move: nothing was limited yet; a scope with only its children is cleared by
                    // forget.
                    return released(target, hold, "the process exited while it was being moved");
                }
                return new ApplyResult(
                        ApplyResult.Phase.UNCERTAIN,
                        fail(
                                OperationResult.NATIVE_ERROR,
                                "the process could not be shown to be in its scope: " + facts.detail()),
                        Optional.empty(),
                        false);
            }
            hold.provenance = proven;
            hold.set(NativeHold.State.ASSIGNED, Optional.of(EffectiveLimits.NONE));
            // A6: the ownership evidence is on disk before any limit is set; without it none is ever set here.
            if (!ownership.recordDurably(proven.get())) {
                return new ApplyResult(
                        ApplyResult.Phase.ASSIGNED_MISMATCH,
                        fail(OperationResult.NATIVE_ERROR, "the scope could not be recorded; no limit was set"),
                        Optional.of(EffectiveLimits.NONE),
                        false);
            }
            hold.recorded = true;
            // A7 and A8.
            Sent sent = new Sent(proven.get().invocationId().orElseThrow());
            setKinds(manager, unit, Optional.of(EffectiveLimits.NONE), spec.effective(), hardConsent, sent);
            Optional<EffectiveLimits> observed =
                    settledReadBack(cgroups, proven.get().cgroupPath().orElseThrow(), spec.effective());
            rememberHardConsent(hold, hardConsent, spec, observed);
            Optional<Outside> left = outsideItsScope(target, hold);
            if (sent.gone || (left.isPresent() && left.get().kind() != Outside.Kind.EXITED)) {
                // Moved out, or the unit changed, while the limits were set: they are not known to be the target's
                // (TOCTOU, see change()). An exit is not this: the scope is still PRISM's and holds what was set.
                hold.set(NativeHold.State.UNCERTAIN, Optional.empty());
                return new ApplyResult(
                        ApplyResult.Phase.UNCERTAIN,
                        fail(
                                OperationResult.NATIVE_ERROR,
                                left.map(Outside::why).orElse("the scope PRISM made is gone")
                                        + " while its limits were set"),
                        Optional.empty(),
                        false);
            }
            if (observed.isPresent() && observed.get().equals(spec.effective())) {
                hold.inForce(observed);
                return new ApplyResult(ApplyResult.Phase.ENFORCED, ControlOutcome.success(), observed, false);
            }
            if (observed.isEmpty() || mayStillLand(sent, observed)) {
                // Unreadable, or a value systemd took may still be written: what the scope holds is not known (A8).
                hold.set(NativeHold.State.UNCERTAIN, Optional.empty());
                return new ApplyResult(
                        ApplyResult.Phase.UNCERTAIN,
                        fail(
                                OperationResult.NATIVE_ERROR,
                                sent.why(
                                        observed.isEmpty()
                                                ? "the limits could not be read back"
                                                : "a limit systemd accepted is not shown yet")),
                        Optional.empty(),
                        false);
            }
            hold.inForce(observed);
            return new ApplyResult(
                    ApplyResult.Phase.ASSIGNED_MISMATCH,
                    fail(OperationResult.NATIVE_ERROR, sent.why("the scope holds other limits than asked")),
                    observed,
                    false);
        } finally {
            if (!kept) {
                close(fd);
            }
        }
    }

    /**
     * A refusal by the manager is a reply: it created nothing and moved nothing. A process that has exited meanwhile is
     * said as gone; one that may no longer be signalled is said as before the start (risk D5): access denied, with no
     * emulated fallback, whose stops would be refused with EPERM too (kill(2); only SIGCONT within the session is
     * exempt).
     */
    private ApplyResult refusedStart(
            ProcessIdentity target, OperationResult result, ScopeManager.Start start, boolean fallback) {
        Hold hold = holds.remove(target);
        Liveness now = Liveness.RUNS;
        if (hold != null) {
            try {
                now = liveness(target, hold.fd);
            } finally {
                close(hold.fd);
            }
            if (now == Liveness.NOT_SIGNALLABLE) {
                // The manager's own answer is kept for the log; the user is told what was said before the start.
                LOG.info(
                        "The scope {} was not created ({}); the process may no longer be signalled",
                        hold.unit,
                        start.detail());
            }
        }
        String why = "the scope was not created: " + start.detail();
        return switch (now) {
            case EXITED -> ApplyResult.notAssigned(fail(OperationResult.PROCESS_NOT_FOUND, why), false);
            case NOT_SIGNALLABLE ->
                ApplyResult.notAssigned(fail(OperationResult.ACCESS_DENIED, NOT_SIGNALLABLE), false);
            case RUNS -> ApplyResult.notAssigned(fail(result, why), fallback);
        };
    }

    private ApplyResult released(ProcessIdentity target, Hold hold, String why) {
        holds.remove(target);
        close(hold.fd);
        return new ApplyResult(
                ApplyResult.Phase.ASSIGNED_RELEASED,
                fail(OperationResult.PROCESS_NOT_FOUND, why),
                Optional.of(EffectiveLimits.NONE),
                false);
    }

    private static String description(ProcessIdentity target) {
        return "PRISM limit for PID " + target.pid();
    }

    /** Waits, at most {@link #START_BOUND}, for the unit to be active, gone or failed. */
    private SystemdUserBus.Reading<SystemdUserBus.UnitFacts> awaitActive(ScopeManager manager, String unit) {
        long deadline = nanoTime.getAsLong() + START_BOUND.toNanos();
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> facts = manager.unit(unit);
        while (true) {
            if (facts.status() == SystemdUserBus.Status.NO_SUCH_UNIT) {
                return facts;
            }
            if (facts.value().isPresent()) {
                String state = facts.value().get().activeState();
                // A new scope reads inactive/dead with no cgroup until its start job runs (the manager runs jobs at
                // idle priority, after bus requests): only an active unit with a cgroup, a failed one or none ends the
                // wait. The fields are read one by one, so an active unit without a cgroup is read again.
                if (state.equals("active") && facts.value().get().controlGroup().isPresent()) {
                    return facts;
                }
                if (state.equals("failed")) {
                    return SystemdUserBus.Reading.not(SystemdUserBus.Status.NO_SUCH_UNIT, "the scope failed");
                }
            }
            if (nanoTime.getAsLong() >= deadline) {
                return facts.value().isPresent()
                        ? SystemdUserBus.Reading.not(
                                SystemdUserBus.Status.TIMEOUT,
                                "the scope is still " + facts.value().get().activeState() + " after " + START_BOUND)
                        : facts;
            }
            try {
                pause.sleep(POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return SystemdUserBus.Reading.not(
                        SystemdUserBus.Status.TIMEOUT, "interrupted while waiting for the scope");
            }
            facts = manager.unit(unit);
        }
    }

    /**
     * The ownership evidence, when the process is shown to be exactly in the unit's cgroup: alive by its descriptor,
     * the same identity, its cgroup the unit's, and listed in the unit's {@code cgroup.procs}.
     */
    private Optional<ScopeProvenance> membership(
            ProcessIdentity target, int fd, String unit, SystemdUserBus.UnitFacts facts, CgroupFiles cgroups) {
        if (facts.controlGroup().isEmpty() || facts.invocationId().isEmpty()) {
            return Optional.empty();
        }
        String group = facts.controlGroup().get();
        if (!group.startsWith(managerGroup + "/") || liveness(target, fd) != Liveness.RUNS) {
            return Optional.empty();
        }
        if (identityProblem(target).isPresent()) {
            return Optional.empty();
        }
        ProcFs.Reading<String> path = cgroups.processCgroup((int) target.pid());
        ProcFs.Reading<List<Integer>> members = cgroups.procs(group);
        if (!path.value().filter(group::equals).isPresent()
                || !members.value()
                        .filter(list -> list.contains((int) target.pid()))
                        .isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new ScopeProvenance(unit, facts.invocationId(), Optional.of(group)));
    }

    // ----- Limits -----

    /**
     * Sets, one property per call, every kind in which {@code to} differs from what is known to be in force ({@code from}
     * empty: unknown, so every kind). Problems are collected; nothing is retried.
     */
    private void setKinds(
            ScopeManager manager,
            String unit,
            Optional<EffectiveLimits> from,
            EffectiveLimits to,
            boolean hardConsent,
            Sent sent) {
        if (from.isEmpty() || !from.get().cpuRate().equals(to.cpuRate())) {
            long value = to.cpuRate().isPresent() ? quota(to.cpuRate().getAsInt()) : ScopeManager.INFINITY;
            set(manager, unit, CPU_QUOTA, value, sent);
        }
        long high = to.memoryBytes().isPresent() && to.memoryBoundary() == MemoryBoundary.THROTTLE
                ? to.memoryBytes().getAsLong()
                : ScopeManager.INFINITY;
        long max = to.memoryBytes().isPresent() && to.memoryBoundary() == MemoryBoundary.HARD
                ? to.memoryBytes().getAsLong()
                : ScopeManager.INFINITY;
        long oldHigh = from.map(f -> f.memoryBytes().isPresent() && f.memoryBoundary() == MemoryBoundary.THROTTLE
                        ? f.memoryBytes().getAsLong()
                        : ScopeManager.INFINITY)
                .orElse(Long.MIN_VALUE);
        long oldMax = from.map(f -> f.memoryBytes().isPresent() && f.memoryBoundary() == MemoryBoundary.HARD
                        ? f.memoryBytes().getAsLong()
                        : ScopeManager.INFINITY)
                .orElse(Long.MIN_VALUE);
        // Loosen first, tighten after: the scope never holds both a throttle and a hard limit of PRISM's at once.
        if (high == ScopeManager.INFINITY && oldHigh != high) {
            set(manager, unit, MEMORY_HIGH, high, sent);
        }
        if (max == ScopeManager.INFINITY && oldMax != max) {
            set(manager, unit, MEMORY_MAX, max, sent);
        }
        if (high != ScopeManager.INFINITY && oldHigh != high) {
            set(manager, unit, MEMORY_HIGH, high, sent);
        }
        if (max != ScopeManager.INFINITY && oldMax != max) {
            SystemdUserBus.Reading<String> policyRead = manager.scopeProperty(unit, "OOMPolicy");
            if (!policyRead.value().filter("continue"::equals).isPresent()) {
                sent.problems.add("the hard memory limit was not set: OOMPolicy is not continue ("
                        + policyRead.value().orElse(policyRead.detail()) + ")");
            } else if (!hardConsent) {
                sent.problems.add("the hard memory limit was not set: no consent");
            } else {
                set(manager, unit, MEMORY_MAX, max, sent);
            }
        }
    }

    private void set(ScopeManager manager, String unit, String property, long value, Sent sent) {
        if (past(sent.deadline)) {
            sent.problems.add(property + " not sent: the release's bound has passed");
            return;
        }
        ScopeManager.LimitSet done = manager.setLimit(unit, sent.invocation, property, value);
        if (done.status() == ScopeManager.SetStatus.NO_SUCH_UNIT) {
            sent.gone = true;
        }
        if (done.status() == ScopeManager.SetStatus.SET || done.status() == ScopeManager.SetStatus.UNKNOWN) {
            sent.landing.put(property, value);
        }
        if (done.status() != ScopeManager.SetStatus.SET) {
            sent.problems.add(
                    property + " " + done.status().name().toLowerCase(java.util.Locale.ROOT) + ": " + done.detail());
        }
    }

    /**
     * Whether a value systemd accepted, or a call that got no answer, is not what the read-back shows: systemd may still
     * write it, so the scope's limits are not known (an unanswered call is never taken as "nothing was done").
     */
    private boolean mayStillLand(Sent sent, Optional<EffectiveLimits> observed) {
        if (sent.landing.isEmpty()) {
            return false;
        }
        if (observed.isEmpty()) {
            return true;
        }
        EffectiveLimits now = observed.get();
        for (Map.Entry<String, Long> asked : sent.landing.entrySet()) {
            long shown = switch (asked.getKey()) {
                case CPU_QUOTA ->
                    now.cpuRate().isPresent() ? quota(now.cpuRate().getAsInt()) : ScopeManager.INFINITY;
                case MEMORY_HIGH ->
                    now.memoryBytes().isPresent() && now.memoryBoundary() == MemoryBoundary.THROTTLE
                            ? now.memoryBytes().getAsLong()
                            : ScopeManager.INFINITY;
                default ->
                    now.memoryBytes().isPresent() && now.memoryBoundary() == MemoryBoundary.HARD
                            ? now.memoryBytes().getAsLong()
                            : ScopeManager.INFINITY;
            };
            if (shown != asked.getValue()) {
                return true;
            }
        }
        return false;
    }

    /** {@code CPUQuotaPerSecUSec} for a rate in hundredths of a percent of the whole machine (a whole percentage). */
    private long quota(int rate) {
        if (processors.getAsInt() < 1) {
            throw new IllegalStateException("the number of processors is not known");
        }
        int percent = rate / 100;
        return Math.multiplyExact(Math.multiplyExact((long) percent, processors.getAsInt()), 10_000L);
    }

    /**
     * Reads the scope's limits until they show {@code expected}, at most {@link #SETTLE_BOUND}; the last reading
     * otherwise (which the caller reports as a mismatch, or empty when the files could not be read).
     */
    private Optional<EffectiveLimits> settledReadBack(CgroupFiles cgroups, String group, EffectiveLimits expected) {
        return settledReadBack(cgroups, group, expected, SETTLE_BOUND.toNanos());
    }

    private Optional<EffectiveLimits> settledReadBack(
            CgroupFiles cgroups, String group, EffectiveLimits expected, long boundNanos) {
        long deadline = nanoTime.getAsLong() + boundNanos;
        Optional<EffectiveLimits> observed = readBack(cgroups, group);
        while (!observed.equals(Optional.of(expected)) && nanoTime.getAsLong() < deadline) {
            try {
                pause.sleep(POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return observed;
            }
            observed = readBack(cgroups, group);
        }
        return observed;
    }

    /**
     * What the scope's cgroup holds, in PRISM's terms; empty when a file cannot be read. A controller that is not
     * enabled for the cgroup (absent from its {@code cgroup.controllers}) has no interface files there and sets no limit
     * at that level, so it reads as "no limit of that kind" (cgroup v2: a controller's files exist only where it is
     * enabled). systemd enables the cpu controller for a unit only while it has a CPU property set.
     */
    private Optional<EffectiveLimits> readBack(CgroupFiles cgroups, String group) {
        ProcFs.Reading<java.util.Set<String>> enabled = cgroups.controllers(group);
        if (enabled.value().isEmpty()) {
            return Optional.empty();
        }
        boolean cpuEnabled = enabled.value().get().contains("cpu");
        boolean memoryEnabled = enabled.value().get().contains("memory");
        ProcFs.Reading<CgroupV2.CpuMax> cpu = cpuEnabled
                ? cgroups.cpuMax(group)
                : ProcFs.Reading.found(new CgroupV2.CpuMax(OptionalLong.empty(), CpuQuota.PERIOD_USEC));
        ProcFs.Reading<OptionalLong> high =
                memoryEnabled ? cgroups.memoryHigh(group) : ProcFs.Reading.found(OptionalLong.empty());
        ProcFs.Reading<OptionalLong> max =
                memoryEnabled ? cgroups.memoryMax(group) : ProcFs.Reading.found(OptionalLong.empty());
        if (cpu.value().isEmpty() || high.value().isEmpty() || max.value().isEmpty()) {
            return Optional.empty();
        }
        List<String> foreign = new ArrayList<>();
        CpuQuota.Share share = CpuQuota.share(cpu.value().get(), processors.getAsInt());
        OptionalInt rate = OptionalInt.empty();
        if (share.kind() == CpuQuota.Kind.EXACT) {
            rate = OptionalInt.of(share.rate());
        } else if (share.kind() == CpuQuota.Kind.FOREIGN) {
            foreign.add("a CPU quota PRISM does not set");
        }
        OptionalLong memoryHigh = high.value().get();
        OptionalLong memoryMax = max.value().get();
        OptionalLong bytes = OptionalLong.empty();
        MemoryBoundary boundary = MemoryBoundary.HARD;
        if (memoryHigh.isPresent() && memoryMax.isPresent()) {
            foreign.add("both memory.high and memory.max");
        } else if (memoryHigh.isPresent()) {
            bytes = memoryHigh;
            boundary = MemoryBoundary.THROTTLE;
        } else if (memoryMax.isPresent()) {
            bytes = memoryMax;
        }
        return Optional.of(new EffectiveLimits(
                rate, bytes, foreign.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", foreign)), boundary));
    }

    @Override
    public ChangeResult change(ProcessIdentity target, LimitSpec spec) {
        return change(target, spec, Set.of());
    }

    /**
     * A change of the scope the limiter holds. It needs no adoption consent, as the process was adopted; a hard memory
     * limit the scope has not had needs the hard-memory consent, from the limiter's {@link NativeConsent} or {@code
     * granted}, and is otherwise answered {@link ChangeResult.Phase#CONSENT_REQUIRED} with nothing sent. A process that
     * is no longer the user's gets no new or tighter limit; its scope's limits can still be removed (risk D4).
     */
    @Override
    public ChangeResult change(ProcessIdentity target, LimitSpec spec, Set<NativeConsentKind> granted) {
        Objects.requireNonNull(granted, "granted");
        lock.lock();
        try {
            Hold hold = holds.get(target);
            if (hold == null) {
                return new ChangeResult(
                        ChangeResult.Phase.NO_HOLD,
                        fail(OperationResult.INVALID_ARGUMENT, "PRISM holds no scope for this process"),
                        Optional.empty());
            }
            if (closed) {
                return notDone(OperationResult.NATIVE_ERROR, "PRISM is closing");
            }
            if (hold.provenance.isEmpty()) {
                return notDone(OperationResult.NATIVE_ERROR, "whether the process is in its scope is unknown");
            }
            if (!hold.recorded) {
                return notDone(
                        OperationResult.NATIVE_ERROR,
                        "the scope's ownership could not be recorded, so no limit is set in it");
            }
            // Only an exit ends here; a process that may no longer be signalled is caught by the proof below.
            if (liveness(target, hold.fd) == Liveness.EXITED) {
                return notDone(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
            }
            Optional<String> system = kindsUnavailable(spec);
            if (system.isPresent()) {
                return notDone(OperationResult.UNSUPPORTED, system.get());
            }
            Optional<NativeConsentOffer> missing = missingConsent(target, spec, granted, false, hold.hardMemory);
            if (missing.isPresent()) {
                return ChangeResult.consentRequired(consentRefusal(missing.get()), hold.inForce(), missing.get());
            }
            boolean hardConsent = hard(spec) && (hold.hardMemory || hardGranted(target, granted));
            // Ownership and membership now, immediately before anything is sent: the unit is still the invocation PRISM
            // made and the process is still in it. Otherwise nothing is sent (independent audit follow-up).
            Optional<Outside> outside = outsideItsScope(target, hold);
            // Risk D4: a process that is no longer the user's (EPERM on signal 0, or other user IDs) gets no new or
            // tighter limit, but its scope's limits can still be cleared: the scope was just shown to be the invocation
            // PRISM made with its recorded cgroup, the proof every clearing in the session uses (clearScope; ADR-0016,
            // "clearing in the session compares the recorded cgroup as well as the invocation id"), the writes go to
            // that invocation and set every kind to infinity, and no signal is needed for them.
            boolean clearingOnly = outside.isPresent()
                    && outside.get().kind() == Outside.Kind.NOT_THE_USERS
                    && spec.effective().none();
            if (outside.isPresent() && !clearingOnly) {
                switch (outside.get().kind()) {
                    case EXITED -> {
                        return notDone(
                                OperationResult.PROCESS_NOT_FOUND, outside.get().why());
                    }
                    case UNREADABLE -> {
                        // Nothing is known to have changed: the hold keeps its state and what it last read.
                        return notDone(
                                OperationResult.NATIVE_ERROR, outside.get().why() + "; nothing was changed");
                    }
                    default -> {
                        hold.set(NativeHold.State.UNCERTAIN, Optional.empty());
                        return notDone(
                                OperationResult.NATIVE_ERROR, outside.get().why() + "; nothing was changed");
                    }
                }
            }
            String group = hold.provenance.get().cgroupPath().orElseThrow();
            // A clearing sends every kind: what is in force may have changed after the last read-back.
            Optional<EffectiveLimits> before = clearingOnly ? Optional.empty() : hold.inForce();
            Sent sent = new Sent(hold.provenance.get().invocationId().orElseThrow());
            setKinds(scopes.orElseThrow(), hold.unit, before, spec.effective(), hardConsent, sent);
            Optional<EffectiveLimits> observed = settledReadBack(files.orElseThrow(), group, spec.effective());
            rememberHardConsent(hold, hardConsent, spec, observed);
            Optional<Outside> movedDuring = outsideItsScope(target, hold);
            // EXITED and NOT_THE_USERS are found only once the unit is again the invocation PRISM made with its cgroup.
            boolean stillOurs = movedDuring.isEmpty()
                    || movedDuring.get().kind() == Outside.Kind.EXITED
                    || (clearingOnly && movedDuring.get().kind() == Outside.Kind.NOT_THE_USERS);
            if (sent.gone || !stillOurs) {
                // Left, or the unit changed, between the check and the sends (a window systemd offers no way to close):
                // what was set is not known to be the target's. A failed read leaves the state as it was.
                // A clearing's process was already found no longer the user's: its hold is uncertain whatever this
                // read.
                boolean notOurs = sent.gone || clearingOnly || movedDuring.get().kind() != Outside.Kind.UNREADABLE;
                hold.set(notOurs ? NativeHold.State.UNCERTAIN : hold.view.state(), Optional.empty());
                return new ChangeResult(
                        ChangeResult.Phase.UNVERIFIED,
                        fail(
                                OperationResult.NATIVE_ERROR,
                                movedDuring.map(Outside::why).orElse("the scope PRISM made is gone")
                                        + " while its limits were changed"),
                        Optional.empty());
            }
            // Proven again: the scope is the invocation PRISM made, with the target in it (or the target has exited).
            // After
            // a clearing of a process that is no longer the user's, only the scope is proven, not that the process is
            // in
            // it, so the hold stays uncertain: it gets no further limit, and the release at shutdown still clears it.
            NativeHold.State state = clearingOnly ? NativeHold.State.UNCERTAIN : NativeHold.State.ASSIGNED;
            if (observed.isPresent() && observed.get().equals(spec.effective())) {
                hold.set(state, observed);
                return new ChangeResult(ChangeResult.Phase.APPLIED, ControlOutcome.success(), observed);
            }
            if (observed.isEmpty() || mayStillLand(sent, observed)) {
                // Unknown until read again: the next change sends every kind.
                hold.set(state, Optional.empty());
                return new ChangeResult(
                        ChangeResult.Phase.UNVERIFIED,
                        fail(
                                OperationResult.NATIVE_ERROR,
                                sent.why(
                                        observed.isEmpty()
                                                ? "the limits could not be read back"
                                                : "a limit systemd accepted is not shown yet")),
                        Optional.empty());
            }
            hold.set(state, observed);
            String why = sent.why("the scope holds other limits than asked");
            if (before.isPresent() && observed.equals(before)) {
                return new ChangeResult(ChangeResult.Phase.NOT_DONE, fail(OperationResult.NATIVE_ERROR, why), observed);
            }
            return new ChangeResult(ChangeResult.Phase.PARTIAL, fail(OperationResult.NATIVE_ERROR, why), observed);
        } finally {
            lock.unlock();
        }
    }

    private static ChangeResult notDone(OperationResult result, String why) {
        return new ChangeResult(ChangeResult.Phase.NOT_DONE, fail(result, why), Optional.empty());
    }

    @Override
    public ForgetResult forget(ProcessIdentity target) {
        lock.lock();
        try {
            Hold hold = holds.get(target);
            if (hold == null) {
                return new ForgetResult(ForgetResult.Outcome.NOT_HELD, ControlOutcome.success(), 0, Optional.empty());
            }
            if (liveness(target, hold.fd) != Liveness.EXITED) {
                return new ForgetResult(
                        ForgetResult.Outcome.STILL_RUNS,
                        fail(OperationResult.INVALID_ARGUMENT, "the process still runs"),
                        -1,
                        hold.inForce());
            }
            Optional<ForgetResult> done = clearScope(hold);
            if (done.isPresent() && done.get().released()) {
                holds.remove(target);
                close(hold.fd);
            }
            return done.orElseGet(() -> new ForgetResult(
                    ForgetResult.Outcome.UNRESOLVED,
                    fail(OperationResult.NATIVE_ERROR, "the scope could not be read"),
                    -1,
                    hold.inForce()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the limits of a scope this session created, for the processes left in it: every kind set to infinity and
     * read back. Empty when the scope could not be read at all.
     */
    private Optional<ForgetResult> clearScope(Hold hold) {
        // Without a bound of its own (forget); every call in it is still bounded.
        return clearScope(hold, OptionalLong.empty());
    }

    /**
     * {@link #clearScope(Hold)}, sending nothing more once {@code deadline} ({@link #nanoTime}) has passed. Empty exactly
     * when nothing was sent: the scope could not be read, or the deadline passed before the first send.
     */
    private Optional<ForgetResult> clearScope(Hold hold, OptionalLong deadline) {
        ScopeManager manager = scopes.orElseThrow();
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> facts = manager.unit(hold.unit);
        if (facts.status() == SystemdUserBus.Status.NO_SUCH_UNIT) {
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE, ControlOutcome.success(), 0, Optional.empty()));
        }
        if (facts.value().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> invocation = hold.provenance.flatMap(ScopeProvenance::invocationId);
        if (invocation.isEmpty()) {
            // Never proven PRISM's (no answer at the start, or the membership not shown): no limit was ever set in it
            // (limits follow A6 only), and a unit with that name is not changed.
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE,
                    ControlOutcome.success("no limit was set in it"),
                    0,
                    Optional.empty()));
        }
        if (!facts.value().get().invocationId().equals(invocation)
                || !facts.value().get().controlGroup().equals(hold.provenance.flatMap(ScopeProvenance::cgroupPath))) {
            // Another unit has the name now: it is not the scope PRISM made, and nothing is changed in it.
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE,
                    ControlOutcome.success("the scope PRISM made is gone"),
                    0,
                    Optional.empty()));
        }
        Optional<String> group = facts.value().get().controlGroup();
        if (group.isEmpty()) {
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE, ControlOutcome.success(), 0, Optional.empty()));
        }
        CgroupFiles cgroups = files.orElseThrow();
        ProcFs.Reading<List<Integer>> members = cgroups.procs(group.get());
        if (members.value().isEmpty()) {
            return Optional.empty();
        }
        int survivors = members.value().get().size();
        if (survivors == 0) {
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE, ControlOutcome.success(), 0, Optional.empty()));
        }
        if (past(deadline)) {
            return Optional.empty();
        }
        Sent sent = new Sent(invocation.get(), deadline);
        // Clearing only loosens: no hard limit is ever set here. Every kind is sent: what is in force may have changed
        // after the last read-back.
        setKinds(manager, hold.unit, Optional.empty(), EffectiveLimits.NONE, false, sent);
        if (sent.gone) {
            // The invocation went before the sends reached it; a unit that took the name since has the same cgroup
            // path, so its files are not read back as PRISM's.
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE,
                    ControlOutcome.success("the scope PRISM made is gone"),
                    0,
                    Optional.empty()));
        }
        Optional<EffectiveLimits> left = settledReadBack(
                cgroups,
                group.get(),
                EffectiveLimits.NONE,
                deadline.isEmpty()
                        ? SETTLE_BOUND.toNanos()
                        : Math.min(SETTLE_BOUND.toNanos(), Math.max(0, deadline.getAsLong() - nanoTime.getAsLong())));
        hold.inForce(left);
        if (left.isEmpty()) {
            return Optional.of(new ForgetResult(
                    ForgetResult.Outcome.UNRESOLVED,
                    fail(OperationResult.NATIVE_ERROR, "the limits could not be read back"),
                    survivors,
                    Optional.empty()));
        }
        if (left.get().none()) {
            return Optional.of(
                    new ForgetResult(ForgetResult.Outcome.RELEASED, ControlOutcome.success(), survivors, left));
        }
        return Optional.of(new ForgetResult(
                ForgetResult.Outcome.RESIDUE,
                fail(OperationResult.NATIVE_ERROR, sent.why("limits are left")),
                survivors,
                left));
    }

    // ----- Recovery -----

    @Override
    public RecoveredLimit recover(LimitRecord record) {
        lock.lock();
        try {
            return classify(record);
        } finally {
            lock.unlock();
        }
    }

    /** The R table of plan Rev 2.2 §4.3; reads only. Under the lock. */
    private RecoveredLimit classify(LimitRecord record) {
        if (scopes.isEmpty() || files.isEmpty()) {
            return new RecoveredLimit(
                    record, RecoveredLimit.Verdict.FAILED, "the systemd user manager cannot be reached");
        }
        ProcessIdentity identity = record.identity();
        Optional<String> unitName =
                record.provenance().map(ScopeProvenance::unit).or(() -> ScopeNames.of(identity));
        if (unitName.isEmpty()) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.FAILED, "the record names no scope");
        }
        // Risk D11: a record proves only the scope PRISM names for its process. A record naming another unit, however
        // it came to be, is not asked about.
        if (!unitName.equals(ScopeNames.of(identity))) {
            return new RecoveredLimit(
                    record,
                    RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                    "the record names a scope PRISM does not give this process: " + unitName.get());
        }
        boolean alive = identityProblem(identity).isEmpty();
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> facts = scopes.get().unit(unitName.get());
        if (facts.status() == SystemdUserBus.Status.NO_SUCH_UNIT) {
            // R2, R3, R10.
            return alive
                    ? new RecoveredLimit(
                            record, RecoveredLimit.Verdict.NOT_IN_JOB, "its scope is gone; the process is not limited")
                    : new RecoveredLimit(record, RecoveredLimit.Verdict.GONE, "the process and its scope are gone");
        }
        if (facts.value().isEmpty()) {
            // R16.
            return new RecoveredLimit(record, RecoveredLimit.Verdict.FAILED, facts.detail());
        }
        SystemdUserBus.UnitFacts unit = facts.value().get();
        Optional<String> recorded = record.provenance().flatMap(ScopeProvenance::invocationId);
        if (recorded.isEmpty() || !unit.invocationId().equals(recorded)) {
            // R7, R9: a scope of that name exists, but nothing proves it is the one PRISM made.
            return new RecoveredLimit(
                    record,
                    RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                    recorded.isEmpty()
                            ? "a scope of its name exists, but its invocation id was never recorded"
                            : "a scope of its name exists with another invocation id");
        }
        Optional<String> group = unit.controlGroup();
        if (group.isEmpty()) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.GONE, "its scope has no processes left");
        }
        // Risk D11: the live invocation must have the cgroup the record names, which is also where a clearing reads its
        // limits back. Ownership evidence always records both (membership), so a record without a cgroup proves
        // nothing.
        Optional<String> recordedGroup = record.provenance().flatMap(ScopeProvenance::cgroupPath);
        if (!group.equals(recordedGroup)) {
            return new RecoveredLimit(
                    record,
                    RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                    recordedGroup.isEmpty()
                            ? "its scope's cgroup was never recorded"
                            : "its scope's cgroup is not the recorded one");
        }
        ProcFs.Reading<List<Integer>> members = files.get().procs(group.get());
        if (members.value().isEmpty()) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.FAILED, members.detail());
        }
        List<Integer> pids = members.value().get();
        if (pids.isEmpty()) {
            // R6.
            return new RecoveredLimit(record, RecoveredLimit.Verdict.GONE, "its scope has no processes left");
        }
        boolean inside = alive
                && pids.contains((int) identity.pid())
                && files.get()
                        .processCgroup((int) identity.pid())
                        .value()
                        .filter(group.get()::equals)
                        .isPresent();
        if (inside) {
            if (record.state() == LedgerState.CLEARED) {
                // R12.
                return new RecoveredLimit(record, RecoveredLimit.Verdict.NOTHING_TO_CLEAR, "its limits were cleared");
            }
            // R4, R11.
            return new RecoveredLimit(
                    record, RecoveredLimit.Verdict.OWNED_LIMITED, "its scope is PRISM's and holds it");
        }
        if (!alive && pids.contains((int) identity.pid())) {
            // R8: the PID is in the scope, but it is another process now.
            return new RecoveredLimit(
                    record, RecoveredLimit.Verdict.FOREIGN_MEMBER, "another process with its PID is in the scope");
        }
        if (alive) {
            // It runs elsewhere while its scope holds other processes: neither its membership nor its exit is shown, so
            // ownership is not proven (plan Rev 2.2 §4.1: E3, or the target gone with survivors). Listed and blocked.
            return new RecoveredLimit(
                    record,
                    RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                    "the process left its scope; " + pids.size() + " other process(es) remain in it");
        }
        // R5.
        return new RecoveredLimit(
                record,
                RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY,
                "the process has exited; " + pids.size() + " process(es) it started remain in its scope");
    }

    @Override
    public boolean recoveryClears() {
        return !admitsNone();
    }

    @Override
    public ClearResult clearOwned(LimitRecord record) {
        lock.lock();
        try {
            if (admitsNone() || closed) {
                return ClearResult.unsupported(closed ? "PRISM is closing" : ADMITS_NONE);
            }
            RecoveredLimit now = classify(record);
            if (!now.ownershipProven()) {
                return new ClearResult(
                        ClearResult.Outcome.NOT_OWNED,
                        fail(OperationResult.ACCESS_DENIED, "ownership is not proven now: " + now.detail()),
                        Optional.empty());
            }
            ScopeProvenance scope = record.provenance().orElseThrow();
            String group = scope.cgroupPath().orElse("");
            if (group.isEmpty()) {
                return new ClearResult(
                        ClearResult.Outcome.NOT_OWNED,
                        fail(OperationResult.ACCESS_DENIED, "the record has no cgroup path"),
                        Optional.empty());
            }
            ScopeManager manager = scopes.orElseThrow();
            List<String> problems = new ArrayList<>();
            boolean anySet = false;
            for (String property : List.of(CPU_QUOTA, MEMORY_HIGH, MEMORY_MAX)) {
                ScopeManager.LimitSet done = manager.setLimit(
                        scope.unit(), scope.invocationId().orElseThrow(), property, ScopeManager.INFINITY);
                if (done.status() == ScopeManager.SetStatus.NO_SUCH_UNIT) {
                    return new ClearResult(
                            ClearResult.Outcome.NOT_DONE,
                            fail(OperationResult.PROCESS_NOT_FOUND, "the scope is gone"),
                            Optional.empty());
                }
                anySet |= done.status() != ScopeManager.SetStatus.REFUSED;
                if (done.status() != ScopeManager.SetStatus.SET) {
                    problems.add(property + ": " + done.detail());
                }
            }
            Optional<EffectiveLimits> left = settledReadBack(files.orElseThrow(), group, EffectiveLimits.NONE);
            if (left.isEmpty()) {
                return anySet
                        ? new ClearResult(
                                ClearResult.Outcome.UNVERIFIED,
                                fail(OperationResult.NATIVE_ERROR, "the limits could not be read back"),
                                Optional.empty())
                        : new ClearResult(
                                ClearResult.Outcome.NOT_DONE,
                                fail(OperationResult.NATIVE_ERROR, String.join("; ", problems)),
                                Optional.empty());
            }
            if (left.get().none()) {
                return new ClearResult(ClearResult.Outcome.CLEARED, ControlOutcome.success(), left);
            }
            return new ClearResult(
                    anySet ? ClearResult.Outcome.PARTIAL : ClearResult.Outcome.NOT_DONE,
                    fail(
                            OperationResult.NATIVE_ERROR,
                            problems.isEmpty() ? "limits are left" : String.join("; ", problems)),
                    left);
        } finally {
            lock.unlock();
        }
    }

    // ----- Membership, shutdown -----

    @Override
    public Optional<String> scopeBarrier(ProcessIdentity target) {
        if (holds.containsKey(target)) {
            return Optional.empty();
        }
        Optional<ProcFs.Reading<String>> read = unifiedCgroup(target);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        ProcFs.Reading<String> path = read.get();
        if (path.status() == ProcFs.Status.MISSING) {
            return Optional.empty();
        }
        if (path.value().isEmpty()) {
            return Optional.of("its cgroup could not be read (" + path.detail() + "); no limit is started");
        }
        return ScopeNames.enclosingPrismScope(path.value().get())
                .map(scope -> "it runs in " + scope + ", a scope a PRISM session created; no second limiter is started"
                        + " on it");
    }

    /**
     * Owner decision 2 of 2026-11-28: a process in a user manager's {@code session.slice} (the compositor, the session
     * bus broker and the other services of the desktop session) gets no emulated limit and is offered none, because
     * stopping it freezes the desktop; native limits refuse it already (the desktop policy). Decided from the same read
     * of {@code /proc/<pid>/cgroup} as the scope-membership rule ({@link #scopeBarrier}), under any policy and whether or
     * not native limits are ready, without the bus. A cgroup that cannot be read is not a session service here: this
     * rule never refuses on a failed read, and the scope-membership rule refuses such a process as before (risk F1).
     */
    @Override
    public Optional<String> sessionBarrier(ProcessIdentity target) {
        return unifiedCgroup(target).flatMap(ProcFs.Reading::value).flatMap(LinuxNativeReadiness::sessionService);
    }

    /**
     * The process's unified-hierarchy cgroup for the barriers: through the mount where it is known, else from {@code
     * /proc/<pid>/cgroup} alone, which the rules need. Empty when the process is listed with no unified line: a system
     * without that hierarchy, where no PRISM scope and no user manager's slice can exist.
     */
    private Optional<ProcFs.Reading<String>> unifiedCgroup(ProcessIdentity target) {
        if (files.isPresent()) {
            return Optional.of(files.get().processCgroup((int) target.pid()));
        }
        ProcFs.Reading<String> text = proc.cgroupText((int) target.pid());
        if (text.value().filter(found -> !CgroupV2.hasUnifiedLine(found)).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(
                text.value().isEmpty()
                        ? text
                        : CgroupV2.unifiedPath(text.value().get())
                                .map(ProcFs.Reading::found)
                                .orElseGet(() -> ProcFs.Reading.not(
                                        ProcFs.Status.FAILED, "/proc/" + target.pid() + "/cgroup is malformed")));
    }

    @Override
    public boolean mayHaveLeftLimits() {
        return !admitsNone();
    }

    /**
     * Clears every scope this session holds and lets go of the descriptor of every hold it reaches, within
     * {@code within} end to end: the work runs on its own thread ({@code srm-linux-release}) and the caller waits no
     * longer than the bound. The work sends no SetProperties once the bound has passed (each send checks it); a
     * hold it did not reach is reported in {@link ReleaseResult#notReached} with {@link ReleaseResult#timedOut}, and
     * keeps its descriptor, so that a later release can still clear it (risk D13); so does a hold whose clearing sent
     * nothing, because its scope could not be read. A bus call in flight at the bound
     * ends by its own call timeout. Nothing is ever stopped or killed: an unreached scope keeps its limits until its
     * processes exit or a later release clears them.
     */
    @Override
    public ReleaseResult releaseAll(Duration within) {
        closed = true;
        long bound = nanosOf(within);
        long deadline = nanoTime.getAsLong() + bound;
        java.util.Set<ProcessIdentity> known = java.util.Set.copyOf(holds.keySet());
        if (known.isEmpty() && lock.tryLock()) {
            // Nothing held and nothing in flight (a limiter that adopted nothing; it can still start one if another
            // call holds the lock at that moment, which then finds nothing): no release thread is started.
            try {
                if (holds.isEmpty()) {
                    return ReleaseResult.NOTHING;
                }
            } finally {
                lock.unlock();
            }
        }
        Map<ProcessIdentity, Boolean> cleared = new ConcurrentHashMap<>();
        CompletableFuture<ReleaseResult> done = new CompletableFuture<>();
        Thread worker = new Thread(
                () -> {
                    try {
                        done.complete(releaseUnderLock(deadline, cleared));
                    } catch (RuntimeException failed) {
                        LOG.warn("Releasing the Linux scopes ended unexpectedly", failed);
                        done.completeExceptionally(failed);
                    }
                },
                "srm-linux-release");
        worker.setDaemon(true);
        worker.start();
        try {
            return done.get(bound, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unfinished(known, cleared);
        } catch (TimeoutException | ExecutionException late) {
            return unfinished(known, cleared);
        }
    }

    /** A bound in nanoseconds; a duration too long for that is as good as no bound. */
    private static long nanosOf(Duration within) {
        try {
            return Math.max(0, within.toNanos());
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE / 4;
        }
    }

    /**
     * What is known when the release did not finish: every hold known when it began that is not reported cleared is
     * not reached (the worker may have let go of it meanwhile, but not cleared it).
     */
    private static ReleaseResult unfinished(
            java.util.Set<ProcessIdentity> known, Map<ProcessIdentity, Boolean> cleared) {
        Map<ProcessIdentity, Boolean> reached = Map.copyOf(cleared);
        java.util.Set<ProcessIdentity> left = new java.util.HashSet<>(known);
        left.removeAll(reached.keySet());
        return new ReleaseResult(reached, left, true);
    }

    private ReleaseResult releaseUnderLock(long deadline, Map<ProcessIdentity, Boolean> cleared) {
        boolean locked;
        try {
            // Risk D13: only what is left until the deadline, not the whole bound again, as this thread starts after it
            // was set; a lock taken late finds the deadline passed and leaves every hold as it is (below).
            locked = lock.tryLock(Math.max(0, deadline - nanoTime.getAsLong()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            // An operation in flight owns the lock and may still use these descriptors: they stay until it is done.
            return ReleaseResult.timedOut(java.util.Set.copyOf(holds.keySet()));
        }
        try {
            if (holds.isEmpty()) {
                return ReleaseResult.NOTHING;
            }
            OptionalLong until = OptionalLong.of(deadline);
            java.util.Set<ProcessIdentity> notReached = new java.util.HashSet<>();
            for (Map.Entry<ProcessIdentity, Hold> entry : new ArrayList<>(holds.entrySet())) {
                Hold hold = entry.getValue();
                if (past(until)) {
                    // Not reached (risk D13): the hold and its descriptor stay, so that a later release can still
                    // clear the scope; letting go of it here would leave its limits beyond any release.
                    notReached.add(entry.getKey());
                    continue;
                }
                Optional<ForgetResult> result;
                try {
                    result = clearScope(hold, until);
                } catch (RuntimeException failed) {
                    LOG.warn("Clearing the scope {} ended unexpectedly; its limits may remain", hold.unit, failed);
                    cleared.put(entry.getKey(), false);
                    letGo(entry.getKey(), hold);
                    continue;
                }
                if (result.isEmpty()) {
                    // Nothing was sent (review C3 of risk D13): the deadline passed before the first send, or the
                    // scope could not be read. As a hold not reached, it keeps its descriptor for a later release.
                    if (past(until)) {
                        notReached.add(entry.getKey());
                    } else {
                        cleared.put(entry.getKey(), false);
                    }
                    continue;
                }
                cleared.put(entry.getKey(), result.get().released());
                // A hold the release cleared, or tried to, goes either way: a scope is not changed by closing its
                // descriptor.
                letGo(entry.getKey(), hold);
            }
            return new ReleaseResult(cleared, notReached, !notReached.isEmpty() || past(until));
        } finally {
            lock.unlock();
        }
    }

    private void letGo(ProcessIdentity target, Hold hold) {
        close(hold.fd);
        holds.remove(target);
    }

    private boolean past(OptionalLong deadline) {
        return deadline.isPresent() && nanoTime.getAsLong() - deadline.getAsLong() >= 0;
    }

    private void close(int fd) {
        if (fd >= 0) {
            LinuxNative.Call done = calls.close(fd);
            if (done.failed()) {
                LOG.warn("Closing pidfd {} failed: errno {}", fd, done.errno());
            }
        }
    }

    private static ControlOutcome fail(OperationResult result, String detail) {
        return ControlOutcome.of(result, detail);
    }

    /** For tests: the descriptors the limiter holds. */
    List<Integer> heldDescriptors() {
        lock.lock();
        try {
            return holds.values().stream().map(h -> h.fd).toList();
        } finally {
            lock.unlock();
        }
    }
}
