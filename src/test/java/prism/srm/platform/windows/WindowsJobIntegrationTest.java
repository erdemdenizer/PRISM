package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;

/**
 * Native limits on the real system (milestone 10, E1, E4, E5, E6): only on processes this test
 * starts, bounded in time and ended in {@link #cleanUp()} (SAFE-6). Every limit it sets is cleared
 * and every job let go before the test returns, also when it fails.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WindowsJobIntegrationTest {

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final WindowsJobLimiter limiter = new WindowsJobLimiter();
    private final List<AutoCloseable> owned = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        limiter.releaseAll(Duration.ofSeconds(5));
        for (AutoCloseable each : owned.reversed()) {
            each.close();
        }
    }

    private DemoTarget demo(String... arguments) throws Exception {
        DemoTarget demo = DemoTarget.start(arguments);
        owned.add(demo);
        NativeValidation.requireOutsideAnyJob(demo.identity().pid());
        return demo;
    }

    @Test
    void bothNativeLimitsAreAvailableHere() {
        assertThat(limiter.capabilities().get(LimitKind.CPU).level()).isEqualTo(CapabilityLevel.NATIVE_HARD);
        assertThat(limiter.capabilities().get(LimitKind.MEMORY).level()).isEqualTo(CapabilityLevel.NATIVE_HARD);
    }

    /** E5 and E0: a hard cap of 20 % of the machine holds a busier workload near it; clearing lets it run again. */
    @Test
    void aTwentyPercentCapHoldsTheWorkloadNearItAndClearingItLetsTheWorkloadRunAgain() throws Exception {
        DemoTarget demo = demo("--cpu", "60", "--duration", "150s");
        double baseline = use(demo);

        ApplyResult applied = limiter.apply(demo.identity(), new LimitSpec(OptionalInt.of(20), OptionalLong.empty()));
        assertThat(applied.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        double capped = use(demo);

        ChangeResult cleared = limiter.change(demo.identity(), LimitSpec.NONE);
        assertThat(cleared.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(cleared.observed()).contains(EffectiveLimits.NONE);
        double free = use(demo);

        System.out.printf(
                Locale.ROOT,
                "native-cap: processors=%d baseline=%.1f capped=%.1f cleared=%.1f%n",
                PROCESSORS,
                baseline,
                capped,
                free);
        assertThat(capped)
                .as("capped at 20 %% of the machine")
                .isBetween(15.0, 25.0)
                .isLessThan(baseline);
        assertThat(free).as("free again").isGreaterThan(30.0);
    }

    /** E6: a commit limit refuses an allocation beyond it; what was committed before is not examined; clearing it allows more. */
    @Test
    void aCommitLimitRefusesAnAllocationBeyondItAndClearingItAllowsTheAllocation() throws Exception {
        try (Child child = Child.start()) {
            NativeValidation.requireOutsideAnyJob(child.identity().pid());
            assertThat(child.ask("alloc 64")).isEqualTo("allocated=64 failed=false");

            ApplyResult applied =
                    limiter.apply(child.identity(), new LimitSpec(OptionalInt.empty(), OptionalLong.of(256L << 20)));
            assertThat(applied.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);

            String refused = child.ask("alloc 512");
            System.out.println("native-commit: under a 256 MiB limit, asking 512 MiB: " + refused);
            assertThat(refused).endsWith("failed=true");
            int got = Integer.parseInt(refused.substring("allocated=".length(), refused.indexOf(' ')));
            assertThat(got).isLessThan(256);
            assertThat(child.ask("free")).isEqualTo("freed");

            ChangeResult cleared = limiter.change(child.identity(), LimitSpec.NONE);
            assertThat(cleared.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
            assertThat(child.ask("alloc 512")).isEqualTo("allocated=512 failed=false");
        }
    }

    /** E4 and plan D1: a process in a job of another program is refused before Prism makes a job. */
    @Test
    void aProcessInAJobOfAnotherProgramIsRefusedAndNoJobIsMade() throws Exception {
        DemoTarget demo = demo("--cpu", "1", "--duration", "60s");
        HANDLE other = JobNative.Kernel32Jobs.INSTANCE.CreateJobObjectW(null, null);
        HANDLE process = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_SET_QUOTA | WinNT.PROCESS_TERMINATE, false, (int)
                demo.identity().pid());
        try {
            assertThat(JobNative.Kernel32Jobs.INSTANCE.AssignProcessToJobObject(other, process))
                    .isTrue();

            assertThat(limiter.capabilitiesFor(demo.identity())
                            .get(LimitKind.CPU)
                            .reason())
                    .contains(WindowsJobLimiter.NOT_HELD_JOB);
            ApplyResult result =
                    limiter.apply(demo.identity(), new LimitSpec(OptionalInt.of(20), OptionalLong.empty()));
            assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
            assertThat(result.fallbackEligible()).isTrue();
            assertThat(limiter.hold(demo.identity())).isEmpty();
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
            Kernel32.INSTANCE.CloseHandle(other);
        }
    }

    /** E1: a process that has exited is not assigned, and no job is left. */
    @Test
    void aProcessThatHasExitedIsNotFoundAndNothingIsLeft() throws Exception {
        DemoTarget demo = demo("--cpu", "1", "--duration", "1s");
        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        ApplyResult result = limiter.apply(demo.identity(), new LimitSpec(OptionalInt.of(20), OptionalLong.empty()));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(limiter.hold(demo.identity())).isEmpty();
    }

    /** A process that exits while it holds a limit: the job is verified empty of limits and let go. */
    @Test
    void anExitedProcessIsForgottenAndItsJobLetGo() throws Exception {
        DemoTarget demo = demo("--cpu", "5", "--duration", "60s");
        assertThat(limiter.apply(demo.identity(), new LimitSpec(OptionalInt.of(10), OptionalLong.empty()))
                        .phase())
                .isEqualTo(ApplyResult.Phase.ENFORCED);
        demo.close();
        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        ForgetResult forgotten = limiter.forget(demo.identity());
        assertThat(forgotten.released()).isTrue();
        assertThat(limiter.hold(demo.identity())).isEmpty();
        assertThat(new JnaWin32()
                        .openJob(WindowsJobLimiter.REOPEN_RIGHTS, JobNames.of(demo.identity()))
                        .error())
                .as("the name went with the last handle")
                .isEqualTo(2);
    }

    /** The workload's share of the whole machine over one window, from its processor time as the JDK reads it. */
    private static double use(DemoTarget demo) throws InterruptedException {
        long before = demo.cpu().toNanos();
        long start = System.nanoTime();
        Thread.sleep(WINDOW.toMillis());
        long used = demo.cpu().toNanos() - before;
        return 100.0 * used / ((System.nanoTime() - start) * (double) PROCESSORS);
    }

    /** The commit child, started from the test classes with a small heap, ended on close. */
    static final class Child implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final Writer input;

        private Child(Process process) {
            this.process = process;
            this.output =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
            this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII);
        }

        static Child start() throws Exception {
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(
                            java,
                            "-Xmx32m",
                            "-Xms8m",
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            CommitChild.class.getName())
                    .redirectErrorStream(true);
            builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
            Child child = new Child(builder.start());
            String first = child.output.readLine();
            if (!"ready".equals(first)) {
                child.close();
                throw new IllegalStateException("The commit child did not start: " + first);
            }
            return child;
        }

        ProcessIdentity identity() {
            return new ProcessIdentity(
                    process.pid(), process.toHandle().info().startInstant().orElseThrow());
        }

        String ask(String line) throws Exception {
            input.write(line + "\n");
            input.flush();
            return output.readLine();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
