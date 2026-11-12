package prism.srm.ui.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The Settings page in a scene: what it changes and opens, by mouse and by keyboard (UI-7, SET-2). */
@Tag("ui-snapshot")
class SettingsPageSceneTest {

    private static SettingsPage page(
            ThemeMode appearance, List<ThemeMode> changes, List<String> opened, Optional<String> loadProblem) {
        return new SettingsPage(new SettingsPage.Hooks(
                appearance,
                changes::add,
                "C:\\logs",
                "C:\\settings",
                () -> opened.add("logs"),
                () -> opened.add("settings"),
                loadProblem));
    }

    private static Map<ThemeMode, RadioButton> choices(Node root) {
        Set<Node> found = root.lookupAll(".settings-appearance");
        java.util.EnumMap<ThemeMode, RadioButton> choices = new java.util.EnumMap<>(ThemeMode.class);
        for (Node node : found) {
            RadioButton choice = (RadioButton) node;
            choices.put((ThemeMode) choice.getUserData(), choice);
        }
        return choices;
    }

    private static List<ThemeMode> selected(Node root) {
        return choices(root).values().stream()
                .filter(RadioButton::isSelected)
                .map(choice -> (ThemeMode) choice.getUserData())
                .toList();
    }

    @Test
    void theAppearanceReadAtStartUpIsSelectedAndIsNoChange() {
        List<ThemeMode> changes = new ArrayList<>();

        List<ThemeMode> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(ThemeMode.DARK, changes, new ArrayList<>(), Optional.empty()),
                SettingsPageSceneTest::selected);

