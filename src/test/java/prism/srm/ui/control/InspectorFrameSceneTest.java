package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Fix round G (K2, LO-25, LO-07, PR-02): the frame of an inspector keeps its pinned part (the name, the
 * actions, the line about them) out of the part that scrolls while it has the room for it and the least
 * of the details; in less, the pinned part scrolls with the details rather than leave them no room. A
 * hairline at the edge of the room says there is more to see, and only then. The sheet has no bar of its
 * own: its close button is its first stop; the reasons of a group of actions take its width; the line
 * under the table names the selection with its last result.
 */
@Tag("ui-snapshot")
class InspectorFrameSceneTest {

    private static final double PINNED = 120;

    private static final double DETAILS = 400;

    @Test
    void withRoomThePinnedPartStaysOutOfTheScrollAndTheEdgeShowsOnlyWhereThereIsMore() {
        List<Object> seen = inFrame(frame -> {
            List<Object> facts = new ArrayList<>();
            // Room for all: pinned, nothing to scroll, no edge.
            facts.add(lay(frame, PINNED + DETAILS + 10));
            facts.add(frame.isPinned());
            facts.add(frame.showsMoreCue());
            // Room for the pinned part and some details: pinned, the details scroll, the edge below.
            facts.add(lay(frame, PINNED + InspectorFrame.LEAST_DETAILS + 40));
            facts.add(frame.isPinned());
            facts.add(frame.showsMoreCue());
            frame.scrollDetailsToEnd();
            frame.layout();
            // Scrolled to the end: still more to see, above.
            facts.add(frame.showsMoreCue());
            // Less than the pinned part and the least of the details: the pinned part scrolls too.
            facts.add(lay(frame, PINNED + InspectorFrame.LEAST_DETAILS - 10));
            facts.add(frame.isPinned());
            // And back: pinned again.
            facts.add(lay(frame, PINNED + DETAILS + 10));
            facts.add(frame.isPinned());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        PINNED + DETAILS + 10,
                        true,
                        false,
                        PINNED + InspectorFrame.LEAST_DETAILS + 40,
                        true,
                        true,
                        true,
                        PINNED + InspectorFrame.LEAST_DETAILS - 10,
                        false,
                        PINNED + DETAILS + 10,
                        true);
    }

    /**
     * Fix round H (PR-03, LO-06): a pinned part with a compact arrangement takes it where the frame is too
     * low for it whole and the least of the details, and stays pinned; only where even the compact part
     * does not fit does it scroll with the details. With room again it is whole. The frame's preferred
     * height is the whole part's even while it is compact, so a page with the room gives it back.
     */
    @Test
    void aLowFrameTurnsItsPinnedPartCompactAndKeepsItPinned() {
        List<Object> seen = inFrame(frame -> {
            Region pinned = (Region) ((VBox) frame.getChildrenUnmodifiable().get(0))
                    .getChildren()
                    .get(0);
            frame.setCompactable(compact -> {
                double height = compact ? PINNED / 2 : PINNED;
                pinned.setMinHeight(height);
                pinned.setPrefHeight(height);
                pinned.setMaxHeight(height);
            });
            List<Object> facts = new ArrayList<>();
            lay(frame, PINNED + InspectorFrame.LEAST_DETAILS + 40);
            facts.add(frame.isCompact());
            facts.add(frame.isPinned());
            lay(frame, PINNED + InspectorFrame.LEAST_DETAILS - 10);
            facts.add(frame.isCompact());
            facts.add(frame.isPinned());
            facts.add(frame.prefHeight(300) >= PINNED + DETAILS);
            lay(frame, PINNED / 2 + InspectorFrame.LEAST_DETAILS - 10);
            facts.add(frame.isPinned());
            lay(frame, PINNED + DETAILS + 10);
            facts.add(frame.isCompact());
            facts.add(frame.isPinned());
            return facts;
        });

        assertThat(seen).containsExactly(false, true, true, true, true, false, false, true);
    }

    /** Fix round H: details shorter than the least of them, or none, never unpin the part above them. */
    @Test
    void shortDetailsKeepThePinnedPartPinned() {
        boolean pinned = SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                800,
                () -> {
                    InspectorFrame frame = new InspectorFrame(new VBox(block(PINNED)), block(20));
                    StackPane holder = new StackPane(frame);
                    holder.setMaxWidth(300);
                    return new VBox(holder);
                },
                root -> {
                    InspectorFrame frame = (InspectorFrame) root.lookup(".inspector-frame");
                    lay(frame, PINNED + 20);
                    return frame.isPinned();
                });

        assertThat(pinned).isTrue();
    }

