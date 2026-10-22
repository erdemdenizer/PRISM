package prism.srm.platform.oshi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import oshi.hardware.CentralProcessor.TickType;
import oshi.util.GlobalConfig;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.DeviceLayer;
import prism.srm.platform.HostSystem;

/**
 * Covers the conversions and choices that need no operating-system call. The readings themselves
 * are checked on a real system with {@code HostMonitoringProbe}.
 */
class OshiSystemMetricsSourceTest {

    @Test
    void idleIncludesIoWaitAndTotalIsEveryState() {
        long[] ticks = new long[TickType.values().length];
        ticks[TickType.USER.getIndex()] = 100;
        ticks[TickType.NICE.getIndex()] = 5;
        ticks[TickType.SYSTEM.getIndex()] = 50;
        ticks[TickType.IDLE.getIndex()] = 800;
        ticks[TickType.IOWAIT.getIndex()] = 30;
        ticks[TickType.IRQ.getIndex()] = 7;
        ticks[TickType.SOFTIRQ.getIndex()] = 6;
        ticks[TickType.STEAL.getIndex()] = 2;

        assertThat(OshiSystemMetricsSource.toCpuTicks(ticks)).isEqualTo(new CpuTicks(830, 1_000));
    }

    /**
     * Prism's CPU percentage is busy time on every system. This OSHI option would turn the
     * Windows counters into the frequency-adjusted "processor utility" instead; a configuration
     * file that switched it on would change what the metric means on one system only.
     */
    @Test
    void theWindowsProcessorUtilityOptionOfOshiIsOff() {
        assertThat(GlobalConfig.get(GlobalConfig.OSHI_OS_WINDOWS_CPU_UTILITY, false))
                .isFalse();
    }

    @Test
    void onWindowsTheConnectorFlagSeparatesHardwareAdaptersFromSoftwareInterfaces() {
        assertThat(OshiSystemMetricsSource.networkLayer(HostSystem.WINDOWS, true))
                .isEqualTo(DeviceLayer.PHYSICAL);
        assertThat(OshiSystemMetricsSource.networkLayer(HostSystem.WINDOWS, false))
                .isEqualTo(DeviceLayer.LOGICAL);
    }

    @Test
    void whereTheConnectorFlagIsNotAHardwareFactTheLayerIsUnknownWhateverTheFlagSays() {
        for (HostSystem system : new HostSystem[] {HostSystem.LINUX, HostSystem.MACOS, HostSystem.OTHER}) {
            assertThat(OshiSystemMetricsSource.networkLayer(system, true))
                    .as("%s, flag set", system)
                    .isEqualTo(DeviceLayer.UNKNOWN);
            assertThat(OshiSystemMetricsSource.networkLayer(system, false))
                    .as("%s, flag clear", system)
                    .isEqualTo(DeviceLayer.UNKNOWN);
        }
    }

    @Test
    void disksAreEnumeratedOnEveryPollOnlyWhereRefreshingOneDiskRepeatsTheEnumeration() {
        assertThat(OshiSystemMetricsSource.diskEnumerationInterval(HostSystem.LINUX))
                .isEqualTo(Duration.ZERO);
        for (HostSystem system : new HostSystem[] {HostSystem.WINDOWS, HostSystem.MACOS, HostSystem.OTHER}) {
            assertThat(OshiSystemMetricsSource.diskEnumerationInterval(system))
                    .as("%s", system)
                    .isPositive();
        }
    }

    @Test
    void aCountThatIsNotPositiveIsAbsent() {
        assertThat(OshiSystemMetricsSource.positive(8)).hasValue(8);
        assertThat(OshiSystemMetricsSource.positive(0)).isEmpty();
        assertThat(OshiSystemMetricsSource.positive(-1)).isEmpty();
    }

    @Test
    void textIsTrimmedAndOshisPlaceholdersAreAbsent() {
        assertThat(OshiSystemMetricsSource.text("AMD Ryzen 7 5800H with Radeon Graphics          "))
                .contains("AMD Ryzen 7 5800H with Radeon Graphics");
        assertThat(OshiSystemMetricsSource.text("unknown")).isEmpty();
        assertThat(OshiSystemMetricsSource.text("   ")).isEmpty();
        assertThat(OshiSystemMetricsSource.text("")).isEmpty();
        assertThat(OshiSystemMetricsSource.text(null)).isEmpty();
    }
}
