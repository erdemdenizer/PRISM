package prism.srm.ui.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.PageNotice;

/**
 * The Performance page: a detailed view each for CPU, memory, disk and network, one at a time,
 * chosen in the selector at the right end of the page toolbar.
 *
 * <p>All four views are built once, with the page. Choosing one puts it in the place of the one
 * shown; nothing is built again. Until the first snapshot arrives every part says that it is
 * being read; a part the system has no value for says so in its own place; a failed start, and
 * monitoring that ended while the application ran, are explained at the top of the page, and then
 * no value is shown.
 */
public final class PerformancePage extends VBox {

    private final PerformanceViewModel model;
    private final Map<Section, Region> sections = new EnumMap<>(Section.class);
    private final SectionTabs tabs;

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
        PageHeader header = new PageHeader("Performance");
        tabs = new SectionTabs(selected, model::select);
        header.setTrailing(tabs);
        getChildren().setAll(header, new PageNotice(model.status()), section(selected));
        // The section on screen is replaced where it stands: the title line, which holds the selector at
        // its right end, may be in the page toolbar of the shell instead of above it (UI lab v2).
        model.selectedProperty()
                .addListener((observable, before, after) ->
                        getChildren().set(getChildren().indexOf(section(before)), section(after)));
    }

    /**
     * Puts a section on screen, as choosing it in the selector does; for a page that leads to it
     * (the Overview, UI-1). Call on the JavaFX Application Thread.
     */
    public void select(Section section) {
        tabs.select(Objects.requireNonNull(section, "section"));
    }

    /** The section on screen. */
    public Section selected() {
        return model.selectedProperty().get();
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
