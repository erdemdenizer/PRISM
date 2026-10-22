package prism.srm.ui.performance;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import prism.srm.ui.graph.HistoryGrid;
import prism.srm.ui.metric.FactView;
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

    CpuSection(CpuModel model, int historyCapacity) {
        getStyleClass().add("performance-section");

        MetricPanel total = MetricPanel.single("Total CPU", "Utilization, last 60 s", model.total(), historyCapacity);
        VBox.setVgrow(total, Priority.ALWAYS);
        VBox processors = processors(model, historyCapacity);
        VBox.setVgrow(processors, Priority.ALWAYS);

        getChildren().setAll(total, processors, facts(model));
    }

    private static VBox processors(CpuModel model, int historyCapacity) {
        Label caption = new Label("Logical processors, utilization, last 60 s");
        caption.getStyleClass().addAll("text-caption", "text-muted");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // The value at the top edge of every cell, above the right corner of the grid.
        Label scale = new Label();
        scale.textProperty().bind(model.processorsScaleProperty());
        scale.getStyleClass().addAll("text-caption", "text-muted");
        scale.setMinWidth(Region.USE_PREF_SIZE);
        HBox axis = new HBox(caption, gap, scale);
        axis.getStyleClass().add("metric-axis");

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

        VBox processors = new VBox(axis, cells);
        processors.getStyleClass().add("processor-grid");
        return processors;
    }

    private static HBox facts(CpuModel model) {
        // A long processor name is cut with an ellipsis; the tooltip has it in full.
        FactView name = FactView.truncating("Processor", model.name());
        // The only fact that gives way when the window is narrow.
        name.setMinWidth(0);
        HBox facts = new HBox(
                name,
                fixed(new FactView("Logical processors", model.logicalProcessors(), null)),
                fixed(new FactView("Physical cores", model.physicalCores(), null)),
                fixed(new FactView("Packages", model.packages(), null)));
        facts.getStyleClass().add("fact-row");
        return facts;
    }

    private static FactView fixed(FactView fact) {
        fact.setMinWidth(Region.USE_PREF_SIZE);
        return fact;
    }
}
