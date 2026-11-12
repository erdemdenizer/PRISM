package prism.srm.app;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivitySeverity;

/**
 * Asks the system's file manager to open a folder, off the JavaFX Application Thread, because the
 * folder is looked at first and starting the file manager takes its time. One thread at most, gone
 * when idle; a request made while one waits is dropped, as a second click on the same button would be.
 *
 * <p>The answer says what Prism knows: that the system was asked. The system does not say whether a
 * window opened.
 */
final class FolderOpener implements AutoCloseable {

    private static final String THREAD = "srm-open-folder";

    private final Consumer<Path> fileManager;
    private final Consumer<Optional<String>> outcome;
    private final ActivityLog activity;
    private final ThreadPoolExecutor executor;

    /**
     * @param fileManager opens a folder in the system's file manager; what it throws is a failure
     * @param outcome told the end of each request: empty when the system was asked, otherwise why not
     */
    FolderOpener(Consumer<Path> fileManager, Consumer<Optional<String>> outcome, ActivityLog activity) {
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.activity = Objects.requireNonNull(activity, "activity");
        executor = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, THREAD);
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        // Also what happens after close(): the application is closing, nobody looks at a folder.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    }

    /** Takes a request and returns at once. Any thread. */
    void open(Path folder) {
        Objects.requireNonNull(folder, "folder");
        executor.execute(() -> outcome.accept(tryOpen(folder)));
    }

    private Optional<String> tryOpen(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Optional.of(
                    "This folder does not exist yet: " + folder + ". Prism creates it when it first writes there.");
        }
        try {
            fileManager.accept(folder);
            return Optional.empty();
        } catch (RuntimeException failed) {
            activity.record(ActivitySeverity.WARNING, "The folder could not be opened: " + folder, failed);
            return Optional.of("The system could not open this folder: " + folder);
        }
    }

    /**
     * Through {@link Desktop}, the system's own association for a folder. Not {@code
     * HostServices.showDocument}, which is a browser launcher: on Windows it showed the folder as a
     * listing in the web browser. Runs on the thread of this class, so the AWT toolkit starts there, and
     * only if a folder is ever opened.
     *
     * @throws UnsupportedOperationException where the system offers no such thing
     * @throws UncheckedIOException where it could not open the folder
     */
    static void openInFileManager(Path folder) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new UnsupportedOperationException("No desktop integration here");
        }
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Drops what waits. A request in flight ends by itself. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
