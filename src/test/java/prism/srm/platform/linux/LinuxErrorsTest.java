package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;

/** The errno table (CTL-2, SAFE-4) and the values of the kernel's errno headers. */
class LinuxErrorsTest {

    @Test
    void theValuesAreThoseOfTheGenericErrnoHeaders() {
        assertThat(Map.of(
                        "EPERM", LinuxErrors.EPERM,
                        "ENOENT", LinuxErrors.ENOENT,
                        "ESRCH", LinuxErrors.ESRCH,
                        "EBADF", LinuxErrors.EBADF,
                        "ENOMEM", LinuxErrors.ENOMEM,
                        "EACCES", LinuxErrors.EACCES,
                        "ENODEV", LinuxErrors.ENODEV,
                        "EINVAL", LinuxErrors.EINVAL,
                        "ENFILE", LinuxErrors.ENFILE,
                        "EMFILE", LinuxErrors.EMFILE))
                .containsAllEntriesOf(Map.of(
                        "EPERM", 1, "ENOENT", 2, "ESRCH", 3, "EBADF", 9, "ENOMEM", 12, "EACCES", 13, "ENODEV", 19,
                        "EINVAL", 22, "ENFILE", 23, "EMFILE", 24));
        assertThat(LinuxErrors.ENOSYS).isEqualTo(38);
    }

    @Test
    void everyErrnoComesToOneResult() {
        assertThat(LinuxErrors.result(LinuxErrors.ESRCH)).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(LinuxErrors.result(LinuxErrors.EPERM)).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(LinuxErrors.result(LinuxErrors.EACCES)).isEqualTo(OperationResult.ACCESS_DENIED);
        for (int other : new int[] {
            LinuxErrors.EINVAL,
            LinuxErrors.EBADF,
            LinuxErrors.EMFILE,
            LinuxErrors.ENFILE,
            LinuxErrors.ENOMEM,
            LinuxErrors.ENOSYS,
            LinuxErrors.ENOENT,
            LinuxErrors.ENODEV,
            999
        }) {
            assertThat(LinuxErrors.result(other)).as("errno " + other).isEqualTo(OperationResult.NATIVE_ERROR);
        }
    }

    @Test
    void theOutcomeCarriesTheCodeAndTheCallForTheLog() {
        ControlOutcome outcome = LinuxErrors.outcome("pidfd_send_signal SIGKILL", LinuxErrors.EPERM);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).hasValue(1);
        assertThat(outcome.detail()).hasValue("pidfd_send_signal SIGKILL failed: EPERM (1)");
    }

    /** Only these say that the mechanism is not there; every other failure of the probe is unexpected. */
    @Test
    void onlyTheFailuresThatSayItIsMissingMeanNoPidfd() {
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.ENOSYS)).isTrue();
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.EPERM)).isTrue();
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.ENODEV)).isTrue();
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.EMFILE)).isFalse();
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.EINVAL)).isFalse();
        assertThat(LinuxErrors.meansNoPidfd(LinuxErrors.ESRCH)).isFalse();
    }
}