        assertThat(seen).containsExactly(ThemeMode.DARK);
        assertThat(changes).isEmpty();
    }

    @Test
    void choosingAnAppearanceReportsItOnce() {
        List<ThemeMode> changes = new ArrayList<>();

        List<ThemeMode> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(ThemeMode.SYSTEM, changes, new ArrayList<>(), Optional.empty()),
                root -> {
                    choices(root).get(ThemeMode.LIGHT).fire();
                    // A click on what is already chosen is no change.
                    choices(root).get(ThemeMode.LIGHT).fire();
                    choices(root).get(ThemeMode.DARK).fire();
                    return selected(root);
                });

        assertThat(seen).containsExactly(ThemeMode.DARK);
        assertThat(changes).containsExactly(ThemeMode.LIGHT, ThemeMode.DARK);
    }

    /** The stock behavior of a toggle group, pinned: the keyboard reaches every choice. */
    @Test
    void theArrowKeysMoveTheChoice() {
        List<ThemeMode> changes = new ArrayList<>();

        SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(ThemeMode.SYSTEM, changes, new ArrayList<>(), Optional.empty()),
                root -> {
                    RadioButton system = choices(root).get(ThemeMode.SYSTEM);
                    system.requestFocus();
                    press(system, KeyCode.RIGHT);
                    RadioButton light = choices(root).get(ThemeMode.LIGHT);
                    // The focus owner, because this scene has no focused window for isFocused to report.
                    assertThat(root.getScene().getFocusOwner())
                            .as("the arrow moved the focus")
                            .isSameAs(light);
                    press(light, KeyCode.SPACE);
                    return null;
                });

        assertThat(changes).containsExactly(ThemeMode.LIGHT);
    }

    private static void press(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
    }

    /**
     * LS-03, LS-05: each folder is plain text, not an editable field, that takes the keyboard, so
     * Ctrl+C and its context menu copy it whole; each button opens its own.
     */
    @Test
    void theFoldersAreShownAsTextThatCopiesAndEachButtonOpensItsOwn() {
        List<String> opened = new ArrayList<>();
        List<String> copied = new ArrayList<>();

        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> {
                    SettingsPage settings = page(ThemeMode.SYSTEM, new ArrayList<>(), opened, Optional.empty());
                    settings.copyTo(copied::add);
                    return settings;
                },
                root -> {
                    List<Object> facts = new ArrayList<>();
                    facts.add(root.lookupAll(".text-field").size());
                    for (Node node : root.lookupAll(".settings-folder-path")) {
                        Label path = (Label) node;
                        facts.add(path.getText());
                        facts.add(path.isFocusTraversable());
                        Event.fireEvent(
                                path,
                                new KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, true, false, true));
                        path.getContextMenu().getItems().get(0).fire();
                    }
                    for (Node node : root.lookupAll(".settings-open")) {
                        ((Button) node).fire();
                    }
                    return facts;
                });

        assertThat(seen).as("no field that looks editable").containsExactly(0, "C:\\logs", true, "C:\\settings", true);
        assertThat(copied).containsExactly("C:\\logs", "C:\\logs", "C:\\settings", "C:\\settings");
        assertThat(opened).containsExactlyInAnyOrder("logs", "settings");
    }

    /** LS-03: in the smallest real window (706 x 435 of scene) both paths are whole on screen, never cut. */
    @Test
    void longPathsAreReadableWholeInTheSmallestWindow() {
        String logs = "C:\\Users\\a.very.long.user.name\\AppData\\Local\\Prism\\SystemResourceManager-UILab\\logs";
        String settings =
                "C:\\Users\\a.very.long.user.name\\AppData\\Local\\Prism" + "\\SystemResourceManager-UILab\\settings";
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                706,
                435,
                () -> new SettingsPage(new SettingsPage.Hooks(
                        ThemeMode.SYSTEM, mode -> {}, logs, settings, () -> {}, () -> {}, Optional.empty())),
                root -> {
                    List<Object> facts = new ArrayList<>();
                    for (Node node : root.lookupAll(".settings-folder-path")) {
                        Label path = (Label) node;
                        StringBuilder shown = new StringBuilder();
                        for (Node part : path.lookupAll(".text")) {
                            shown.append(((javafx.scene.text.Text) part).getText());
                        }
                        // Wrapped lines are drawn whole; nothing is replaced by an ellipsis.
                        facts.add(shown.toString().replace("\n", ""));
                        facts.add(path.getHeight() >= path.prefHeight(path.getWidth()) - 0.5);
                        facts.add(path.localToScene(path.getLayoutBounds()).getMaxX() <= 706);
                    }
                    return facts;
                });

        assertThat(seen).containsExactly(logs, true, true, settings, true, true);
    }

    /** SET-2: the page offers no way to switch on automatic termination, and no switch at all. */
    @Test
    void thePageHasNoSwitchForAutomaticTermination() {
        Set<Node> checks = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(ThemeMode.SYSTEM, new ArrayList<>(), new ArrayList<>(), Optional.empty()),
                root -> root.lookupAll(".check-box"));

        assertThat(checks).noneMatch(CheckBox.class::isInstance);
    }

    @Test
    void aProblemWithTheFileIsSaidByItsFolderApartFromTheSaveOutcome() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(
                        ThemeMode.SYSTEM, new ArrayList<>(), new ArrayList<>(), Optional.of("The file was damaged.")),
                root -> {
                    Label load = (Label) root.lookup(".settings-load-problem");
                    Label save = (Label) root.lookup(".settings-save-problem");
                    SettingsPage settings = (SettingsPage) root;
                    List<Object> facts = new ArrayList<>();
                    facts.add(load.getText());
                    facts.add(load.isVisible());
                    facts.add(save.isVisible());
                    // Said in the Locations group, by the folder of the file, not under Appearance.
                    facts.add(load.getParent().getStyleClass().contains("settings-locations"));
                    settings.showSaveOutcome(Optional.of("Could not save."));
                    facts.add(save.getText());
                    facts.add(load.getText());
                    settings.showSaveOutcome(Optional.empty());
                    facts.add(save.isVisible());
                    facts.add(load.isVisible());
                    return facts;
                });

        // LS-08: two events, two lines; a save that works does not undo what was found at start-up.
        assertThat(seen)
                .containsExactly(
                        "The file was damaged.",
                        true,
                        false,
                        true,
                        "Could not save.",
                        "The file was damaged.",
                        false,
                        true);
    }

    @Test
    void theSummaryShowsEveryRowInItsPlaceAndFillsItOnceItIsKnown() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> page(ThemeMode.SYSTEM, new ArrayList<>(), new ArrayList<>(), Optional.empty()),
                root -> {
                    SettingsPage settings = (SettingsPage) root;
                    List<Object> facts = new ArrayList<>();
                    facts.add(root.lookup(".settings-capability-status").isVisible());
                    // Eight rows of three cells, in their place, each still being read (LS-07).
                    facts.add(root.lookupAll(".settings-capability-reading").size());
                    facts.add(root.lookupAll(".settings-capability-header").size());
                    settings.showControlProfile(new ControlProfile(Map.of(), Map.of()));
                    facts.add(root.lookup(".settings-capability-status").isVisible());
                    // Five actions of three cells each, all unavailable since none is reported.
                    facts.add(root.lookupAll(".settings-capability-unavailable").size());
                    facts.add(root.lookupAll(".settings-capability-reading").size());
                    return facts;
                });

        assertThat(seen).containsExactly(true, 24, 3, false, 15, 9);
    }

    /**
     * LS-06: a screen reader meets a row of the summary as one element that says the function whole,
     * with the names of its columns, never a level or a mechanism alone.
     */
    @Test
    void eachRowOfTheSummaryIsOneSentenceThatNamesItsColumns() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> {
                    SettingsPage settings =
                            page(ThemeMode.SYSTEM, new ArrayList<>(), new ArrayList<>(), Optional.empty());
                    settings.showControlProfile(new ControlProfile(Map.of(), Map.of()));
                    return settings;
                },
                root -> {
                    List<Object> facts = new ArrayList<>();
                    Node row = root.lookup(".settings-capability-row");
                    facts.add(row.getAccessibleRole());
                    facts.add(row.getAccessibleText());
                    facts.add(root.lookupAll(".settings-capability-row").stream()
                            .allMatch(each -> each.getAccessibleRole() == javafx.scene.AccessibleRole.TEXT
                                    && each.getAccessibleText().contains(". Level: ")));
                    return facts;
                });

        assertThat(seen.get(0)).isEqualTo(javafx.scene.AccessibleRole.TEXT);
        // No action reported: each is unavailable, with the reason as how it works.
        assertThat((String) seen.get(1)).startsWith("End task. Level: Unavailable. How it works: ");
        assertThat(seen.get(2)).isEqualTo(true);
    }
}
