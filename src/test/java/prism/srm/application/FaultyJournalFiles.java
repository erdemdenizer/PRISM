package prism.srm.application;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The real file operations in a test's own folder, with switches that make each of them fail the
 * way a disk does: full, refused, a move the system will not make, bytes that read back otherwise,
 * a lock lost.
 */
final class FaultyJournalFiles implements JournalFiles {

    private final JournalFiles real = SuspensionJournal.onDisk();

    /** The next writes fail as on a full disk. */
    volatile boolean diskFull;
    /** Every operation is refused, as on a folder the user may not write. */
    volatile boolean denied;
    /** This many moves fail before one succeeds. */
    volatile int failingMoves;
    /**
     * The names of files whose state cannot be told: present on disk, but every look at them is
     * refused, as for a file whose attributes the user may not read (audit of the recovery trust
     * boundary). {@link #exists} then answers false, as {@code Files.exists} does.
     */
    final java.util.Set<String> metadataUnknown = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The names of files the system reports as gone when asked for their state: removed since a listing. */
    final java.util.Set<String> reportedAbsent = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Run before and after each delete, with the file and "before" or "after": an order of events a test can see. */
    volatile java.util.function.BiConsumer<Path, String> aroundDelete = (file, when) -> {};

    /** This many listings throw a runtime exception, as an unexpected defect would. */
    volatile int listsThrowingUnexpectedly;

    /** This many reads fail, as on a file another program holds for a moment. */
    volatile int readsFail;
    /** A read gives back other bytes than were written. */
    volatile boolean readsBackOtherwise;
    /** Every lock this gives out reports itself lost. */
    volatile boolean locksLost;
    /** A lock is refused as held by another process. */
    volatile boolean lockHeldElsewhere;
    /** The names of lock files another process holds: as a killed session's lock not let go yet. */
    final java.util.Set<String> locksHeldElsewhere = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** While set, a write waits until the gate opens: a disk that has stalled. */
    volatile java.util.concurrent.CountDownLatch stall;
    /** Run as a write begins, on the writing thread: lets a test act between two steps of the coordinator. */
    volatile Runnable beforeWrite = () -> {};

    /** Counted down when a write begins to wait at the stall. */
    final java.util.concurrent.Semaphore stalled = new java.util.concurrent.Semaphore(0);

    int writes;
    int moves;

    @Override
    public void createFolder(Path folder) throws IOException {
        refuseIfDenied(folder);
        real.createFolder(folder);
    }

    @Override
    public void writeDurably(Path file, byte[] bytes) throws IOException {
        refuseIfDenied(file);
        writes++;
        beforeWrite.run();
        java.util.concurrent.CountDownLatch gate = stall;
        if (gate != null) {
            stalled.release();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted while the disk stalled");
            }
        }
        if (diskFull) {
            throw new IOException("There is not enough space on the disk");
        }
        real.writeDurably(file, bytes);
    }

    @Override
    public void moveAtomically(Path from, Path to) throws IOException {
        refuseIfDenied(to);
        moves++;
        if (failingMoves > 0) {
            failingMoves--;
            throw new AccessDeniedException(to.toString(), null, "held open by another program");
        }
        real.moveAtomically(from, to);
    }

    @Override
    public byte[] read(Path file) throws IOException {
        refuseIfDenied(file);
        if (metadataUnknown.contains(String.valueOf(file.getFileName()))) {
            throw new AccessDeniedException(file.toString());
        }
        if (readsFail > 0) {
            readsFail--;
            throw new IOException("The process cannot access the file because another process has locked it");
        }
        byte[] bytes = real.read(file);
        if (readsBackOtherwise && bytes.length > 0) {
            bytes[bytes.length / 2] ^= 0x20;
        }
        return bytes;
    }

    @Override
    public void delete(Path file) throws IOException {
        aroundDelete.accept(file, "before");
        real.delete(file);
        aroundDelete.accept(file, "after");
    }

    @Override
    public List<Path> list(Path folder) throws IOException {
        refuseIfDenied(folder);
        if (listsThrowingUnexpectedly > 0) {
            listsThrowingUnexpectedly--;
            throw new IllegalStateException("an unexpected failure while listing " + folder);
        }
        return real.list(folder);
    }

    @Override
    public boolean exists(Path file) {
        // As Files.exists does when it cannot tell: false, though the file may well be there.
        return !metadataUnknown.contains(String.valueOf(file.getFileName())) && real.exists(file);
    }

    @Override
    public FileState state(Path file) {
        if (denied || metadataUnknown.contains(String.valueOf(file.getFileName()))) {
            return FileState.unknown("access denied: " + file);
        }
        if (reportedAbsent.contains(String.valueOf(file.getFileName()))) {
            return FileState.ABSENT;
        }
        return real.state(file);
    }

    @Override
    public Optional<Lock> tryLock(Path file) throws IOException {
        refuseIfDenied(file);
        if (lockHeldElsewhere || locksHeldElsewhere.contains(String.valueOf(file.getFileName()))) {
            return Optional.empty();
        }
        Optional<Lock> lock = real.tryLock(file);
        return lock.map(held -> new Lock() {
            @Override
            public boolean valid() {
                return !locksLost && held.valid();
            }

            @Override
            public void release() {
                held.release();
            }
        });
    }

    @Override
    public void forceFolder(Path folder) {
        real.forceFolder(folder);
    }

    private void refuseIfDenied(Path path) throws IOException {
        if (denied) {
            throw new AccessDeniedException(path.toString());
        }
    }
}
