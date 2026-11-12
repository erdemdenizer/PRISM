package prism.srm.app;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import prism.srm.application.ActivityLog;
import prism.srm.application.SettingsStore;
import prism.srm.application.SuspensionJournal;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.AppSettings;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.SuspendedProcess;
import prism.srm.domain.SystemSnapshot;
import prism.srm.domain.ThemeMode;
import prism.srm.platform.HostPlatform;
import prism.srm.ui.HighContrast;
import prism.srm.ui.LatestValueRelay;
import prism.srm.ui.Typefaces;
import prism.srm.ui.control.AlreadyRunningDialog;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.settings.SettingsPage;
import prism.srm.ui.shell.Shell;

/**
 * JavaFX application class and composition root: the only place where the user interface, the
 * application services and the platform are wired together.
 *
 * <p>What runs where:
 *
 * <ul>
 *   <li>{@link #init()}, on the launcher thread: the log folder is handed to the logging
 *       configuration, and only then is the first logger created ({@link LoggingSetup}). That is
 *       why this class has no static logger and no field with an initializer: nothing may run
 *       before {@code init()}. The bundled typeface is loaded here too, once, into the font
 *       registry of the toolkit; a failure is logged and the window falls back to the system
 *       font. The settings file is read here too (SET-1, ADR-0014): whatever it holds, or fails to,
 *       start-up goes on.
 *   <li>{@link #start(Stage)}, on the JavaFX Application Thread: the window is built and shown at
 *       once, with every part saying that it is being read. Creating the platform and starting
 *       the monitors is left to {@link MonitoringRuntime}, on its own thread.
 *   <li>{@link #stop()}, on the JavaFX Application Thread after the window has closed: it only
 *       asks for the controlled shutdown, which runs on its own thread.
 * </ul>
 */
public final class App extends Application {

    /** All the waiting the controlled shutdown may do before the process exits regardless. */
    private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(5);

    /** The entries the activity log keeps; the Logs page is told the same number. */
    private static final int ACTIVITY_CAPACITY = ActivityLog.DEFAULT_CAPACITY;

    private Path logFolder;
    private Logger log;
    private ActivityLog activity;
    private Typefaces typefaces;
    private ThemeController theme;
    private TitleBarTint titleBar;
    private HighContrast highContrast;
    private MonitoringRuntime runtime;
    private InstanceLock instance;
    private SettingsStore settingsStore;
    private FolderOpener folders;
    /** The settings as the user last chose them; the JavaFX Application Thread only after init. */
    private AppSettings settings;

    @Override
    public void init() {
        // First: no logger may exist before the logging configuration can find its folder.
        // Absolute, so that the log file and the texts that name its folder cannot disagree.
        logFolder = HostPlatform.userFolders().logs().toAbsolutePath();
        log = LoggingSetup.start(logFolder, App.class);
        Thread.setDefaultUncaughtExceptionHandler(this::logUncaught);

        // One Prism per user profile, decided before anything is monitored, limited or recovered:
        // a second one could limit a process this one already limits (milestone 10). Kept until the
        // process exits; the system lets go of it then, also after a hard kill.
        instance = InstanceLock.acquireAtLaunch(HostPlatform.userFolders().state());
        if (instance.refusal().isPresent()) {
            log.warn("Prism does not start: {}", instance.refusal().get());
            return;
        }

        activity = new ActivityLog(ACTIVITY_CAPACITY, InstantSource.system());
        activity.record(
                ActivitySeverity.INFO,
                "Prism System Resource Manager started on Java " + System.getProperty("java.version") + " ("
                        + System.getProperty("java.vendor") + "), JavaFX "
                        + System.getProperty("javafx.runtime.version") + ".");
        activity.record(ActivitySeverity.INFO, "Detected system: " + HostPlatform.systemDescription() + ".");
        activity.record(ActivitySeverity.INFO, "Log folder: " + logFolder);

        // The settings of the user (SET-1): read before anything is monitored, so the first sample is
        // judged under them. Written on the journal's thread, which exists once monitoring starts.
        settingsStore = new SettingsStore(
                HostPlatform.userFolders().settings().toAbsolutePath(),
                SuspensionJournal.onDisk(),
                activity,
                InstantSource.system(),
                this::onJournalThread);
        settings = settingsStore.load();

        // The third and last process-wide setting: the typeface, registered with the toolkit.
        typefaces = Typefaces.load();
        if (!typefaces.bundled()) {
            log.warn("The bundled typeface is not used, the system font is: {}", typefaces.problem());
        }
    }

