package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.NativeConsentKind;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.domain.OperationResult;
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

    // ----- Risk D8: the words of a systemd scope (Linux, ADR-0016) -----

    private static final LimitCapability SCOPE =
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.SYSTEMD_SCOPE);
    private static final LimitCapability SCOPE_MEMORY =
            LimitCapability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.SYSTEMD_SCOPE);
    private static final LimitRequest MEMORY =
            LimitRequest.memoryLimit(new ProcessIdentity(42, Instant.EPOCH), "demo", 256L << 20);

    @Test
    void aScopesCpuLimitIsEnforcedByTheKernelThroughItsOwnScopeAndNotForRealTimeProcesses() {
        ControlMessages.Wording limited = LimitMessages.limitedNatively(REQUEST, 20, SCOPE);

        assertThat(limited.explanation())
                .startsWith("Enforced by the kernel through the process's own systemd scope.")
                .contains("processes it starts from now on share the cap")
                .contains("stays in the scope until it exits")
                .contains("does not apply to real-time processes")
                .doesNotContain("Job Object", "job")
                .doesNotContainIgnoringCase("approximate");
        assertThat(LimitMessages.enabledNative(REQUEST, SCOPE).explanation()).isEqualTo(LimitMessages.enforced(SCOPE));
    }

    @Test
    void aScopesMemoryHighIsHeldNearTheAmountNotACommitLimit() {
        ControlMessages.Wording held =
                LimitMessages.memoryLimited(MEMORY, "256 MiB", SCOPE_MEMORY, MemoryBoundary.THROTTLE);

        assertThat(held.headline()).isEqualTo("demo (PID 42) is held near 256 MiB.");
        assertThat(held.explanation())
                .contains("slows the process down and reclaims its memory")
                .contains("does not stop or terminate the process")
                .contains("Memory it used before it was moved into its scope is not counted")
                .contains("share the limit")
                .doesNotContain("commit", "crash");
    }

    @Test
    void aScopesMemoryMaxSaysTheKernelMayTerminateAndNeverThatTheTargetWillBeKilled() {
        ControlMessages.Wording hard = LimitMessages.memoryLimited(MEMORY, "256 MiB", SCOPE, MemoryBoundary.HARD);

        assertThat(hard.headline()).isEqualTo("demo (PID 42) may use at most 256 MiB.");
        assertThat(hard.explanation())
                .contains("the kernel may terminate the process or one of the processes it started")
                .contains("before it was moved into its scope is not counted")
                .doesNotContain("commit", "will be killed");
    }

    @Test
    void theWindowsWordsStayForAJobObject() {
        assertThat(LimitMessages.memoryLimited(MEMORY, "256 MiB", JOB, MemoryBoundary.HARD)
                        .headline())
                .isEqualTo("demo (PID 42) may commit at most 256 MiB.");
        assertThat(LimitMessages.memoryRemoved(MEMORY, JOB).explanation())
                .isEqualTo("It stays in its job until it exits.");
        assertThat(LimitMessages.unmanagedNative(REQUEST, JOB).explanation()).contains("it stays in PRISM's job,");
        assertThat(LimitMessages.uncertain(REQUEST, JOB).explanation()).startsWith("It keeps the job,");
    }

    @Test
    void aScopeIsCalledAScopeWhereWindowsSaysJob() {
        assertThat(LimitMessages.memoryRemoved(MEMORY, SCOPE_MEMORY).explanation())
                .isEqualTo("It stays in its scope until it exits.");
        assertThat(LimitMessages.unmanagedNative(REQUEST, SCOPE).explanation())
                .contains("it stays in PRISM's scope, without limits, until it exits")
                .doesNotContain("job");
        assertThat(LimitMessages.uncertain(REQUEST, SCOPE).explanation())
                .startsWith("It keeps the scope,")
                .doesNotContain("job");
        assertThat(LimitMessages.holder(SCOPE)).isEqualTo("scope");
        assertThat(LimitMessages.holder(JOB)).isEqualTo("job");
        assertThat(LimitMessages.holder(LimitCapability.unavailable("none"))).isEqualTo("job");
    }

    /** LIM-6: what the user is asked, and that nothing was changed; the statements are the limiter's own. */
    @Test
    void aConsentAnswerSaysNothingWasChangedAndCarriesTheStatements() {
        NativeConsentOffer adoption =
                new NativeConsentOffer(Set.of(NativeConsentKind.ADOPTION), List.of("The process is moved."));
        LimitResult asked = LimitMessages.consentNeeded(
                REQUEST, ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "consent"), adoption);

        assertThat(asked.message()).isEqualTo("PRISM asks for your consent before it limits demo (PID 42).");
        assertThat(asked.explanation())
                .contains("systemd scope the process is moved into")
                .contains("cannot be undone while it runs")
                .endsWith("Nothing was changed.");
        assertThat(asked.consent()).contains(adoption);
        assertThat(asked.fallback()).isEmpty();
        assertThat(asked.outcome().succeeded()).isFalse();

        NativeConsentOffer hard =
                new NativeConsentOffer(Set.of(NativeConsentKind.HARD_MEMORY), List.of("The kernel may terminate it."));
        assertThat(LimitMessages.consentWording(MEMORY, hard).explanation())
                .isEqualTo("A hard memory limit lets the kernel terminate the process or one of its child processes."
                        + " Nothing was changed.");
    }

    /** The share is of the whole machine, shared with the children: no message calls it per-process. */
    @Test
    void noMessageSpeaksOfAPerProcessCpuShare() throws Exception {
        String source = Files.readString(Path.of("src/main/java/prism/srm/application/LimitMessages.java"));
        assertThat(source).doesNotContainIgnoringCase("per-process cpu");
    }
}
