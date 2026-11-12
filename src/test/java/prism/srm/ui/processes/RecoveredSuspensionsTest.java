package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspensionReason;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.control.ResultTone;

/** What an earlier session may have left suspended, as the Processes page offers it (ADR-0011); needs no toolkit. */
class RecoveredSuspensionsTest {

    private final List<RecoveryRequest> sent = new ArrayList<>();
    private final RecoveredSuspensions model = new RecoveredSuspensions(sent::add);
    private final ProcessIdentity demo = LimitsFixtures.identity(4_242);

    @Test
    void nothingListedTakesNoRoom() {
        model.show(List.of());

        assertThat(model.shownProperty().get()).isFalse();
    }

    @Test
    void oneRequestPerEntryAtATimeAndItsAnswerStaysAfterTheEntryWent() {
        model.show(List.of(LimitsFixtures.userLeft(demo, "demo")));

        model.dismiss(model.rows().get(0));
        model.dismiss(model.rows().get(0));
        assertThat(sent).hasSize(1);
        assertThat(model.pending(model.rows().get(0))).isTrue();

        model.show(List.of());
        model.accept(new RecoveryResult(
                sent.get(0), ControlOutcome.success(), "The entry for demo (PID 4242) is dismissed.", ""));

        assertThat(model.shownProperty().get()).as("the message stays").isTrue();
        assertThat(model.line().toneProperty().get()).isEqualTo(ResultTone.SUCCESS);
    }

    @Test
    void anAnswerToARequestNotMadeHereIsNotShown() {
        model.show(List.of(LimitsFixtures.userLeft(demo, "demo")));

        model.accept(new RecoveryResult(
                new RecoveryRequest(RecoveryRequest.Action.RESUME, demo, "demo"),
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "x"),
                "Prism did not resume demo.",
                ""));

        assertThat(model.line().wholeProperty().get()).isEmpty();
    }

    // --- Whether the list is whole (milestone 9, recovery visibility) ---

    @Test
    void anEmptyListIsNotShownAsWholeWhileAJournalWaits() {
        model.show(List.of());
        model.showCheck(RecoveryCheck.PENDING);

        assertThat(model.summaryShownProperty().get())
                .as("said, though nothing is listed")
                .isTrue();
        assertThat(model.shownProperty().get()).as("no list to review").isFalse();
        assertThat(RecoveredSuspensions.summary(0, RecoveryCheck.PENDING)).isEqualTo("Checking an earlier session…");
        assertThat(RecoveredSuspensions.summaryInFull(0, RecoveryCheck.PENDING))
                .contains("cannot be read yet")
                .contains("may be incomplete");
    }

    @Test
    void theNoteGoesOnceEveryJournalIsRead() {
        model.showCheck(RecoveryCheck.PENDING);
        model.show(List.of(LimitsFixtures.userLeft(demo, "demo")));
        model.showCheck(RecoveryCheck.COMPLETE);

        assertThat(model.summaryShownProperty().get()).isTrue();
        assertThat(RecoveredSuspensions.summary(1, RecoveryCheck.COMPLETE)).isEqualTo("1 possibly left suspended");
        assertThat(RecoveredSuspensions.incompleteness(RecoveryCheck.COMPLETE)).isEmpty();
        assertThat(RecoveredSuspensions.summaryInFull(1, RecoveryCheck.COMPLETE))
                .doesNotContain("incomplete");

        model.show(List.of());

        assertThat(model.summaryShownProperty().get())
                .as("whole and empty: nothing to say")
                .isFalse();
    }

    @Test
    void aJournalLeftUnreadIsSaidToNeedARestart() {
        model.show(List.of());
        model.showCheck(RecoveryCheck.EXHAUSTED);

        assertThat(model.summaryShownProperty().get()).isTrue();
        assertThat(RecoveredSuspensions.summary(0, RecoveryCheck.EXHAUSTED)).contains("restart Prism");
        assertThat(RecoveredSuspensions.summaryInFull(0, RecoveryCheck.EXHAUSTED))
                .contains("could not be read")
                .contains("may be incomplete")
                .contains("restarting Prism checks it again");
        // Short enough for the smallest window; the tooltip says what the entries are.
        assertThat(RecoveredSuspensions.summary(2, RecoveryCheck.EXHAUSTED)).isEqualTo("2 listed; list incomplete");
        assertThat(RecoveredSuspensions.summary(2, RecoveryCheck.PENDING)).isEqualTo("2 listed; checking more…");
        assertThat(RecoveredSuspensions.summaryInFull(2, RecoveryCheck.EXHAUSTED))
                .contains("2 process(es) an earlier session of Prism may have left suspended")
                .contains("may be incomplete");
    }

    @Test
    void theReasonsAreSaidInWords() {
        assertThat(RecoveredSuspensions.reasons(EnumSet.of(SuspensionReason.USER, SuspensionReason.CPU_LIMITER)))
                .isEqualTo("Suspended by you, CPU limit");
        assertThat(RecoveredSuspensions.reasons(EnumSet.noneOf(SuspensionReason.class)))
                .isEqualTo("incomplete suspension");
    }

    @Test
    void theConfirmationSaysTheStopMayNotBePrismsAndOnWindowsWhyACountProvesNothing() {
        assertThat(RecoveredSuspensions.confirmConsequence(Optional.of(ControlMechanism.STOP_SIGNAL)))
                .contains("the stop may not be Prism's")
                .doesNotContain("suspension another program added");
        assertThat(RecoveredSuspensions.confirmConsequence(Optional.of(ControlMechanism.PER_THREAD_SUSPENSION)))
                .contains("remove a suspension another program added");
    }
}
