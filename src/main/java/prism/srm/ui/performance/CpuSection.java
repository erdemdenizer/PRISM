package prism.srm.ui.performance;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import prism.srm.ui.graph.HistoryGrid;
import prism.srm.ui.metric.FactView;
import prism.srm.ui.metric.FactsPanel;
import prism.srm.ui.metric.MetricPanel;

/**
 * The CPU section: the utilization of the whole machine with its history, below it one small
 * history per logical processor, and the facts about the processor at the bottom.
 *
 * <p>Layout and binding only. Every node is created once, here: the histories of all logical
 * processors are drawn on one Canvas, so the section has as many nodes on a machine with 64
 * logical processors as on one with 4.
 */
final class CpuSection extends VBox {

    /**
     * The share of the window the utilization of the whole machine keeps however many processors
     * there are (OP-17): with 128 the grid took the page and left the main graph at its 64 px.
     */
    private static final double TOTAL_SHARE_OF_WINDOW = 0.3;

    /** The least share worth keeping: more than the panel needs for its header and a 64 px graph. */
    private static final double TOTAL_LEAST_SHARE = 160;

    /** The height of one row of cells: the smallest height of a history (docs/UI_GUIDE.md). */
    private static final double GRID_ROW = 64;

    CpuSection(CpuModel model, int historyCapacity) {
        getStyleClass().addAll("performance-section", "metric-cpu");

        MetricPanel total = MetricPanel.single("Total CPU", "Utilization, last 60 s", model.total(), historyCapacity);
        VBox.setVgrow(total, Priority.ALWAYS);
        keepAShareOfTheWindow(total);
        VBox processors = processors(model, historyCapacity);
        VBox.setVgrow(processors, Priority.ALWAYS);

        getChildren().setAll(total, processors, facts(model));
    }

    /**
     * Gives the panel at least a share of the height of the window, where that is more than it needs
     * anyway. Of the window, not of the page: the page grows with what it holds, and a share of it would
     * feed back into its own height.
     */
    private static void keepAShareOfTheWindow(Region panel) {
        ChangeListener<Number> height = (observable, before, after) -> {
            double share = after.doubleValue() * TOTAL_SHARE_OF_WINDOW;
            // Below that the panel needs its own least height anyway, which the stylesheet decides.
            panel.setMinHeight(share > TOTAL_LEAST_SHARE ? share : Region.USE_COMPUTED_SIZE);
        };
        panel.sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.heightProperty().removeListener(height);
            }
            if (after != null) {
                after.heightProperty().addListener(height);
                height.changed(after.heightProperty(), null, after.getHeight());
            }
        });
    }

    private static VBox processors(CpuModel model, int historyCapacity) {
        Label title = new Label("Logical processors");
        title.getStyleClass().add("text-section-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Label caption = new Label("Utilization, last 60 s");
        caption.getStyleClass().addAll("text-caption", "text-muted");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // The value at the top edge of every cell, above the right corner of the grid.
        Label scale = new Label();
        scale.textProperty().bind(model.processorsScaleProperty());
        scale.getStyleClass().addAll("text-caption", "text-muted");
        scale.setMinWidth(Region.USE_PREF_SIZE);
        HBox axis = new HBox(title, caption, gap, scale);
        axis.getStyleClass().add("section-heading");

        HistoryGrid grid = new HistoryGrid(historyCapacity);
        grid.setAccessibleText("Utilization of each logical processor, last 60 s");
        grid.show(model.processorsProperty().get());
        model.processorsProperty().addListener((observable, before, after) -> grid.show(after));

        // Shown where the cells will be while there are none: "Reading..." or "Unavailable".
        Label status = new Label();
        status.textProperty().bind(model.processorsStatusProperty());
        status.getStyleClass().add("text-muted");
        status.visibleProperty().bind(model.processorsStatusProperty().isNotEmpty());

        StackPane cells = new StackPane(grid, status);
        VBox.setVgrow(cells, Priority.ALWAYS);
        // While a sentence stands in for the cells, it takes the room of one row of them, not the
        // rest of the page (OP-09: a band of 600 px on an ultrawide window).
        status.textProperty()
                .addListener((observable, before, after) ->
                        cells.setMaxHeight(after.isEmpty() ? Double.MAX_VALUE : GRID_ROW));
        cells.setMaxHeight(status.getText().isEmpty() ? Double.MAX_VALUE : GRID_ROW);

        VBox processors = new VBox(axis, cells);
        processors.getStyleClass().add("processor-grid");
        return processors;
    }

    private static FactsPanel facts(CpuModel model) {
        // A long processor name is cut with an ellipsis; the tooltip has it in full.
        FactView name = FactView.truncating("Processor", model.name());
        // The only fact that gives way when the window is narrow.
        name.setMinWidth(0);
        return new FactsPanel(
                name,
                fixed(new FactView("Logical processors", model.logicalProcessors(), null)),
                fixed(new FactView("Physical cores", model.physicalCores(), null)),
                fixed(new FactView("Packages", model.packages(), null)));
    }

    private static FactView fixed(FactView fact) {
        fact.setMinWidth(Region.USE_PREF_SIZE);
        return fact;
    }
}
