package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file operations on the real file system, where their answers carry a meaning recovery relies
 * on (audit of the recovery trust boundary, D): only a folder that is not there is listed as empty;
 * one that cannot be listed says so.
 */
class NioJournalFilesTest {

    private final NioJournalFiles files = new NioJournalFiles();

    @TempDir
    Path folder;

    @Test
    void aFolderThatIsNotThereIsListedAsEmpty() throws IOException {
        assertThat(files.list(folder.resolve("missing"))).isEmpty();
    }

    @Test
    void anEmptyFolderIsListedAsEmpty() throws IOException {
        assertThat(files.list(folder)).isEmpty();
    }

    /** A path that cannot be listed as a folder is not an empty folder: the caller is told. */
    @Test
    void aPathThatCannotBeListedIsAFailureNotAnEmptyFolder() throws IOException {
        Path file = Files.writeString(folder.resolve("journal"), "x");

        assertThatThrownBy(() -> files.list(file)).isInstanceOf(IOException.class);
    }

    @Test
    void theStateOfAFileThatIsNotThereIsAbsentAndOfOneThatIsThereIsPresentWithItsTime() throws IOException {
        assertThat(files.state(folder.resolve("missing")).presence()).isEqualTo(JournalFiles.FileState.Presence.ABSENT);
        Path file = Files.writeString(folder.resolve("ledger"), "x");
        JournalFiles.FileState state = files.state(file);
        assertThat(state.presence()).isEqualTo(JournalFiles.FileState.Presence.PRESENT);
        assertThat(state.modified()).contains(Files.getLastModifiedTime(file).toInstant());
    }

    /** The one proof recovery takes that a ledger is absent. */
    @Test
    void readingAFileThatIsNotThereThrowsNoSuchFile() {
        assertThatThrownBy(() -> files.read(folder.resolve("missing")))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    /**
     * Below a regular file nothing can be present. Windows reports such a path as not found; POSIX
     * reports "not a directory", which is not a proof of absence and is told as unknown.
     */
    @Test
    void aPathBelowARegularFileIsNeverPresent() throws IOException {
        Path file = Files.writeString(folder.resolve("journal"), "x");
        assertThat(files.state(file.resolve("child")).presence()).isNotEqualTo(JournalFiles.FileState.Presence.PRESENT);
    }
}
