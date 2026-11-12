package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
import prism.srm.platform.HostPlatform;
import prism.srm.platform.SpecNice;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * Process control on the running Linux, on the demo workload the test starts and nothing else
 * (SAFE-6): what the kernel answers, what the workload does, and that no descriptor is left open.
 */
@EnabledOnOs(OS.LINUX)
class LinuxControlIntegrationTest {

    private final LinuxProcessController controller =
            (LinuxProcessController) HostPlatform.create().processController();
    private DemoProcess demo;
    private long pidfdsBefore;

    @BeforeEach
    void start() throws Exception {
        assertThat(controller.limitations())
                .as("pidfd signalling on this kernel")
                .isEmpty();
        demo = DemoProcess.start("--cpu", DemoProcess.oneWorkerOfCpu(), "--workers", "1", "--duration", "60s");
        pidfdsBefore = openPidfds();
    }

    /** Every pidfd Prism opened is closed again: the test's own pidfds, counted by what they link to. */
    @AfterEach
    void stop() throws IOException {
        demo.close();
        assertThat(openPidfds()).as("pidfds of the test's own process").isEqualTo(pidfdsBefore);
    }

    @Test
    void suspendStopsTheWholeProcessAndResumeLetsItRun() throws Exception {
        SuspendResult suspended = controller.suspend(demo.identity());
        assertThat(suspended.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        Suspension stop = suspended.suspension().orElseThrow();
        assertThat(waitForState('T')).isTrue();
        Duration before = demo.cpu();
        Thread.sleep(800);
        assertThat(demo.cpu().minus(before)).as("CPU while stopped").isLessThan(Duration.ofMillis(40));

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(stop.outstanding()).isZero();
        Duration resumed = demo.cpu();
        Thread.sleep(800);
        assertThat(demo.cpu().minus(resumed)).as("CPU after resume").isGreaterThan(Duration.ofMillis(100));
    }

    /** A process stopped already, here by the test's own stop through Prism, is not taken over twice. */
    @Test
    void aStoppedProcessIsNotSuspendedAgain() {
        Suspension stop = controller.suspend(demo.identity()).suspension().orElseThrow();
        assertThat(waitForState('T')).isTrue();

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
        assertThat(demo.process().exitValue()).isEqualTo(128 + LinuxProcessController.SIGKILL);
    }

    /**
     * CTL-6: lowering is allowed; raising again without privilege is refused, also back to where it
     * was. From the nice value the workload actually starts with, which the runner decides: every
     * thread inherits the test's, read from /proc and checked against ps, never assumed to be 0.
     */
    @Test
    void aPriorityCanBeLoweredButNotRaisedAgainWithoutPrivilege() throws Exception {
        int baseline = baselineNice();
        Optional<PriorityLevel> lower = SpecNice.lowerThan(baseline);
        System.out.println("linux priority: workload starts at nice " + baseline + ", lowered to "
                + lower.map(Object::toString).orElse("nothing (lowering not exercised)"));
        int now = baseline;
        if (lower.isPresent()) {
            ControlOutcome lowered = controller.changePriority(demo.identity(), lower.get());
            assertThat(lowered.result()).as("lowered from nice " + baseline).isEqualTo(OperationResult.SUCCESS);
            now = SpecNice.NICE.get(lower.get());
            assertThat(threadNices()).containsOnly(now);
        }

        // Raising is asked for in every case: refused without privilege, allowed with it.
        PriorityLevel higher = SpecNice.higherThan(now).orElseThrow();
        ControlOutcome raised = controller.changePriority(demo.identity(), higher);
        if (!mayRaiseTo(SpecNice.NICE.get(higher))) {
            assertThat(raised.result()).as("raised from nice " + now).isEqualTo(OperationResult.ACCESS_DENIED);
            assertThat(raised.nativeCode()).hasValue(LinuxErrors.EACCES);
            assertThat(threadNices()).containsOnly(now);
        } else {
            assertThat(raised.result()).as("raised where the kernel allows it").isEqualTo(OperationResult.SUCCESS);
            assertThat(threadNices()).containsOnly(SpecNice.NICE.get(higher));
        }
    }

    /**
     * The nice value the workload starts with: one value on every thread, and the value procps reads
     * for the process. procps reads /proc too, so this ties Prism's parsing to procps's, not to
     * another kernel path.
     */
    private int baselineNice() throws Exception {
        List<Integer> nices = threadNices();
        assertThat(nices).as("one nice value on every thread at the start").containsOnly(nices.get(0));
        assertThat(SpecNice.byPs(demo.identity().pid()))
                .as("procps agrees with Prism's reading of /proc")
                .isEqualTo(nices.get(0));
        return nices.get(0);
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
        assertThat(state()).isNotEqualTo('T');
        assertThat(threadNices()).as("the nice value it started with").containsOnly(baseline);
    }

    @Test
    void theFactsAndTheLivenessOfTheWorkload() throws Exception {
        assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller.protectionFacts(demo.identity()).systemProcess()).isFalse();
        controller.terminate(demo.identity());
        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    private boolean waitForState(char wanted) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (state() == wanted) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private char state() {
        return new ProcFs(Path.of("/proc"))
                .stat(pid())
                .value()
                .map(ProcStat::state)
                .orElse('?');
    }

    /** The nice value of every thread of the workload. */
    private List<Integer> threadNices() {
        ProcFs proc = new ProcFs(Path.of("/proc"));
        return proc.taskIds(pid()).get().stream()
                .map(tid -> proc.threadStat(pid(), tid).value())
                .flatMap(Optional::stream)
                .map(ProcStat::nice)
                .toList();
    }

    private int pid() {
        return Math.toIntExact(demo.identity().pid());
    }

    /**
     * Whether the kernel lets the test raise the workload to the nice value: with CAP_SYS_NICE, or
     * within the workload's soft RLIMIT_NICE, which allows nice values down to 20 - limit (can_nice
     * in kernel/sched; setpriority(2), getrlimit(2)). Being root counts only through the capability.
     */
    private boolean mayRaiseTo(int nice) throws IOException {
        String status = Files.readString(Path.of("/proc/self/status"), StandardCharsets.UTF_8);
        String effective = status.lines()
                .filter(line -> line.startsWith("CapEff:"))
                .findFirst()
                .orElseThrow();
        // CAP_SYS_NICE is capability 23 (include/uapi/linux/capability.h).
        if ((Long.parseUnsignedLong(effective.split("\\s+")[1], 16) & (1L << 23)) != 0) {
            return true;
        }
        String limits = Files.readString(Path.of("/proc", Integer.toString(pid()), "limits"), StandardCharsets.UTF_8);
        String soft = limits.lines()
                .filter(line -> line.startsWith("Max nice priority"))
                .findFirst()
                .orElseThrow()
                .substring("Max nice priority".length())
                .trim()
                .split("\\s+")[0];
        return soft.equals("unlimited") || 20 - nice <= Long.parseLong(soft);
    }

    /** The pidfds of the test's own process, by what their entries in /proc/self/fd link to. */
    private static long openPidfds() throws IOException {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.filter(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString().equals("anon_inode:[pidfd]");
                        } catch (IOException gone) {
                            return false;
                        }
                    })
                    .count();
        }
    }
}