    @Test
    void itsPreferredHeightIsAllOfItAndItsLeastIsNone() {
        List<Object> seen = inFrame(frame -> List.of(
                frame.prefHeight(300) >= PINNED + DETAILS,
                frame.pinnedHeight(300) >= PINNED && frame.pinnedHeight(300) < PINNED + 1,
                frame.minHeight(300)));

        assertThat(seen).containsExactly(true, true, 0.0);
    }

    @Test
    void theSheetHasNoBarAndItsCloseButtonIsItsFirstStop() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                720,
                480,
                () -> {
                    SheetOverlay sheet = new SheetOverlay("Process details", "Details of demo", () -> null);
                    sheet.setContent(new VBox(new Label("demo"), new Button("End task…")));
                    StackPane root = new StackPane(sheet);
                    return root;
                },
                root -> {
                    SheetOverlay sheet = (SheetOverlay) root.lookup(".sheet-overlay");
                    sheet.open(null);
                    root.applyCss();
                    root.layout();
                    List<Node> stops = sheet.focusStops();
                    Node panel = sheet.lookup(".sheet");
                    return List.of(
                            sheet.lookup(".sheet-bar") == null,
                            stops.size(),
                            stops.get(0).getStyleClass().contains("sheet-close"),
                            panel.getAccessibleRoleDescription());
                });

        assertThat(seen).containsExactly(true, 2, true, "Process details");
    }

    @Test
    void theReasonsOfAGroupTakeItsWidth() {
        double width = SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                100,
                () -> new StackPane(new DisabledReasons()),
                root -> ((Region) root.lookup(".disabled-reasons")).getMaxWidth());

        assertThat(width).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void theSelectionLineNamesTheProcessWithItsLastResult() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                720,
                120,
                () -> {
                    ResultLineState state = new ResultLineState();
                    state.showText("Close request sent to demo (PID 10).");
                    SelectionLine line = new SelectionLine(
                            new SimpleStringProperty("demo"), new SimpleStringProperty("PID 10"), state);
                    return new StackPane(line);
                },
                root -> {
                    root.applyCss();
                    root.layout();
                    List<String> texts = root.lookupAll(".label").stream()
                            .map(Label.class::cast)
                            .map(Label::getText)
                            .filter(text -> !text.isEmpty())
                            .toList();
                    return List.of(
                            texts.contains("demo"),
                            texts.contains("PID 10"),
                            texts.stream().anyMatch(text -> text.startsWith("Close request sent")));
                });

        assertThat(seen).containsExactly(true, true, true);
    }

    /** Lays the frame out at a height; returns the height it took. */
    private static double lay(InspectorFrame frame, double height) {
        StackPane holder = (StackPane) frame.getParent();
        holder.setMinHeight(height);
        holder.setPrefHeight(height);
        holder.setMaxHeight(height);
        for (int pass = 0; pass < 3; pass++) {
            holder.getParent().applyCss();
            holder.getParent().layout();
        }
        return frame.getHeight();
    }

    private static <T> T inFrame(java.util.function.Function<InspectorFrame, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                800,
                () -> {
                    Region pinned = block(PINNED);
                    VBox pinnedPart = new VBox(pinned);
                    Region details = block(DETAILS);
                    InspectorFrame frame = new InspectorFrame(pinnedPart, details);
                    StackPane holder = new StackPane(frame);
                    holder.setMaxWidth(300);
                    VBox root = new VBox(holder);
                    return root;
                },
                root -> scenario.apply((InspectorFrame) root.lookup(".inspector-frame")));
    }

    private static Region block(double height) {
        Region block = new Region();
        block.setMinHeight(height);
        block.setPrefHeight(height);
        block.setMaxHeight(height);
        block.setPrefWidth(280);
        return block;
    }
}
