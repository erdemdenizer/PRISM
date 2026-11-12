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

    /**
     * Why there is no total, in full. A snapshot does not say why: on a system that does not say
     * which interfaces are physical there is never one, and a failed reading leaves one out. The
     * note under the graph names the reason where the interfaces show it (OP-07).
     */
    static final String NO_TOTAL_REASON = "There is no total: it is the sum over the physical adapters, and the"
            + " system did not say which are physical, or listed none, or reading them failed (then the log file"
            + " says why).";

    /** The same on the page, in the axis line of the graph, where the sentence would be cut. */
    static final String NO_TOTAL_CAPTION = "No total for the physical adapters";

    /** Wide enough for the largest rate, as "1023.9", and the part its unit has. */
    private static final double RATE_COLUMN_WIDTH = 104;

    private static final double LAYER_COLUMN_WIDTH = 80;

    NetworkSection(NetworkModel model, int historyCapacity) {
        getStyleClass().addAll("performance-section", "metric-network");

        MetricPanel rates = MetricPanel.dual(
                "Physical adapters, total",
                "Transfer rate, last 60 s",
                "Download",
                "Upload",
                model.rates(),
                historyCapacity,
                NO_TOTAL_CAPTION,
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
        heading.getStyleClass().add("section-heading");

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