    @Override
    public void start(Stage stage) {
        if (instance.refusal().isPresent()) {
            // Nothing is created: no monitor, no limit, no recovery; the other Prism is not touched.
            AlreadyRunningDialog.build(instance.refusal().get()).showAndWait();
            Platform.exit();
            return;
        }
        try {
            startOwned(stage);
        } catch (RuntimeException failed) {
            if (runtime == null) {
                // Nothing was started: the profile is let go of at once, not only when the JVM exits.
                instance.close();
            }
            throw failed;
        }
    }

    private void startOwned(Stage stage) {
        // The one place that reads the user's locale and time zone; everything below is handed
        // them explicitly.
        ValueFormatter formatter = new ValueFormatter(Locale.getDefault(Locale.Category.FORMAT));
        Path settingsFolder = HostPlatform.userFolders().settings().toAbsolutePath();
        // The holder lets the opener hand its results to the shell that is built with it.
        AtomicReference<Consumer<Optional<String>>> folderOutcome = new AtomicReference<>(outcome -> {});
        folders = new FolderOpener(
                FolderOpener::openInFileManager, outcome -> folderOutcome.get().accept(outcome), activity);
        Shell shell = new Shell(
                formatter,
                ZoneId.systemDefault(),
                SystemMonitor.historyCapacity(SystemMonitor.DEFAULT_INTERVAL),
                logFolder.toString(),
                // Only hands the request to the runtime, which reads on a thread of its own.
                identity -> runtime.requestProcessDetails(identity),
                // So does an action on a process; it runs on the control thread.
                request -> runtime.requestProcessAction(request),
                // A request about managed processes and limits runs on the limiter thread.
                request -> runtime.requestLimit(request),
                // A request about a process an earlier session left suspended runs on the control thread.
                request -> runtime.requestRecovery(request),
                () -> folders.open(logFolder),
                settings.oom(),
                // A change of the OOM settings is published at once and saved on the journal's thread.
                this::changeOomSettings,
                // Ending what OOM Guard holds runs on the limiter thread.
                identity -> runtime.releaseOomHold(identity),
                new SettingsPage.Hooks(
                        settings.appearance(),
                        this::changeAppearance,
                        logFolder.toString(),
                        settingsFolder.toString(),
                        () -> folders.open(logFolder),
                        () -> folders.open(settingsFolder),
                        settingsStore.loadProblem()),
                ACTIVITY_CAPACITY);
        // The same PID the services protect (PlatformSources): the page disables its actions too.
        shell.showOwnProcess(ProcessHandle.current().pid());
        // The ends of saves and of folder requests come from other threads; only the newest matters.
        LatestValueRelay<Optional<String>> savedToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showSettingsSaved);
        settingsStore.onSaveOutcome(savedToUserInterface::offer);
        LatestValueRelay<Optional<String>> folderToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showFolderOpened);
        folderOutcome.set(folderToUserInterface::offer);

        // Snapshots are made on the sampling thread once a second. The relay keeps the newest one
        // and has at most one task waiting on this thread, however slow a frame is.
        LatestValueRelay<SystemSnapshot> toUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showSnapshot);
        // The listings of the processes, every two seconds, take a bridge of the same kind.
        LatestValueRelay<List<ProcessSnapshot>> processesToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showProcesses);
        // So do the details of a process. Results arrive in the order they were asked for, and
        // the page wants only the one it asked for last, so keeping the newest loses nothing.
        LatestValueRelay<ProcessDetailsResult> processDetailsToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showProcessDetails);
        // What has failed takes the same kind of bridge. Every message holds all failures so
        // far, so keeping the newest loses nothing, and here too at most one task waits.
        LatestValueRelay<Set<MonitoringFailure>> failuresToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showFailures);
        // What process control is on this system comes once; a relay of the same kind is enough.
        LatestValueRelay<ControlProfile> controlProfileToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showControlProfile);
        // The processes Prism holds suspended, whole each time they change: only the newest matters.
        LatestValueRelay<List<SuspendedProcess>> heldToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showHeldSuspensions);
        // The activity log is handed over whole each time it changes; only the newest state matters.
        LatestValueRelay<List<ActivityEntry>> activityToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showActivity);
        activity.setListener(() -> activityToUserInterface.offer(activity.entries()));
        activityToUserInterface.offer(activity.entries());
        // What the Limits page shows changes with every measurement of every limit: only the newest matters.
        LatestValueRelay<LimitsSnapshot> limitsToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showLimits);
        // The processes an earlier session may have left suspended, whole each time the list changes.
        LatestValueRelay<List<RecoveredSuspension>> recoveredToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showRecovered);
        // Whether that list is whole: only the newest state matters.
        LatestValueRelay<RecoveryCheck> recoveryCheckToUserInterface =
                new LatestValueRelay<>(Platform::runLater, shell::showRecoveryCheck);
        // What OOM Guard shows comes with every evaluation, about once a second: only the newest matters.
        LatestValueRelay<OomView> oomToUserInterface = new LatestValueRelay<>(Platform::runLater, shell::showOom);
        runtime = new MonitoringRuntime(
                PlatformSources::ofRunningSystem,
                System::nanoTime,
                activity,
                new MonitoringRuntime.Listeners(
                        toUserInterface::offer,
                        processesToUserInterface::offer,
                        processDetailsToUserInterface::offer,
                        // Every result of an action is shown: each answers a request the user made and
                        // unlocks the actions on its process, so none may be dropped. There are few.
                        result -> Platform.runLater(() -> shell.showControlResult(result)),
                        controlProfileToUserInterface::offer,
                        heldToUserInterface::offer,
                        failuresToUserInterface::offer,
                        limitsToUserInterface::offer,
                        // The results of limit and recovery requests answer the user too: none is dropped.
                        result -> Platform.runLater(() -> shell.showLimitResult(result)),
                        recoveredToUserInterface::offer,
                        recoveryCheckToUserInterface::offer,
                        result -> Platform.runLater(() -> shell.showRecoveryResult(result)),
                        oomToUserInterface::offer),
                SHUTDOWN_BOUND,
                this::logUncaught,
                MonitoringRuntime.RECOVERY_RETRIES,
                settings.oom());
        // Runs when the JVM exits without the controlled shutdown, for instance on a console close
        // or SIGTERM (LIFE-5): what the CPU limiter holds is released; the user's suspensions stay.
        // The hook also keeps the ownership of the profile reachable until the JVM exits, so that no
        // collector closes it while the shutdown still clears native limits (milestone 10).
        InstanceLock owned = instance;
        Thread exitHook = new Thread(
                () -> {
                    runtime.releaseForExit();
                    java.lang.ref.Reference.reachabilityFence(owned);
                },
                "srm-exit-hook");
        exitHook.setUncaughtExceptionHandler(this::logUncaught);
        Runtime.getRuntime().addShutdownHook(exitHook);
        // A closed console ends the process on Windows as soon as the JVM's own handler returns, which
        // can be before the hook above has released anything: the same release runs first (LIFE-5).
        // The runtime bounds the release at 2.5 s, half the five seconds the system waits by default.
        // Registered off this thread: it is a native call.
        MonitoringRuntime released = runtime;
        Thread.ofPlatform()
                .name("srm-console-close")
                .uncaughtExceptionHandler(this::logUncaught)
                .start(() -> HostPlatform.onConsoleClose(released::releaseForExit));

        MainWindow.prepare(stage, shell, typefaces.bundled());
        // The appearance follows the operating system until the settings say otherwise (UI-7).
        theme = new ThemeController(stage.getScene());
        // The appearance of the settings file, chosen on the Settings page (UI-7).
        theme.modeProperty().set(settings.appearance());
        // UI lab v2: the system's caption and border in the colors of the theme, where the system allows it.
        titleBar = new TitleBarTint(HostPlatform.windowChrome(), theme.modeProperty(), log);
        // The content in the colors of a Windows high-contrast theme while one is on.
        highContrast = new HighContrast();
        stage.show();

        runtime.start();
    }

    @Override
    public void stop() {
        // Nothing is logged here: a log line is a file write, and this is the JavaFX Application
        // Thread. The shutdown thread records that the shutdown began.
        // Null when start() failed before the runtime existed; there is nothing to stop then.
        if (theme != null) {
            theme.close();
        }
        if (titleBar != null) {
            titleBar.close();
        }
        if (highContrast != null) {
            highContrast.close();
        }
        if (folders != null) {
            folders.close();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** Publishes changed OOM settings and saves them; on the JavaFX Application Thread, which touches no file. */
    private void changeOomSettings(OomSettings next) {
        settings = settings.withOom(next);
        runtime.publishOomSettings(next);
        settingsStore.save(settings);
    }

    /** Applies a chosen appearance at once and saves it; on the JavaFX Application Thread, which touches no file. */
    private void changeAppearance(ThemeMode next) {
        settings = settings.withAppearance(next);
        theme.modeProperty().set(next);
        settingsStore.save(settings);
    }

    /** Where the settings file is written: the journal's thread of the running monitoring. */
    private void onJournalThread(Runnable task) {
        MonitoringRuntime current = runtime;
        if (current == null) {
            throw new RejectedExecutionException("monitoring has not started");
        }
        current.executeOnJournalThread(task);
    }

    /** Logs what ended a thread. It handles nothing: the failure has already taken its course. */
    private void logUncaught(Thread thread, Throwable failure) {
        log.error("Uncaught failure on thread {}", thread.getName(), failure);
    }
}
