package prism.srm.app;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import prism.srm.application.ActivityLog;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.HostPlatform;
import prism.srm.ui.LatestValueRelay;
import prism.srm.ui.format.ValueFormatter;
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
 *       before {@code init()}.
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

    private Path logFolder;
    private Logger log;
    private ActivityLog activity;
    private MonitoringRuntime runtime;

    @Override
    public void init() {
        // First: no logger may exist before the logging configuration can find its folder.
        // Absolute, so that the log file and the texts that name its folder cannot disagree.
        logFolder = HostPlatform.userFolders().logs().toAbsolutePath();
        log = LoggingSetup.start(logFolder, App.class);
        Thread.setDefaultUncaughtExceptionHandler(this::logUncaught);

        activity = new ActivityLog(ActivityLog.DEFAULT_CAPACITY, InstantSource.system());
        activity.record(
                ActivitySeverity.INFO,
                "Prism System Resource Manager started on Java " + System.getProperty("java.version") + " ("
                        + System.getProperty("java.vendor") + "), JavaFX "
                        + System.getProperty("javafx.runtime.version") + ".");
        activity.record(ActivitySeverity.INFO, "Detected system: " + HostPlatform.systemDescription() + ".");
        activity.record(ActivitySeverity.INFO, "Log folder: " + logFolder);
    }

    @Override
    public void start(Stage stage) {
        // The one place that reads the user's locale and time zone; everything below is handed
        // them explicitly.
        ValueFormatter formatter = new ValueFormatter(Locale.getDefault(Locale.Category.FORMAT));
        Shell shell = new Shell(
                formatter,
                ZoneId.systemDefault(),
                SystemMonitor.historyCapacity(SystemMonitor.DEFAULT_INTERVAL),
                logFolder.toString(),
                // Only hands the request to the runtime, which reads on a thread of its own.
                identity -> runtime.requestProcessDetails(identity));

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
        runtime = new MonitoringRuntime(
                PlatformSources::ofRunningSystem,
                System::nanoTime,
                activity,
                toUserInterface::offer,
                processesToUserInterface::offer,
                processDetailsToUserInterface::offer,
                failuresToUserInterface::offer,
                SHUTDOWN_BOUND,
                this::logUncaught);

        MainWindow.prepare(stage, shell);
        stage.show();

        runtime.start();
    }

    @Override
    public void stop() {
        // Nothing is logged here: a log line is a file write, and this is the JavaFX Application
        // Thread. The shutdown thread records that the shutdown began.
        // Null when start() failed before the runtime existed; there is nothing to stop then.
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** Logs what ended a thread. It handles nothing: the failure has already taken its course. */
    private void logUncaught(Thread thread, Throwable failure) {
        log.error("Uncaught failure on thread {}", thread.getName(), failure);
    }
}
