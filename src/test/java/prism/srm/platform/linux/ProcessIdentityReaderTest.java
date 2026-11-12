package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.domain.ProcessIdentity;

/**
 * The shared Linux identity reader (S2): the start time computed as the listing computes it, exactly, with unknown
 * inputs and overflows answered empty, and read from a proc file system of the test's own.
 */
class ProcessIdentityReaderTest {

    private static final long BOOT = FakeProc.BOOT_TIME_SECONDS;
    private static final long HZ = FakeProc.TICKS_PER_SECOND;

    @Test
    void theStartTimeIsTheListingsFormula() {
        for (long ticks : new long[] {0, 1, 99, 100, 3_328, 5_615, 123_456_789}) {
            assertThat(ProcessIdentityReader.startMillis(BOOT, HZ, ticks))
                    .as("ticks " + ticks)
                    .isEqualTo(OptionalLong.of(FakeProc.start(ticks).toEpochMilli()));
        }
        assertThat(ProcessIdentityReader.startMillis(1_791_970_249, 100, 3_328))
                .isEqualTo(OptionalLong.of(1_791_970_249_000L + 33_280));
    }

    @Test
    void unknownInputsAndOverflowsAreEmpty() {
        assertThat(ProcessIdentityReader.startMillis(0, HZ, 5)).isEmpty();
        assertThat(ProcessIdentityReader.startMillis(-1, HZ, 5)).isEmpty();
        assertThat(ProcessIdentityReader.startMillis(BOOT, 0, 5)).isEmpty();
        assertThat(ProcessIdentityReader.startMillis(BOOT, HZ, -1))
                .as("an unsigned field above Long.MAX")
                .isEmpty();
        assertThat(ProcessIdentityReader.startMillis(Long.MAX_VALUE / 10, HZ, 5))
                .isEmpty();
        assertThat(ProcessIdentityReader.startMillis(BOOT, HZ, Long.MAX_VALUE / 10))
                .isEmpty();
    }

    @Test
    void theIdentityIsReadFromTheStatFile(@TempDir Path root) {
        FakeProc proc = new FakeProc(root).process(2_071, "python3", 3_328);
        ProcFs fs = new ProcFs(root);
        ProcFs.Reading<ProcessIdentity> found = ProcessIdentityReader.read(fs, 2_071, BOOT, HZ);
        assertThat(found.value()).contains(new ProcessIdentity(2_071, FakeProc.start(3_328)));
        assertThat(ProcessIdentityReader.read(fs, 4_242, BOOT, HZ).status()).isEqualTo(ProcFs.Status.MISSING);
        assertThat(ProcessIdentityReader.read(fs, 0, BOOT, HZ).status()).isEqualTo(ProcFs.Status.FAILED);
        assertThat(ProcessIdentityReader.read(fs, 2_071, 0, HZ).status()).isEqualTo(ProcFs.Status.FAILED);

        ProcStat stat = fs.stat(2_071).get();
        assertThat(ProcessIdentityReader.matches(new ProcessIdentity(2_071, FakeProc.start(3_328)), stat, BOOT, HZ))
                .isTrue();
        assertThat(ProcessIdentityReader.matches(new ProcessIdentity(2_071, FakeProc.start(3_329)), stat, BOOT, HZ))
                .as("another start time: another process with the same PID")
                .isFalse();
        assertThat(ProcessIdentityReader.identity(0, stat, BOOT, HZ)).isEmpty();
        assertThat(proc.root).isEqualTo(root);
    }

    @Test
    void theControllerComputesTheSameStartTime(@TempDir Path root) {
        FakeProc proc = new FakeProc(root).process(2_071, "python3", 3_328);
        LinuxProcessController controller =
                new LinuxProcessController(new FakeLinux(proc), new ProcFs(root), () -> BOOT);
        ProcStat stat = new ProcFs(root).stat(2_071).get();
        assertThat(controller.startMillis(stat)).isEqualTo(ProcessIdentityReader.startMillis(BOOT, HZ, 3_328));
        assertThat(Instant.ofEpochMilli(controller.startMillis(stat).getAsLong()))
                .isEqualTo(FakeProc.start(3_328));
    }
}
