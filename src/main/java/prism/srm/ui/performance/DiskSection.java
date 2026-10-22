package prism.srm.ui.performance;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.ui.metric.MetricPanel;

/**
 * The Disk section: the read and write rate of the physical disks with their history, and below
 * it the table of the local file systems with their capacity.
 *
 * <p>Layout and binding only. Every node is created once, here; the table creates its cells for
 * the rows it has room for and keeps them.
 */
final class DiskSection extends VBox {

    static final String NO_TOTAL_REASON = "No total was produced for this cycle. The total is the sum over the"
            + " physical disks. If reading failed, the log file says why.";

    /** Wide enough for the largest size, as "1023.9", and the part its unit has. */
    private static final double SIZE_COLUMN_WIDTH = 96;

    DiskSection(DiskModel model, int historyCapacity) {
        getStyleClass().add("performance-section");

        MetricPanel rates = MetricPanel.dual(
                "Physical disks, total",
                "Transfer rate, last 60 s",
                "Read",
                "Write",
                model.rates(),
                historyCapacity,
                NO_TOTAL_REASON);
        VBox.setVgrow(rates, Priority.ALWAYS);

        Label title = new Label("File systems");
        title.getStyleClass().add("text-section-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // Why there is no total above, while there is none.
        Label note = new Label();
        note.textProperty().bind(model.totalNoteProperty());
        note.getStyleClass().addAll("text-small", "text-muted");
        HBox heading = new HBox(title, gap, note);
        heading.getStyleClass().add("device-list-heading");

        DeviceTable<FileSystemRow> table =
                new DeviceTable<>(model.fileSystems(), model.fileSystemsStatusProperty(), "File systems");
        // The preferred widths add up to less than the table has in the smallest window.
        table.addTextColumn("File system", FileSystemRow::nameProperty, 180);
        table.addTextColumn("Mount point", FileSystemRow::mountPointProperty, 100);
        table.addQuantityColumn("Capacity", FileSystemRow::capacityProperty, "size", SIZE_COLUMN_WIDTH);
        table.addQuantityColumn("Available", FileSystemRow::availableProperty, "size", SIZE_COLUMN_WIDTH);

        DeviceList fileSystems = new DeviceList(heading, table);
        VBox.setVgrow(fileSystems, Priority.ALWAYS);

        getChildren().setAll(rates, fileSystems);
    }
}
