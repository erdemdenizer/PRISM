package prism.srm.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The layout of the head of {@code struct kinfo_proc} as {@link KinfoProc} maps it: the offsets of
 * xnu's {@code struct extern_proc} on LP64 (bsd/sys/proc.h). The fields are Java {@code long}, not
 * {@code NativeLong}, so the layout is LP64 on any 64-bit system and is checked here on whichever
 * runs the tests; on macOS it is also read from the real system ({@code JnaMacosTest}).
 */
@EnabledIf("sixtyFourBit")
class KinfoProcTest {

    static boolean sixtyFourBit() {
        return Native.POINTER_SIZE == 8;
    }

    @Test
    void theTimevalIsEightBytesOfSecondsAndFourOfMicrosecondsPaddedToSixteen() {
        KinfoProc.Timeval timeval = new KinfoProc.Timeval();

        assertThat(timeval.size()).isEqualTo(16);
    }

    @Test
    void theFieldsLieWhereExternProcHasThem() {
        KinfoProc head = new KinfoProc();

        assertThat(head.offsetOf("p_starttime")).isZero();
        assertThat(head.offsetOf("p_vmspace")).isEqualTo(16);
        assertThat(head.offsetOf("p_sigacts")).isEqualTo(24);
        assertThat(head.offsetOf("p_flag")).isEqualTo(32);
        assertThat(head.offsetOf("p_stat")).isEqualTo(36);
        assertThat(head.offsetOf("p_pid")).isEqualTo(40);
        assertThat(head.size()).isEqualTo(JnaMacos.HEAD_SIZE);
    }

    /** Bytes laid out as the kernel lays them are read back as the fields. */
    @Test
    void theHeadIsReadFromTheStartOfABuffer() {
        try (Memory buffer = new Memory(648)) {
            buffer.clear();
            buffer.setLong(0, 1_790_000_123L);
            buffer.setInt(8, 456_789);
            buffer.setInt(32, 0x0000_4204);
            buffer.setByte(36, (byte) 4);
            buffer.setInt(40, 7_000);

            KinfoProc read = new KinfoProc(buffer);

            assertThat(read.p_pid).isEqualTo(7_000);
            assertThat(read.p_flag).isEqualTo(0x4204);
            assertThat(read.p_stat).isEqualTo((byte) 4);
            assertThat(read.startMillis()).isEqualTo(1_790_000_123_456L);
        }
    }
}
