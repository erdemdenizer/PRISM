package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.MemoryBoundary;
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

/**
 * The Linux scope limiter against a fake systemd and fake proc/cgroup files (milestone 12, S3B/S4, plan Rev 2.2 T-D7 to
 * T-D14, T-D20, T-D23, T-D24): eligibility and consent before any call, pidfd-only adoption with identity checks, the
 * ownership evidence recorded before any limit, per-kind limits read back, unknown answers kept as uncertain, recovery
 * that only classifies, clearing only with proof, and the scope-membership rule.
 */
class LinuxScopeLimiterTest {

    private static final String MANAGER = FakeScopeManager.MANAGER;
    private static final int PID = 2071;
    private static final long TICKS = 5_000;
    private static final ProcessIdentity DEMO = new ProcessIdentity(PID, FakeProc.start(TICKS));
    private static final String UNIT =
            "prism-srm-limit-" + PID + "-" + DEMO.startTime().toEpochMilli() + ".scope";
    private static final LimitSpec CPU20_THROTTLE256 =
            new LimitSpec(OptionalInt.of(20), OptionalLong.of(256L << 20), MemoryBoundary.THROTTLE);

    @TempDir
    Path temp;

    private FakeProc proc;
    private FakeLinux linux;
    private CgroupFiles files;
    private FakeScopeManager systemd;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final long[] clock = {0};
    private LinuxScopeLimiter limiter;

