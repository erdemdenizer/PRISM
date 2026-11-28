package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.NativeConsentKind;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.ui.snapshot.DialogFit;
import prism.srm.ui.snapshot.DialogSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The consent to a native limit (LIM-6, ADR-0016 decisions 4 and 6) with a toolkit: every statement listed, the act
 * named on its button, Cancel the default, the buttons whole inside the window, and the rendering in both themes.
 */
@Tag("ui-snapshot")
class NativeConsentDialogSceneTest {

    /** The statements as the Linux limiter fixes them (platform.linux.NativeConsent), copied here as test data. */
    private static final List<String> ADOPTION_STATEMENTS = List.of(
            "This process will be moved into a systemd scope that PRISM creates for it.",
            "When the limits are removed, the process does not return to its earlier systemd unit; it stays in this scope"
                    + " until it exits.",
            "Processes it starts from now on share the same limits and stay in the same scope.",
            "Your desktop environment or service manager may see this application's ownership or lifecycle differently.",
            "Anyone who stops this scope from outside, for example with systemctl --user stop, terminates the processes"
                    + " in it; PRISM never does.");

    private static final String HARD_STATEMENT =
            "The Linux kernel may terminate this process or one of its child processes when it reaches the limit.";

    private static final NativeConsentOffer ADOPTION =
            new NativeConsentOffer(Set.of(NativeConsentKind.ADOPTION), ADOPTION_STATEMENTS);

    private static final NativeConsentOffer BOTH = new NativeConsentOffer(
            Set.of(NativeConsentKind.ADOPTION, NativeConsentKind.HARD_MEMORY), withHard(ADOPTION_STATEMENTS));

    private static final NativeConsentOffer HARD =
            new NativeConsentOffer(Set.of(NativeConsentKind.HARD_MEMORY), List.of(HARD_STATEMENT));

    private static final String LONG = "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut-somewhere.exe";

    private static List<String> withHard(List<String> statements) {
        List<String> all = new ArrayList<>(statements);
        all.add(HARD_STATEMENT);
        return all;
    }

