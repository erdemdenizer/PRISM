package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import prism.srm.domain.Capability;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;

/** CAP-3: no parity is faked where a system has no controller yet. */
class UnavailableProcessControllerTest {

    private static final ProcessIdentity ANY = new ProcessIdentity(42, Instant.EPOCH);
    private final UnavailableProcessController controller = new UnavailableProcessController();

    @Test
    void everyActionIsUnavailable() {
        assertThat(controller.capabilities())
                .hasSize(5)
                .allSatisfy((action, capability) -> assertThat(capability).isEqualTo(Capability.UNAVAILABLE));
    }

    @Test
    void everyCallIsUnsupportedAndNothingIsHeld() {
        assertThat(controller.requestClose(ANY).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.terminate(ANY).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.changePriority(ANY, PriorityLevel.LOW).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.liveness(ANY).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.suspend(ANY).suspension()).isEmpty();
        assertThat(controller.protectionFacts(ANY)).isEqualTo(ProtectionFacts.NONE);
    }
}
