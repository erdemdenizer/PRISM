package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.ProcessIdentity;

/**
 * The native-limit ledger (plan §6.4, D12, D17): durable and read back, written whole from the model
 * as it is when the writer runs, so that a late write can neither bring back an older record nor a
 * removed one; waits that run out fail it; it goes away only when the newest model, empty, is on disk.
 */
class NativeLimitLedgerTest {

    private static final ProcessIdentity A = new ProcessIdentity(4_100, Instant.ofEpochMilli(1_791_000_000_123L));
    private static final ProcessIdentity B = new ProcessIdentity(4_200, Instant.ofEpochMilli(1_791_000_000_456L));
    private static final EffectiveLimits CPU20 = EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty());
    private static final EffectiveLimits BOTH = EffectiveLimits.of(OptionalInt.of(3000), OptionalLong.of(256L << 20));

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();

    private NativeLimitLedger ledger(java.util.concurrent.Executor writes) {
        return new NativeLimitLedger(Optional.of(folder.path()), "1-1", files, writes);
    }

    private List<LimitRecord> onDisk() {
        return NativeLimitLedger.readDead(folder.path(), "1-1", files);
    }

    @Test
    void aRecordIsOnDiskWhenItsWaitReturnsAndReadsBackAsWritten() {
        NativeLimitLedger ledger = ledger(Runnable::run);
        LimitRecord record = LimitRecord.intended(A, "demo ü/x %", CPU20, "1-1");

        assertThat(ledger.putDurably(record)).isTrue();
        assertThat(onDisk()).containsExactly(record);

        LimitRecord changing = record.applied(CPU20).changing(BOTH).flagged(LimitRecord.Flag.UNVERIFIED);
        ledger.put(changing);
        assertThat(onDisk()).containsExactly(changing);
    }

    /** Plan D17: the late write carries the newest model, never the one it was handed over with. */
    @Test
    void aLateWriteNeitherBringsBackARemovedRecordNorAnOlderOne() {
        QueuedExecutor writes = new QueuedExecutor();
        NativeLimitLedger ledger = ledger(writes);
        LimitRecord first = LimitRecord.intended(A, "a", CPU20, "1-1");
        LimitRecord other = LimitRecord.intended(B, "b", BOTH, "1-1");

        ledger.put(first);
        ledger.put(first.applied(CPU20));
        ledger.remove(A);
        ledger.put(other);
        assertThat(writes.waiting())
                .as("one drain, whatever the number of changes")
                .isEqualTo(1);
        writes.runAll();

        assertThat(onDisk()).containsExactly(other);
    }

    @Test
    void aWaitThatRunsOutFailsTheLedgerForTheSessionAndTakesTheRecordBackOut() {
        QueuedExecutor writes = new QueuedExecutor();
        NativeLimitLedger ledger = ledger(writes);

        assertThat(ledger.putDurably(LimitRecord.intended(A, "a", CPU20, "1-1")))
                .isFalse();

        assertThat(ledger.healthy()).isFalse();
        assertThat(ledger.problem())
                .hasValueSatisfying(problem -> assertThat(problem).contains("did not answer"));
        assertThat(ledger.find(A)).isEmpty();
        assertThat(ledger.putDurably(LimitRecord.intended(B, "b", CPU20, "1-1")))
                .as("no second wait")
                .isFalse();
    }

    @Test
    void aWriteThatFailsFailsTheLedgerAndKeepsTheEarlierFile() {
        NativeLimitLedger ledger = ledger(Runnable::run);
        LimitRecord first = LimitRecord.intended(A, "a", CPU20, "1-1");
        assertThat(ledger.putDurably(first)).isTrue();
        files.diskFull = true;
        assertThat(ledger.putDurably(LimitRecord.intended(B, "b", CPU20, "1-1")))
                .isFalse();
        files.diskFull = false;
        assertThat(ledger.healthy()).isFalse();
        assertThat(onDisk()).containsExactly(first);
    }

    @Test
    void aFileThatReadsBackOtherwiseIsAFailedWrite() {
        NativeLimitLedger ledger = ledger(Runnable::run);
        files.readsBackOtherwise = true;
        assertThat(ledger.putDurably(LimitRecord.intended(A, "a", CPU20, "1-1")))
                .isFalse();
        assertThat(ledger.problem())
                .hasValueSatisfying(problem -> assertThat(problem).contains("read back"));
    }

    @Test
    void theFileGoesAtTheEndOnlyWhenTheNewestModelIsEmptyAndOnDisk() throws Exception {
        NativeLimitLedger ledger = ledger(Runnable::run);
        ledger.putDurably(LimitRecord.intended(A, "a", CPU20, "1-1"));
        ledger.close(Duration.ofSeconds(1));
        assertThat(onDisk()).as("a ledger that names a process stays").hasSize(1);

        ledger.remove(A);
        ledger.close(Duration.ofSeconds(1));
        assertThat(Files.exists(NativeLimitLedger.file(folder.path(), "1-1"))).isFalse();
    }

    @Test
    void aLedgerWithoutAFolderRefusesEverything() {
        NativeLimitLedger none = NativeLimitLedger.none();
        assertThat(none.healthy()).isFalse();
        assertThat(none.putDurably(LimitRecord.intended(A, "a", CPU20, "none"))).isFalse();
    }

    @Test
    void aDamagedFileIsReadAsFarAsItGoesSaidToBeDamagedAndLeftAsItIs() throws Exception {
        NativeLimitLedger ledger = ledger(Runnable::run);
        ledger.putDurably(LimitRecord.intended(A, "a", CPU20, "1-1"));
        byte[] bytes = Files.readAllBytes(NativeLimitLedger.file(folder.path(), "1-1"));
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("end 1", "end 2");
        Files.writeString(NativeLimitLedger.file(folder.path(), "1-1"), text);
        byte[] damaged = Files.readAllBytes(NativeLimitLedger.file(folder.path(), "1-1"));

        NativeLimitLedger.DeadLedger read = NativeLimitLedger.readDeadLedger(folder.path(), "1-1", files);

        assertThat(read.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.DAMAGED);
        assertThat(read.whole()).isFalse();
        assertThat(read.records()).hasSize(1);
        assertThat(Files.readAllBytes(NativeLimitLedger.file(folder.path(), "1-1")))
                .as("the evidence is left exactly as it was (audit item 1)")
                .isEqualTo(damaged);
        assertThat(files.list(folder.path()))
                .as("no copy and no temporary file beside it")
                .extracting(path -> path.getFileName().toString())
                .containsExactly("native-limits-1-1.ledger");
    }

    /** A value changed under an intact count: only the checksum tells, and the file is damaged. */
    @Test
    void aValueThatNoLongerMatchesItsChecksumMakesTheFileDamaged() throws Exception {
        ledger(Runnable::run).putDurably(LimitRecord.intended(A, "a", CPU20, "1-1"));
        String text = Files.readString(NativeLimitLedger.file(folder.path(), "1-1"));
        assertThat(text).contains(" cpu=2000");
        Files.writeString(NativeLimitLedger.file(folder.path(), "1-1"), text.replace(" cpu=2000", " cpu=2001"));

        NativeLimitLedger.DeadLedger read = NativeLimitLedger.readDeadLedger(folder.path(), "1-1", files);

        assertThat(read.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.DAMAGED);
        assertThat(read.whole()).isFalse();
    }

    /** Trust boundary A: a ledger whose state cannot be told is never taken for absent. */
    @Test
    void aLedgerWhoseStateCannotBeToldIsUnreadableNeverAbsent() throws Exception {
        ledger(Runnable::run).putDurably(LimitRecord.intended(A, "a", CPU20, "1-1"));
        byte[] before = Files.readAllBytes(NativeLimitLedger.file(folder.path(), "1-1"));
        files.metadataUnknown.add("native-limits-1-1.ledger");

        NativeLimitLedger.DeadLedger read = NativeLimitLedger.readDeadLedger(folder.path(), "1-1", files);

        assertThat(read.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.UNREADABLE);
        assertThat(Files.readAllBytes(NativeLimitLedger.file(folder.path(), "1-1")))
                .isEqualTo(before);
    }

    /** Audit item 1: a file that exists but cannot be read is never taken for an empty ledger. */
    @Test
    void anExistingFileThatCannotBeReadIsUnreadableNotEmpty() {
        ledger(Runnable::run).putDurably(LimitRecord.intended(A, "a", CPU20, "1-1"));
        files.readsFail = 1;

        NativeLimitLedger.DeadLedger read = NativeLimitLedger.readDeadLedger(folder.path(), "1-1", files);

        assertThat(read.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.UNREADABLE);
        assertThat(read.whole()).isFalse();
        assertThat(read.problem()).contains("locked");
        assertThat(NativeLimitLedger.readDeadLedger(folder.path(), "1-1", files).status())
                .as("read again once the file can be read")
                .isEqualTo(NativeLimitLedger.DeadLedger.Status.VALID);
        assertThat(NativeLimitLedger.readDeadLedger(folder.path(), "9-9", files).status())
                .isEqualTo(NativeLimitLedger.DeadLedger.Status.ABSENT);
    }

    @Test
    void everyStateAndFlagSurvivesTheFormat() {
        LimitRecord record = LimitRecord.intended(A, "a", BOTH, "1-1")
                .applied(BOTH)
                .changing(EffectiveLimits.NONE)
                .flagged(LimitRecord.Flag.TARGET_EXITED)
                .flagged(LimitRecord.Flag.UNCERTAIN);
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(NativeLimitLedger.encode(7, List.of(record)));
        assertThat(decoded.corrupt()).isFalse();
        assertThat(decoded.version()).isEqualTo(7);
        assertThat(decoded.records()).containsExactly(record);
        for (LedgerState state : List.of(LedgerState.INTENDED, LedgerState.APPLIED, LedgerState.CLEARED)) {
            LimitRecord plain = new LimitRecord(A, "a", CPU20, Optional.empty(), java.util.Set.of(), state, "1-1");
            assertThat(NativeLimitLedger.decode(NativeLimitLedger.encode(1, List.of(plain)))
                            .records())
                    .containsExactly(plain);
        }
    }
}