    /** Enter must not move a process: Cancel is the default and the cancel button; the act is named. */
    @Test
    void cancelIsTheDefaultAndTheConfirmingButtonNamesTheAct() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            List<Object> facts = new ArrayList<>();
            for (NativeConsentOffer offer : List.of(ADOPTION, HARD, BOTH)) {
                Dialog<ButtonType> dialog = NativeConsentDialog.build("firefox", 4711, offer);
                List<ButtonType> types = dialog.getDialogPane().getButtonTypes();
                Button confirm = (Button) dialog.getDialogPane().lookupButton(types.get(0));
                Button cancel = (Button) dialog.getDialogPane().lookupButton(types.get(1));
                facts.add(confirm.getText());
                facts.add(confirm.isDefaultButton());
                facts.add(confirm.getStyleClass().contains("confirm-act"));
                facts.add(confirm.getStyleClass().contains("confirm-act-lasting"));
                facts.add(confirm.getStyleClass().contains("primary"));
                facts.add(cancel.getText());
                facts.add(cancel.isDefaultButton());
                facts.add(cancel.isCancelButton());
                facts.add(types.get(1).getButtonData());
            }
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "Move and limit",
                        false,
                        true,
                        false,
                        false,
                        "Cancel",
                        true,
                        true,
                        ButtonData.CANCEL_CLOSE,
                        "Set hard limit",
                        false,
                        true,
                        true,
                        false,
                        "Cancel",
                        true,
                        true,
                        ButtonData.CANCEL_CLOSE,
                        "Move and set limits",
                        false,
                        true,
                        true,
                        false,
                        "Cancel",
                        true,
                        true,
                        ButtonData.CANCEL_CLOSE);
    }

    /** Every statement of the offer is shown, as given, in its order; and the process by name and PID. */
    @Test
    void everyStatementIsListedAsTheLimiterFixedIt() {
        List<String> shown = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = NativeConsentDialog.build("firefox", 4711, BOTH);
            dialog.getDialogPane().applyCss();
            List<String> texts = new ArrayList<>();
            dialog.getDialogPane()
                    .lookupAll(".native-consent-statement")
                    .forEach(node -> texts.add(((Label) node).getText()));
            texts.add(dialog.getDialogPane().lookupAll(".label").stream()
                    .map(node -> ((Label) node).getText())
                    .filter(text -> text.startsWith("PID "))
                    .findFirst()
                    .orElse(""));
            texts.add(dialog.getTitle());
            return texts;
        });

        List<String> expected = new ArrayList<>(withHard(ADOPTION_STATEMENTS));
        expected.add("PID 4711");
        expected.add("Move into a systemd scope?");
        assertThat(shown).containsExactlyElementsOf(expected);
        assertThat(NativeConsentDialog.heading(BOTH)).contains("systemd scope").contains("hard memory limit");
        assertThat(NativeConsentDialog.heading(HARD)).isEqualTo("Set a hard memory limit on this process?");
    }

    /** A single statement without a move is the body of the dialog, not a list of one. */
    @Test
    void aSingleStatementIsTheBodyNotAListOfOne() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = NativeConsentDialog.build("firefox", 4711, HARD);
            dialog.getDialogPane().applyCss();
            return List.of(
                    dialog.getDialogPane().lookupAll(".native-consent-line").size(),
                    ((Label) dialog.getDialogPane().lookup(".native-consent-statement")).getText());
        });
        assertThat(seen).containsExactly(0, HARD_STATEMENT);
        assertThat(NativeConsentDialog.lead(HARD)).isEmpty();
    }

    /** The window takes the statements: its buttons stay inside with their words whole, at 100 % and 125 %. */
    @Test
    void theButtonsStayInsideTheWindowWithTheirWordsWhole() {
        for (double scale : new double[] {1.0, 1.25}) {
            DialogFit.Fit fit = SnapshotHarness.inspect(
                    Theme.LIGHT,
                    200,
                    100,
                    Pane::new,
                    root -> DialogFit.measure(NativeConsentDialog.build(LONG, 18812, BOTH), dialog -> {}, scale));
            assertThat(fit.fits()).as("at " + scale + ": " + fit).isTrue();
        }
    }

    /**
     * Shown as the user sees it, the last line of the content ends above the buttons for every offer (a wrapped line
     * the window was not sized for would run under them).
     */
    @Test
    void theContentEndsAboveTheButtons() {
        for (NativeConsentOffer offer : List.of(ADOPTION, HARD, BOTH)) {
            List<Double> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
                Dialog<ButtonType> dialog = NativeConsentDialog.build(LONG, 18812, offer);
                dialog.show();
                try {
                    javafx.scene.control.DialogPane pane = dialog.getDialogPane();
                    pane.getScene().getWindow().sizeToScene();
                    for (int pass = 0; pass < 3; pass++) {
                        pane.applyCss();
                        pane.layout();
                    }
                    javafx.scene.Node content = pane.getContent();
                    javafx.scene.Node last = ((javafx.scene.layout.VBox) content)
                            .getChildren()
                            .get(((javafx.scene.layout.VBox) content)
                                            .getChildren()
                                            .size()
                                    - 1);
                    double contentBottom =
                            last.localToScene(last.getLayoutBounds()).getMaxY();
                    Button confirm =
                            (Button) pane.lookupButton(pane.getButtonTypes().get(0));
                    double buttonsTop =
                            confirm.localToScene(confirm.getLayoutBounds()).getMinY();
                    return List.of(contentBottom, buttonsTop);
                } finally {
                    dialog.close();
                }
            });
            assertThat(seen.get(0))
                    .as(offer.kinds() + ": content bottom vs buttons top")
                    .isLessThanOrEqualTo(seen.get(1));
        }
    }

    static Stream<Arguments> themes() {
        return Stream.of(Arguments.of(Theme.LIGHT), Arguments.of(Theme.DARK));
    }

    /**
     * Renders the dialog for inspection (target/ui-snapshots/dialog-native-consent-*, dialog-native-consent-hard-*,
     * dialog-native-consent-both-*), each at the size its window takes (LO-17): the adoption with a long name, the hard
     * memory consent alone, and both at once, the tallest.
     */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersTheConsentDialog(Theme theme) {
        DialogSnapshots.render("dialog-native-consent", theme, () -> NativeConsentDialog.build(LONG, 18812, ADOPTION));
        DialogSnapshots.render(
                "dialog-native-consent-hard", theme, () -> NativeConsentDialog.build("firefox", 4711, HARD));
        DialogSnapshots.render("dialog-native-consent-both", theme, () -> NativeConsentDialog.build(LONG, 18812, BOTH));
    }
}
