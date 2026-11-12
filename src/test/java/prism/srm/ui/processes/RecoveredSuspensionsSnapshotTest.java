package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspensionReason;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The list of what an earlier session may have left suspended, as its dialog shows it (ADR-0011):
 * an entry Prism offers to resume, one only the user can handle outside Prism, and the line about
 * a request. A dialog is not in the snapshots of the page, so the list is rendered on its own here.
 */
@Tag("ui-snapshot")
class RecoveredSuspensionsSnapshotTest {

    private static List<RecoveredSuspension> entries() {
        return List.of(
                LimitsFixtures.recovered(
                        LimitsFixtures.identity(30_324),
                        "java.exe",
                        EnumSet.of(SuspensionReason.USER),
                        true,
                        "Whether it is still suspended is checked when you ask to resume it."),
                LimitsFixtures.recovered(
                        LimitsFixtures.identity(18_812),
                        "a-build-tool-with-a-long-name-from-the-earlier-session.exe",
                        EnumSet.of(SuspensionReason.CPU_LIMITER),
                        false,
                        "Prism did not record which threads it suspended, and a suspension count does not show whose"
                                + " it is. Prism changes nothing. If the process is stuck, resume it outside Prism, for"
                                + " example in Resource Monitor."));
    }

    private static VBox list(boolean withResult) {
        return list(withResult, prism.srm.domain.RecoveryCheck.COMPLETE);
    }

    private static VBox list(boolean withResult, prism.srm.domain.RecoveryCheck check) {
        RecoveredSuspensions model = new RecoveredSuspensions(request -> {});
        model.show(entries());
        model.showCheck(check);
        RecoveredSuspensionsPane pane = new RecoveredSuspensionsPane(model, entry -> {});
        if (withResult) {
            RecoveredSuspension first = model.rows().get(0);
            model.resume(first);
            model.accept(new RecoveryResult(
                    new RecoveryRequest(RecoveryRequest.Action.RESUME, first.identity(), first.name()),
                    ControlOutcome.of(prism.srm.domain.OperationResult.INVALID_ARGUMENT, "x"),
                    "Prism did not resume java.exe (PID 30324).",
                    "Another suspension also holds it. Prism changed nothing."));
        }
        VBox root = new VBox(pane);
        root.getStylesheets().addAll(Stylesheets.control(), Stylesheets.processes());
        root.setStyle("-fx-padding: 16; -fx-background-color: -srm-surface;");
        return root;
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void rendersTheListAndTheLineAboutARequest(Theme theme) {
        Path listed = SnapshotHarness.render("recovered-list", theme, 760, 260, () -> list(false));
        Path answered = SnapshotHarness.render("recovered-list-result", theme, 760, 260, () -> list(true));

        Path incomplete = SnapshotHarness.render(
                "recovered-list-incomplete",
                theme,
                760,
                300,
                () -> list(false, prism.srm.domain.RecoveryCheck.EXHAUSTED));

        Path pending = SnapshotHarness.render(
                "recovered-list-pending", theme, 760, 300, () -> list(false, prism.srm.domain.RecoveryCheck.PENDING));
        assertThat(pending).isRegularFile();
        assertThat(listed).isRegularFile();
        assertThat(answered).isRegularFile();
        assertThat(incomplete).isRegularFile();
    }
}
