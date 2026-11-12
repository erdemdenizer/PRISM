package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/** The writer of the journal on its own: what it does when its thread takes no more work. */
class JournalWriterTest {

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private SuspensionJournal journal;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
    }

    @AfterEach
    void tearDown() {
        journal.close();
    }

    private static List<JournalEntry> oneEntry() {
        return List.of(new JournalEntry(
                new ProcessIdentity(4_242, Instant.EPOCH),
                "demo",
                java.util.EnumSet.of(SuspensionReason.USER),
                Optional.empty(),
                Optional.empty(),
                "1-1",
                Instant.EPOCH,
                Instant.EPOCH,
                false,
                false));
    }

    /**
     * A full queue (in the application, 64 tasks waiting on a stuck disk) stalls the journal; the
     * caller, which may be one that releases a process, never writes on its own thread.
     */
    @Test
    void aWriterWhoseThreadTakesNoMoreWorkStallsAndNeverWritesOnTheCallersThread() {
        AtomicInteger failures = new AtomicInteger();
        JournalWriter writer = new JournalWriter(
                journal,
                task -> {
                    throw new RejectedExecutionException("full");
                },
                failures::incrementAndGet);
        int writesBefore = files.writes;

        writer.submit(new JournalWriter.Batch(1, oneEntry()));

        assertThat(files.writes).as("nothing written on the caller's thread").isEqualTo(writesBefore);
        assertThat(writer.stalled()).isTrue();
        assertThat(writer.stallReason().orElseThrow()).contains("takes no more work");
        assertThat(failures.get()).isEqualTo(1);
        assertThat(writer.await(1, Duration.ofSeconds(2))).isFalse();
        assertThat(writer.call(() -> true, Duration.ofSeconds(2))).isEmpty();
        assertThat(failures.get()).as("told once").isEqualTo(1);
    }

    /** Only the newest of what was handed over is written, and never an older one after it. */
    @Test
    void anOlderBatchHandedOverLateIsNotWritten() {
        JournalWriter writer = new JournalWriter(journal, Runnable::run, () -> {});

        writer.submit(new JournalWriter.Batch(2, oneEntry()));
        writer.submit(new JournalWriter.Batch(1, List.of()));

        assertThat(writer.written()).isEqualTo(2);
        assertThat(journal.entries()).hasSize(1);
    }
}
