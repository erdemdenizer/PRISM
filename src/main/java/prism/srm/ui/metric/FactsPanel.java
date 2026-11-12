package prism.srm.ui.metric;

import javafx.scene.layout.HBox;

/**
 * A row of facts on a surface of its own: the system facts of the Overview, the processor facts
 * of the Performance page. Without a title: the captions of the facts name what the row is.
 */
public final class FactsPanel extends HBox {

    public FactsPanel(FactView... facts) {
        getStyleClass().addAll("facts-panel", "panel", "panel-compact");
        getChildren().addAll(facts);
    }
}
