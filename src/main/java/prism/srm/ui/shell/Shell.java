package prism.srm.ui.shell;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.OverviewPage;
import prism.srm.ui.performance.PerformancePage;
import prism.srm.ui.processes.ProcessesPage;
import prism.srm.ui.shell.StatusBar.Monitoring;

/**
 * The content of the application window: the navigation on the left, the current page in the
 * middle, the status bar at the bottom.
 *
 * <p>The shell does no monitoring. It is handed each system snapshot, each listing of the
 * processes, and the news of what has failed, on the JavaFX Application Thread, and passes them
 * to what it shows. What has failed stays failed: a monitor that ended is not started again, so a
 * snapshot or a listing that was already on its way when its monitor ended is not shown as if it
 * were live.
 *
 * <p>The pages are built once, with the shell. Selecting a destination puts its page into the
 * page area; nothing else of the window changes and nothing is built again. A snapshot or a
 * listing goes to the page on screen only, so a page costs nothing while it is not shown; the
 * page that is selected is given the newest one before it appears.
 */
public final class Shell extends BorderPane {

    private final OverviewPage overview;
    private final ProcessesPage processes;
    private final PerformancePage performance;

    /** The destinations that have a page, each with its page. */
    private final Map<Destination, Region> pages = new EnumMap<>(Destination.class);

    private final ScrollPane pageArea;
    private final StatusBar statusBar = new StatusBar();
    private final String logFolder;
    private final Set<MonitoringFailure> failures = EnumSet.noneOf(MonitoringFailure.class);
    private Destination shown;
    private boolean snapshotSeen;
    private boolean processesSeen;

    /** The newest snapshot; {@code null} before the first one and once its values are not live. */
    private SystemSnapshot latest;

    /**
     * The newest listing of the processes; {@code null} before the first one and once process
     * monitoring is not running. It is the only listing kept, for the page that is not on screen.
     */
    private List<ProcessSnapshot> latestProcesses;

