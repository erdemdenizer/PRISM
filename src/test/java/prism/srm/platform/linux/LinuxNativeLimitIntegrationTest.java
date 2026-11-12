package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.demo.DemoWorkload;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ClearResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.ReleaseResult;

/**
 * Linux native limits on a real systemd user manager (milestone 12, S6, plan Rev 2.2 §8.2 T-L1 to T-L16), on disposable
 * processes the harness starts. Run only where {@code -Dprism.linux.native.required=true} asks for them (the
 * {@code linux-native-validation} CI job); there a missing capability fails the test, it never skips. Elsewhere the
 * class is skipped as a whole, and a skip is never counted as a pass (the CI job's validator checks that every test ran).
 *
 * <p>Nothing here touches a process the harness did not start, stops or kills a unit, or writes a cgroup file; the
 * harness kills its own disposable processes at the end of each test.
 */
@EnabledOnOs(OS.LINUX)
class LinuxNativeLimitIntegrationTest {

    static final String REQUIRED = "prism.linux.native.required";

    private LinuxHarness harness;
    private final List<LinuxScopeLimiter> limiters = new ArrayList<>();
    private final List<Long> extraPids = new ArrayList<>();
    private long descriptorsBefore;
    private long pidfdsBefore;
    private long socketsBefore;

