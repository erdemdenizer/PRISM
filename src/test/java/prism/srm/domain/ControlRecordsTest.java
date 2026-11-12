package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlRecordsTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.EPOCH);

    /** PRODUCT_SPEC section 2: exactly these six results. */
    @Test
    void theOperationResultsAreTheSixOfTheSpecification() {
        assertThat(Arrays.stream(OperationResult.values()).map(Enum::name))
                .containsExactly(
                        "SUCCESS",
                        "ACCESS_DENIED",
                        "PROCESS_NOT_FOUND",
                        "UNSUPPORTED",
                        "INVALID_ARGUMENT",
                        "NATIVE_ERROR");
    }

    @Test
    void anAvailableCapabilityNamesItsMechanismAndAnUnavailableOneNone() {
        assertThat(Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST)
                        .available())
                .isTrue();
        assertThat(Capability.UNAVAILABLE.available()).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Capability(CapabilityLevel.NATIVE_HARD, Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Capability(CapabilityLevel.UNAVAILABLE, Optional.of(ControlMechanism.CLOSE_REQUEST)));
    }

    @Test
    void aPriorityIsGivenExactlyForAChangeOfPriority() {
        assertThat(ControlRequest.priority(DEMO, "java", PriorityLevel.LOW).priority())
                .contains(PriorityLevel.LOW);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ControlRequest(ControlAction.CHANGE_PRIORITY, DEMO, "java", Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ControlRequest(ControlAction.SUSPEND, DEMO, "java", Optional.of(PriorityLevel.LOW)));
    }

    @Test
    void aResultHasASentenceForTheUser() {
        ControlRequest request = ControlRequest.of(ControlAction.SUSPEND, DEMO, "java");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessControlResult(request, ControlOutcome.success(), " "));
        assertThat(new ProcessControlResult(request, ControlOutcome.success(), "done").identity())
                .isEqualTo(DEMO);
    }

    @Test
    void onlyTheUsersReasonIsNotAutomatic() {
        assertThat(SuspensionReason.USER.automatic()).isFalse();
        assertThat(SuspensionReason.CPU_LIMITER.automatic()).isTrue();
        assertThat(SuspensionReason.OOM_GUARD.automatic()).isTrue();
    }

    @Test
    void noLevelIsRealtime() {
        assertThat(Arrays.stream(PriorityLevel.values()).map(Enum::name)).doesNotContain("REALTIME");
        assertThat(PriorityLevel.values()).hasSize(5);
    }
}
