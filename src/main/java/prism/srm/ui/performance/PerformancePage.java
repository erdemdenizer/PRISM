package prism.srm.ui.performance;

import java.util.EnumMap;
import java.util.Map;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.PageNotice;

/**
 * The Performance page: a detailed view each for CPU, memory, disk and network, one at a time,
 * chosen in the row at the top.
 *
 * <p>All four views are built once, with the page. Choosing one puts it in the place of the one
 * shown; nothing is built again. Until the first snapshot arrives every part says that it is
 * being read; a part the system has no value for says so in its own place; a failed start, and
 * monitoring that ended while the application ran, are explained at the top of the page, and then
 * no value is shown.
 */
public final class PerformancePage extends VBox {

    /** Where the section on screen is among the children: below the notice and the tabs. */
    private static final int SECTION_INDEX = 2;

    private final PerformanceViewModel model;
    private final Map<Section, Region> sections = new EnumMap<>(Section.class);

    /**
     * @param formatter formats the values in the notation of the user's locale
     * @param historyCapacity the number of samples the histories hold when they are full
     */
    public PerformancePage(ValueFormatter formatter, int historyCapacity) {
        model = new PerformanceViewModel(formatter);
        getStyleClass().add("performance");
        getStylesheets().addAll(Stylesheets.metric(), Stylesheets.table(), Stylesheets.performance());

        sections.put(Section.CPU, new CpuSection(model.cpu(), historyCapacity));
        sections.put(Section.MEMORY, new MemorySection(model.memory(), historyCapacity));
        sections.put(Section.DISK, new DiskSection(model.disk(), historyCapacity));
        sections.put(Section.NETWORK, new NetworkSection(model.network(), historyCapacity));
        for (Region section : sections.values()) {
            VBox.setVgrow(section, Priority.ALWAYS);
        }

        Section selected = model.selectedProperty().get();
        getChildren()
                .setAll(new PageNotice(model.status()), new SectionTabs(selected, model::select), section(selected));
        model.selectedProperty()
                .addListener((observable, before, after) -> getChildren().set(SECTION_INDEX, section(after)));
    }

    /** Shows a snapshot. Call on the JavaFX Application Thread. */
    public void show(SystemSnapshot snapshot) {
        model.update(snapshot);
    }

    /**
     * Shows that monitoring could not start. Call on the JavaFX Application Thread.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void showStartupFailure(String logFolder) {
        model.startupFailed(logFolder);
    }

    /**
     * Shows that system monitoring has ended and takes the values off the page: they would
     * otherwise stand there as if they were current. Call on the JavaFX Application Thread.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void showMonitoringStopped(String logFolder) {
        model.monitoringStopped(logFolder);
    }

    private Region section(Section section) {
        return sections.get(section);
    }
}
