package prism.srm.platform.linux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Deletes a test's temporary folder, trying again for a short while. On Windows a file another process holds open with
 * delete sharing (a virus scanner reading new files in the temporary folder does) is only marked for deletion, so its
 * folder stays "not empty" for a moment; JUnit's own cleanup fails the test on the first try. The folder is still
 * required to go: a folder that cannot be deleted within the bound fails, as before.
 */
final class TempFolders {

    private static final int ATTEMPTS = 20;
    private static final long PAUSE_MILLIS = 100;

    private TempFolders() {}

    static void delete(Path folder) {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                deleteOnce(folder);
                return;
            } catch (IOException failed) {
                last = failed;
            }
            try {
                Thread.sleep(PAUSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new UncheckedIOException("the test folder could not be deleted: " + folder, last);
    }

    private static void deleteOnce(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                deleteIfThere(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                deleteIfThere(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteIfThere(Path path) throws IOException {
        try {
            Files.delete(path);
        } catch (NoSuchFileException gone) {
            // Deleted by an earlier attempt whose folder was not empty yet.
        }
    }
}
