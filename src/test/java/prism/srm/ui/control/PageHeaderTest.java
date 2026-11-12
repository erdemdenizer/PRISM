package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The header keeps its title and, at the right end, one trailing node: the latest one given. */
@Tag("ui-snapshot")
class PageHeaderTest {

    @Test
    void theTitleIsTheFirstChildAndTheTrailingNodeTheLastOne() {
        List<String> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                60,
                () -> {
                    PageHeader header = new PageHeader("Processes");
                    header.setTrailing(new Label("394 processes"));
                    return header;
                },
                root -> texts(root));

        assertThat(seen).containsExactly("Processes", "394 processes");
    }

    @Test
    void aSecondTrailingNodeTakesThePlaceOfTheFirst() {
        List<String> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                60,
                () -> {
                    PageHeader header = new PageHeader("Processes");
                    header.setTrailing(new Label("first"));
                    header.setTrailing(new Label("second"));
                    return header;
                },
                root -> texts(root));

        assertThat(seen).containsExactly("Processes", "second");
    }

    private static List<String> texts(Node root) {
        List<String> texts = new ArrayList<>();
        for (Node child : ((PageHeader) root).getChildren()) {
            if (child instanceof Label label) {
                texts.add(label.getText());
            }
        }
        return texts;
    }
}
