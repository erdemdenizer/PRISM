package prism.srm.ui.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.NativeConsentKind;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;

/**
 * The consent to a native limit on the Limits page (LIM-6), through the view model as the page uses it: the answer to a
 * request the page sent hands the offer over to be asked, and a confirmation sends the same request again with the
 * consents, for that process only. Needs no toolkit.
 */
class LimitsViewModelConsentTest {

    private static final ProcessIdentity DEMO = identity(4_242);
    static final NativeConsentOffer OFFER = new NativeConsentOffer(
            Set.of(NativeConsentKind.ADOPTION), List.of("The process is moved into a scope.", "It stays there."));

    private final List<LimitRequest> sent = new ArrayList<>();
    private final LimitsViewModel model = new LimitsViewModel(new ValueFormatter(Locale.US), sent::add);

    static LimitResult consentAsked(LimitRequest request, NativeConsentOffer offer) {
        return new LimitResult(
                request,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "consent"),
                "PRISM asks for your consent before it limits demo (PID 4242).",
                "Nothing was changed.",
                Optional.empty(),
                Optional.of(offer));
    }

    private LimitRequest setTheLimit() {
        model.show(LimitsFixtures.snapshot(LimitsFixtures.managed(DEMO, "demo")));
        model.select(DEMO);
        model.setCpuLimit(DEMO, 20);
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    @Test
    void theAnswerToARequestOfThisPageHandsTheOfferOverAndTheConfirmationSendsTheSameRequestWithIt() {
        LimitRequest first = setTheLimit();

        Optional<NativeConsentOffer> offered = model.accept(consentAsked(first, OFFER));
        String shown = model.line().wholeProperty().get();
        model.consent(first, offered.orElseThrow());

        assertThat(offered).contains(OFFER);
        assertThat(shown).contains("asks for your consent").contains("Nothing was changed");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(first.withConsents(Set.of(NativeConsentKind.ADOPTION)));
        assertThat(sent.get(1).consents()).containsExactly(NativeConsentKind.ADOPTION);
        assertThat(model.line().wholeProperty().get()).as("on its way again").contains("Setting the CPU limit");
    }

    /** Cancel: the dialog's no sends nothing; the next request about the process carries no consent. */
    @Test
    void withoutAConfirmationNothingIsSentAndNothingIsRemembered() {
        LimitRequest first = setTheLimit();
        model.accept(consentAsked(first, OFFER));

        model.setCpuLimit(DEMO, 30);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).consents()).isEmpty();
    }

    @Test
    void anAnswerToARequestOfAnotherPageAsksNothing() {
        setTheLimit();
        LimitRequest fromElsewhere = LimitRequest.cpuLimit(DEMO, "demo", 50);

        assertThat(model.accept(consentAsked(fromElsewhere, OFFER))).isEmpty();
        assertThat(model.accept(new LimitResult(sent.get(0), ControlOutcome.success(), "demo is limited.", "")))
                .as("an answer that asks for no consent")
                .isEmpty();
    }

    @Test
    void aConfirmationForAProcessNoLongerListedSendsNothing() {
        LimitRequest first = setTheLimit();
        model.accept(consentAsked(first, OFFER));
        model.show(LimitsFixtures.snapshot());

        model.consent(first, OFFER);

        assertThat(sent).hasSize(1);
    }

    /** Consents the request already carried stay with it, so that a second offer never drops the first consent. */
    @Test
    void theConsentsOfTheRequestAndOfTheOfferAreSentTogether() {
        LimitRequest first = setTheLimit();
        LimitRequest withAdoption = first.withConsents(Set.of(NativeConsentKind.ADOPTION));
        NativeConsentOffer hard =
                new NativeConsentOffer(Set.of(NativeConsentKind.HARD_MEMORY), List.of("The kernel may terminate it."));

        model.consent(withAdoption, hard);

        assertThat(sent.get(1).consents())
                .containsExactlyInAnyOrder(NativeConsentKind.ADOPTION, NativeConsentKind.HARD_MEMORY);
    }
}
