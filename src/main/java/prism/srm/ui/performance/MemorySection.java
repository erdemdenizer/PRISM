package prism.srm.ui.performance;

import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import prism.srm.ui.metric.FactView;
import prism.srm.ui.metric.MetricPanel;

/**
 * The Memory section: physical memory in use with its history, and below it the available and
 * the total memory and the swap space or pagefile.
 *
 * <p>Layout and binding only. Every node is created once, here.
 */
final class MemorySection extends VBox {

    MemorySection(MemoryModel model, int historyCapacity) {
        getStyleClass().add("performance-section");

        MetricPanel inUse = MetricPanel.single("Physical memory", "In use, last 60 s", model.inUse(), historyCapacity);
        VBox.setVgrow(inUse, Priority.ALWAYS);

        HBox facts = new HBox(
                new FactView("Available", model.available(), null),
                new FactView("Total", model.total(), null),
                new FactView("Swap/pagefile in use", model.swap(), null));
        facts.getStyleClass().add("fact-row");

        getChildren().setAll(inUse, facts);
    }
}
