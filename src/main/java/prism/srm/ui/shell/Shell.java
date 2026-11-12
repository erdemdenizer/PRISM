package prism.srm.ui.shell;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspendedProcess;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.format.OomWords;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.limits.LimitsPage;
import prism.srm.ui.logs.LogsPage;
import prism.srm.ui.oom.OomGuardPage;
import prism.srm.ui.overview.OverviewPage;
import prism.srm.ui.performance.PerformancePage;
import prism.srm.ui.performance.Section;
import prism.srm.ui.processes.ProcessesPage;
import prism.srm.ui.settings.SettingsPage;
import prism.srm.ui.shell.StatusBar.Monitoring;

/**
 * The content of the application window: the navigation on the left, the current page in the
 * middle, the status bar at the bottom.
 *
 * <p>UI lab v2 (lab-docs/LAB_DESIGN_SPEC.md, sections 2 and 7). The navigation takes the form the
 * width gives it (K1, {@link Sidebar.Mode}): a sidebar with names from {@link
 * Breakpoints#LABELLED_NAV_FROM}, below it the rail of {@link Breakpoints#RAIL_WIDTH}, each icon with
 * its name under it. The navigation is at the left of every window and always names its destinations
 * (owner decision after fix round H: a name only on hover is not understood). The shell follows its own
 * size with pseudo-classes the stylesheets use ({@link Breakpoints}): {@code narrow} below 820, {@code
 * wide} from 1100, {@code ultrawide} from 2200 and {@code low} below 560 high. Ctrl+1 to Ctrl+7 choose a
 * destination. An open sheet of a page ({@link prism.srm.ui.control.SheetOverlay}) closes when the focus
 * leaves it for the navigation, or when its page leaves the screen.
 *
 * <p>The page toolbar: the title line of the page on screen (its {@link PageHeader}) stands above the
 * area that scrolls, so the title and the tools of the page stay in view when a page is longer than
 * the window. The shell takes it from the page when the page is built; the page lays out the rest.
 *
 * <p>The shell does no monitoring. It is handed each system snapshot, each listing of the
 * processes, and the news of what has failed, on the JavaFX Application Thread, and passes them
 * to what it shows. What has failed stays failed: a monitor that ended is not started again, so a
 * snapshot or a listing that was already on its way when its monitor ended is not shown as if it
 * were live.
 *
 * <p>The pages are built once, with the shell. Selecting a destination puts its page into the
 * page area, with a short fade as it comes in; nothing else of the window changes and nothing is
 * built again. A snapshot or a
 * listing goes to the page on screen only, so a page costs nothing while it is not shown; the
 * page that is selected is given the newest one before it appears.
 */
public final class Shell extends BorderPane {

    private final OverviewPage overview;
    private final ProcessesPage processes;
    private final PerformancePage performance;
    private final LimitsPage limits;
    private final OomGuardPage oom;
    private final LogsPage logs;
    private final SettingsPage settings;
    private final OomWords oomWords;

    /** The destinations that have a page, each with its page. */
    private final Map<Destination, Region> pages = new EnumMap<>(Destination.class);

    private final ScrollPane pageArea;
    /** The title lines of the pages, taken from them; the one of the page on screen is in the toolbar. */
    private final Map<Destination, PageHeader> headers = new EnumMap<>(Destination.class);

    private final StackPane toolbar = new StackPane();
    private final PageTransition pageTransition = new PageTransition();
    private final StatusBar statusBar = new StatusBar();
    private final Sidebar sidebar;
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
     * The newest state of the activity log, for the Logs page when it comes on screen; {@code null}
     * before the first, while the page says it is reading the log.
     */
    private List<ActivityEntry> latestActivity;

