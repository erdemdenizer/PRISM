package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.ProcessIdentity;

/** The words of native limits (LIM-4, CAP-5): never "approximate", never a per-process CPU share. */
class LimitMessagesNativeTest {

    private static final LimitRequest REQUEST =
            LimitRequest.cpuLimit(new ProcessIdentity(42, Instant.EPOCH), "demo", 20);
    private static final LimitCapability JOB =
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT);

    @Test
    void aNativeLimitIsEnforcedByTheSystemInTheWordsOfItsMechanismAndNeverApproximate() {
        ControlMessages.Wording limited = LimitMessages.limitedNatively(REQUEST, 20, JOB);

        assertThat(limited.headline()).isEqualTo("demo (PID 42) is limited to 20 % of the CPU.");
        assertThat(limited.explanation())
                .startsWith("Enforced by the system (Job Object).")
                .contains("child processes it starts from now on share the cap")
                .doesNotContainIgnoringCase("approximate");
        assertThat(LimitMessages.enforced(LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.DUTY_CYCLE)))
                .isEqualTo("Enforced by the system.");
        assertThat(LimitMessages.emulatedChosen(REQUEST, 20).explanation()).containsIgnoringCase("approximate");
    }

    /** The share is of the whole machine, shared with the children: no message calls it per-process. */
    @Test
    void noMessageSpeaksOfAPerProcessCpuShare() throws Exception {
        String source = Files.readString(Path.of("src/main/java/prism/srm/application/LimitMessages.java"));
        assertThat(source).doesNotContainIgnoringCase("per-process cpu");
    }
}
