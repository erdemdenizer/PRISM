package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ScopeProvenance;

/**
 * Format 2 of the native-limit ledger (milestone 12, S1): written only when a record needs it (a throttling memory
 * boundary, scope provenance), so a ledger of Job Object records stays format 1 byte for byte; read beside format 1;
 * a later, unknown format is never interpreted; and every field that breaks its rules makes its line no record, even
 * when the file's checksum is right.
 */
class NativeLimitLedgerFormat2Test {

    private static final long MIB = 1L << 20;
    private static final ProcessIdentity A = new ProcessIdentity(4_100, Instant.ofEpochMilli(1_791_000_000_123L));
    private static final ProcessIdentity B = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));
    private static final String UNIT = "prism-srm-limit-2071-1791970282990.scope";
    private static final String ID = "86f1083782564429bf3a1a34ede0c182";
    private static final String CGROUP = "/user.slice/user-1001.slice/user@1001.service/app.slice/" + UNIT;
    private static final EffectiveLimits HARD = EffectiveLimits.of(OptionalInt.of(3_000), OptionalLong.of(256 * MIB));
    private static final EffectiveLimits THROTTLE =
            EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512 * MIB), MemoryBoundary.THROTTLE);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();

    private static String text(long version, List<LimitRecord> records) {
        return new String(NativeLimitLedger.encode(version, records), StandardCharsets.UTF_8);
    }

    /** The body of an encoded file, edited, with its end line made right again: only the edit can make it damaged. */
    private static byte[] edited(String encoded, String from, String to) {
        String body = encoded.substring(0, encoded.lastIndexOf("\nend ") + 1);
        assertThat(body).contains(from);
        String changed = body.replace(from, to);
        CRC32 crc = new CRC32();
        crc.update(changed.getBytes(StandardCharsets.UTF_8));
        long lines = changed.lines().filter(line -> line.startsWith("record ")).count();
        return (changed + "end " + lines + " " + Long.toHexString(crc.getValue()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static LimitRecord linux() {
        return LimitRecord.intended(B, "demo", THROTTLE, "1-1")
                .applied(THROTTLE)
                .withProvenance(new ScopeProvenance(UNIT, Optional.of(ID), Optional.of(CGROUP)));
    }

    @Test
    void jobObjectRecordsStayFormatOneByteForByte() {
        LimitRecord windows =
                LimitRecord.intended(A, "notepad.exe", HARD, "1-1").applied(HARD);
        String encoded = text(5, List.of(windows));
        assertThat(encoded).startsWith("prism native limits 1\nversion 5\n");
        assertThat(encoded)
                .contains("record pid=4100 start=1791000000123 name=notepad.exe state=APPLIED cpu=3000 mem=268435456"
                        + " tocpu=. tomem=. flags=. instance=1-1\n");
        assertThat(encoded).doesNotContain("memb=").doesNotContain("unit=");
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(encoded.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.corrupt()).isFalse();
        assertThat(decoded.records()).containsExactly(windows);
    }

    @Test
    void aRecordWithABoundaryOrAProvenanceMakesTheWholeFileFormatTwoAndSurvivesIt() {
        LimitRecord windows =
                LimitRecord.intended(A, "notepad.exe", HARD, "1-1").applied(HARD);
        LimitRecord incomplete = LimitRecord.intended(
                        new ProcessIdentity(3_000, Instant.ofEpochMilli(5)),
                        "half",
                        EffectiveLimits.of(OptionalInt.of(1_000), OptionalLong.empty()),
                        "1-1")
                .withProvenance(ScopeProvenance.unitOnly("prism-srm-limit-3000-5.scope"));
        LimitRecord changing = linux().changing(EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(128 * MIB)));
        String encoded = text(9, List.of(windows, linux(), incomplete, changing));
        assertThat(encoded).startsWith("prism native limits 2\nversion 9\n");
        assertThat(encoded).contains(" memb=throttle tomemb=. unit=" + UNIT + " inv=" + ID + " cg=%2Fuser.slice");
        assertThat(encoded).contains(" memb=hard tomemb=. unit=. inv=. cg=.");
        assertThat(encoded).contains(" memb=throttle tomemb=hard ");

        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(encoded.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.corrupt()).isFalse();
        assertThat(decoded.version()).isEqualTo(9);
        assertThat(decoded.records()).containsExactly(windows, linux(), incomplete, changing);
        assertThat(decoded.records().get(2).provenance())
                .hasValueSatisfying(p -> assertThat(p.complete()).isFalse());
    }

    @Test
    void aFormatTwoLedgerOfAnEndedSessionIsReadWhole() throws Exception {
        Files.write(NativeLimitLedger.file(folder.path(), "7-7"), NativeLimitLedger.encode(3, List.of(linux())));
        NativeLimitLedger.DeadLedger dead = NativeLimitLedger.readDeadLedger(folder.path(), "7-7", files);
        assertThat(dead.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.VALID);
        assertThat(dead.records()).containsExactly(linux());
    }

    @Test
    void aLaterFormatIsNeverInterpretedAndIsKeptAsDamaged() throws Exception {
        String later = text(3, List.of(linux())).replaceFirst("prism native limits 2", "prism native limits 3");
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(later.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.corrupt()).isTrue();
        assertThat(decoded.records()).as("nothing of an unknown format is read").isEmpty();

        Files.writeString(NativeLimitLedger.file(folder.path(), "8-8"), later);
        NativeLimitLedger.DeadLedger dead = NativeLimitLedger.readDeadLedger(folder.path(), "8-8", files);
        assertThat(dead.status()).isEqualTo(NativeLimitLedger.DeadLedger.Status.DAMAGED);
        assertThat(dead.whole()).isFalse();
    }

    @Test
    void everyBrokenFormatTwoFieldMakesItsLineNoRecordEvenWithTheRightChecksum() {
        String encoded = text(1, List.of(linux()));
        List<String[]> breaks = List.of(
                new String[] {" memb=throttle", " memb=soft"},
                new String[] {" memb=throttle", " memb=THROTTLE"},
                new String[] {" memb=throttle", ""},
                new String[] {" tomemb=.", " tomemb=hard"},
                new String[] {" inv=" + ID, " inv=" + ID.toUpperCase(java.util.Locale.ROOT)},
                new String[] {" inv=" + ID, " inv=" + "0".repeat(32)},
                new String[] {" unit=" + UNIT, " unit=prism.service"},
                new String[] {" unit=" + UNIT, " unit=."},
                new String[] {" cg=%2Fuser.slice", " cg=user.slice"},
                new String[] {" cg=%2Fuser.slice", " cg=%2F..%2Fuser.slice"});
        for (String[] change : breaks) {
            NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(edited(encoded, change[0], change[1]));
            assertThat(decoded.corrupt()).as(change[1]).isTrue();
            assertThat(decoded.records()).as(change[1]).isEmpty();
        }
    }

    @Test
    void aThrottlingBoundaryWithoutAMemoryLimitIsNoRecord() {
        LimitRecord cpuOnly = LimitRecord.intended(
                        B, "demo", EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty()), "1-1")
                .withProvenance(ScopeProvenance.unitOnly(UNIT));
        String encoded = text(1, List.of(cpuOnly));
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(edited(encoded, " memb=hard", " memb=throttle"));
        assertThat(decoded.corrupt()).isTrue();
        assertThat(decoded.records()).isEmpty();
    }

    @Test
    void aFormatOneFileMustNotCarryFormatTwoFields() {
        LimitRecord windows =
                LimitRecord.intended(A, "notepad.exe", HARD, "1-1").applied(HARD);
        String encoded = text(1, List.of(windows));
        NativeLimitLedger.Decoded decoded =
                NativeLimitLedger.decode(edited(encoded, " instance=1-1\n", " instance=1-1 memb=throttle\n"));
        assertThat(decoded.corrupt()).isTrue();
        assertThat(decoded.records()).isEmpty();
    }

    @Test
    void aFieldOfALaterVersionInAFormatTwoLineIsIgnored() {
        String encoded = text(1, List.of(linux()));
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(edited(encoded, " cg=", " future=x cg="));
        assertThat(decoded.corrupt()).isFalse();
        assertThat(decoded.records()).containsExactly(linux());
    }

    @Test
    void aDamagedHeaderStillYieldsTheRecordsItCanReadAndIsDamaged() {
        String encoded = text(1, List.of(linux()));
        byte[] garbled = ("garbage\n" + encoded.substring(encoded.indexOf('\n') + 1)).getBytes(StandardCharsets.UTF_8);
        NativeLimitLedger.Decoded decoded = NativeLimitLedger.decode(garbled);
        assertThat(decoded.corrupt()).isTrue();
        assertThat(decoded.records())
                .as("read as far as it goes, as milestone 10 does")
                .containsExactly(linux());
    }
}
