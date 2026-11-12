package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The reveal of the inspector, read from a real scene: it comes in once, when a process is
 * shown after none was, and not again when the selection moves to another process or a listing
 * brings new values; with reduced motion it is simply there. A started transition applies its
 * first frame at once, which is what is read here.
 */
@Tag("ui-snapshot")
class ProcessDetailsMotionSceneTest {

    private static final String LOG_FOLDER = "C:\\logs";

    @Test
    @SuppressWarnings("unchecked")
    void theInspectorComesInOnceWhenAProcessIsShownAfterNoneAndNotOnUpdates() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, identity -> {});
                    page.show(ProcessListings.windows(40, 0));
                    return page;
                },
                root -> {
                    ProcessesPage page = (ProcessesPage) root;
                    TableView<ProcessRow> table = (TableView<ProcessRow>) page.lookup(".process-table");
                    Node inspector = page.lookup(".process-details");
                    List<Object> facts = new ArrayList<>();
                    // Reduced motion: the inspector is simply there.
                    table.getSelectionModel().select(2);
                    facts.add(inspector.getOpacity());
                    table.getSelectionModel().clearSelection();
                    page.getScene().getPreferences().setReducedMotion(false);
                    // A process after none: the reveal starts, at opacity 0 and a few units down.
                    table.getSelectionModel().select(3);
                    facts.add(inspector.getOpacity() < 1 && inspector.getTranslateY() > 0);
                    inspector.setOpacity(1);
                    inspector.setTranslateY(0);
                    // Another process while one is shown: nothing moves.
                    table.getSelectionModel().select(4);
                    facts.add(inspector.getOpacity() == 1 && inspector.getTranslateY() == 0);
                    // A listing that changes the values of the shown process: nothing moves.
                    page.show(ProcessListings.windows(40, 1));
                    facts.add(inspector.getOpacity() == 1 && inspector.getTranslateY() == 0);
                    // Nothing, then a process again: the reveal again.
                    table.getSelectionModel().clearSelection();
                    table.getSelectionModel().select(1);
                    facts.add(inspector.getOpacity() < 1);
                    return facts;
                });

        assertThat(seen).containsExactly(1.0, true, true, true, true);
    }
}
