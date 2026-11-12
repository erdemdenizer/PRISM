package prism.srm.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The file operations of the suspension journal, the native-limit ledger and the settings file
 * (ADR-0014), one method per operation, so that a test can make
 * any of them fail the way a disk does (full, refused, a move the system will not make, a file that
 * reads back otherwise than it was written). {@link NioJournalFiles} is the implementation.
 */
public interface JournalFiles {

    /** A lock held on a file by this process. */
    interface Lock {
        /** Whether the lock is still held; false once the system or this process let go of it. */
        boolean valid();

        /** Lets go of the lock; safe to call more than once. */
        void release();
    }

    /** Creates the folder and those above it, readable by the owner only where the system has POSIX permissions. */
    void createFolder(Path folder) throws IOException;

    /** Writes the bytes to a new or emptied file and forces them to the device before returning. */
    void writeDurably(Path file, byte[] bytes) throws IOException;

    /** Moves a file over another as one step, replacing it. */
    void moveAtomically(Path from, Path to) throws IOException;

    /**
     * The bytes of the file. A file that is not there throws {@link java.nio.file.NoSuchFileException},
     * and only such a file: recovery takes that, and nothing else, as proof that a ledger is absent.
     */
    byte[] read(Path file) throws IOException;

    /** Deletes the file if it exists. */
    void delete(Path file) throws IOException;

    /**
     * The files of a folder; empty only if the folder does not exist. A folder that exists but
     * cannot be listed, or a path that is not a folder, throws: it is never taken for an empty one
     * (audit of the recovery trust boundary, D).
     */
    List<Path> list(Path folder) throws IOException;

    /**
     * Whether the file is there. False also when that cannot be told, as {@code Files.exists}: for
     * the suspension journal's own files only. Recovery of native limits asks {@link #state}, which
     * keeps "not there" apart from "cannot tell".
     */
    boolean exists(Path file);

    /**
     * What can be told of a file: there, with when it was last written; not there, which only a
     * system that says so proves; or unknown, with why (milestone 10, audit of the recovery trust
     * boundary, A and B).
     */
    FileState state(Path file);

    /** What {@link #state} could tell of a file. */
    record FileState(Presence presence, Optional<Instant> modified, String problem) {
        public enum Presence {
            PRESENT,
            /** The system said the file is not there. */
            ABSENT,
            /** It could not be told whether the file is there. */
            UNKNOWN
        }

        public FileState {
            java.util.Objects.requireNonNull(presence, "presence");
            java.util.Objects.requireNonNull(modified, "modified");
            java.util.Objects.requireNonNull(problem, "problem");
        }

        public static FileState present(Instant modified) {
            return new FileState(Presence.PRESENT, Optional.of(modified), "");
        }

        public static final FileState ABSENT = new FileState(Presence.ABSENT, Optional.empty(), "");

        public static FileState unknown(String problem) {
            return new FileState(Presence.UNKNOWN, Optional.empty(), problem);
        }
    }

    /**
     * Takes an exclusive lock on the file, creating it. Empty if another process, or another part of
     * this one, holds it already.
     */
    Optional<Lock> tryLock(Path file) throws IOException;

    /** Forces the folder's entries to the device where the system allows it; does nothing where it does not. */
    void forceFolder(Path folder);
}
