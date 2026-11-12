package prism.srm.application;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.AppSettings;

/**
 * The settings file of the user (SET-1, SET-2, ADR-0014): read once at start-up, written whole after
 * each change. Nothing here ever prevents start-up: a file that is missing, unreadable, damaged or of
 * a later version means the defaults and a line in the log. The location is given; the store creates
 * the folder when it first writes.
 *
 * <p>Threads: {@link #load} on the thread that starts the application, before anything is monitored;
 * {@link #save} from any thread, which only hands the newest settings to the writing executor (the
 * journal's): the file is never written on the JavaFX thread, and several changes that wait are
 * written once, as the newest.
 */
public final class SettingsStore {

    /** The name of the file in its folder. */
    public static final String FILE_NAME = "prism.settings";

    static final int MOVE_ATTEMPTS = 3;

    private static final String NOT_SAVED =
            "Prism could not save this. It stays in force for this session; the log says why.";

    private static final String NEWER_FILE = "The settings file belongs to a later version of Prism. Prism uses the"
            + " defaults, and what you change here lasts for this session only.";

    private final Path folder;
    private final Path file;
    private final Path temporary;
    private final JournalFiles files;
    private final ActivityLog activity;
    private final InstantSource clock;
    private final Executor writes;
    private final AtomicReference<AppSettings> waiting = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();

    /** False once a file of a later version was found: this Prism never overwrites it. */
    private volatile boolean saveAllowed = true;

    /** What {@link #load} found wrong with the file, for the Settings page; empty when nothing. */
    private volatile Optional<String> loadProblem = Optional.empty();

    /** Told the end of every save: empty when it was written, otherwise why not. */
    private volatile Consumer<Optional<String>> saveOutcome = outcome -> {};

    /**
     * @param folder where the file is, from {@code UserFolders.settings()}
     * @param writes where a save runs: the journal's thread
     */
    public SettingsStore(Path folder, JournalFiles files, ActivityLog activity, InstantSource clock, Executor writes) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.file = folder.resolve(FILE_NAME);
        this.temporary = folder.resolve(FILE_NAME + ".tmp");
        this.files = Objects.requireNonNull(files, "files");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    /** Where the file is. */
    public Path file() {
        return file;
    }

    /** Whether a save may write: false after a file of a later version was found. */
    public boolean saveAllowed() {
        return saveAllowed;
    }

    /**
     * What the last {@link #load} found wrong with the file, as a sentence for the user; empty for a
     * file that was read and for one that does not exist yet.
     */
    public Optional<String> loadProblem() {
        return loadProblem;
    }

    /**
     * Who is told the end of every save, on the writing thread: empty when it was written, otherwise a
     * sentence that says why not. It must return at once.
     */
    public void onSaveOutcome(Consumer<Optional<String>> receiver) {
        saveOutcome = Objects.requireNonNull(receiver, "receiver");
    }

    /** Reads the settings; the defaults when there is no file or it cannot be taken as written. */
    public AppSettings load() {
        byte[] bytes;
        try {
            bytes = files.read(file);
        } catch (NoSuchFileException absent) {
            activity.record(ActivitySeverity.INFO, "There is no settings file yet; the defaults are used: " + file);
            return AppSettings.DEFAULTS;
        } catch (IOException | RuntimeException unreadable) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "The settings file could not be read (" + unreadable + "); the defaults are used: " + file);
            loadProblem = Optional.of("The settings file could not be read, so Prism uses the defaults: " + file);
            return AppSettings.DEFAULTS;
        }
        SettingsCodec.Decoded decoded = SettingsCodec.decode(bytes);
        switch (decoded.status()) {
            case NEWER -> {
                saveAllowed = false;
                activity.record(
                        ActivitySeverity.WARNING,
                        "The settings file was written by a later version of Prism ("
                                + String.join("; ", decoded.problems()) + "). The defaults are used, and this"
                                + " session does not change the file: " + file);
                loadProblem = Optional.of(NEWER_FILE);
                return AppSettings.DEFAULTS;
            }
            case CORRUPT -> {
                Path aside = moveAside();
                activity.record(
                        ActivitySeverity.WARNING,
                        "The settings file is damaged (" + String.join("; ", decoded.problems())
                                + "). The defaults are used"
                                + (aside == null
                                        ? "; the file could not be moved aside: " + file
                                        : "; the file is kept as " + aside));
                loadProblem = Optional.of("The settings file was damaged, so Prism uses the defaults. "
                        + (aside == null ? "It could not be moved aside: " + file : "It is kept as " + aside));
                return AppSettings.DEFAULTS;
            }
            case READ -> {
                for (String problem : decoded.problems()) {
                    activity.record(ActivitySeverity.WARNING, "Settings: " + problem + ".");
                }
                activity.record(ActivitySeverity.INFO, "Settings read from " + file);
                return decoded.settings();
            }
        }
        throw new IllegalStateException("Unknown status " + decoded.status());
    }

    /**
     * Hands the settings to the writing thread and returns at once. A save that cannot be queued is a
     * warning; the settings in memory stay in force, and the next change writes the file.
     */
    public void save(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        waiting.set(settings);
        // One task at a time on the shared journal thread: a burst of changes never fills its queue.
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            writes.execute(this::writeWaiting);
        } catch (RejectedExecutionException refused) {
            queued.set(false);
            activity.record(
                    ActivitySeverity.WARNING,
                    "The settings were not saved now (" + refused.getMessage()
                            + "); they stay in force, and the next change saves them.");
            saveOutcome.accept(Optional.of(NOT_SAVED));
        }
    }

    private void writeWaiting() {
        queued.set(false);
        AppSettings next = waiting.getAndSet(null);
        if (next == null) {
            return;
        }
        if (!saveAllowed) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "The settings were not saved: the file belongs to a later version of Prism. They stay in force"
                            + " for this session.");
            saveOutcome.accept(Optional.of(NEWER_FILE));
            return;
        }
        try {
            write(SettingsCodec.encode(next));
            activity.record(ActivitySeverity.INFO, "Settings saved: appearance " + next.appearance() + ".");
            saveOutcome.accept(Optional.empty());
        } catch (IOException | RuntimeException failed) {
            deleteQuietly(temporary);
            activity.record(
                    ActivitySeverity.WARNING,
                    "The settings could not be saved (" + failed + "); they stay in force for this session: " + file);
            saveOutcome.accept(Optional.of(NOT_SAVED));
        }
    }

    /** The journal's way: a new file forced to the device, moved over the old one, read back. */
    private void write(byte[] bytes) throws IOException {
        files.createFolder(folder);
        files.writeDurably(temporary, bytes);
        IOException lastMove = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                files.moveAtomically(temporary, file);
                lastMove = null;
                break;
            } catch (IOException e) {
                lastMove = e;
            }
        }
        if (lastMove != null) {
            throw lastMove;
        }
        files.forceFolder(folder);
        if (!Arrays.equals(files.read(file), bytes)) {
            throw new IOException("the settings file did not read back as it was written");
        }
    }

    /** Moves a damaged file aside once, so that the next start finds none; null when that failed. */
    private Path moveAside() {
        Path aside = file.resolveSibling(FILE_NAME + ".corrupt-" + clock.millis());
        try {
            files.moveAtomically(file, aside);
            return aside;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            files.delete(path);
        } catch (IOException | RuntimeException e) {
            // A temporary file left behind is overwritten by the next save.
        }
    }
}
