package prism.srm.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * One running Prism per user profile (milestone 10, final multi-instance audit; ADR-0007 left the
 * rule open as a product decision, which is now taken). Two instances could otherwise each limit the
 * same process: one natively, the other emulated over it, as {@code ConcurrentInstancesTest} shows.
 *
 * <p>The ownership is an exclusive lock on a file in the user's state folder, held by the operating
 * system for this process: it goes when the process ends, also when it is killed, so the file is no
 * marker and never keeps a later launch out. Only the lock decides, never whether the file exists.
 * The lock is taken before monitoring, any native assignment or any recovery starts, and is kept
 * until the process exits, so that no other instance starts while this one still clears its limits
 * at shutdown. Anything that keeps ownership from being proven (the folder or file cannot be made,
 * the lock cannot be asked for) refuses the launch: two controllers are worse than none, an
 * accepted cost on a file system without working locks (ADR-0013).
 *
 * <p>One acquisition per process: on some systems closing any channel to the file lets go of every
 * lock this JVM holds on it, so a second acquisition in the same process, which closes its channel,
 * may end the first one's lock there. The application asks once; only tests ask more than once.
 */
final class InstanceLock implements AutoCloseable {

    /** The file whose lock is the ownership, in the state folder of the user. */
    static final String FILE = "prism.instance.lock";

    /** What asking for the ownership came to. */
    enum Outcome {
        /** This process owns the profile. */
        OWNED,
        /** Another Prism process, or another part of this one, owns it. */
        HELD_ELSEWHERE,
        /** Ownership could not be asked for: the launch is refused all the same. */
        FAILED
    }

    /** Opens the channel the lock is taken on; a test makes it fail. */
    @FunctionalInterface
    interface Opener {
        FileChannel open(Path file) throws IOException;
    }

    private final Outcome outcome;
    private final String problem;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(Outcome outcome, String problem, FileChannel channel, FileLock lock) {
        this.outcome = outcome;
        this.problem = problem;
        this.channel = channel;
        this.lock = lock;
    }

    /** How often a launch asks again, and how long it waits between: Windows may let go of a killed owner's lock late. */
    static final int LAUNCH_ATTEMPTS = 6;

    static final java.time.Duration LAUNCH_PAUSE = java.time.Duration.ofMillis(500);

    /**
     * The launch: asks, and while another owner holds the profile asks again a few times, as the
     * lock of a Prism just killed or still closing may be let go of only a moment later (ADR-0011,
     * late locks). At most about three seconds, on the launcher thread; never on the JavaFX thread.
     */
    static InstanceLock acquireAtLaunch(Path stateFolder) {
        InstanceLock lock = acquire(stateFolder);
        for (int attempt = 1; attempt < LAUNCH_ATTEMPTS && lock.outcome() == Outcome.HELD_ELSEWHERE; attempt++) {
            try {
                Thread.sleep(LAUNCH_PAUSE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return lock;
            }
            lock = acquire(stateFolder);
        }
        return lock;
    }

    /** Asks once for the ownership of the profile whose state folder this is. Blocking file I/O: never on the JavaFX thread. */
    static InstanceLock acquire(Path stateFolder) {
        return acquire(
                stateFolder, file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
    }

    static InstanceLock acquire(Path stateFolder, Opener opener) {
        Objects.requireNonNull(stateFolder, "stateFolder");
        Objects.requireNonNull(opener, "opener");
        Path file = stateFolder.resolve(FILE);
        FileChannel channel;
        try {
            Files.createDirectories(stateFolder);
            channel = opener.open(file);
        } catch (IOException | RuntimeException e) {
            return failed("the file that keeps a second Prism out cannot be opened (" + file + ": " + e + ")");
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            closeQuietly(channel);
            return new InstanceLock(Outcome.HELD_ELSEWHERE, "", null, null);
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            return failed("it cannot be told whether another Prism runs (" + file + ": " + e + ")");
        }
        if (lock == null) {
            closeQuietly(channel);
            return new InstanceLock(Outcome.HELD_ELSEWHERE, "", null, null);
        }
        return new InstanceLock(Outcome.OWNED, "", channel, lock);
    }

    private static InstanceLock failed(String problem) {
        return new InstanceLock(Outcome.FAILED, problem, null, null);
    }

    Outcome outcome() {
        return outcome;
    }

    boolean owned() {
        return outcome == Outcome.OWNED && lock != null && lock.isValid();
    }

    /** Why the launch is refused, in words for the user; empty when this process owns the profile. */
    Optional<String> refusal() {
        return switch (outcome) {
            case OWNED -> Optional.empty();
            case HELD_ELSEWHERE ->
                Optional.of("Prism is already running for this user, or is still closing. Only one Prism may run"
                        + " at a time, so that two never limit the same process: switch to the window that is open,"
                        + " or try again in a moment.");
            case FAILED ->
                Optional.of("Prism cannot start: " + problem + ". Only one Prism may run at a time, and this one"
                        + " cannot make sure that it is the only one.");
        };
    }

    /**
     * Lets go of the ownership: for a launch that failed before monitoring began, and for tests. A
     * running Prism keeps it until the process exits, when the system lets go of it.
     */
    @Override
    public void close() {
        if (lock != null) {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
                // Closing the channel next lets go of the lock in any case.
            }
        }
        if (channel != null) {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing is left to do for a channel that cannot be closed.
        }
    }
}
