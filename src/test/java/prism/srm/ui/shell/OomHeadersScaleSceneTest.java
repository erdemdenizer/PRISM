package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableCell;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Every column header of the two tables of the OOM Guard page, and every cell of a column measured from
 * the texts it may hold, is drawn whole at the display scales of
 * Windows (UI audit LO-26, fix round H): at 125 % the owner saw "Stat…" over a Status column whose rows
 * held only "–". A column measured from its texts must keep room for its header as the header is laid
 * out at that scale, where the padding of the header snaps to device pixels.
 */
@Tag("ui-snapshot")
class OomHeadersScaleSceneTest {

    static Stream<Arguments> cases() {
        List<Arguments> all = new ArrayList<>();
        // The scene of a 1366 x 768 and of a 1100 x 700 window at 125 % (fix brief K8), and the same
        // windows at 150 %, 175 % and 200 %.
        double[][] scenes = {{1352, 730.4}, {1085.6, 662.4}, {705.6, 442.4}};
        for (double scale : new double[] {1.0, 1.25, 1.5, 1.75, 2.0}) {
            for (double[] scene : scenes) {
                for (String state : List.of("oom-off", "oom-normal", "oom-emergency-acted")) {
                    all.add(Arguments.of(state, scale, scene));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} at {1} in {2}")
    @MethodSource("cases")
    void everyHeaderIsDrawnWhole(String name, double scale, double[] scene) {
        Consumer<Shell> state = OomSnapshotTest.cases().get(name);
        List<String> cut = ScaledScenes.inspect(
                Theme.DARK,
                scene[0],
                scene[1],
                scale,
                () -> {
                    Shell shell = OomSnapshotTest.oomShell();
                    state.accept(shell);
                    return shell;
                },
                OomHeadersScaleSceneTest::cutHeaders);
        assertThat(cut).as("LO-26: headers drawn cut at %s %%", scale * 100).isEmpty();
    }

    /** The headers and the cells of measured columns whose text is drawn otherwise than it is, as "drawn ≠ whole". */
    private static List<String> cutHeaders(Parent root) {
        Node page = ((Shell) root).pageArea().getContent();
        List<String> cut = new ArrayList<>();
        for (String table : List.of(".oom-table", ".oom-recent")) {
            for (Node header : page.lookup(table).lookupAll(".column-header")) {
                for (Node node : header.lookupAll(".label")) {
                    if (node instanceof Labeled labeled
                            && labeled.getText() != null
                            && !labeled.getText().isEmpty()) {
                        String drawn = labeled.getChildrenUnmodifiable().stream()
                                .filter(Text.class::isInstance)
                                .map(text -> ((Text) text).getText())
                                .findFirst()
                                .orElse("");
                        if (!drawn.equals(labeled.getText())) {
                            cut.add(drawn + " ≠ " + labeled.getText());
                        }
                    }
                }
            }
        }
        // The cells of the columns measured from the texts they may hold; a failure in Status is cut
        // on purpose, at the widest fixed status, and has its tooltip.
        List<String> measured = List.of("Emergency action", "Status", "Priority", "Time", "State", "Action");
        for (String table : List.of(".oom-table", ".oom-recent")) {
            for (Node node : page.lookup(table).lookupAll(".table-cell")) {
                if (node instanceof TableCell<?, ?> cell
                        && cell.isVisible()
                        && cell.getTableColumn() != null
                        && cell.getTableColumn().getText() != null
                        && measured.contains(cell.getTableColumn().getText())
                        && cell.getText() != null
                        && !cell.getText().contains("failed")) {
                    Node text = cell.lookup(".text");
                    String drawn = text instanceof Text shown ? shown.getText() : "";
                    if (!drawn.equals(cell.getText())) {
                        cut.add(drawn + " ≠ " + cell.getText());
                    }
                }
            }
        }
        return cut;
    }
}
