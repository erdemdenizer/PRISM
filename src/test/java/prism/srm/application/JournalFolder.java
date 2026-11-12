package prism.srm.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A folder of its own for each test that writes a suspension journal, deleted after the test with a
 * few tries. On Windows a program that scans a file just written, an antivirus program, may hold it
 * open for a moment; the file is then only marked for deletion, and the folder is not empty yet.
 * JUnit's {@code @TempDir} fails the test then, though nothing in the test is wrong. The retries
 * are for that moment only: a file the test itself leaves open still fails it.
 */
public final class JournalFolder implements BeforeEachCallback, AfterEachCallback {

    private static final int TRIES = 50;
    private static final long PAUSE_MILLIS = 20;

    private Path path;

    public Path path() {
        return path;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        path = Files.createTempDirectory("prism-journal-test");
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                deleteTree();
                return;
            } catch (IOException | UncheckedIOException stillThere) {
                if (attempt == TRIES) {
                    throw stillThere;
                }
                Thread.sleep(PAUSE_MILLIS);
            }
        }
    }

    private void deleteTree() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(path)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path each : paths) {
            Files.deleteIfExists(each);
        }
    }
}
