package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.platform.HostPlatform;

/**
 * The JNA side on Linux. The architecture rule runs everywhere, touching nothing native; the rest
 * runs on Linux only, read-only, on the test's own process.
 */
class JnaLinuxTest {

    /** The numbers 434 and 424 are verified for these two only (syscall_64.tbl, scripts/syscall.tbl). */
    @ParameterizedTest
    @ValueSource(strings = {"amd64", "aarch64", "AMD64"})
    void theVerifiedArchitecturesMayMakeThePidfdCalls(String architecture) {
        assertThat(new JnaLinux(architecture).pidfdUnreachable()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x86", "arm", "riscv64", "ppc64le", "s390x", "mips64", ""})
    void anyOtherArchitectureMakesNone(String architecture) {
        assertThat(new JnaLinux(architecture).pidfdUnreachable())
                .hasValueSatisfying(reason -> assertThat(reason).contains("not verified"));
    }

    @Test
    void theSystemCallNumbersAreThoseOfTheKernelTables() {
        assertThat(JnaLinux.SYS_PIDFD_OPEN).isEqualTo(434);
        assertThat(JnaLinux.SYS_PIDFD_SEND_SIGNAL).isEqualTo(424);
    }

    /** The probe on the running kernel; on a Linux of 5.3 or later, pidfd signalling is there. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void onThisLinuxThePidfdPathWorksOnTheTestsOwnProcess() {
        JnaLinux linux = new JnaLinux();

        assertThat(LinuxProcessController.probePidfd(linux)).isEmpty();
        assertThat(linux.ownPid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(linux.clockTicksPerSecond()).isPositive();
    }

    /**
     * The identity Prism checks is the identity the listing gives: this JVM's start time, computed
     * from /proc, equals the JDK's and the process listing's.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theStartTimeOfThisProcessIsTheOneTheJdkAndTheListingRead() {
        int pid = Math.toIntExact(ProcessHandle.current().pid());
        LinuxProcessController controller =
                (LinuxProcessController) HostPlatform.create().processController();
        ProcStat own = new ProcFs(Path.of("/proc")).stat(pid).get();

        long computed = controller.startMillis(own).orElseThrow();

        assertThat(computed)
                .isEqualTo(ProcessHandle.current()
                        .info()
                        .startInstant()
                        .orElseThrow()
                        .toEpochMilli());
        assertThat(HostPlatform.create().processSource().processes().stream()
                        .filter(reading -> reading.identity().pid() == pid)
                        .findFirst()
                        .orElseThrow()
                        .identity()
                        .startTime()
                        .toEpochMilli())
                .isEqualTo(computed);
        assertThat(own.ended()).isFalse();
    }

    /** No way to signal by PID exists on Linux here at all: signals go through a pidfd or not at all. */
    @Test
    void neitherTheSeamNorTheMappingCanSignalByPid() {
        java.util.Set<String> byPid = java.util.Set.of("kill", "tkill", "tgkill", "sigqueue", "rt_sigqueueinfo");
        for (Class<?> type : java.util.List.of(LinuxNative.class, JnaLinux.Libc.class, JnaLinux.class)) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                assertThat(byPid).as(type.getSimpleName()).doesNotContain(method.getName());
            }
        }
    }

    /** errno really reaches Prism from a failing call: an exited child, and a descriptor that is none. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void aFailingCallOnThisLinuxReportsItsErrno() throws Exception {
        JnaLinux linux = new JnaLinux();
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        child.waitFor();

        assertThat(linux.pidfdOpen(Math.toIntExact(child.pid())).errno()).isEqualTo(LinuxErrors.ESRCH);
        assertThat(linux.close(-1).errno()).isEqualTo(LinuxErrors.EBADF);
    }
}