    /**
     * @param formatter formats values in the notation of the user's locale
     * @param zone the time zone moments are shown in
     * @param historyCapacity the number of samples the histories hold when they are full
     * @param logFolder where the log file is, as text for the user; named when something fails
     * @param processDetailsRequest asks for the path and the command line of a process and
     *     returns at once; the answer is handed to {@link #showProcessDetails}
     */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest) {
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder");
        getStyleClass().add("shell");
        getStylesheets().add(Stylesheets.shell());
        overview = new OverviewPage(formatter, historyCapacity);
        processes = new ProcessesPage(formatter, zone, logFolder, processDetailsRequest);
        performance = new PerformancePage(formatter, historyCapacity);
        // The pages that exist so far; the other destinations are listed, disabled.
        pages.put(Destination.OVERVIEW, overview);
        pages.put(Destination.PROCESSES, processes);
        pages.put(Destination.PERFORMANCE, performance);
        Sidebar sidebar = new Sidebar(pages.keySet());
        sidebar.selectedProperty().addListener((observable, before, after) -> showPage(after));
        shown = sidebar.selected();
        pageArea = pageArea(pages.get(shown));
        setLeft(sidebar);
        setCenter(pageArea);
        setBottom(statusBar);
    }

    /**
     * The page fills the area and grows with the window. Only when the area is smaller than the
     * least the page needs, as with a long message in the smallest window, does the area scroll
     * instead of pushing the status bar out of the window.
     */
    private static ScrollPane pageArea(Region page) {
        ScrollPane area = new ScrollPane(page);
        area.getStyleClass().add("page-area");
        area.setFitToWidth(true);
        area.setFitToHeight(true);
        return area;
    }

    /** Shows a system snapshot. Call on the JavaFX Application Thread. */
    public void showSnapshot(SystemSnapshot snapshot) {
        if (!Monitoring.systemValuesAreLive(failures)) {
            // Sent before system monitoring ended and arriving after the news of it.
            return;
        }
        snapshotSeen = true;
        latest = snapshot;
        showLatestSnapshotOn(shown);
        showStatus();
    }

    /**
     * Shows a listing of all processes. Call on the JavaFX Application Thread.
     *
     * @param listing one snapshot per running process
     */
    public void showProcesses(List<ProcessSnapshot> listing) {
        if (!Monitoring.processValuesAreLive(failures)) {
            // Sent before process monitoring ended and arriving after the news of it.
            return;
        }
        processesSeen = true;
        // No copy is made of a listing that cannot be modified, which those of the monitor are.
        latestProcesses = List.copyOf(listing);
        showLatestProcessesOn(shown);
    }

    /**
     * Takes the result of a read of process details. Call on the JavaFX Application Thread.
     *
     * <p>The Processes page is given it whether it is on screen or not: it is the answer to a
     * question that page asked, and the page drops an answer it no longer wants.
     */
    public void showProcessDetails(ProcessDetailsResult result) {
        processes.showDetails(result);
    }

    /**
     * Shows what has failed. Call on the JavaFX Application Thread.
     *
     * <p>If monitoring could not start, or system monitoring has ended, every page of system
     * metrics says so and shows no values. If only process monitoring has ended, those pages go
     * on as before, the status bar says that monitoring is degraded, and the Processes page says
     * so and shows no processes. The Processes page goes on when only system monitoring has
     * ended, unless no process was listed before: process monitoring starts with a system
     * snapshot, so the page then says that it may get none.
     *
     * @param newFailures every failure so far; telling the shell again what it knows changes
     *     nothing
     */
    public void showFailures(Set<MonitoringFailure> newFailures) {
        failures.addAll(newFailures);
        if (!Monitoring.systemValuesAreLive(failures)) {
            // No page may be given this snapshot later as if it were current.
            latest = null;
        }
        if (!Monitoring.processValuesAreLive(failures)) {
            latestProcesses = null;
        }
        if (failures.contains(MonitoringFailure.STARTUP)) {
            overview.showStartupFailure(logFolder);
            performance.showStartupFailure(logFolder);
            processes.showStartupFailure();
        } else {
            if (failures.contains(MonitoringFailure.SYSTEM_MONITORING_STOPPED)) {
                overview.showMonitoringStopped(logFolder);
                performance.showMonitoringStopped(logFolder);
            }
            if (failures.contains(MonitoringFailure.PROCESS_MONITORING_STOPPED)) {
                processes.showMonitoringStopped();
            } else if (Monitoring.processesMayNeverBeListed(failures, processesSeen)) {
                processes.showProcessesNotListed();
            }
        }
        showStatus();
    }

    /** Puts the page of a destination into the page area, with the newest values on it. */
    private void showPage(Destination destination) {
        shown = destination;
        showLatestSnapshotOn(destination);
        showLatestProcessesOn(destination);
        pageArea.setContent(pages.get(destination));
        pageArea.setVvalue(0);
        pageArea.setHvalue(0);
    }

    /**
     * Gives the page of a destination the newest system snapshot, if the page shows system
     * metrics and there is a snapshot. A snapshot is nothing to the Processes page: it is not
     * given its listing again because a snapshot arrived.
     */
    private void showLatestSnapshotOn(Destination destination) {
        if (latest == null) {
            return;
        }
        switch (destination) {
            case OVERVIEW -> overview.show(latest);
            case PERFORMANCE -> performance.show(latest);
            // The Processes page shows listings; the others are listed in the navigation, disabled.
            case PROCESSES, LIMITS, OOM_GUARD, LOGS, SETTINGS -> {}
        }
    }

    /** Gives the Processes page the newest listing, if it is the page of the destination. */
    private void showLatestProcessesOn(Destination destination) {
        if (destination == Destination.PROCESSES && latestProcesses != null) {
            processes.show(latestProcesses);
        }
    }

    private void showStatus() {
        statusBar.show(Monitoring.of(failures, snapshotSeen), logFolder);
    }
}