    /**
     * The page whose button asked last to open a folder: the end of the request is said there. The
     * text names the folder, so an end said on the other page after two quick requests is still read
     * right.
     */
    private Destination folderAsker = Destination.SETTINGS;

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
        this(formatter, zone, historyCapacity, logFolder, processDetailsRequest, request -> {}, () -> {});
    }

    /**
     * @param processAction carries out an action on a process and returns at once; the result is
     *     handed to {@link #showControlResult}
     * @param openLogFolder opens the folder of the log file in the system's file manager
     */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest,
            Consumer<ControlRequest> processAction,
            Runnable openLogFolder) {
        this(
                formatter,
                zone,
                historyCapacity,
                logFolder,
                processDetailsRequest,
                processAction,
                request -> {},
                request -> {},
                openLogFolder);
    }

    /**
     * @param limitAction manages a process or changes its limits and returns at once; the result is
     *     handed to {@link #showLimitResult}
     * @param recoveryAction resumes or dismisses a process an earlier session may have left
     *     suspended and returns at once; the result is handed to {@link #showRecoveryResult}
     */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest,
            Consumer<ControlRequest> processAction,
            Consumer<LimitRequest> limitAction,
            Consumer<RecoveryRequest> recoveryAction,
            Runnable openLogFolder) {
        this(
                formatter,
                zone,
                historyCapacity,
                logFolder,
                processDetailsRequest,
                processAction,
                limitAction,
                recoveryAction,
                openLogFolder,
                OomSettings.DEFAULTS,
                settings -> {},
                identity -> {});
    }

    /**
     * @param oomSettings the OOM settings read at start-up, shown until OOM Guard reports
     * @param oomSettingsChange publishes changed OOM settings and returns at once
     * @param oomRelease ends what OOM Guard holds on a process and returns at once
     */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest,
            Consumer<ControlRequest> processAction,
            Consumer<LimitRequest> limitAction,
            Consumer<RecoveryRequest> recoveryAction,
            Runnable openLogFolder,
            OomSettings oomSettings,
            Consumer<OomSettings> oomSettingsChange,
            Consumer<ProcessIdentity> oomRelease) {
        this(
                formatter,
                zone,
                historyCapacity,
                logFolder,
                processDetailsRequest,
                processAction,
                limitAction,
                recoveryAction,
                openLogFolder,
                oomSettings,
                oomSettingsChange,
                oomRelease,
                SettingsPage.Hooks.inert(logFolder));
    }

    /** With an activity log of the default capacity, for a shell with no application behind it. */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest,
            Consumer<ControlRequest> processAction,
            Consumer<LimitRequest> limitAction,
            Consumer<RecoveryRequest> recoveryAction,
            Runnable openLogFolder,
            OomSettings oomSettings,
            Consumer<OomSettings> oomSettingsChange,
            Consumer<ProcessIdentity> oomRelease,
            SettingsPage.Hooks settingsHooks) {
        this(
                formatter,
                zone,
                historyCapacity,
                logFolder,
                processDetailsRequest,
                processAction,
                limitAction,
                recoveryAction,
                openLogFolder,
                oomSettings,
                oomSettingsChange,
                oomRelease,
                settingsHooks,
                ActivityLog.DEFAULT_CAPACITY);
    }

    /**
     * @param settingsHooks what the Settings page shows at first, and what it changes and opens
     * @param activityCapacity the number of entries the activity log keeps, as the application built it
     */
    public Shell(
            ValueFormatter formatter,
            ZoneId zone,
            int historyCapacity,
            String logFolder,
            Consumer<ProcessIdentity> processDetailsRequest,
            Consumer<ControlRequest> processAction,
            Consumer<LimitRequest> limitAction,
            Consumer<RecoveryRequest> recoveryAction,
            Runnable openLogFolder,
            OomSettings oomSettings,
            Consumer<OomSettings> oomSettingsChange,
            Consumer<ProcessIdentity> oomRelease,
            SettingsPage.Hooks settingsHooks,
            int activityCapacity) {
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder");
        this.oomWords = new OomWords(formatter);
        getStyleClass().add("shell");
        getStylesheets().add(Stylesheets.shell());
        overview = new OverviewPage(formatter, historyCapacity);
        processes = new ProcessesPage(
                formatter, zone, logFolder, processDetailsRequest, processAction, limitAction, recoveryAction);
        performance = new PerformancePage(formatter, historyCapacity);
        limits = new LimitsPage(formatter, limitAction);
        oom = new OomGuardPage(formatter, zone, oomSettings, oomSettingsChange, limitAction, oomRelease);
        logs = new LogsPage(zone, activityCapacity, logFolder, () -> {
            folderAsker = Destination.LOGS;
            openLogFolder.run();
        });
        settings = new SettingsPage(new SettingsPage.Hooks(
                settingsHooks.appearance(),
                settingsHooks.appearanceChange(),
                settingsHooks.logFolder(),
                settingsHooks.settingsFolder(),
                () -> {
                    folderAsker = Destination.SETTINGS;
                    settingsHooks.openLogFolder().run();
                },
                () -> {
                    folderAsker = Destination.SETTINGS;
                    settingsHooks.openSettingsFolder().run();
                },
                settingsHooks.loadProblem()));
        pages.put(Destination.OVERVIEW, overview);
        pages.put(Destination.PROCESSES, processes);
        pages.put(Destination.PERFORMANCE, performance);
        pages.put(Destination.LIMITS, limits);
        pages.put(Destination.OOM_GUARD, oom);
        pages.put(Destination.LOGS, logs);
        pages.put(Destination.SETTINGS, settings);
        for (Map.Entry<Destination, Region> entry : pages.entrySet()) {
            entry.getValue().getStyleClass().add("page");
            liftHeader(entry.getValue()).ifPresent(header -> headers.put(entry.getKey(), header));
        }
        toolbar.getStyleClass().add("page-toolbar");
        sidebar = new Sidebar(pages.keySet());
        // UI-1: each part of the Overview leads to its page (overview area, round G).
        overview.setOnOpen(this::openFromOverview);
        sidebar.selectedProperty().addListener((observable, before, after) -> showPage(after));
        shown = sidebar.selected();
        pageArea = pageArea(pages.get(shown));
        showHeader(shown);
        VBox.setVgrow(pageArea, Priority.ALWAYS);
        VBox center = new VBox(toolbar, pageArea);
        center.getStyleClass().add("page-column");
        setLeft(sidebar);
        setCenter(center);
        setBottom(statusBar);

        addEventFilter(KeyEvent.KEY_PRESSED, this::shortcut);
        widthProperty().addListener((observable, before, after) -> adapt());
        heightProperty().addListener((observable, before, after) -> adapt());
    }

    // ---- adaptive layout (UI lab v2) ----

    private static final PseudoClass NARROW = PseudoClass.getPseudoClass("narrow");
    private static final PseudoClass WIDE = PseudoClass.getPseudoClass("wide");
    private static final PseudoClass ULTRAWIDE = PseudoClass.getPseudoClass("ultrawide");
    private static final PseudoClass LOW = PseudoClass.getPseudoClass("low");

    /** Sets the pseudo-classes of the size and gives the navigation the form of the width. */
    private void adapt() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        pseudoClassStateChanged(NARROW, width < Breakpoints.NARROW_BELOW);
        pseudoClassStateChanged(WIDE, width >= Breakpoints.WIDE_FROM);
        pseudoClassStateChanged(ULTRAWIDE, width >= Breakpoints.ULTRAWIDE_FROM);
        pseudoClassStateChanged(LOW, height < Breakpoints.LOW_BELOW);
        // K1: the sidebar from the width where it costs the page no inspector (Breakpoints), the rail
        // with names under the icons below it; the same nodes in another form, so crossing the line
        // builds nothing.
        sidebar.setMode(railMode(width));
    }

    /** The form of the navigation at the left of a window of this width. */
    private static Sidebar.Mode railMode(double width) {
        return width >= Breakpoints.LABELLED_NAV_FROM ? Sidebar.Mode.SIDEBAR : Sidebar.Mode.RAIL;
    }

    private boolean isInSidebar(Node node) {
        return isInside(node, sidebar);
    }

    private static boolean isInside(Node node, Node container) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ctrl+1 to Ctrl+7 choose a destination (K6, {@link Shortcuts}). A sheet open on the page closes with
     * it (A-M3). A modal dialog is a window of its own, so these keys do nothing while one is open.
     */
    private void shortcut(KeyEvent event) {
        Optional<Destination> destination = Shortcuts.destination(event);
        if (destination.isPresent()) {
            sidebar.select(destination.get());
            event.consume();
        }
    }

    /** The navigation; for tests. */
    Sidebar navigation() {
        return sidebar;
    }

    /** Where the keyboard begins: the selected entry of the navigation. */
    Node navigationStart() {
        return sidebar.selectedEntry();
    }

    /** The area that scrolls the page on screen, under the page toolbar; for tests. */
    ScrollPane pageArea() {
        return pageArea;
    }

    /** The page toolbar, which holds the title line of the page on screen; for tests. */
    StackPane toolbar() {
        return toolbar;
    }

    /**
     * Takes the title line from a page whose first child it is, so that the shell shows it in the page
     * toolbar, above the area that scrolls. A page without one keeps all it has.
     */
    private static Optional<PageHeader> liftHeader(Region page) {
        if (page instanceof Pane pane
                && !pane.getChildren().isEmpty()
                && pane.getChildren().get(0) instanceof PageHeader header) {
            pane.getChildren().remove(0);
            // The rules of the page (its toolbar, its count) were scoped to the page: they follow the
            // title line out of it, or its tools would lose their alignment and gaps in the toolbar.
            Bindings.bindContent(header.getStylesheets(), page.getStylesheets());
            return Optional.of(header);
        }
        return Optional.empty();
    }

    private void showHeader(Destination destination) {
        PageHeader header = headers.get(destination);
        if (header == null) {
            toolbar.getChildren().clear();
        } else {
            toolbar.getChildren().setAll(header);
        }
    }

    /**
     * The page fills the area and grows with the window. Only when the area is smaller than the
     * least the page needs, as with a long message in the smallest window or a grid of many
     * processor cells, does the area scroll instead of pushing the status bar out of the window.
     *
     * <p>The height is given to the page by hand: fitting the page to the height of the area
     * would size it by the least height it reports for no width at all, and a grid whose height
     * follows its width would then be cut off at the bottom instead of scrolling. It is given
     * again at every layout of the area, which the page asks for whenever it changes: the least
     * height of a page can change while the area keeps its size, as when its groups move closer
     * in the smallest window.
     */
    private static ScrollPane pageArea(Region page) {
        ScrollPane area = new ScrollPane(page) {
            @Override
            protected void layoutChildren() {
                fitPage(this);
                super.layoutChildren();
            }
        };
        area.getStyleClass().add("page-area");
        area.setFitToWidth(true);
        area.setFitToHeight(false);
        area.viewportBoundsProperty().addListener((observable, before, after) -> fitPage(area));
        return area;
    }

    /** Gives the page of the area the height of the viewport, or more if it needs more. */
    private static void fitPage(ScrollPane area) {
        if (!(area.getContent() instanceof Region page)) {
            return;
        }
        double width = area.getViewportBounds().getWidth();
        double height = area.getViewportBounds().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        page.setPrefHeight(Math.max(height, page.minHeight(width)));
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
     * The PID of Prism itself: the Processes page offers no action on its row and says why (CAP-2).
     * The services refuse such a request on their own as well (SAFE-2). Call on the JavaFX
     * Application Thread.
     */
    public void showOwnProcess(long pid) {
        processes.showOwnProcess(pid);
    }

    /**
     * Takes what process control is on this system: how each action is provided and which level
     * each listed priority stands for. Call on the JavaFX Application Thread.
     */
    public void showControlProfile(ControlProfile profile) {
        processes.showControlProfile(profile);
        oom.showControlProfile(profile);
        settings.showControlProfile(profile);
    }

    /**
     * Takes what OOM Guard shows: its page, the fact of the Overview and the sentence of the status
     * bar are given it whether they are on screen or not, which costs little: it comes about once a
     * second. Call on the JavaFX Application Thread.
     */
    public void showOom(OomView view) {
        oom.show(view);
        overview.showOom(view);
        settings.showOom(view);
        Optional<OomWords.Line> pressure = oomWords.pressure(view);
        statusBar.showPressure(pressure);
        // A real state only: the pressure sentence exists from WARNING up.
        sidebar.setBadge(
                Destination.OOM_GUARD,
                pressure.map(OomWords.Line::text),
                pressure.map(line -> line.tone() == OomWords.Tone.ERROR).orElse(false));
    }

    /**
     * Takes the processes Prism holds suspended. The Processes page is given them whether it is on
     * screen or not: once process monitoring has stopped, it is where they can still be resumed.
     * Call on the JavaFX Application Thread.
     */
    public void showHeldSuspensions(List<SuspendedProcess> held) {
        processes.showHeldSuspensions(held);
    }

    /**
     * Takes the result of an action on a process. The Processes page is given it whether it is on
     * screen or not: it answers a request that page made. Call on the JavaFX Application Thread.
     */
    public void showControlResult(ProcessControlResult result) {
        processes.showControlResult(result);
    }

    /**
     * Takes the managed processes and their limits. The Limits page, the marks of the Processes page
     * and the count of the Overview are given them whether they are on screen or not: there are few,
     * and the Limits page keeps its selection by them. Call on the JavaFX Application Thread.
     */
    public void showLimits(LimitsSnapshot snapshot) {
        limits.show(snapshot);
        processes.showLimits(snapshot);
        overview.showManaged(snapshot.processes().size());
        settings.showLimits(snapshot);
    }

    /**
     * Takes the result of a request about limits: for the page that sent it, the Processes page or
     * the Limits page, whether it is on screen or not. Call on the JavaFX Application Thread.
     */
    public void showLimitResult(LimitResult result) {
        limits.showResult(result);
        processes.showLimitResult(result);
        oom.showResult(result);
    }

    /**
     * Takes the processes an earlier session may have left suspended: the Processes page lists them,
     * the Overview counts them. Call on the JavaFX Application Thread.
     */
    public void showRecovered(List<RecoveredSuspension> entries) {
        processes.showRecovered(entries);
        overview.showLeftSuspended(entries.size());
    }

    /**
     * Takes whether the list of what an earlier session may have left suspended is whole; the
     * Processes page says so while it is not. Call on the JavaFX Application Thread.
     */
    public void showRecoveryCheck(RecoveryCheck check) {
        processes.showRecoveryCheck(check);
    }

    /** Takes the result of a Resume or a Dismiss of such a process. Call on the JavaFX Application Thread. */
    public void showRecoveryResult(RecoveryResult result) {
        processes.showRecoveryResult(result);
    }

    /**
     * Takes the activity log as it is now. Kept, and given to the Logs page while it is on screen.
     * Call on the JavaFX Application Thread.
     */
    public void showActivity(List<ActivityEntry> entries) {
        latestActivity = List.copyOf(entries);
        if (shown == Destination.LOGS) {
            logs.show(latestActivity);
        }
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
            settings.showStartupFailure(logFolder);
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

    /**
     * Takes the end of a save of the settings: empty when it was written, otherwise why not. Call on
     * the JavaFX Application Thread.
     */
    public void showSettingsSaved(Optional<String> problem) {
        settings.showSaveOutcome(problem);
    }

    /**
     * Takes the end of a request to open a folder: empty when the system was asked to, otherwise why
     * not. Call on the JavaFX Application Thread.
     */
    public void showFolderOpened(Optional<String> problem) {
        if (folderAsker == Destination.LOGS) {
            logs.showFolderOutcome(problem);
        } else {
            settings.showFolderOutcome(problem);
        }
    }

    /**
     * Shows the page of a part of the Overview (UI-1), as its entry in the navigation does: a metric
     * opens the Performance page at its section, chosen before the page comes in.
     */
    private void openFromOverview(OverviewPage.Part part) {
        switch (part) {
            case CPU -> performance.select(Section.CPU);
            case MEMORY -> performance.select(Section.MEMORY);
            case DISK -> performance.select(Section.DISK);
            case NETWORK -> performance.select(Section.NETWORK);
            case PROCESSES, LEFT_SUSPENDED, MANAGED, OOM_GUARD -> {}
        }
        sidebar.select(
                switch (part) {
                    case CPU, MEMORY, DISK, NETWORK -> Destination.PERFORMANCE;
                    case PROCESSES, LEFT_SUSPENDED -> Destination.PROCESSES;
                    case MANAGED -> Destination.LIMITS;
                    case OOM_GUARD -> Destination.OOM_GUARD;
                });
    }

    /** Puts the page of a destination into the page area, with the newest values on it. */
    private void showPage(Destination destination) {
        // The focus on a control of the page that goes, as after Ctrl+1 to Ctrl+7, would leave the
        // window with it: it starts again where the keyboard begins.
        Node focused = getScene() == null ? null : getScene().getFocusOwner();
        boolean focusLeaves = focused != null && isInside(focused, getCenter());
        shown = destination;
        showLatestSnapshotOn(destination);
        showLatestProcessesOn(destination);
        if (destination == Destination.LOGS && latestActivity != null) {
            logs.show(latestActivity);
        }
        Region page = pages.get(destination);
        showHeader(destination);
        // A sheet open on the page that goes closes with it (SheetOverlay): the page leaves the scene.
        pageArea.setContent(page);
        if (focusLeaves) {
            navigationStart().requestFocus();
        }
        fitPage(pageArea);
        pageArea.setVvalue(0);
        pageArea.setHvalue(0);
        pageTransition.playFor(page);
    }

    /** Whether a page is coming into the page area right now; for tests of the motion. */
    boolean pageComingIn() {
        return pageTransition.running();
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
            // These pages show no system metrics.
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
