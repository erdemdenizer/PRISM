package prism.srm.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.SpecNice;

/**
 * The JNA side on the running macOS, read-only, on the test's own process: the entry sysctl gives
 * is the one the JDK and the process listing read, so the identity Prism checks is the listing's.
 */
@EnabledOnOs(OS.MAC)
class JnaMacosTest {

    private final JnaMacos macos = new JnaMacos();
    private final int pid = Math.toIntExact(ProcessHandle.current().pid());

    @Test
    void theEntryOfThisProcessIsReadWithTheIdentityOfTheJdkAndTheListing() {
        MacosNative.ProcessInfo own = macos.processInfo(pid).info().orElseThrow();

        assertThat(own.pid()).isEqualTo(pid);
        assertThat(own.startMillis())
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
                .isEqualTo(own.startMillis());
        assertThat(own.state()).isNotEqualTo(MacosProcessController.SZOMB).isNotEqualTo(MacosProcessController.SSTOP);
        assertThat(own.flags() & MacosProcessController.P_SYSTEM).isZero();
    }

    @Test
    void launchdHasItsEntryUnderPidOne() {
        assertThat(macos.processInfo(1).info())
                .hasValueSatisfying(info -> assertThat(info.pid()).isEqualTo(1));
    }

    /** xnu hands out PIDs below PID_MAX, 99999, so no process ever has that one (bsd/kern/kern_fork.c). */
    @Test
    void aPidNoProcessCanHaveIsMissing() {
        assertThat(macos.processInfo(99_999).missingProcess()).isTrue();
    }

    @Test
    void thePriorityAndTheNameOfThisProcessAreRead() {
        assertThat(macos.getPriority(pid).failed()).isFalse();
        assertThat(macos.executable(pid)).hasValue("java");
    }

    /** errno really reaches Prism from a failing call, also through getpriority's own way. */
    @Test
    void aFailingCallOnThisMacReportsItsErrno() {
        assertThat(macos.kill(99_999, 0).errno()).isEqualTo(MacosErrors.ESRCH);
        assertThat(macos.getPriority(99_999).errno()).isEqualTo(MacosErrors.ESRCH);
    }

    /**
     * The nice value of this JVM through Prism's getpriority mapping and through ps, a system tool
     * that reads the process table on its own: they must agree. The first run on GitHub read -10
     * where 0 was assumed; this tells a value the runner gave from a misreading of Prism's.
     */
    @Test
    void getpriorityReadsTheNiceValueThatPsReports() throws Exception {
        MacosNative.Call read = macos.getPriority(pid);
        int byPs = SpecNice.byPs(pid);
        // who = 0 is the calling process itself, read without looking the PID up (kern_resource.c).
        System.out.println("macos nice of the test JVM: getpriority=" + read.value() + " getpriority(self)="
                + macos.getPriority(0).value() + " ps=" + byPs);
        System.out.println("macos nice of its ancestors by ps: " + ancestorNices());

        assertThat(read.failed()).isFalse();
        assertThat(read.value()).isEqualTo(byPs);
    }

    /**
     * Where a nice value the test JVM inherits comes from: each ancestor's command and nice value by
     * ps, up to the first that cannot be read. A diagnostic for the log, not an assertion.
     */
    private static String ancestorNices() {
        StringBuilder chain = new StringBuilder();
        Optional<ProcessHandle> next = ProcessHandle.current().parent();
        while (next.isPresent()) {
            ProcessHandle ancestor = next.get();
            String nice;
            try {
                nice = Integer.toString(SpecNice.byPs(ancestor.pid()));
            } catch (IOException | NumberFormatException unreadable) {
                nice = "?";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            chain.append(ancestor.pid())
                    .append(' ')
                    .append(ancestor.info().command().orElse("?"))
                    .append(" nice=")
                    .append(nice)
                    .append("; ");
            next = ancestor.parent();
        }
        return chain.toString();
    }
}
