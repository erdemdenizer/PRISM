package prism.srm.ui.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;

/** The Limits page with native limits (milestone 10): enforced rows, memory, the offered fallback, notices. */
class LimitsViewModelNativeTest {

    private static final ProcessIdentity DEMO = identity(4_242);
    private static final long MIB = 1L << 20;

    private final List<LimitRequest> sent = new ArrayList<>();
    private final LimitsViewModel model = new LimitsViewModel(new ValueFormatter(Locale.US), sent::add);

    private LimitRow only() {
        assertThat(model.rows()).hasSize(1);
        return model.rows().get(0);
    }

    @Test
    void anEnforcedLimitSaysSoAndNamesTheJobObjectAndTheMemoryLimit() {
        model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.enforced(DEMO, "demo", 20, 512))));

        LimitRow row = only();
        assertThat(row.cpuLimit()).isEqualTo("20 %");
        assertThat(row.status()).isEqualTo("Enforced");
        assertThat(row.implementation()).isEqualTo("Native hard");
        assertThat(row.implementationDetail()).contains("Job Object").contains("the system enforces");
        assertThat(row.memoryLimit()).isEqualTo("512 MiB");
        assertThat(row.hasMemoryLimit()).isTrue();
        assertThat(row.fallbackOffered()).isFalse();
    }

    /** A memory limit alone: the status is about the CPU and says so; the implementation is the memory's. */
    @Test
    void aMemoryLimitAloneSaysThereIsNoCpuLimitAndIsStillNative() {
        model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.enforced(DEMO, "demo", 0, 256))));

        LimitRow row = only();
        assertThat(row.status()).isEqualTo("No CPU limit");
        assertThat(row.memoryLimit()).isEqualTo("256 MiB");
        assertThat(row.implementation()).isEqualTo("Native hard");
    }

    /**
     * The scope-membership rule (milestone 12) is said as such: not as a cap an earlier session holds, which the scope
     * may not hold at all, and not as a failure of a limit that ran.
     */
    @Test
    void aProcessInAPrismScopeIsShownAsNotOfferedNotAsCapped() {
        for (LimitStatus status : List.of(LimitStatus.FAILED, LimitStatus.UNAVAILABLE)) {
            model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.nativeLimited(
                    DEMO,
                    "demo",
                    OptionalInt.of(20),
                    OptionalLong.empty(),
                    status,
                    LimitNote.IN_PRISM_SCOPE,
                    Optional.of("it runs in prism-srm-limit-4242-1.scope, a scope a Prism session created")))));

            LimitRow row = only();
            assertThat(row.status()).as("%s", status).isEqualTo("Not offered: in a Prism scope");
            assertThat(row.detail()).as("%s", status).contains("prism-srm-limit-4242-1.scope");
            assertThat(row.status()).as("%s", status).doesNotContain("Capped", "Failed");
        }
    }

    /** The emulated limit is offered, never assumed: the action is enabled only with an offer, and asks first. */
    @Test
    void theEmulatedLimitCanBeChosenOnlyWhenOfferedAndIsSentAsAnAcceptance() {
        model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.enforced(DEMO, "demo", 20, 0))));
        model.select(DEMO);
        assertThat(model.disabledProperty(LimitsViewModel.Action.USE_EMULATED).get())
                .isTrue();
        assertThat(model.offer()).isEmpty();
        model.acceptEmulated();
        assertThat(sent).isEmpty();

        model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.nativeLimited(
                DEMO,
                "demo",
                OptionalInt.of(25),
                OptionalLong.empty(),
                LimitStatus.FAILED,
                LimitNote.FALLBACK_OFFERED,
                Optional.of("the native limit was not applied: access is denied")))));

        LimitRow row = only();
        assertThat(row.status()).isEqualTo("Not applied, emulated offered");
        assertThat(row.detail()).contains("nothing runs until you do");
        assertThat(model.disabledProperty(LimitsViewModel.Action.USE_EMULATED).get())
                .isFalse();
        assertThat(model.offer()).isPresent();
        model.acceptEmulated();
        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.action()).isEqualTo(LimitAction.ACCEPT_EMULATED_CPU);
            assertThat(request.identity()).isEqualTo(DEMO);
        });
    }

    @Test
    void aMemoryLimitIsSentInBytesAndRemovedOnlyWhenThereIsOne() {
        model.show(LimitsFixtures.nativeSnapshot(List.of(LimitsFixtures.enforced(DEMO, "demo", 20, 0))));
        model.select(DEMO);
        assertThat(model.disabledProperty(LimitsViewModel.Action.EDIT_MEMORY).get())
                .isFalse();
        assertThat(model.disabledProperty(LimitsViewModel.Action.REMOVE_MEMORY).get())
                .isTrue();

        model.setMemoryLimit(DEMO, 300 * MIB);

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.action()).isEqualTo(LimitAction.SET_MEMORY_LIMIT);
            assertThat(request.bytes()).hasValue(300 * MIB);
        });
    }

    /** Without a native limiter the memory action is disabled with its reason (CAP-2), not hidden. */
    @Test
    void withoutANativeLimiterSettingAMemoryLimitIsDisabledWithItsReason() {
        model.show(LimitsFixtures.snapshot(LimitsFixtures.managed(DEMO, "demo")));
        model.select(DEMO);

        assertThat(model.disabledProperty(LimitsViewModel.Action.EDIT_MEMORY).get())
                .isTrue();
        assertThat(model.explanationProperty(LimitsViewModel.Action.EDIT_MEMORY).get())
                .startsWith("A memory limit is not available for this process");
    }

    /** A failure of Prism is red; a limit that may still be in force is a warning; a fact is muted. */
    @Test
    void eachNoticeHasTheToneOfWhatItSays() {
        model.show(LimitsFixtures.nativeSnapshot(
                List.of(),
                new LimitsNotice(LimitsNotice.Kind.LEDGER_FAILED, "ledger"),
                new LimitsNotice(LimitsNotice.Kind.RESIDUE, "residue"),
                new LimitsNotice(LimitsNotice.Kind.PRIOR_SESSION, "prior"),
                new LimitsNotice(LimitsNotice.Kind.SELF_IN_JOB, "self")));

        assertThat(model.notices())
                .extracting(LimitsViewModel.Notice::tone)
                .containsExactly(
                        LimitRow.Tone.ERROR, LimitRow.Tone.WARNING, LimitRow.Tone.WARNING, LimitRow.Tone.MUTED);
        assertThat(model.noticeProperty().get()).isEqualTo("ledger\nresidue\nprior\nself");

        model.show(
                LimitsFixtures.nativeSnapshot(List.of(), new LimitsNotice(LimitsNotice.Kind.PRIOR_SESSION, "prior")));
        assertThat(model.limitsOffProperty().get()).isFalse();
    }
}
