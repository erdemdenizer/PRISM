package prism.srm.ui.performance;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.ui.metric.MetricPanel;

/**
 * The Network section: the download and upload rate of the physical adapters with their history,
 * and below it the table of all interfaces with their own rates.
 *
 * <p>Layout and binding only. Every node is created once, here; the table creates its cells for
 * the rows it has room for and keeps them.
 */
final class NetworkSection extends VBox {

    static final String NO_TOTAL_REASON = "No total was produced for this cycle. The total is the sum over the"
            + " physical adapters. If reading failed, the log file says why.";

    /** Wide enough for the largest rate, as "1023.9", and the part its unit has. */
    private static final double RATE_COLUMN_WIDTH = 104;

    private static final double LAYER_COLUMN_WIDTH = 80;

    NetworkSection(NetworkModel model, int historyCapacity) {
        getStyleClass().add("performance-section");

        MetricPanel rates = MetricPanel.dual(
                "Physical adapters, total",
                "Transfer rate, last 60 s",
                "Download",
                "Upload",
                model.rates(),
                historyCapacity,
                NO_TOTAL_REASON);
        VBox.setVgrow(rates, Priority.ALWAYS);

        Label title = new Label("Interfaces");
        title.getStyleClass().add("text-section-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // What the total above is made of, or why there is none.
        Label note = new Label();
        note.textProperty().bind(model.totalNoteProperty());
        note.getStyleClass().addAll("text-small", "text-muted");
        HBox heading = new HBox(title, gap, note);
        heading.getStyleClass().add("device-list-heading");

        DeviceTable<InterfaceRow> table =
                new DeviceTable<>(model.interfaces(), model.interfacesStatusProperty(), "Network interfaces");
        table.addTextColumn("Interface", InterfaceRow::displayNameProperty, 200);
        table.addFixedTextColumn("Layer", InterfaceRow::layerProperty, LAYER_COLUMN_WIDTH);
        table.addQuantityColumn("Receive", InterfaceRow::receiveProperty, "rate", RATE_COLUMN_WIDTH);
        table.addQuantityColumn("Send", InterfaceRow::sendProperty, "rate", RATE_COLUMN_WIDTH);

        DeviceList interfaces = new DeviceList(heading, table);
        VBox.setVgrow(interfaces, Priority.ALWAYS);

        getChildren().setAll(rates, interfaces);
    }
}
