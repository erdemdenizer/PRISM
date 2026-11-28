package prism.srm.ui.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.managed;
import static prism.srm.ui.LimitsFixtures.snapshot;
import static prism.srm.ui.LimitsFixtures.throttling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.LimitRequest;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The Limits page in a scene, through the updates the running application sends. */
@Tag("ui-snapshot")
class LimitsPageSceneTest {

    private static Button button(LimitsPage page, String text) {
        return page.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The measured share of a throttled process changes about once a second, and each change
     * replaces the rows. The selection stays on the process and its actions stay enabled; found in
     * the real application, where they went disabled within a second (milestone 9 review). When the
     * process leaves the list, the selection goes with it.
     */
    @Test
    void theSelectionAndTheActionsSurviveEveryUpdateOfAThrottledProcess() {
        List<LimitRequest> sent = new ArrayList<>();
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT, 1100, 700, () -> new LimitsPage(new ValueFormatter(Locale.US), sent::add), root -> {
                    LimitsPage page = (LimitsPage) root;
                    @SuppressWarnings("unchecked")
                    TableView<LimitRow> table = (TableView<LimitRow>) page.lookup(".limits-table");
                    page.show(snapshot(
                            managed(identity(3_000), "notepad.exe"),
                            throttling(identity(30_324), "java.exe", 30, 29.6)));
                    table.getSelectionModel().select(1);
                    List<Object> facts = new ArrayList<>();
                    facts.add(button(page, "Disable").isDisabled());
                    for (double measured : new double[] {28.1, 30.4, 29.9}) {
                        page.show(snapshot(
                                managed(identity(3_000), "notepad.exe"),
                                throttling(identity(30_324), "java.exe", 30, measured)));
                    }
                    facts.add(table.getSelectionModel()
                            .getSelectedItem()
                            .identity()
                            .pid());
                    facts.add(button(page, "Disable").isDisabled());
                    button(page, "Disable").fire();
                    facts.add(sent.size());
                    page.show(snapshot(managed(identity(3_000), "notepad.exe")));
                    facts.add(table.getSelectionModel().isEmpty());
                    facts.add(button(page, "Unmanage").isDisabled());
                    return facts;
                });

        assertThat(seen).containsExactly(false, 30_324L, false, 1, true, true);
    }

    /**
     * Fix round H (KB-1, found by the keyboard acceptance of the packaged image): with no managed process
     * the table is still a Tab stop, and with the keyboard focus it draws the 2 px ring in the focus color
     * around its placeholder, as a filled table draws it on its row; without the focus the area is as before.
     */
    @Test
    void anEmptyTableWithTheKeyboardFocusShowsTheFocusRing() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT, 1100, 700, () -> new LimitsPage(new ValueFormatter(Locale.US), request -> {}), root -> {
                    LimitsPage page = (LimitsPage) root;
                    page.show(snapshot());
                    TableView<?> table = (TableView<?>) page.lookup(".limits-table");
                    root.applyCss();
                    root.layout();
                    javafx.scene.layout.Region placeholder = (javafx.scene.layout.Region) table.lookup(".placeholder");
                    javafx.scene.paint.Paint before = ringColor(placeholder);
                    table.requestFocus();
                    table.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focus-visible"), true);
                    root.applyCss();
                    root.layout();
                    javafx.scene.paint.Paint after = ringColor(placeholder);
                    javafx.scene.image.WritableImage image = page.snapshot(null, null);
                    javafx.geometry.Bounds area =
                            page.sceneToLocal(placeholder.localToScene(placeholder.getLayoutBounds()));
                    javafx.scene.paint.Color edge = image.getPixelReader()
                            .getColor((int) Math.round(area.getMinX() + area.getWidth() / 2), (int)
                                    Math.round(area.getMinY() + 3));
                    return List.of(
                            table.isFocusTraversable(),
                            placeholder.isVisible(),
                            before.equals(javafx.scene.paint.Color.TRANSPARENT),
                            after.equals(javafx.scene.paint.Color.TRANSPARENT),
                            edge.getBlue() > edge.getRed() + 0.2);
                });

        assertThat(seen).containsExactly(true, true, true, false, true);
    }

    private static javafx.scene.paint.Paint ringColor(javafx.scene.layout.Region region) {
        javafx.scene.layout.Border border = region.getBorder();
        if (border == null || border.getStrokes().isEmpty()) {
            return javafx.scene.paint.Color.TRANSPARENT;
        }
        return border.getStrokes().get(0).getTopStroke();
    }

    /**
     * LIM-6: the answer to a request of this page that asks for a consent opens the consent dialog at once; Cancel
     * sends nothing, a confirmation sends the same request again with the consents, and an answer to a request of
     * another page opens nothing.
     */
    @Test
    void aConsentAnswerOpensTheConsentDialogAndOnlyAConfirmationSendsTheRequestAgain() {
        List<LimitRequest> sent = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        boolean[] confirm = {false};
        prism.srm.ui.control.NativeConsentDialog consent = (name, pid, offer, owner) -> {
            asked.add(name + " " + pid + " " + offer.kinds());
            return confirm[0];
        };
        prism.srm.domain.ProcessIdentity notepad = identity(3_000);
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new LimitsPage(
                        new ValueFormatter(Locale.US),
                        sent::add,
                        (name, pid, current, means, owner) -> java.util.OptionalInt.empty(),
                        (name, pid, current, inUse, watched, owner) -> java.util.OptionalLong.empty(),
                        (name, pid, offer, owner) -> false,
                        (name, pid, reason, owner) -> false,
                        consent),
                root -> {
                    LimitsPage page = (LimitsPage) root;
                    page.show(snapshot(managed(notepad, "notepad.exe")));
                    page.select(notepad);
                    page.model().setCpuLimit(notepad, 20);
                    page.showResult(
                            LimitsViewModelConsentTest.consentAsked(sent.get(0), LimitsViewModelConsentTest.OFFER));
                    List<Object> facts = new ArrayList<>();
                    facts.add(sent.size());
                    page.model().setCpuLimit(notepad, 20);
                    confirm[0] = true;
                    page.showResult(
                            LimitsViewModelConsentTest.consentAsked(sent.get(1), LimitsViewModelConsentTest.OFFER));
                    facts.add(sent.size());
                    facts.add(sent.get(2).consents());
                    facts.add(sent.get(2).percent().getAsInt());
                    page.showResult(LimitsViewModelConsentTest.consentAsked(
                            LimitRequest.cpuLimit(notepad, "notepad.exe", 50), LimitsViewModelConsentTest.OFFER));
                    facts.add(asked.size());
                    return facts;
                });

        assertThat(seen).containsExactly(1, 3, java.util.Set.of(prism.srm.domain.NativeConsentKind.ADOPTION), 20, 2);
        assertThat(asked).first().isEqualTo("notepad.exe 3000 [ADOPTION]");
    }
}