    @BeforeEach
    void setUp() {
        proc = new FakeProc(temp.resolve("proc"));
        linux = new FakeLinux(proc);
        Path cgroups = temp.resolve("cgroup");
        Path procCgroups = temp.resolve("proc-cgroup");
        files = new CgroupFiles(cgroups, procCgroups);
        systemd = new FakeScopeManager(linux, cgroups, procCgroups, 4, events);
        proc.process(PID, "demo", TICKS);
        systemd.placeProcess(PID, MANAGER + "/app.slice/app-demo-1.scope");
        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of());
    }

    @AfterEach
    void noDescriptorLeaks() {
        assertThat(linux.doubleCloses()).as("no descriptor closed twice").isZero();
        Set<Integer> held = Set.copyOf(limiter.heldDescriptors());
        assertThat(linux.openFds())
                .as("every open descriptor belongs to a hold")
                .isEqualTo(held);
    }

    private static LinuxNativeReadiness.SystemReadiness ready() {
        return LinuxNativeReadiness.classify(new LinuxNativeReadiness.Facts(
                Optional.of("/sys/fs/cgroup"),
                Optional.of(false),
                Optional.of("255.4-1ubuntu8.18"),
                Optional.of(Set.of("cpu", "memory", "pids")),
                Optional.of(true)));
    }

    private LinuxScopeLimiter harness(
            Set<ProcessIdentity> admitted, Set<ProcessIdentity> adoption, Set<ProcessIdentity> hardMemory) {
        return new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(admitted),
                NativeConsent.harness(adoption, hardMemory),
                linux,
                new ProcFs(proc.root),
                () -> FakeProc.BOOT_TIME_SECONDS,
                Optional.of(files),
                Optional.of(systemd),
                MANAGER,
                1000,
                Optional.of(ready()),
                List.of(),
                () -> 4,
                duration -> {
                    clock[0] += duration.toNanos();
                    systemd.settle();
                },
                () -> clock[0]));
    }

    private ApplyResult apply(LimitSpec spec) {
        return limiter.apply(DEMO, spec, provenance -> {
            events.add("record " + provenance.unit() + " "
                    + provenance.invocationId().orElse("-"));
            return true;
        });
    }

    private String group() {
        return MANAGER + "/app.slice/" + UNIT;
    }

    // ----- K16, eligibility, consent -----

    @Test
    void theProductLimiterAdmitsNothingAndNeverTouchesTheSystem() {
        LinuxScopeLimiter product = new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.none(),
                NativeConsent.none(),
                linux,
                new ProcFs(proc.root),
                () -> FakeProc.BOOT_TIME_SECONDS,
                Optional.of(files),
                Optional.empty(),
                "",
                -1,
                Optional.empty(),
                List.of(),
                () -> 4,
                duration -> {},
                () -> 0));

        assertThat(product.capabilities().values())
                .allSatisfy(c -> assertThat(c.reason()).contains(LinuxScopeLimiter.PRODUCT_REASON));
        assertThat(product.capabilitiesFor(DEMO).get(LimitKind.CPU).reason()).contains(LinuxScopeLimiter.NOT_VALIDATED);
        ApplyResult refused = product.apply(DEMO, CPU20_THROTTLE256, p -> true);
        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(refused.fallbackEligible()).isTrue();
        assertThat(product.mayHaveLeftLimits()).isFalse();
        assertThat(product.recoveryClears()).isFalse();
        assertThat(product.clearOwned(LimitRecord.intended(DEMO, "demo", EffectiveLimits.NONE, "1-1"))
                        .result())
                .isEqualTo(ClearResult.Outcome.UNSUPPORTED);
        assertThat(linux.calls).as("no system call at all").isEmpty();
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void aProcessNotNamedByTheHarnessIsRefusedBeforeAnyCall() {
        limiter = harness(Set.of(), Set.of(), Set.of());
        assertThat(limiter.apply(DEMO, CPU20_THROTTLE256, p -> true)
                        .outcome()
                        .detail()
                        .orElseThrow())
                .contains(LinuxScopeLimiter.NOT_VALIDATED);
        assertThat(linux.calls).isEmpty();
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void withoutConsentNothingIsAsked() {
        limiter = harness(Set.of(DEMO), Set.of(), Set.of());
        ApplyResult noAdoption = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);
        assertThat(noAdoption.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(noAdoption.fallbackEligible()).isFalse();

        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of());
        ApplyResult noHard = limiter.apply(
                DEMO, new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.HARD), p -> true);
        assertThat(noHard.outcome().detail().orElseThrow()).contains("hard memory limit needs its own consent");
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void aProcessOutsideTheManagerOrInAPrismScopeIsNotEligible() {
        systemd.placeProcess(PID, "/user.slice/user-1000.slice/session-2.scope");
        assertThat(limiter.capabilitiesFor(DEMO).get(LimitKind.CPU).reason().orElseThrow())
                .contains("outside your session manager");

        systemd.placeProcess(PID, MANAGER + "/app.slice/prism-srm-limit-2071-1.scope");
        assertThat(limiter.apply(DEMO, CPU20_THROTTLE256, p -> true)
                        .outcome()
                        .detail()
                        .orElseThrow())
                .contains("scope with Prism's name");
        assertThat(systemd.calls).isEmpty();
    }

    // ----- Adoption -----

    @Test
    void anAdoptionMovesByPidfdRecordsOwnershipFirstAndReadsTheLimitsBack() {
        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(result.observed()).contains(CPU20_THROTTLE256.effective());
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("80000 100000");
        assertThat(systemd.read(group(), "memory.high")).isEqualTo(String.valueOf(256L << 20));
        assertThat(systemd.read(group(), "memory.max")).isEqualTo("max");
        int record = events.indexOf(
                events.stream().filter(e -> e.startsWith("record ")).findFirst().orElseThrow());
        int firstSet = events.indexOf(
                events.stream().filter(e -> e.startsWith("set ")).findFirst().orElseThrow());
        assertThat(record).as("ownership is on disk before any limit").isLessThan(firstSet);
        assertThat(events.stream().filter(e -> e.startsWith("start ")).count()).isEqualTo(1);
        assertThat(limiter.hold(DEMO)).contains(new NativeHold(NativeHold.State.ASSIGNED, result.observed()));
        assertThat(linux.calls).anyMatch(c -> c.equals("pidfd_open " + PID));
        assertThat(limiter.heldDescriptors()).hasSize(1);
    }

    @Test
    void aPidReusedBeforeOrAfterTheDescriptorIsNeverMoved() {
        // Reused before: the identity no longer matches, nothing is opened.
        proc.remove(PID);
        proc.process(PID, "other", TICKS + 700);
        assertThat(limiter.apply(DEMO, CPU20_THROTTLE256, p -> true)
                        .outcome()
                        .detail()
                        .orElseThrow())
                .contains("another process");
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));

        // Reused between the open and the identity check (T-D8): the descriptor names the newcomer; nothing is created.
        proc.remove(PID);
        proc.process(PID, "demo", TICKS);
        linux.afterOpen = () -> {
            proc.remove(PID);
            proc.process(PID, "newcomer", TICKS + 900);
        };
        ApplyResult raced = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);
        assertThat(raced.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(systemd.calls).noneMatch(c -> c.startsWith("start "));
        assertThat(systemd.units).isEmpty();
    }

    /** A process that does not run wholly as the user (set-user-ID, changed IDs) is not the user's to limit. */
    @Test
    void aProcessWithAnotherUserIdIsNotEligibleAndNothingIsAsked() {
        for (long[] ids :
                new long[][] {{1000, 0, 1000, 1000}, {1000, 1000, 0, 1000}, {0, 1000, 1000, 1000}, {1000, 1000, 1000, 0}
                }) {
            proc.uids(PID, ids[0], ids[1], ids[2], ids[3]);

            ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

            assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
            assertThat(refused.outcome().detail().orElseThrow()).contains("does not run wholly as your user");
            assertThat(limiter.capabilitiesFor(DEMO).values()).noneMatch(c -> c.available());
        }
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void anUnreadableStatusRefuses() throws java.io.IOException {
        java.nio.file.Files.delete(proc.root.resolve(Integer.toString(PID)).resolve("status"));

        ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(refused.outcome().detail().orElseThrow()).contains("user IDs could not be read");
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void aMalformedUidLineRefuses() throws java.io.IOException {
        for (String status :
                List.of("Name:\tdemo\nUid:\t1000\t1000\t1000\n", "Uid:\t1000\tx\t1000\t1000\n", "Name:\tdemo\n")) {
            java.nio.file.Files.writeString(
                    proc.root.resolve(Integer.toString(PID)).resolve("status"), status);

            ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

            assertThat(refused.outcome().detail().orElseThrow()).as(status).contains("user IDs could not be read");
        }
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    /** The IDs change between the eligibility check and the open (a set-user-ID exec keeps PID and start time). */
    @Test
    void userIdsThatChangeBeforeTheOpenAreSeenAfterIt() {
        linux.beforeOpen = () -> proc.uids(PID, 1000, 0, 0, 0);

        ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(refused.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(refused.outcome().detail().orElseThrow()).contains("does not run wholly as your user");
        assertThat(linux.calls).anyMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    /**
     * T-D8 on its own: the PID is reused after the eligibility check and before the open, so the descriptor names the
     * live newcomer and signal 0 succeeds; only the identity check after the open keeps it from being moved.
     */
    @Test
    void aNewcomerTheDescriptorNamesIsNeverMoved() {
        linux.beforeOpen = () -> {
            proc.remove(PID);
            proc.process(PID, "newcomer", TICKS + 900);
        };

        ApplyResult raced = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(raced.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(raced.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.calls).anyMatch(c -> c.startsWith("pidfd_open "));
        assertThat(systemd.calls).noneMatch(c -> c.startsWith("start "));
        assertThat(systemd.units).isEmpty();
        assertThat(files.processCgroup(PID).value()).contains(MANAGER + "/app.slice/app-demo-1.scope");
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    /** The process exits and is reaped after the open: the manager refuses the descriptor and creates nothing. */
    @Test
    void aProcessReapedAfterTheOpenIsRefusedByTheManagerAndNotFound() {
        systemd.beforeStart = () -> proc.remove(PID);

        ApplyResult gone = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(gone.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(gone.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(gone.fallbackEligible()).isFalse();
        assertThat(systemd.units).isEmpty();
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    @Test
    void aProcessGoneBeforeTheOpenIsNotFoundAndNothingIsAsked() {
        linux.beforeOpen = () -> proc.remove(PID);

        ApplyResult gone = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(gone.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(systemd.calls).isEmpty();
    }

    // ----- The start job (native review 1, test review S4) -----

    @Test
    void aStartJobStillQueuedIsAwaitedNotTakenAsFailed() {
        systemd.queuedPolls = 3;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).as(result.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(systemd.members(group())).containsExactly(PID);
    }

    @Test
    void aStartJobThatDoesNotRunInTimeIsUncertainAndKeepsItsDescriptor() {
        systemd.queuedPolls = 1_000;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).as(result.toString()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.UNCERTAIN);
        assertThat(limiter.heldDescriptors()).hasSize(1);
        assertThat(systemd.setCalls()).isZero();
        // Never proven Prism's (native review 3): after the process exits, forget sends nothing to a unit of that name.
        systemd.units.get(UNIT).queuedPolls = 0;
        systemd.exit(proc, PID);
        ForgetResult forgotten = limiter.forget(DEMO);
        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.NOTHING_TO_RELEASE);
        assertThat(systemd.setCalls()).isZero();
    }

    @Test
    void aDeadDescriptorMovesNobody() {
        proc.remove(PID);
        proc.process(PID, "demo", TICKS);
        // The process exits after the identity check and before the manager is asked.
        LinuxNative realOpen = linux;
        linux.afterOpen = () -> {};
        systemd.afterStart = () -> {};
        LinuxScopeLimiter dying = new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(Set.of(DEMO)),
                NativeConsent.harness(Set.of(DEMO), Set.of()),
                new LinuxNative() {
                    @Override
                    public Optional<String> pidfdUnreachable() {
                        return Optional.empty();
                    }

                    @Override
                    public Call pidfdOpen(int pid) {
                        return realOpen.pidfdOpen(pid);
                    }

                    private int signals;

                    @Override
                    public Call pidfdSendSignal(int fd, int signal) {
                        if (++signals == 3) {
                            // Two liveness checks come before the start (after the identity and after the user IDs);
                            // the third comes after it: exit just before that one.
                            proc.remove(PID);
                        }
                        return realOpen.pidfdSendSignal(fd, signal);
                    }

                    @Override
                    public Call close(int fd) {
                        return realOpen.close(fd);
                    }

                    @Override
                    public Call setPriority(int tid, int nice) {
                        return realOpen.setPriority(tid, nice);
                    }

                    @Override
                    public long clockTicksPerSecond() {
                        return realOpen.clockTicksPerSecond();
                    }

                    @Override
                    public int ownPid() {
                        return realOpen.ownPid();
                    }
                },
                new ProcFs(proc.root),
                () -> FakeProc.BOOT_TIME_SECONDS,
                Optional.of(files),
                Optional.of(systemd),
                MANAGER,
                1000,
                Optional.of(ready()),
                List.of(),
                () -> 4,
                duration -> clock[0] += duration.toNanos(),
                () -> clock[0]));
        limiter = dying;

        ApplyResult result = dying.apply(DEMO, CPU20_THROTTLE256, provenance -> {
            throw new AssertionError("nothing may be recorded for a process that exited");
        });

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_RELEASED);
        assertThat(systemd.setCalls()).as("no limit set").isZero();
        assertThat(dying.hold(DEMO)).isEmpty();
    }

    @Test
    void aManagerThatRefusesPidfdsMakesNativeLimitsUnavailableWithoutAPidFallback() {
        systemd.startAnswer = ScopeManager.StartStatus.PIDFDS_REFUSED;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.fallbackEligible()).isTrue();
        assertThat(systemd.calls).filteredOn(c -> c.startsWith("start ")).hasSize(1);
        assertThat(limiter.capabilities().get(LimitKind.CPU).reason().orElseThrow())
                .contains("never moves one by PID");
        assertThat(limiter.hold(DEMO)).isEmpty();
    }

    @Test
    void anUnansweredStartIsUncertainAndKeepsItsDescriptor() {
        systemd.startAnswer = ScopeManager.StartStatus.UNKNOWN;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(limiter.hold(DEMO)).contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
        assertThat(limiter.heldDescriptors()).hasSize(1);
        assertThat(apply(CPU20_THROTTLE256).outcome().detail().orElseThrow()).contains("already holds");
        assertThat(limiter.change(DEMO, LimitSpec.NONE).phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(systemd.setCalls()).isZero();
    }

    @Test
    void anExistingUnitOrARefusalCreatesAndKeepsNothing() {
        systemd.addUnit(UNIT, "0123456789abcdef0123456789abcdef");
        ApplyResult exists = apply(CPU20_THROTTLE256);
        assertThat(exists.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(exists.fallbackEligible()).isFalse();
        assertThat(systemd.setCalls()).isZero();

        systemd.units.clear();
        systemd.startAnswer = ScopeManager.StartStatus.ACCESS_DENIED;
        assertThat(apply(CPU20_THROTTLE256).outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(limiter.hold(DEMO)).isEmpty();
    }

    @Test
    void aScopeThatDoesNotBecomeActiveInTimeIsUncertain() {
        systemd.pollsUntilActive = 1_000;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(clock[0]).isGreaterThanOrEqualTo(LinuxScopeLimiter.START_BOUND.toNanos());
        assertThat(systemd.setCalls()).isZero();
    }

    @Test
    void aScopeThatDidNotTakeTheProcessIsUncertainNotAssumedEmpty() {
        systemd.moveOnStart = false;

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(events).noneMatch(e -> e.startsWith("record "));
    }

    @Test
    void ownershipThatCannotBeRecordedSetsNoLimit() {
        ApplyResult result = limiter.apply(DEMO, CPU20_THROTTLE256, provenance -> false);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);
        assertThat(result.observed()).contains(EffectiveLimits.NONE);
        assertThat(systemd.setCalls()).isZero();
    }

    @Test
    void withoutAPlaceToRecordOwnershipNothingIsAsked() {
        ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256);

        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(refused.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    @Test
    void theProcessorCountIsTheKernelsOnlineListNotTheJvmsShare() throws java.io.IOException {
        Path stat = temp.resolve("stat");
        java.nio.file.Files.write(
                stat,
                List.of(
                        "cpu  10 0 10 100 0 0 0 0 0 0",
                        "cpu0 5 0 5 50 0 0 0 0 0 0",
                        "cpu1 5 0 5 50 0 0 0 0 0 0",
                        "cpu3 5 0 5 50 0 0 0 0 0 0",
                        "intr 1 2 3",
                        "ctxt 100",
                        "btime 1790000000",
                        "processes 10",
                        "procs_running 1"));
        assertThat(LinuxScopeLimiter.onlineProcessors(stat))
                .as("cpu2 is offline")
                .isEqualTo(3);
        assertThat(LinuxScopeLimiter.onlineProcessors(temp.resolve("absent"))).isZero();
    }

    @Test
    void theReadBackWaitsBoundedlyForTheManagerToWriteTheCgroup() {
        // systemd answers SetUnitProperties before it writes the cgroup files.
        systemd.lagging = true;
        long before = clock[0];

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(result.observed()).contains(CPU20_THROTTLE256.effective());
        assertThat(clock[0] - before).isPositive().isLessThan(LinuxScopeLimiter.SETTLE_BOUND.toNanos());
    }

    @Test
    void anAcceptedValueThatNeverShowsIsUncertainAfterTheBound() {
        // systemd took the value and has not written it: it may still be written, so nothing is known (A8).
        systemd.lagging = true;
        systemd.setIgnored.add("CPUQuotaPerSecUSec");
        long before = clock[0];

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(result.observed()).isEmpty();
        assertThat(limiter.hold(DEMO).orElseThrow())
                .isEqualTo(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
        long waited = clock[0] - before;
        assertThat(waited).isGreaterThanOrEqualTo(LinuxScopeLimiter.SETTLE_BOUND.toNanos());
        assertThat(waited).isLessThan(LinuxScopeLimiter.SETTLE_BOUND.toNanos() + 2 * LinuxScopeLimiter.POLL.toNanos());
    }

    @Test
    void aControllerNotEnabledForTheScopeReadsAsNoLimitOfThatKind() {
        // systemd enables the cpu controller for a unit only while a CPU property is set: no cpu.max file then.
        systemd.afterStart = () -> {
            try {
                java.nio.file.Files.writeString(systemd.dir(group()).resolve("cgroup.controllers"), "memory pids\n");
                java.nio.file.Files.delete(systemd.dir(group()).resolve("cpu.max"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        LimitSpec throttleOnly =
                new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.THROTTLE);

        ApplyResult result = apply(throttleOnly);

        assertThat(result.phase()).as(result.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(result.observed()).contains(throttleOnly.effective());
    }

    @Test
    void aKindTheManagerRefusesIsAMismatchReadBackAsItIs() {
        systemd.setAnswer.put("MemoryHigh", ScopeManager.SetStatus.REFUSED);

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);
        assertThat(result.observed()).contains(EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty()));
        assertThat(result.outcome().detail().orElseThrow()).contains("MemoryHigh refused");
    }

    @Test
    void aHardMemoryLimitNeedsConsentAndOomPolicyContinue() {
        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of(DEMO));
        LimitSpec hard = new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.HARD);

        assertThat(apply(hard).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(systemd.read(group(), "memory.max")).isEqualTo(String.valueOf(256L << 20));
        assertThat(systemd.calls).anyMatch(c -> c.equals("property " + UNIT + " OOMPolicy"));
    }

    @Test
    void aHardLimitIsNotSetWhereOomPolicyIsNotContinueOrCannotBeRead() {
        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of(DEMO));
        LimitSpec hard = new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.HARD);
        systemd.afterStart = () -> systemd.units.get(UNIT).oomPolicy = "stop";

        ApplyResult stop = apply(hard);

        assertThat(stop.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);
        assertThat(stop.outcome().detail().orElseThrow()).contains("OOMPolicy");
        assertThat(systemd.calls).noneMatch(c -> c.startsWith("set " + UNIT + " MemoryMax " + (256L << 20)));
        assertThat(systemd.read(group(), "memory.max")).isEqualTo("max");

        // Through a change from a throttle, and with the policy unreadable.
        systemd.units.get(UNIT).oomPolicy = "unreadable";
        ChangeResult change = limiter.change(DEMO, hard);
        assertThat(change.phase()).isIn(ChangeResult.Phase.NOT_DONE, ChangeResult.Phase.PARTIAL);
        assertThat(change.outcome().detail().orElseThrow()).contains("OOMPolicy");
        assertThat(systemd.read(group(), "memory.max")).isEqualTo("max");
    }

    @Test
    void aChangeToAHardLimitNeedsItsConsent() {
        apply(CPU20_THROTTLE256);
        long sets = systemd.setCalls();

        ChangeResult change = limiter.change(
                DEMO, new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.HARD));

        assertThat(change.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().detail().orElseThrow()).contains("consent");
        assertThat(systemd.setCalls()).isEqualTo(sets);
    }

    /** Test review M3: without the ownership evidence on disk no limit is ever set in the scope, not by a change either. */
    @Test
    void aScopeWhoseOwnershipWasNotRecordedNeverGetsALimit() {
        ApplyResult unrecorded = limiter.apply(DEMO, CPU20_THROTTLE256, provenance -> false);
        assertThat(unrecorded.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256);

        assertThat(change.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().detail().orElseThrow()).contains("could not be recorded");
        assertThat(systemd.setCalls()).isZero();
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("max 100000");
    }

    // ----- Ownership at the moment of a change (independent audit follow-up) -----

    /**
     * The target was moved out of its scope by another program (systemd's AttachProcessesToUnit, a launcher) while a
     * process it started stays in the scope. A change must not set limits there and report them as the target's.
     */
    @Test
    void aChangeAfterTheTargetWasMovedOutSetsNothing() {
        apply(CPU20_THROTTLE256);
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(group(), PID, 3001);
        // Moved out: the target is in another scope now; its child stays.
        systemd.placeProcess(PID, MANAGER + "/app.slice/app-elsewhere.scope");
        systemd.writeMembers(group(), 3001);
        long sets = systemd.setCalls();

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(systemd.setCalls())
                .as("nothing sent to a scope the target is not in")
                .isEqualTo(sets);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("80000 100000");
        assertThat(limiter.hold(DEMO))
                .as("the limits are no longer said to be in force on the target")
                .contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
    }

    /**
     * The scope was collected after the target left it, and another program made a unit of the same name for another
     * process: same name, same cgroup path, another invocation id. A change must not touch it.
     */
    @Test
    void aChangeNeverReachesAUnitThatReusedTheName() {
        apply(CPU20_THROTTLE256);
        systemd.placeProcess(PID, MANAGER + "/app.slice/app-elsewhere.scope");
        systemd.units.remove(UNIT);
        proc.process(4000, "stranger", TICKS + 70);
        systemd.addUnit(UNIT, "fedcba9876543210fedcba9876543210", 4000);
        long sets = systemd.setCalls();

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(systemd.setCalls())
                .as("nothing sent to a unit that is not Prism's")
                .isEqualTo(sets);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("max 100000");
        assertThat(limiter.hold(DEMO))
                .as("the limits are no longer said to be in force on the target")
                .contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
    }

    /**
     * Only the invocation differs: a unit of Prism's name and cgroup path, with the target in it, but not the invocation
     * Prism made (the scope was collected and made again by someone else). The invocation comparison alone must stop the
     * change before anything is sent.
     */
    @Test
    void aChangeNeverReachesAnotherInvocationEvenWithTheTargetInIt() {
        apply(CPU20_THROTTLE256);
        systemd.units.remove(UNIT);
        systemd.addUnit(UNIT, "fedcba9876543210fedcba9876543210", PID);
        long sets = systemd.setCalls();

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().detail().orElseThrow()).contains("another unit has the scope's name now");
        assertThat(systemd.setCalls())
                .as("nothing sent to an invocation that is not Prism's")
                .isEqualTo(sets);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("max 100000");
        assertThat(limiter.hold(DEMO)).contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
    }

    /**
     * The time-of-check/time-of-use window systemd leaves open: the name is reused between the ownership check and the
     * send. The send is bound to the invocation Prism proved, so the new unit is never changed.
     */
    @Test
    void aNameReusedBetweenTheCheckAndTheSendIsNeverChanged() {
        apply(CPU20_THROTTLE256);
        proc.process(4000, "stranger", TICKS + 70);
        boolean[] replaced = {false};
        systemd.onSet = () -> {
            if (!replaced[0]) {
                replaced[0] = true;
                systemd.placeProcess(PID, MANAGER + "/app.slice/app-elsewhere.scope");
                systemd.units.remove(UNIT);
                systemd.addUnit(UNIT, "fedcba9876543210fedcba9876543210", 4000);
            }
        };

        String ours = systemd.units.get(UNIT).invocationId;
        int before = systemd.setInvocations.size();

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(systemd.setInvocations.subList(before, systemd.setInvocations.size()))
                .as("every send was addressed to Prism's invocation, never to the name")
                .isNotEmpty()
                .containsOnly(ours);
        assertThat(systemd.read(group(), "cpu.max"))
                .as("the stranger's scope is unchanged")
                .isEqualTo("max 100000");
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.UNCERTAIN);
    }

    /** The target is moved out after the check and before the read-back: said as unverified, never as applied. */
    @Test
    void aTargetMovedOutDuringAChangeIsUnverified() {
        apply(CPU20_THROTTLE256);
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(group(), PID, 3001);
        boolean[] moved = {false};
        systemd.onSet = () -> {
            if (!moved[0]) {
                moved[0] = true;
                systemd.placeProcess(PID, MANAGER + "/app.slice/app-elsewhere.scope");
                systemd.writeMembers(group(), 3001);
            }
        };

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(change.outcome().detail().orElseThrow()).contains("no longer in its scope");
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.UNCERTAIN);
    }

    /** Recovery: proven at classification, the name reused before the clearing's sends: the stranger is untouched. */
    @Test
    void aClearingNeverReachesAUnitThatTookTheNameAfterTheProof() {
        FakeScopeManager.Unit unit = systemd.addUnit(UNIT, INV, PID);
        systemd.writeLimits(unit.group, "80000 100000", "max", "max");
        proc.process(4000, "stranger", TICKS + 70);
        boolean[] replaced = {false};
        systemd.onSet = () -> {
            if (!replaced[0]) {
                replaced[0] = true;
                systemd.exit(proc, PID);
                systemd.units.remove(UNIT);
                FakeScopeManager.Unit stranger = systemd.addUnit(UNIT, "fedcba9876543210fedcba9876543210", 4000);
                systemd.writeLimits(stranger.group, "80000 100000", "max", "max");
            }
        };

        ClearResult cleared = limiter.clearOwned(record(INV));

        assertThat(cleared.result()).isEqualTo(ClearResult.Outcome.NOT_DONE);
        assertThat(cleared.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(systemd.read(unit.group, "cpu.max"))
                .as("the stranger's limit stays")
                .isEqualTo("80000 100000");
    }

    /** A read that fails during the check changes nothing and leaves the hold as it was; the next change proves again. */
    @Test
    void aFailedReadBeforeAChangeLeavesTheHoldAsItWas() {
        apply(CPU20_THROTTLE256);
        NativeHold before = limiter.hold(DEMO).orElseThrow();
        long sets = systemd.setCalls();
        systemd.unitReadFails = true;

        ChangeResult unread = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(unread.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(systemd.setCalls()).isEqualTo(sets);
        assertThat(limiter.hold(DEMO)).contains(before);
        systemd.unitReadFails = false;
        ChangeResult proven = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));
        assertThat(proven.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.ASSIGNED);
    }

    /** An uncertain hold that is proven again by a change is assigned again (the closure review's state finding). */
    @Test
    void anUncertainHoldProvenAgainByAChangeIsAssignedAgain() {
        systemd.lagging = true;
        systemd.setIgnored.add("CPUQuotaPerSecUSec");
        assertThat(apply(CPU20_THROTTLE256).phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        systemd.setIgnored.clear();
        systemd.lagging = false;

        ChangeResult proven = limiter.change(DEMO, CPU20_THROTTLE256);

        assertThat(proven.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(limiter.hold(DEMO))
                .contains(new NativeHold(NativeHold.State.ASSIGNED, Optional.of(CPU20_THROTTLE256.effective())));
    }

    /** An exit during a change is said as an exit, not as a native error. */
    @Test
    void anExitBeforeAChangeIsNotFound() {
        apply(CPU20_THROTTLE256);
        proc.remove(PID);

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    /**
     * After the adoption the process ran a set-user-ID program: its real user ID stays the user's, so signal 0 still
     * works, but its effective and saved IDs are root's. It is no longer the user's to limit: nothing is sent.
     */
    @Test
    void aChangeAfterTheProcessTookOtherUserIdsSendsNothing() {
        apply(CPU20_THROTTLE256);
        long sets = systemd.setCalls();
        proc.uids(PID, 1000, 0, 0, 0);

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().detail().orElseThrow()).contains("does not run wholly as your user");
        assertThat(systemd.setCalls())
                .as("nothing sent for a process that is not the user's")
                .isEqualTo(sets);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("80000 100000");
        assertThat(limiter.hold(DEMO)).contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
    }

    // ----- Signal 0 refused with EPERM: the process runs, but no longer as the user (final review, native F2) -----
    // pidfd_send_signal(2): ESRCH alone means the process has exited; EPERM means it runs and this caller may not
    // signal it (kill(2) rules: the target's real and saved set-user-IDs are no longer the user's, e.g. after
    // setuid(0)).

    @Test
    void aProcessThatMayNoLongerBeSignalledIsNotForgottenAsExited() {
        apply(CPU20_THROTTLE256);
        long sets = systemd.setCalls();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        ForgetResult forgotten = limiter.forget(DEMO);

        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.STILL_RUNS);
        assertThat(systemd.setCalls())
                .as("its limits are not cleared as if it had exited")
                .isEqualTo(sets);
        assertThat(limiter.hold(DEMO)).isPresent();
    }

    @Test
    void aChangeToAProcessThatMayNoLongerBeSignalledIsNotAnExitAndSendsNothing() {
        apply(CPU20_THROTTLE256);
        long sets = systemd.setCalls();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        ChangeResult change = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));

        assertThat(change.phase()).as(change.toString()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(change.outcome().result()).isNotEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(systemd.setCalls()).isEqualTo(sets);
        assertThat(limiter.hold(DEMO)).contains(new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
    }

    @Test
    void anAdoptionOfAProcessThatMayNotBeSignalledIsRefusedAsNotTheUsers() {
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        ApplyResult refused = limiter.apply(DEMO, CPU20_THROTTLE256, p -> true);

        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(refused.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(systemd.calls).noneMatch(c -> c.startsWith("start"));
        assertThat(linux.openFds()).isEmpty();
    }

    /** The invocation goes during a clearing and a unit takes the name: its files are not read back as Prism's. */
    @Test
    void aClearingWhoseInvocationWentReadsNothingBackAsPrisms() {
        apply(CPU20_THROTTLE256);
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(group(), PID, 3001);
        systemd.exit(proc, PID);
        proc.process(4000, "stranger", TICKS + 70);
        boolean[] replaced = {false};
        systemd.onSet = () -> {
            if (!replaced[0]) {
                replaced[0] = true;
                systemd.units.remove(UNIT);
                FakeScopeManager.Unit stranger = systemd.addUnit(UNIT, "fedcba9876543210fedcba9876543210", 4000);
                systemd.writeLimits(stranger.group, "max 100000", "max", "max");
            }
        };

        ForgetResult forgotten = limiter.forget(DEMO);

        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.NOTHING_TO_RELEASE);
        assertThat(forgotten.outcome().detail()).contains("the scope Prism made is gone");
    }

    /** Uncertain native operations keep their descriptor and are released by a later release, sending nothing unproven. */
    @Test
    void anUnansweredSetKeepsTheHoldUncertainAndTheReleaseLetsGoOfIt() {
        systemd.lagging = true;
        systemd.setAnswer.put("CPUQuotaPerSecUSec", ScopeManager.SetStatus.UNKNOWN);
        systemd.setIgnored.add("CPUQuotaPerSecUSec");

        ApplyResult result = apply(CPU20_THROTTLE256);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(limiter.heldDescriptors()).hasSize(1);
        systemd.setAnswer.clear();
        systemd.setIgnored.clear();
        ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(5));
        assertThat(released.timedOut()).isFalse();
        assertThat(released.cleared()).containsKey(DEMO);
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    /** CPU edges on the fake: 1 % and 100 % of the machine (4 processors). */
    @Test
    void theCpuEdgesAreExactQuotas() {
        ApplyResult lowest = apply(new LimitSpec(OptionalInt.of(1), OptionalLong.empty()));
        assertThat(lowest.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("4000 100000");

        ChangeResult whole = limiter.change(DEMO, new LimitSpec(OptionalInt.of(100), OptionalLong.empty()));
        assertThat(whole.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(whole.observed().orElseThrow().cpuRate()).hasValue(10_000);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("400000 100000");
    }

    /** A root-owned process in the session (all IDs 0) is never eligible. */
    @Test
    void aPrivilegedProcessIsNeverEligible() {
        proc.uids(PID, 0, 0, 0, 0);

        assertThat(limiter.capabilitiesFor(DEMO).values()).noneMatch(c -> c.available());
        assertThat(limiter.apply(DEMO, CPU20_THROTTLE256, p -> true).phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(linux.calls).noneMatch(c -> c.startsWith("pidfd_open"));
        assertThat(systemd.calls).isEmpty();
    }

    /**
     * Hard memory, OOM kill of the target (model): the kernel kills the target, the sibling it started stays in the
     * scope; the scope (OOMPolicy=continue) stays, keeps its evidence, keeps the sibling from a second limiter and is
     * cleared for it by forget.
     */
    @Test
    void anOomKillOfTheTargetLeavesTheSiblingInAProvenScope() {
        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of(DEMO));
        assertThat(apply(new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20), MemoryBoundary.HARD))
                        .phase())
                .isEqualTo(ApplyResult.Phase.ENFORCED);
        proc.process(3001, "sibling", TICKS + 50);
        systemd.writeMembers(group(), PID, 3001);
        systemd.placeProcess(3001, group());
        systemd.exit(proc, PID);

        ProcessIdentity sibling = new ProcessIdentity(3001, FakeProc.start(TICKS + 50));
        assertThat(limiter.scopeBarrier(sibling)).isPresent();
        assertThat(limiter.recover(record(systemd.units.get(UNIT).invocationId)).verdict())
                .isEqualTo(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY);
        ForgetResult forgotten = limiter.forget(DEMO);
        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.RELEASED);
        assertThat(systemd.read(group(), "memory.max")).isEqualTo("max");
        assertThat(systemd.units).containsKey(UNIT);
    }

    // ----- Change, forget -----

    @Test
    void aChangeSetsOnlyWhatDiffersAndLoosensBeforeItTightens() {
        limiter = harness(Set.of(DEMO), Set.of(DEMO), Set.of(DEMO));
        apply(CPU20_THROTTLE256);
        systemd.calls.clear();

        LimitSpec hard = new LimitSpec(OptionalInt.of(20), OptionalLong.of(512L << 20), MemoryBoundary.HARD);
        ChangeResult changed = limiter.change(DEMO, hard);

        assertThat(changed.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        List<String> sets =
                systemd.calls.stream().filter(c -> c.startsWith("set ")).toList();
        assertThat(sets)
                .containsExactly("set " + UNIT + " MemoryHigh infinity", "set " + UNIT + " MemoryMax " + (512L << 20));
        assertThat(systemd.read(group(), "memory.high")).isEqualTo("max");

        ChangeResult cleared = limiter.change(DEMO, LimitSpec.NONE);
        assertThat(cleared.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(cleared.observed()).contains(EffectiveLimits.NONE);
        assertThat(limiter.change(new ProcessIdentity(9, Instant.EPOCH), LimitSpec.NONE)
                        .phase())
                .isEqualTo(ChangeResult.Phase.NO_HOLD);
    }

    @Test
    void aChangeOfAnExitedProcessIsNotDoneAndAnUnreadableOneIsUnverified() {
        apply(CPU20_THROTTLE256);
        // A definite refusal: nothing was done.
        systemd.setAnswer.put("CPUQuotaPerSecUSec", ScopeManager.SetStatus.REFUSED);
        ChangeResult refused = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));
        assertThat(refused.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        systemd.setAnswer.clear();
        // Accepted but not shown, or not answered: it may still be written, so unknown (test review M5).
        systemd.setIgnored.add("CPUQuotaPerSecUSec");
        ChangeResult ignored = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));
        assertThat(ignored.phase()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(limiter.hold(DEMO).orElseThrow().inForce()).isEmpty();
        systemd.setIgnored.clear();
        systemd.setAnswer.put("CPUQuotaPerSecUSec", ScopeManager.SetStatus.UNKNOWN);
        systemd.setIgnored.add("CPUQuotaPerSecUSec");
        assertThat(limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)))
                        .phase())
                .isEqualTo(ChangeResult.Phase.UNVERIFIED);
        systemd.setAnswer.clear();
        systemd.setIgnored.clear();
        // Back to what the hold last read: every kind is sent again, since what is in force is unknown.
        long setsBefore = systemd.setCalls();
        assertThat(limiter.change(DEMO, CPU20_THROTTLE256).phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(systemd.setCalls() - setsBefore).isEqualTo(3);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("80000 100000");

        systemd.dir(group()).resolve("memory.max").toFile().delete();
        ChangeResult unreadable = limiter.change(DEMO, CPU20_THROTTLE256.withCpuPercent(OptionalInt.of(50)));
        assertThat(unreadable.phase()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(limiter.hold(DEMO).orElseThrow().inForce()).isEmpty();

        systemd.exit(proc, PID);
        assertThat(limiter.change(DEMO, LimitSpec.NONE).outcome().result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void forgetClearsTheScopeForTheProcessesLeftAndLetsGoOfTheDescriptor() {
        apply(CPU20_THROTTLE256);
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.STILL_RUNS);

        // The demo started a child inside its scope, then exited (F8, T-L6).
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(group(), PID, 3001);
        systemd.placeProcess(3001, group());
        systemd.exit(proc, PID);

        ForgetResult forgotten = limiter.forget(DEMO);

        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.RELEASED);
        assertThat(forgotten.survivors()).isEqualTo(1);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("max 100000");
        assertThat(systemd.read(group(), "memory.high")).isEqualTo("max");
        assertThat(limiter.hold(DEMO)).isEmpty();
        assertThat(systemd.calls).noneMatch(c -> c.contains("Stop") || c.contains("Kill") || c.contains("Abandon"));
    }

    @Test
    void forgetWithAnEmptyOrVanishedScopeHasNothingToRelease() {
        apply(CPU20_THROTTLE256);
        systemd.exit(proc, PID);
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.NOTHING_TO_RELEASE);
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.NOT_HELD);
    }

    // ----- Recovery (R table), clearing -----

    private LimitRecord record(String invocationId) {
        return LimitRecord.intended(DEMO, "demo", CPU20_THROTTLE256.effective(), "1-1")
                .applied(CPU20_THROTTLE256.effective())
                .withProvenance(new ScopeProvenance(UNIT, Optional.of(invocationId), Optional.of(group())));
    }

    private static final String INV = "0123456789abcdef0123456789abcdef";

    @Test
    void recoveryClassifiesEveryRowAndChangesNothing() {
        // R2: no unit, process gone.
        proc.remove(PID);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.GONE);
        // R3: no unit, process alive elsewhere.
        proc.process(PID, "demo", TICKS);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.NOT_IN_JOB);
        // R4: proven, holds the process.
        FakeScopeManager.Unit unit = systemd.addUnit(UNIT, INV, PID);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.OWNED_LIMITED);
        // R12: proven and recorded as cleared.
        assertThat(limiter.recover(record(INV).cleared()).verdict()).isEqualTo(RecoveredLimit.Verdict.NOTHING_TO_CLEAR);
        // R7: another invocation id.
        assertThat(limiter.recover(record("fedcba9876543210fedcba9876543210")).verdict())
                .isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE);
        // R9: no invocation id recorded.
        LimitRecord intended = LimitRecord.intended(DEMO, "demo", CPU20_THROTTLE256.effective(), "1-1");
        assertThat(limiter.recover(intended).verdict()).isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE);
        // R5: the process exited, a child it started remains.
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(unit.group, PID, 3001);
        systemd.exit(proc, PID);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY);
        // The recorded process runs outside its scope while others remain: neither membership nor exit is shown.
        proc.process(PID, "demo", TICKS);
        systemd.placeProcess(PID, MANAGER + "/app.slice/app-demo-1.scope");
        systemd.writeMembers(unit.group, 3001);
        RecoveredLimit outside = limiter.recover(record(INV));
        assertThat(outside.verdict()).isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE);
        assertThat(outside.ownershipProven()).isFalse();
        proc.remove(PID);
        // R8: the PID is in the scope again, as another process.
        proc.process(PID, "other", TICKS + 999);
        systemd.writeMembers(unit.group, PID, 3001);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.FOREIGN_MEMBER);
        // R6: empty scope.
        systemd.writeMembers(unit.group);
        proc.remove(PID);
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.GONE);
        // R16: the manager does not answer.
        systemd.unitReadFails = true;
        assertThat(limiter.recover(record(INV)).verdict()).isEqualTo(RecoveredLimit.Verdict.FAILED);

        assertThat(systemd.setCalls()).as("recovery only reads").isZero();
        assertThat(systemd.calls).noneMatch(c -> c.startsWith("start "));
    }

    @Test
    void clearingNeedsProofAtThatMomentAndIsReadBack() {
        FakeScopeManager.Unit unit = systemd.addUnit(UNIT, INV, PID);
        systemd.writeLimits(unit.group, "80000 100000", String.valueOf(256L << 20), "max");

        ClearResult unproven = limiter.clearOwned(record("fedcba9876543210fedcba9876543210"));
        assertThat(unproven.result()).isEqualTo(ClearResult.Outcome.NOT_OWNED);
        assertThat(systemd.setCalls()).isZero();

        ClearResult cleared = limiter.clearOwned(record(INV));
        assertThat(cleared.result()).isEqualTo(ClearResult.Outcome.CLEARED);
        assertThat(systemd.read(unit.group, "cpu.max")).isEqualTo("max 100000");
        assertThat(systemd.read(unit.group, "memory.high")).isEqualTo("max");

        systemd.writeLimits(unit.group, "80000 100000", "max", "max");
        systemd.setIgnored.add("CPUQuotaPerSecUSec");
        assertThat(limiter.clearOwned(record(INV)).result()).isEqualTo(ClearResult.Outcome.PARTIAL);
        assertThat(limiter.recoveryClears()).isTrue();
    }

    /** Test review S10: each way a clearing can go part of the way, and the proofs it needs at that moment. */
    @Test
    void aClearingThatGoesPartOfTheWayIsSaidAsSuch() throws java.io.IOException {
        FakeScopeManager.Unit unit = systemd.addUnit(UNIT, INV, PID);

        // Every property refused: nothing changed.
        systemd.writeLimits(unit.group, "80000 100000", String.valueOf(256L << 20), "max");
        for (String property : List.of("CPUQuotaPerSecUSec", "MemoryHigh", "MemoryMax")) {
            systemd.setAnswer.put(property, ScopeManager.SetStatus.REFUSED);
        }
        ClearResult refused = limiter.clearOwned(record(INV));
        assertThat(refused.result()).isEqualTo(ClearResult.Outcome.NOT_DONE);
        assertThat(systemd.read(unit.group, "cpu.max")).isEqualTo("80000 100000");
        systemd.setAnswer.clear();

        // The unit goes in the middle of the clearing.
        systemd.setAnswer.put("MemoryHigh", ScopeManager.SetStatus.NO_SUCH_UNIT);
        ClearResult vanished = limiter.clearOwned(record(INV));
        assertThat(vanished.result()).isEqualTo(ClearResult.Outcome.NOT_DONE);
        assertThat(vanished.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        systemd.setAnswer.clear();

        // Set, but nothing can be read back.
        systemd.writeLimits(unit.group, "80000 100000", String.valueOf(256L << 20), "max");
        java.nio.file.Files.delete(systemd.dir(unit.group).resolve("memory.max"));
        systemd.setIgnored.add("MemoryMax");
        assertThat(limiter.clearOwned(record(INV)).result()).isEqualTo(ClearResult.Outcome.UNVERIFIED);
        systemd.setIgnored.clear();
        systemd.writeLimits(unit.group, "max 100000", "max", "max");

        // A pending clear the crashed session left half done (R11/R18): still proven, cleared now.
        systemd.writeLimits(unit.group, "max 100000", String.valueOf(256L << 20), "max");
        LimitRecord pending = record(INV).changing(EffectiveLimits.NONE);
        assertThat(limiter.recover(pending).verdict()).isEqualTo(RecoveredLimit.Verdict.OWNED_LIMITED);
        assertThat(limiter.clearOwned(pending).result()).isEqualTo(ClearResult.Outcome.CLEARED);
        assertThat(systemd.read(unit.group, "memory.high")).isEqualTo("max");

        // The recorded process runs outside its scope while another remains: nothing is changed.
        systemd.writeLimits(unit.group, "80000 100000", "max", "max");
        proc.process(3001, "child", TICKS + 50);
        systemd.writeMembers(unit.group, 3001);
        systemd.placeProcess(PID, MANAGER + "/app.slice/app-demo-1.scope");
        long sets = systemd.setCalls();
        assertThat(limiter.clearOwned(record(INV)).result()).isEqualTo(ClearResult.Outcome.NOT_OWNED);
        assertThat(systemd.setCalls()).isEqualTo(sets);

        // The recorded process exited and its child remains (R5): proven, cleared.
        systemd.exit(proc, PID);
        systemd.writeMembers(unit.group, 3001);
        assertThat(limiter.clearOwned(record(INV)).result()).isEqualTo(ClearResult.Outcome.CLEARED);
        assertThat(systemd.read(unit.group, "cpu.max")).isEqualTo("max 100000");
    }

    // ----- Membership, shutdown, concurrency -----

    @Test
    void theScopeMembershipRuleBlocksEveryProcessInAPrismScopeExceptTheOneHeld() {
        proc.process(4100, "in-recordless-scope", TICKS);
        systemd.placeProcess(4100, MANAGER + "/app.slice/prism-srm-limit-4100-17.scope");
        proc.process(4200, "in-app-scope", TICKS);
        systemd.placeProcess(4200, MANAGER + "/app.slice/app-gnome-terminal-1.scope");
        ProcessIdentity recordless = new ProcessIdentity(4100, FakeProc.start(TICKS));
        ProcessIdentity ordinary = new ProcessIdentity(4200, FakeProc.start(TICKS));

        assertThat(limiter.scopeBarrier(recordless))
                .hasValueSatisfying(why -> assertThat(why).contains("prism-srm-limit-4100-17.scope"));
        assertThat(limiter.scopeBarrier(ordinary)).isEmpty();
        assertThat(limiter.scopeBarrier(new ProcessIdentity(4999, Instant.EPOCH)))
                .as("gone")
                .isEmpty();

        apply(CPU20_THROTTLE256);
        assertThat(limiter.scopeBarrier(DEMO))
                .as("the process this limiter holds")
                .isEmpty();
        proc.process(3001, "child", TICKS + 50);
        systemd.placeProcess(3001, group());
        assertThat(limiter.scopeBarrier(new ProcessIdentity(3001, FakeProc.start(TICKS + 50))))
                .isPresent();
    }

    /** ADR-0016 decision 8: the rule covers every process inside a Prism scope, not only one directly in it. */
    @Test
    void theScopeMembershipRuleCoversACgroupBelowAPrismScope() {
        proc.process(4100, "below-a-scope", TICKS);
        systemd.placeProcess(4100, MANAGER + "/app.slice/prism-srm-limit-4100-17.scope/worker");
        ProcessIdentity below = new ProcessIdentity(4100, FakeProc.start(TICKS));

        assertThat(limiter.scopeBarrier(below))
                .hasValueSatisfying(why -> assertThat(why).contains("prism-srm-limit-4100-17.scope"));
    }

    /**
     * The rule needs only {@code /proc/<pid>/cgroup}. A limiter that could not read the cgroup mount, as on a host with
     * an unreadable or ambiguous mountinfo, keeps it: ADR-0016 decision 8, "a failed read refuses".
     */
    @Test
    void theScopeMembershipRuleHoldsWithoutTheCgroupMount() throws IOException {
        LinuxScopeLimiter noMount = new LinuxScopeLimiter(new LinuxScopeLimiter.Parts(
                LinuxNativeReadiness.EligibilityPolicy.none(),
                NativeConsent.none(),
                linux,
                new ProcFs(proc.root),
                () -> FakeProc.BOOT_TIME_SECONDS,
                Optional.empty(),
                Optional.empty(),
                "",
                -1,
                Optional.empty(),
                List.of(),
                () -> 4,
                duration -> {},
                () -> 0));
        proc.process(4100, "in-a-scope", TICKS);
        Files.writeString(
                proc.root.resolve("4100/cgroup"), "0::" + MANAGER + "/app.slice/prism-srm-limit-4100-17.scope\n");
        proc.process(4200, "ordinary", TICKS);
        Files.writeString(proc.root.resolve("4200/cgroup"), "0::" + MANAGER + "/app.slice/app-1.scope\n");
        proc.process(4300, "no unified hierarchy", TICKS);
        Files.writeString(proc.root.resolve("4300/cgroup"), "4:cpu:/user.slice\n");
        proc.process(4400, "malformed", TICKS);
        Files.writeString(proc.root.resolve("4400/cgroup"), "0::/../outside\n");

        assertThat(noMount.scopeBarrier(new ProcessIdentity(4100, FakeProc.start(TICKS))))
                .hasValueSatisfying(why -> assertThat(why).contains("prism-srm-limit-4100-17.scope"));
        assertThat(noMount.scopeBarrier(new ProcessIdentity(4200, FakeProc.start(TICKS))))
                .isEmpty();
        assertThat(noMount.scopeBarrier(new ProcessIdentity(4300, FakeProc.start(TICKS))))
                .as("a system with no unified hierarchy has no scope of Prism's")
                .isEmpty();
        assertThat(noMount.scopeBarrier(new ProcessIdentity(4400, FakeProc.start(TICKS))))
                .as("a failed read refuses")
                .hasValueSatisfying(why -> assertThat(why).contains("could not be read"));
        assertThat(noMount.scopeBarrier(new ProcessIdentity(4999, Instant.EPOCH)))
                .as("gone")
                .isEmpty();
    }

    @Test
    void releaseAllClearsEveryScopeLetsGoOfEveryDescriptorAndRefusesAfterwards() {
        apply(CPU20_THROTTLE256);

        ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(1));

        assertThat(released.complete()).isTrue();
        assertThat(released.cleared()).containsEntry(DEMO, true);
        assertThat(systemd.read(group(), "cpu.max")).isEqualTo("max 100000");
        assertThat(linux.openFds()).isEmpty();
        assertThat(apply(CPU20_THROTTLE256).outcome().detail().orElseThrow()).contains("closing");
    }

    @Test
    void releaseAllGivesUpWithinItsBoundWhileAnAdoptionWaitsForTheManager() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        systemd.afterStart = () -> {
            inside.countDown();
            try {
                answer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApplyResult> adoption = pool.submit(() -> apply(CPU20_THROTTLE256));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            ReleaseResult timedOut = limiter.releaseAll(Duration.ofMillis(200));

            assertThat(timedOut.timedOut()).isTrue();
            assertThat(timedOut.notReached()).containsExactly(DEMO);
            // The worker waits for the lock as long as the caller waits for it, so it can outlast the caller. It has to
            // end first: if it took the lock after the adoption, it would let go of the hold, past its bound,
            // unreached.
            awaitNoReleaseThread();
            answer.countDown();
            adoption.get(10, TimeUnit.SECONDS);
            // The adoption finished after the release gave up: the hold it made is cleared by a later release.
            assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsKey(DEMO);
        } finally {
            answer.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitNoReleaseThread() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals("srm-linux-release"))) {
            assertThat(System.nanoTime() - deadline)
                    .as("the release worker ends")
                    .isNegative();
            Thread.sleep(5);
        }
    }

    /**
     * Test review S12: the second adoption is forced to overlap the first, held inside the manager's start; it makes no
     * manager call until the first is done (the fake fails on two calls at once).
     */
    @Test
    void concurrentAdoptionsAreMadeOneAtATime() throws Exception {
        ProcessIdentity second = secondDemo();
        limiter = harness(Set.of(DEMO, second), Set.of(DEMO, second), Set.of());
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        systemd.afterStart = () -> {
            if (insideFirst.getCount() > 0) {
                insideFirst.countDown();
                try {
                    releaseFirst.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApplyResult> a = pool.submit(() -> limiter.apply(DEMO, CPU20_THROTTLE256, p -> true));
            assertThat(insideFirst.await(10, TimeUnit.SECONDS)).isTrue();
            int callsWhileHeld = systemd.calls.size();
            Future<ApplyResult> b = pool.submit(() -> limiter.apply(second, CPU20_THROTTLE256, p -> true));
            Thread.sleep(300);
            assertThat(b.isDone()).as("the second waits for the first").isFalse();
            assertThat(systemd.calls)
                    .as("no call of the second while the first is inside")
                    .hasSize(callsWhileHeld);
            releaseFirst.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
            assertThat(b.get(10, TimeUnit.SECONDS).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(limiter.heldDescriptors()).hasSize(2);
        assertThat(systemd.calls.stream().filter(c -> c.startsWith("start ")).count())
                .isEqualTo(2);
    }

    private ProcessIdentity secondDemo() {
        ProcessIdentity second = new ProcessIdentity(2072, FakeProc.start(TICKS + 1));
        proc.process(2072, "demo2", TICKS + 1);
        systemd.placeProcess(2072, MANAGER + "/app.slice/app-demo-2.scope");
        return second;
    }

    /** The release is bounded end to end: a manager that hangs keeps the caller no longer than the bound. */
    @Test
    void releaseAllReturnsWithinItsBoundWhenTheManagerHangs() throws Exception {
        apply(CPU20_THROTTLE256);
        CountDownLatch hung = new CountDownLatch(1);
        systemd.onUnitRead = () -> {
            try {
                hung.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            long start = System.nanoTime();
            ReleaseResult released = limiter.releaseAll(Duration.ofMillis(300));
            long took = System.nanoTime() - start;

            assertThat(took).isLessThan(Duration.ofSeconds(2).toNanos());
            assertThat(released.timedOut()).isTrue();
            assertThat(released.notReached()).containsExactly(DEMO);
        } finally {
            hung.countDown();
        }
        // The work ends by itself once the manager answers, and lets go of the descriptor.
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!limiter.heldDescriptors().isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    /** The bound is checked before every SetUnitProperties, not only before a scope (closure review, finding 2). */
    @Test
    void theBoundIsCheckedBeforeEverySend() {
        apply(CPU20_THROTTLE256);
        long sets = systemd.setCalls();
        // Each set takes longer than the whole bound, on the limiter's clock.
        systemd.onSet = () -> clock[0] += Duration.ofSeconds(5).toNanos();

        ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(1));

        assertThat(systemd.setCalls() - sets)
                .as("only the send begun before the bound")
                .isEqualTo(1);
        assertThat(released.timedOut()).as("the bound was hit").isTrue();
        assertThat(released.cleared()).containsEntry(DEMO, false);
        assertThat(limiter.heldDescriptors()).isEmpty();
    }

    @Test
    void aClearingThatFailsUnexpectedlyStillLetsGoOfItsDescriptorAndTheOthersAreReached() {
        ProcessIdentity second = secondDemo();
        limiter = harness(Set.of(DEMO, second), Set.of(DEMO, second), Set.of());
        apply(CPU20_THROTTLE256);
        limiter.apply(second, CPU20_THROTTLE256, p -> true);
        int[] reads = {0};
        systemd.onUnitRead = () -> {
            if (reads[0]++ == 0) {
                throw new IllegalStateException("the manager went away");
            }
        };

        ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(5));

        assertThat(released.cleared()).hasSize(2).containsValue(false);
        assertThat(released.timedOut()).isFalse();
        assertThat(limiter.heldDescriptors()).isEmpty();
        assertThat(limiter.releaseAll(Duration.ofSeconds(1)))
                .as("a second release finds nothing")
                .isEqualTo(ReleaseResult.NOTHING);
    }

    /** Past the bound nothing more is sent: a hold not reached keeps its scope as it is and is said so. */
    @Test
    void pastTheBoundNothingMoreIsSentAndUnreachedHoldsAreSaid() {
        ProcessIdentity second = secondDemo();
        limiter = harness(Set.of(DEMO, second), Set.of(DEMO, second), Set.of());
        apply(CPU20_THROTTLE256);
        limiter.apply(second, CPU20_THROTTLE256, p -> true);
        long sets = systemd.setCalls();
        // Every read of a unit takes longer than the whole bound (on the limiter's clock).
        systemd.onUnitRead = () -> clock[0] += Duration.ofSeconds(5).toNanos();

        ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(1));

        assertThat(released.timedOut()).isTrue();
        assertThat(released.cleared()).hasSize(1).containsValue(false);
        assertThat(released.notReached()).hasSize(1);
        assertThat(systemd.setCalls()).as("nothing sent past the bound").isEqualTo(sets);
        assertThat(limiter.heldDescriptors()).isEmpty();
    }
}
