package prism.srm.ui.performance;

import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A table of devices under its heading.
 *
 * <p>It grows with the window only as far as its table does, which is as far as the rows need:
 * the room a short table cannot use goes to the graph above it instead of staying empty.
 */
final class DeviceList extends VBox {

    private final DeviceTable<?> table;

    DeviceList(Node heading, DeviceTable<?> table) {
        this.table = table;
        getStyleClass().add("device-list");
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().setAll(heading, table);
    }

    @Override
    protected double computeMaxHeight(double width) {
        return computePrefHeight(width) - table.prefHeight(width) + table.maxHeight(width);
    }
}
