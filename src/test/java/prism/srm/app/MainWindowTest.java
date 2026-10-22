package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.shell.Shell;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The application window as it is prepared before it is shown, read from a real stage. The stage
 * is never shown, so nothing appears on the screen.
 *
 * <p>The sizes are those of docs/UI_GUIDE.md, in JavaFX units. That a window keeps the smallest
 * size when it is moved to a monitor with another scale is {@link MinimumWindowSizeTest} for the
 * rule and a check by hand for the real thing.
 */
@Tag("ui-snapshot")
class MainWindowTest {

    @Test
    void aPreparedStageHasTheTitleTheDefaultContentSizeAndTheSmallestSize() {
        List<Object> seen = onTheApplicationThread(() -> {
            Stage stage = new Stage();
            Shell shell = new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, 60, "logs", identity -> {});

            MainWindow.prepare(stage, shell);

            return List.of(
                    stage.getTitle(),
                    stage.getMinWidth(),
                    stage.getMinHeight(),
                    stage.getScene().getWidth(),
                    stage.getScene().getHeight(),
                    stage.getScene().getRoot() == shell,
                    stage.getScene().getStylesheets().contains(Stylesheets.app()),
                    stage.isShowing(),
                    stage.isResizable());
        });

        assertThat(seen)
                .containsExactly("Prism System Resource Manager", 720.0, 480.0, 1100.0, 700.0, true, true, false, true);
    }

    /** No upper bound is set: the window may be made as large as the monitor allows. */
    @Test
    void aPreparedStageHasNoLargestSize() {
        List<Double> seen = onTheApplicationThread(() -> {
            Stage stage = new Stage();
            MainWindow.prepare(stage, new StackPane());
            return List.of(stage.getMaxWidth(), stage.getMaxHeight());
        });

        assertThat(seen).containsExactly(Double.MAX_VALUE, Double.MAX_VALUE);
    }

    /** Runs on the JavaFX Application Thread, where a stage has to be created. */
    private static <T> T onTheApplicationThread(Supplier<T> work) {
        return SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> work.get());
    }
}
