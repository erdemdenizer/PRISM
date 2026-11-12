package prism.srm.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** For tests in other packages: what a journal file on disk holds, read without its lock. */
public final class JournalReading {

    private JournalReading() {}

    /** The entries of the journal file, as far as it reads; whether it ended as a journal ends. */
    public record Read(List<JournalEntry> entries, boolean corrupt) {}

    public static Read of(Path file) throws IOException {
        SuspensionJournal.Decoded decoded = SuspensionJournal.decode(Files.readAllBytes(file));
        return new Read(decoded.entries(), decoded.corrupt());
    }
}
