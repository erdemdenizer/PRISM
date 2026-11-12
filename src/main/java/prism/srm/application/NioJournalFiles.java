package prism.srm.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link JournalFiles} on the file system, through {@code java.nio.file}.
 *
 * <ul>
 *   <li>Where the system has POSIX permissions the folder is created with 0700 and the files with
 *       0600: the journal names processes the user runs, which other users need not see.
 *   <li>Every write opens a channel of its own, so an interrupt that closes one channel costs that
 *       write only.
 *   <li>The lock is a {@link FileLock} on a file of its own, never on the journal, which other
 *       readers must be able to read: on Windows a locked range cannot be read. The channel of the
 *       lock stays open for as long as it is held; closing any channel to a locked file lets go of
 *       the lock on POSIX systems, so no other channel is ever opened to it.
 *   <li>A move is {@code ATOMIC_MOVE} with {@code REPLACE_EXISTING}: on Windows MoveFileEx, which
 *       an antivirus program or the indexer holding the target open can refuse; the journal tries a
 *       few times.
 * </ul>
 *
 * The force of a write survives a process that is killed, not a power loss on every system: a
 * rename on Windows is not written through.
 */
final class NioJournalFiles implements JournalFiles {

    private static final Set<PosixFilePermission> OWNER_FOLDER = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    private final boolean posix =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @Override
    public void createFolder(Path folder) throws IOException {
        if (posix) {
            Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(OWNER_FOLDER));
            Files.setPosixFilePermissions(folder, OWNER_FOLDER);
        } else {
            Files.createDirectories(folder);
        }
    }

    @Override
    public void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = posix
                ? FileChannel.open(
                        file,
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING),
                        PosixFilePermissions.asFileAttribute(OWNER_FILE))
                : FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    @Override
    public void moveAtomically(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Far more than any journal Prism writes: a file larger than this is not read at all. */
    static final long LARGEST_READ = 4L * 1024 * 1024;

    @Override
    public byte[] read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > LARGEST_READ) {
            throw new IOException(file + " is " + size + " bytes, larger than a suspension journal can be");
        }
        return Files.readAllBytes(file);
    }

    @Override
    public void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    @Override
    public List<Path> list(Path folder) throws IOException {
        // Only a folder the system says is not there is empty; one it cannot list is not (audit, D).
        try (Stream<Path> files = Files.list(folder)) {
            return new ArrayList<>(files.sorted().toList());
        } catch (NoSuchFileException notThere) {
            return List.of();
        } catch (UncheckedIOException failedWhileListing) {
            // The listing is read lazily: an error met on the way is the folder's failure (audit, D).
            throw failedWhileListing.getCause();
        }
    }

    @Override
    public boolean exists(Path file) {
        return Files.exists(file);
    }

    @Override
    public FileState state(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return FileState.present(attributes.lastModifiedTime().toInstant());
        } catch (NoSuchFileException notThere) {
            return FileState.ABSENT;
        } catch (IOException | RuntimeException cannotTell) {
            return FileState.unknown(cannotTell.toString());
        }
    }

    @Override
    public Optional<Lock> tryLock(Path file) throws IOException {
        FileChannel channel = posix
                ? FileChannel.open(
                        file,
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(OWNER_FILE))
                : FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            lock = null;
        } catch (IOException | RuntimeException failed) {
            channel.close();
            throw failed;
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        FileLock held = lock;
        return Optional.of(new Lock() {
            @Override
            public boolean valid() {
                return held.isValid();
            }

            @Override
            public void release() {
                try {
                    if (held.isValid()) {
                        held.release();
                    }
                } catch (IOException ignored) {
                    // The channel is closed next, which lets go of the lock in any case.
                } finally {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // Nothing is left to do for a channel that cannot be closed.
                    }
                }
            }
        });
    }

    @Override
    public void forceFolder(Path folder) {
        if (!posix) {
            return;
        }
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException notSupported) {
            // Best effort: some file systems refuse to force a folder.
        }
    }
}