    @BeforeEach
    void open() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean(REQUIRED), "Linux native tests run only in the linux-native-validation job");
        harness = LinuxHarness.open();
        List<String> missing = harness.missing();
        if (!missing.isEmpty()) {
            fail("Required Linux native capabilities are missing: " + missing);
        }
        descriptorsBefore = LinuxHarness.openDescriptors();
        pidfdsBefore = LinuxHarness.descriptorsOf("anon_inode:[pidfd]");
        socketsBefore = LinuxHarness.descriptorsOf("socket:");
        System.out.println("=== " + harness.report.describe().replace(System.lineSeparator(), " | "));
    }

    @AfterEach
    void close() throws IOException, InterruptedException {
        if (harness == null) {
            return;
        }
        List<ReleaseResult> releases = new ArrayList<>();
        try {
            for (LinuxScopeLimiter limiter : limiters) {
                ReleaseResult released = limiter.releaseAll(Duration.ofSeconds(10));
                System.out.println("release: " + released);
                releases.add(released);
            }
        } finally {
            harness.killAll();
            for (long pid : extraPids) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
        assertThat(releases)
                .as("every release finished in time and reached every hold")
                .allSatisfy(r -> {
                    assertThat(r.timedOut()).isFalse();
                    assertThat(r.notReached()).isEmpty();
                });
        Thread.sleep(300);
        long pidfds = LinuxHarness.descriptorsOf("anon_inode:[pidfd]");
        long sockets = LinuxHarness.descriptorsOf("socket:");
        System.out.println("descriptors: pidfd " + pidfdsBefore + " -> " + pidfds + ", socket " + socketsBefore + " -> "
                + sockets + ", all " + descriptorsBefore + " -> " + LinuxHarness.openDescriptors());
        assertThat(pidfds).as("every pidfd the limiters opened is closed").isEqualTo(pidfdsBefore);
        assertThat(sockets).as("no bus connection left open").isLessThanOrEqualTo(socketsBefore);
        assertThat(LinuxHarness.openDescriptors())
                .as("no other descriptor leaked (the harness's pipes go with its processes)")
                .isLessThanOrEqualTo(descriptorsBefore + 2);
    }

    private LinuxScopeLimiter limiter(Set<ProcessIdentity> admitted, Set<ProcessIdentity> hard) {
        LinuxScopeLimiter limiter = harness.limiter(admitted, hard);
        limiters.add(limiter);
        return limiter;
    }

    private LinuxHarness.Started busyDemo(boolean inUserManager) throws IOException, InterruptedException {
        return demo(inUserManager, Math.floor(100_000.0 / harness.processors) / 1000);
    }

    /** A demo that uses little CPU, for the tests that do not measure it (test review S13: less load on the runner). */
    private LinuxHarness.Started lightDemo(boolean inUserManager) throws IOException, InterruptedException {
        return demo(inUserManager, 5.0);
    }

    private LinuxHarness.Started demo(boolean inUserManager, double share) throws IOException, InterruptedException {
        return harness.start(
                inUserManager,
                "event=start",
                List.of(
                        harness.java(),
                        "-cp",
                        harness.testClasses(),
                        DemoWorkload.class.getName(),
                        "--cpu",
                        String.format(java.util.Locale.ROOT, "%.3f", share),
                        "--duration",
                        "300s"));
    }

    private LinuxHarness.Started allocator(int mib) throws IOException, InterruptedException {
        return allocator(mib, false);
    }

    private LinuxHarness.Started allocator(int mib, boolean sibling) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                harness.java(),
                "-Xmx64m",
                "-XX:MaxDirectMemorySize=1100m",
                "-cp",
                harness.testClasses(),
                LateAllocator.class.getName(),
                Integer.toString(mib),
                "120000"));
        if (sibling) {
            command.add("sibling");
        }
        return harness.start(true, "event=ready", command);
    }

    private static long millisOf(LinuxHarness.Started allocator) {
        return allocator.lines.stream()
                .filter(l -> l.startsWith("event=done"))
                .map(l -> l.substring(l.indexOf("millis=") + 7))
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElse(-1);
    }

    private int cpuPercentForHalfACore() {
        return Math.max(1, Math.min(100, 40 / harness.processors));
    }

    // ----- T-L1/T-L2: CPU enforcement and release; PIDFD-only adoption -----

    @Test
    void aCpuQuotaIsEnforcedOnTheAdoptedProcessAndReleased() throws Exception {
        LinuxHarness.Started demo = busyDemo(true);
        LinuxScopeLimiter limiter = limiter(Set.of(demo.identity), Set.of());
        int percent = cpuPercentForHalfACore();
        int processors = harness.processors;
        ScopeProvenance[] seen = new ScopeProvenance[1];

        ApplyResult applied = limiter.apply(
                demo.identity, new LimitSpec(OptionalInt.of(percent), OptionalLong.empty()), provenance -> {
                    seen[0] = provenance;
                    return true;
                });

        assertThat(applied.phase()).as(applied.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();
        assertThat(seen[0].unit()).isEqualTo(ScopeNames.of(demo.identity).orElseThrow());
        assertThat(seen[0].invocationId()).isPresent();
        assertThat(harness.files.processCgroup((int) demo.identity.pid()).value())
                .contains(group);
        assertThat(harness.cgroupFile(group, "cpu.max")).contains(((long) percent * processors * 1000) + " 100000");
        long throttledBefore = harness.counter(group, "cpu.stat", "nr_throttled");
        long throttledUsecBefore = harness.counter(group, "cpu.stat", "throttled_usec");
        double limited = harness.cores(group, Duration.ofSeconds(3));
        long throttled = harness.counter(group, "cpu.stat", "nr_throttled") - throttledBefore;
        long throttledUsec = harness.counter(group, "cpu.stat", "throttled_usec") - throttledUsecBefore;
        double allowed = percent * processors / 100.0;
        System.out.printf(
                java.util.Locale.ROOT,
                "T-L1 limited: %.3f cores (quota %.3f), throttled %d periods, %d us%n",
                limited,
                allowed,
                throttled,
                throttledUsec);
        assertThat(limited).isLessThanOrEqualTo(allowed * 1.25 + 0.05);
        assertThat(throttled)
                .as("the kernel throttled the scope (cpu.stat nr_throttled)")
                .isGreaterThan(0);
        assertThat(throttledUsec).as("cpu.stat throttled_usec").isGreaterThan(0);

        ChangeResult released = limiter.change(demo.identity, LimitSpec.NONE);
        assertThat(released.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(harness.noCpuQuota(group))
                .as(harness.cgroupFile(group, "cpu.max").toString())
                .isTrue();
        long releasedBefore = harness.counter(group, "cpu.stat", "nr_throttled");
        double free = harness.cores(group, Duration.ofSeconds(3));
        long releasedThrottled = harness.counter(group, "cpu.stat", "nr_throttled");
        System.out.printf(
                java.util.Locale.ROOT,
                "T-L1 released: %.3f cores, nr_throttled %d -> %d (-1: the cpu controller is off)%n",
                free,
                releasedBefore,
                releasedThrottled);
        assertThat(releasedThrottled - releasedBefore)
                .as("no throttling once released")
                .isLessThanOrEqualTo(0);
        assertThat(free).isGreaterThan(Math.min(0.7, allowed * 1.5));
        assertThat(demo.process.isAlive()).isTrue();
    }

    // ----- T-L2: the edges of the CPU rate, 1 % and 100 % of the machine -----

    @Test
    void theCpuRateEdgesAreSetExactlyAndHold() throws Exception {
        LinuxHarness.Started demo = busyDemo(true);
        LinuxScopeLimiter limiter = limiter(Set.of(demo.identity), Set.of());
        int processors = harness.processors;
        ScopeProvenance[] seen = new ScopeProvenance[1];

        ApplyResult lowest =
                limiter.apply(demo.identity, new LimitSpec(OptionalInt.of(1), OptionalLong.empty()), provenance -> {
                    seen[0] = provenance;
                    return true;
                });
        assertThat(lowest.phase()).as(lowest.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();
        assertThat(harness.cgroupFile(group, "cpu.max")).contains((processors * 1000L) + " 100000");
        double atOne = harness.cores(group, Duration.ofSeconds(3));
        double allowedOne = processors / 100.0;
        System.out.printf(java.util.Locale.ROOT, "T-L2 1 %%: %.3f cores (quota %.3f)%n", atOne, allowedOne);
        assertThat(atOne).isLessThanOrEqualTo(allowedOne * 1.25 + 0.03);

        ChangeResult whole = limiter.change(demo.identity, new LimitSpec(OptionalInt.of(100), OptionalLong.empty()));
        assertThat(whole.phase()).as(whole.toString()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(whole.observed().orElseThrow().cpuRate()).hasValue(10_000);
        assertThat(harness.cgroupFile(group, "cpu.max")).contains((processors * 100_000L) + " 100000");
        double atHundred = harness.cores(group, Duration.ofSeconds(3));
        System.out.printf(java.util.Locale.ROOT, "T-L2 100 %%: %.3f cores (one busy worker)%n", atHundred);
        assertThat(atHundred)
                .as("a whole-machine quota does not hold back one busy core")
                .isGreaterThan(0.7);
    }

    // ----- T-L5: memory.high (default, soft) -----

    @Test
    void aMemoryThrottleSlowsNewAllocationsWithoutKilling() throws Exception {
        LinuxHarness.Started allocator = allocator(384);
        LinuxScopeLimiter limiter = limiter(Set.of(allocator.identity), Set.of());
        ScopeProvenance[] seen = new ScopeProvenance[1];
        ApplyResult applied = limiter.apply(
                allocator.identity,
                new LimitSpec(OptionalInt.empty(), OptionalLong.of(128L << 20), MemoryBoundary.THROTTLE),
                provenance -> {
                    seen[0] = provenance;
                    return true;
                });
        assertThat(applied.phase()).as(applied.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();
        assertThat(harness.cgroupFile(group, "memory.high")).contains(String.valueOf(128L << 20));
        assertThat(harness.cgroupFile(group, "memory.max")).contains("max");

        long pressureBefore = harness.memoryPressureSome(group);
        allocator.send("go");
        // With swap the kernel pushes the excess out and the allocation finishes, slowly. Without swap anonymous memory
        // above memory.high cannot be reclaimed, so the kernel keeps delaying the allocator and it creeps on far too
        // slowly to finish within the bound (seen on a swapless Ubuntu 24.04 guest, 2026-10-16): not killed.
        boolean finished = allocator.await("event=done", Duration.ofSeconds(90));
        long high = harness.counter(group, "memory.events", "high");
        long pressure = harness.memoryPressureSome(group) - pressureBefore;
        long throttledMillis = finished ? millisOf(allocator) : -1;

        // The same allocation without a limit, in a scope of its own: the comparison for "slows".
        LinuxHarness.Started free = allocator(384);
        free.send("go");
        assertThat(free.await("event=done", Duration.ofSeconds(90))).isTrue();
        long freeMillis = millisOf(free);

        System.out.println("T-L5 memory.events high=" + high + " current="
                + harness.cgroupFile(group, "memory.current").orElse("?") + " pressure_some_us=" + pressure
                + " finished=" + finished + " millis=" + throttledMillis + " unlimited_millis=" + freeMillis
                + " alive=" + allocator.process.isAlive() + " swap_total_kib=" + harness.swapTotalKib());
        assertThat(high).as("the throttle was reached").isGreaterThan(0);
        assertThat(pressureBefore).as("memory.pressure (PSI) is readable").isGreaterThanOrEqualTo(0);
        assertThat(pressure)
                .as("tasks of the scope stalled on memory (PSI some)")
                .isGreaterThan(0);
        if (finished) {
            assertThat(throttledMillis)
                    .as("the throttled allocation took longer")
                    .isGreaterThan(freeMillis);
        } else {
            assertThat(harness.swapTotalKib())
                    .as("an unfinished throttled allocation is expected only where the excess cannot be swapped out")
                    .isZero();
            assertThat(freeMillis)
                    .as("the same allocation unlimited finished within the bound the throttled one did not")
                    .isLessThan(90_000);
        }
        assertThat(allocator.process.isAlive()).as("a throttle never kills").isTrue();
    }

    // ----- T-L4: memory.max with separate consent -----

    @Test
    void aHardMemoryLimitNeedsItsConsentAndBoundsTheCgroup() throws Exception {
        LinuxHarness.Started allocator = allocator(384, true);
        LimitSpec hard = new LimitSpec(OptionalInt.empty(), OptionalLong.of(128L << 20), MemoryBoundary.HARD);

        LinuxScopeLimiter withoutConsent = limiter(Set.of(allocator.identity), Set.of());
        assertThat(withoutConsent.apply(allocator.identity, hard, p -> true).phase())
                .isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(harness.files
                        .processCgroup((int) allocator.identity.pid())
                        .value()
                        .orElseThrow())
                .doesNotContain("prism-srm-limit-");

        LinuxScopeLimiter limiter = limiter(Set.of(allocator.identity), Set.of(allocator.identity));
        ScopeProvenance[] seen = new ScopeProvenance[1];
        ApplyResult applied = limiter.apply(allocator.identity, hard, provenance -> {
            seen[0] = provenance;
            return true;
        });
        assertThat(applied.phase()).as(applied.toString()).isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();
        assertThat(harness.cgroupFile(group, "memory.max")).contains(String.valueOf(128L << 20));

        assertThat(harness.bus()
                        .scopeStringProperty(seen[0].unit(), "OOMPolicy")
                        .value())
                .as("an OOM kill inside the scope does not stop it (decision 2)")
                .contains("continue");

        allocator.send("go");
        long peak = 0;
        long reached = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline
                && allocator.process.isAlive()
                && allocator.lines.stream().noneMatch(l -> l.startsWith("event=done"))) {
            peak = Math.max(peak, harness.counter(group, "memory.stat", "anon"));
            // Read while the scope exists: after an OOM kill it may be collected with its files.
            reached = Math.max(reached, limitReached(group));
            Optional<String> current = harness.cgroupFile(group, "memory.current");
            current.ifPresent(c -> assertThat(Long.parseLong(c)).isLessThanOrEqualTo((128L << 20) + (8L << 20)));
            Thread.sleep(100);
        }
        reached = Math.max(reached, limitReached(group));
        System.out.println("T-L4 outcome: alive=" + allocator.process.isAlive()
                + (allocator.process.isAlive() ? "" : " exit=" + allocator.process.exitValue())
                + " oom_kill=" + harness.counter(group, "memory.events", "oom_kill")
                + " max=" + harness.counter(group, "memory.events", "max")
                + " peak_anon=" + peak
                + " swap=" + harness.cgroupFile(group, "memory.swap.current").orElse("?"));
        assertThat(reached)
                .as("the hard limit was reached (reclaim/swap or an OOM kill: either is accepted, plan A-10)")
                .isGreaterThan(0);
        long sibling = allocator.lines.stream()
                .filter(l -> l.startsWith("event=sibling pid="))
                .mapToLong(l -> Long.parseLong(l.substring(18)))
                .findFirst()
                .orElseThrow();
        extraPids.add(sibling);
        boolean killed =
                Math.max(0, harness.counter(group, "memory.events", "oom_kill")) > 0 || !allocator.process.isAlive();
        System.out.println("T-L4 sibling " + sibling + " alive="
                + ProcessHandle.of(sibling).isPresent() + " allocator killed=" + killed);
        assertThat(ProcessHandle.of(sibling))
                .as("the other member of the scope lives")
                .isPresent();
        assertThat(harness.bus().unit(seen[0].unit()).value().map(SystemdUserBus.UnitFacts::activeState))
                .as("the scope stays: an OOM kill inside it does not stop it (OOMPolicy=continue)")
                .contains("active");
    }

    private long limitReached(String group) {
        return Math.max(0, harness.counter(group, "memory.events", "max"))
                + Math.max(0, harness.counter(group, "memory.events", "oom_kill"));
    }

    // ----- T-L3/T-L10: a descriptor of a reaped process moves nobody (test review M7) -----

    @Test
    void theManagerMovesNobodyForTheDescriptorOfAReapedProcess() throws Exception {
        Map.Entry<ScopeManager.Start, String> answer = harness.startWithReapedProcess();
        System.out.println("T-L10 answer to the pidfd of a reaped process: " + answer.getKey());

        Thread.sleep(LinuxScopeLimiter.START_BOUND.toMillis());
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> unit = harness.bus().unit(answer.getValue());
        System.out.println("T-L10 unit afterwards: " + unit.status() + " " + unit.detail());
        assertThat(answer.getKey().status())
                .as("a refusal (UnixProcessIdUnknown), not an unknown outcome")
                .isEqualTo(ScopeManager.StartStatus.REFUSED);
        boolean holdsNobody = unit.status() == SystemdUserBus.Status.NO_SUCH_UNIT
                || unit.value()
                        .flatMap(SystemdUserBus.UnitFacts::controlGroup)
                        .flatMap(group -> harness.files.procs(group).value())
                        .map(List::isEmpty)
                        .orElse(true);
        assertThat(holdsNobody).as("no process was moved into the scope").isTrue();
    }

    // ----- Limits are addressed by invocation id (independent audit follow-up) -----

    @Test
    void aLimitAddressedToAnInvocationNoUnitHasChangesNothing() throws Exception {
        LinuxHarness.Started demo = lightDemo(true);
        LinuxScopeLimiter limiter = limiter(Set.of(demo.identity), Set.of());
        ScopeProvenance[] seen = new ScopeProvenance[1];
        assertThat(limiter.apply(
                                demo.identity,
                                new LimitSpec(OptionalInt.of(cpuPercentForHalfACore()), OptionalLong.empty()),
                                provenance -> {
                                    seen[0] = provenance;
                                    return true;
                                })
                        .phase())
                .as("the success path: limits reached the scope through its invocation id")
                .isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();
        String before = harness.cgroupFile(group, "cpu.max").orElseThrow();

        ScopeManager.LimitSet set = new SystemdScopeManager(harness.api)
                .setLimit(
                        seen[0].unit(),
                        "0123456789abcdef0123456789abcdef",
                        "CPUQuotaPerSecUSec",
                        ScopeManager.INFINITY);

        System.out.println("T-INV answer to an unknown invocation id: " + set);
        assertThat(set.status())
                .as("NoUnitForInvocationID: nothing was done")
                .isEqualTo(ScopeManager.SetStatus.NO_SUCH_UNIT);
        assertThat(harness.cgroupFile(group, "cpu.max"))
                .as("the unit of that name is unchanged")
                .contains(before);
    }

    // ----- T-L6: the target exits, a process it started survives -----

    @Test
    void forgetClearsTheLimitsLeftOnTheProcessesTheTargetStarted() throws Exception {
        LinuxHarness.Started shell = harness.start(
                true, "ready", List.of("bash", "-c", "echo ready; read line; sleep 300 & echo child=$!; exit 0"));
        LinuxScopeLimiter limiter = limiter(Set.of(shell.identity), Set.of());
        ScopeProvenance[] seen = new ScopeProvenance[1];
        assertThat(limiter.apply(
                                shell.identity,
                                new LimitSpec(OptionalInt.of(cpuPercentForHalfACore()), OptionalLong.empty()),
                                provenance -> {
                                    seen[0] = provenance;
                                    return true;
                                })
                        .phase())
                .isEqualTo(ApplyResult.Phase.ENFORCED);
        String group = seen[0].cgroupPath().orElseThrow();

        shell.send("go");
        assertThat(shell.await("child=", Duration.ofSeconds(10))).isTrue();
        long child = Long.parseLong(shell.lines.stream()
                .filter(l -> l.startsWith("child="))
                .findFirst()
                .orElseThrow()
                .substring(6));
        extraPids.add(child);
        assertThat(shell.process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(harness.files.procs(group).value().orElseThrow()).containsExactly((int) child);

        ForgetResult forgotten = limiter.forget(shell.identity);

        assertThat(forgotten.result()).as(forgotten.toString()).isEqualTo(ForgetResult.Outcome.RELEASED);
        assertThat(forgotten.survivors()).isEqualTo(1);
        assertThat(harness.noCpuQuota(group))
                .as(harness.cgroupFile(group, "cpu.max").toString())
                .isTrue();
        assertThat(limiter.scopeBarrier(harness.identityOf((int) child)))
                .as("the survivor is still kept from a second limiter")
                .isPresent();
    }

    // ----- T-L7 / T-D26 on a real system: crash, then proven recovery and clearing -----

    @Test
    void aScopeLeftByACrashedSessionIsProvenAndClearedOnlyThen() throws Exception {
        LinuxHarness.Started demo = busyDemo(true);
        int percent = cpuPercentForHalfACore();
        // The session is a separate JVM; it adopts the demo, records the evidence, and is killed with SIGKILL.
        LinuxHarness.Started session = harness.start(
                false,
                "event=",
                List.of(
                        harness.java(),
                        "-cp",
                        harness.fullClassPath(),
                        CrashingAdopter.class.getName(),
                        Long.toString(demo.identity.pid()),
                        Long.toString(demo.identity.startTime().toEpochMilli()),
                        Integer.toString(percent)));
        String adopted = session.lines.stream()
                .filter(l -> l.startsWith("event="))
                .findFirst()
                .orElseThrow();
        assertThat(adopted).startsWith("event=adopted phase=ENFORCED");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (String field : adopted.split(" ")) {
            int eq = field.indexOf('=');
            fields.put(field.substring(0, eq), field.substring(eq + 1));
        }
        ScopeProvenance evidence = new ScopeProvenance(
                fields.get("unit"), Optional.of(fields.get("invocation")), Optional.of(fields.get("group")));
        String group = evidence.cgroupPath().orElseThrow();
        session.process.destroyForcibly();
        assertThat(session.process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        System.out.println("T-L7 session killed: exit " + session.process.exitValue());
        double whileLimited = harness.cores(group, Duration.ofSeconds(2));
        assertThat(harness.cgroupFile(group, "cpu.max").orElseThrow())
                .as("the crash left the limit in force")
                .doesNotContain("max");
        LimitSpec spec = new LimitSpec(OptionalInt.of(percent), OptionalLong.empty());
        LimitRecord record = LimitRecord.intended(demo.identity, "demo", spec.effective(), "1-1")
                .applied(spec.effective())
                .withProvenance(evidence);

        LinuxScopeLimiter next = limiter(Set.of(), Set.of());
        LinuxScopeLimiter recovering = harness.limiter(Set.of(demo.identity), Set.of());
        limiters.add(recovering);
        LimitRecord foreign = record.withProvenance(new ScopeProvenance(
                evidence.unit(), Optional.of("0123456789abcdef0123456789abcdef"), evidence.cgroupPath()));
        assertThat(recovering.recover(foreign).verdict()).isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE);
        assertThat(recovering.clearOwned(foreign).result()).isEqualTo(ClearResult.Outcome.NOT_OWNED);
        assertThat(harness.cgroupFile(group, "cpu.max").orElseThrow()).doesNotContain("max");

        assertThat(recovering.recover(record).verdict()).isEqualTo(RecoveredLimit.Verdict.OWNED_LIMITED);
        ClearResult cleared = recovering.clearOwned(record);
        assertThat(cleared.result()).as(cleared.toString()).isEqualTo(ClearResult.Outcome.CLEARED);
        assertThat(harness.noCpuQuota(group))
                .as(harness.cgroupFile(group, "cpu.max").toString())
                .isTrue();
        double afterClearing = harness.cores(group, Duration.ofSeconds(3));
        System.out.printf(
                java.util.Locale.ROOT,
                "T-L7 after the crash %.3f cores, after clearing %.3f cores%n",
                whileLimited,
                afterClearing);
        assertThat(afterClearing).as("the demo runs freely again").isGreaterThan(0.7);
        assertThat(demo.process.isAlive()).isTrue();
        assertThat(next.capabilitiesFor(demo.identity).get(LimitKind.CPU).available())
                .isFalse();
        assertThat(recovering.scopeBarrier(harness.identityOf((int) demo.identity.pid())))
                .as("it stays in the Prism scope, kept from a second limiter")
                .isPresent();
    }

    // ----- T-L11, T-L15: not eligible, recordless Prism scope -----

    @Test
    void aProcessOutsideTheUserManagerIsNotEligibleAndNothingIsCreated() throws Exception {
        LinuxHarness.Started outside = lightDemo(false);
        LinuxScopeLimiter limiter = limiter(Set.of(outside.identity), Set.of());

        String reason = limiter.capabilitiesFor(outside.identity)
                .get(LimitKind.CPU)
                .reason()
                .orElse("");
        ApplyResult refused =
                limiter.apply(outside.identity, new LimitSpec(OptionalInt.of(10), OptionalLong.empty()), p -> true);

        System.out.println("T-L11: " + reason);
        assertThat(reason).contains("outside your session manager");
        assertThat(refused.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(harness.files
                        .processCgroup((int) outside.identity.pid())
                        .value()
                        .orElseThrow())
                .doesNotContain("prism-srm-limit-");
    }

    @Test
    void aRecordlessScopeWithPrismsNameIsNeverChangedAndBlocksItsMembers() throws Exception {
        double share = 5.0;
        String name = "prism-srm-limit-1-" + System.currentTimeMillis() + ".scope";
        List<String> command = new ArrayList<>(List.of(
                "systemd-run",
                "--user",
                "--scope",
                "--quiet",
                "--collect",
                "--unit=" + name,
                "--",
                harness.java(),
                "-cp",
                harness.testClasses(),
                DemoWorkload.class.getName(),
                "--cpu",
                String.format(java.util.Locale.ROOT, "%.3f", share),
                "--duration",
                "300s"));
        LinuxHarness.Started member = harness.start(false, "event=start", command);
        LinuxScopeLimiter limiter = limiter(Set.of(member.identity), Set.of());

        assertThat(limiter.capabilitiesFor(member.identity)
                        .get(LimitKind.CPU)
                        .reason()
                        .orElse(""))
                .contains("scope with Prism's name");
        assertThat(limiter.scopeBarrier(member.identity)).isPresent();
        LimitRecord guess = LimitRecord.intended(member.identity, "demo", EffectiveLimits.NONE, "9-9")
                .withProvenance(ScopeProvenance.unitOnly(name));
        assertThat(limiter.recover(guess).verdict()).isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE);
        assertThat(limiter.clearOwned(guess).result()).isEqualTo(ClearResult.Outcome.NOT_OWNED);
    }

    // ----- Concurrency, exit before adoption -----

    @Test
    void twoAdoptionsAtOnceBothHoldAndAnExitedProcessIsNeverAdopted() throws Exception {
        LinuxHarness.Started first = lightDemo(true);
        LinuxHarness.Started second = lightDemo(true);
        LinuxHarness.Started gone = lightDemo(true);
        LinuxScopeLimiter limiter = limiter(Set.of(first.identity, second.identity, gone.identity), Set.of());
        LimitSpec spec = new LimitSpec(OptionalInt.of(cpuPercentForHalfACore()), OptionalLong.empty());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApplyResult> a = pool.submit(() -> limiter.apply(first.identity, spec, p -> true));
            Future<ApplyResult> b = pool.submit(() -> limiter.apply(second.identity, spec, p -> true));
            assertThat(a.get(30, TimeUnit.SECONDS).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
            assertThat(b.get(30, TimeUnit.SECONDS).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(limiter.heldDescriptors()).hasSize(2);

        gone.process.destroyForcibly();
        assertThat(gone.process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        ApplyResult late = limiter.apply(gone.identity, spec, p -> true);
        assertThat(late.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(harness.bus()
                        .unit(ScopeNames.of(gone.identity).orElseThrow())
                        .status())
                .isEqualTo(SystemdUserBus.Status.NO_SUCH_UNIT);
    }
}
