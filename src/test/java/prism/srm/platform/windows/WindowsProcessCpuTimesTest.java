package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.ProcessIdentity;

/** The processor time of one process on Windows, against a fake system: read from the identity only. */
class WindowsProcessCpuTimesTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final ProcessIdentity TARGET = new ProcessIdentity(5_000, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsProcessCpuTimes times = new WindowsProcessCpuTimes(win32);

    @Test
    void theKernelAndUserTimeOfTheIdentityIsReadInMilliseconds() {
        win32.process(5_000, START).processorTime = OptionalLong.of(12_345_678L);

        assertThat(times.cpuTime(TARGET)).isEqualTo(new CpuTimeReading.Read(1_234));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aPidThatBelongsToAnotherProcessIsGone() {
        win32.process(5_000, START.plusSeconds(3));

        assertThat(times.cpuTime(TARGET)).isEqualTo(CpuTimeReading.GONE);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("GetProcessTimes"));
    }

    @Test
    void aProcessThatHasExitedIsGone() {
        win32.process(5_000, START).exitCode = 0;

        assertThat(times.cpuTime(TARGET)).isEqualTo(CpuTimeReading.GONE);
    }

    @Test
    void aProcessThatMayNotBeOpenedIsUnreadableNotGone() {
        win32.process(5_000, START).openError = 5;

        assertThat(times.cpuTime(TARGET)).isInstanceOf(CpuTimeReading.Unreadable.class);
    }

    @Test
    void aFailedReadOfTheTimesIsUnreadable() {
        win32.process(5_000, START).processorTime = OptionalLong.empty();

        assertThat(times.cpuTime(TARGET)).isInstanceOf(CpuTimeReading.Unreadable.class);
        assertThat(win32.openHandles()).isEmpty();
    }
}
