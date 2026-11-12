package prism.srm.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.DemoProcess;
import prism.srm.platform.SpecNice;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * Process control on the running macOS, on the demo workload the test starts and nothing else
 * (SAFE-6): what the kernel answers and what the workload does. A macOS stop holds no descriptor.
 */
@EnabledOnOs(OS.MAC)
class MacosControlIntegrationTest {

    private final JnaMacos macos = new JnaMacos();
    private final MacosProcessController controller = new MacosProcessController(macos);
    private DemoProcess demo;

    @BeforeEach
    void start() throws Exception {
        demo = DemoProcess.start("--cpu", DemoProcess.oneWorkerOfCpu(), "--workers", "1", "--duration", "60s");
    }

    @AfterEach
    void stop() {
        demo.close();
    }

    @Test
    void suspendStopsTheProcessAndResumeLetsItRun() throws Exception {
        SuspendResult suspended = controller.suspend(demo.identity());
        assertThat(suspended.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        Suspension stop = suspended.suspension().orElseThrow();
        assertThat(waitForState(MacosProcessController.SSTOP)).isTrue();
        Duration before = demo.cpu();
        Thread.sleep(800);
        assertThat(demo.cpu().minus(before)).as("CPU while stopped").isLessThan(Duration.ofMillis(40));

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(stop.outstanding()).isZero();
        Duration resumed = demo.cpu();
        Thread.sleep(800);
        assertThat(demo.cpu().minus(resumed)).as("CPU after resume").isGreaterThan(Duration.ofMillis(100));
    }

    @Test
    void aStoppedProcessIsNotSuspendedAgain() {
        Suspension stop = controller.suspend(demo.identity()).suspension().orElseThrow();
        assertThat(waitForState(MacosProcessController.SSTOP)).isTrue();

        assertThat(controller.suspend(demo.identity()).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
    }

    @Test
    void endTaskSendsSigtermAndTheWorkloadEndsOnIt() throws Exception {
        assertThat(controller.requestClose(demo.identity()).result()).isEqualTo(OperationResult.SUCCESS);

        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        assertThat(demo.output()).anyMatch(line -> line.startsWith("event=end") && line.contains("exit=signal"));
    }

    @Test
    void forceKillEndsTheWorkloadAtOnce() throws Exception {
        assertThat(controller.terminate(demo.identity()).result()).isEqualTo(OperationResult.SUCCESS);

        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        assertThat(demo.process().exitValue()).isEqualTo(128 + MacosProcessController.SIGKILL);
    }

    /**
     * CTL-6: lowering is allowed; raising again without privilege is refused, also back to where it
     * was. From the nice value the workload actually starts with, which the runner decides (the first
     * run on GitHub read -10): read through getpriority and checked against ps, never assumed to be 0.
     */
    @Test
    void aPriorityCanBeLoweredButNotRaisedAgainWithoutPrivilege() throws Exception {
        int baseline = baselineNice();
        boolean unprivileged = !"root".equals(System.getProperty("user.name"));
        Optional<PriorityLevel> lower = SpecNice.lowerThan(baseline);
        System.out.println("macos priority: workload starts at nice " + baseline + ", unprivileged=" + unprivileged
                + ", lowered to " + lower.map(Object::toString).orElse("nothing (lowering not exercised)"));
        int now = baseline;
        if (lower.isPresent()) {
            ControlOutcome lowered = controller.changePriority(demo.identity(), lower.get());
            assertThat(lowered.result()).as("lowered from nice " + baseline).isEqualTo(OperationResult.SUCCESS);
            now = SpecNice.NICE.get(lower.get());
            assertThat(niceNow()).isEqualTo(now);
            assertThat(SpecNice.byPs(demo.identity().pid()))
                    .as("ps sees the change")
                    .isEqualTo(now);
        }

        // Raising is asked for in every case: refused without privilege, allowed with it.
        PriorityLevel higher = SpecNice.higherThan(now).orElseThrow();
        ControlOutcome raised = controller.changePriority(demo.identity(), higher);
        if (unprivileged) {
            assertThat(raised.result()).as("raised from nice " + now).isEqualTo(OperationResult.ACCESS_DENIED);
            assertThat(raised.nativeCode()).hasValue(MacosErrors.EACCES);
            assertThat(niceNow()).isEqualTo(now);
        } else {
            assertThat(raised.result()).as("raised as root").isEqualTo(OperationResult.SUCCESS);
            assertThat(niceNow()).isEqualTo(SpecNice.NICE.get(higher));
        }
    }

    /**
     * The nice value the workload starts with, through getpriority, and the same value through ps:
     * the independent reading that tells a value the runner gave from a misreading of Prism's.
     */
    private int baselineNice() throws Exception {
        int read = niceNow();
        assertThat(SpecNice.byPs(demo.identity().pid()))
                .as("ps agrees with getpriority")
                .isEqualTo(read);
        return read;
    }

    private int niceNow() {
        MacosNative.Call read =
                macos.getPriority(Math.toIntExact(demo.identity().pid()));
        assertThat(read.failed()).as("getpriority").isFalse();
        return Math.toIntExact(read.value());
    }

    @Test
    void anIdentityWhoseStartTimeIsNotTheWorkloadsIsActedOnByNoAction() throws Exception {
        ProcessIdentity stale = new ProcessIdentity(
                demo.identity().pid(), demo.identity().startTime().minusSeconds(1));
        int baseline = baselineNice();

        assertThat(controller.requestClose(stale).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(controller.terminate(stale).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(controller.suspend(stale).outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        // A level it is not at, so that acting on the stale identity would show.
        assertThat(controller
                        .changePriority(stale, SpecNice.lowerThan(baseline).orElse(PriorityLevel.NORMAL))
                        .result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(demo.exitsWithin(Duration.ofMillis(300))).isFalse();
        assertThat(state()).isNotEqualTo(MacosProcessController.SSTOP);
        assertThat(niceNow()).as("the nice value it started with").isEqualTo(baseline);
    }

    @Test
    void theFactsAndTheLivenessOfTheWorkload() throws Exception {
        assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller.protectionFacts(demo.identity()).systemProcess()).isFalse();
        controller.terminate(demo.identity());
        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    private boolean waitForState(int wanted) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (state() == wanted) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private int state() {
        return macos.processInfo(Math.toIntExact(demo.identity().pid()))
                .info()
                .map(MacosNative.ProcessInfo::state)
                .orElse(-1);
    }
}
