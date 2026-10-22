package prism.srm.ui.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Proves the snapshot pipeline end to end with stock controls. It is not a product screen; real
 * screens get their own snapshot tests next to this one.
 */
@Tag("ui-snapshot")
class SnapshotHarnessSmokeTest {

    @ParameterizedTest
    @EnumSource(Theme.class)
    void rendersStockControlsToPng(Theme theme) {
        Path png = SnapshotHarness.render("harness-smoke", theme, 720, 320, SnapshotHarnessSmokeTest::stockControls);

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Parent stockControls() {
        Button enabled = new Button("Action");
        Button primary = new Button("Default action");
        primary.setDefaultButton(true);
        Button disabled = new Button("Disabled");
        disabled.setDisable(true);
        TextField filter = new TextField();
        filter.setPromptText("Filter");

        HBox toolbar = new HBox(8, enabled, primary, disabled, filter);
        toolbar.setPadding(new Insets(8));

        TableView<String> table = new TableView<>();
        table.getColumns().add(new TableColumn<String, String>("Name"));
        table.getColumns().add(new TableColumn<String, String>("Value"));
        table.setPlaceholder(new Label("No rows"));

        Label status = new Label("Snapshot harness smoke test");
        status.getStyleClass().add("text-muted");
        status.setPadding(new Insets(4, 8, 4, 8));

        BorderPane root = new BorderPane(table);
        root.setTop(toolbar);
        root.setBottom(status);
        return root;
    }
}
