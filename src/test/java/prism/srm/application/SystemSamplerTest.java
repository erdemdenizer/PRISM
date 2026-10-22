package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static prism.srm.application.FakeSystemMetricsSource.GIB;
import static prism.srm.application.FakeSystemMetricsSource.disk;
import static prism.srm.application.FakeSystemMetricsSource.nic;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import prism.srm.application.FakeSystemMetricsSource.Metric;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.DiskRates;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;

class SystemSamplerTest {

    private static final long SECOND = 1_000_000_000L;

    private final FakeSystemMetricsSource source = new FakeSystemMetricsSource();
    private long nowNanos = 1_000 * SECOND;
    private final SystemSampler sampler = new SystemSampler(source, () -> nowNanos, 5);

    @Test
    void theFirstSampleHasFactsAndInstantValuesButNoRates() {
        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.processor()).contains(source.processorInfo);
        assertThat(snapshot.operatingSystem()).contains(source.operatingSystem);
        assertThat(snapshot.memory()).contains(source.memory);
        assertThat(snapshot.fileSystems()).contains(source.fileSystems);
        assertThat(snapshot.activity()).contains(source.activity);
        assertThat(snapshot.cpu()).isEmpty();
        assertThat(snapshot.diskRates()).isEmpty();
        assertThat(snapshot.network().orElseThrow().total()).isEmpty();
        assertThat(snapshot.network().orElseThrow().interfaces())
                .containsExactly(
                        new NetworkInterfaceUsage("eth0", "Fake Ethernet", DeviceLayer.PHYSICAL, Optional.empty()));
    }

    @Test
    void ratesComeFromTheCounterDifferencesAndTheClock() {
        sampler.sample();
        advance(2 * SECOND);
        source.cpu = cpuReading(new CpuTicks(150, 200), new CpuTicks(90, 100), new CpuTicks(60, 100));
        source.disks = List.of(disk("disk0", 4_000, 1_000), disk("disk1", 600, 0));
        source.interfaces = List.of(nic("eth0", "Fake Ethernet", 10_000, 2_000), nic("wlan0", "Fake Wi-Fi", 0, 0));

        SystemSnapshot second = sampler.sample();
        // disk1 and wlan0 are new in this reading, so the totals wait for the next one.
        assertThat(second.cpu())
                .contains(new CpuUsage(25.0, List.of(OptionalDouble.of(10.0), OptionalDouble.of(40.0))));
        assertThat(second.diskRates()).isEmpty();
        assertThat(second.network().orElseThrow().total()).isEmpty();

        advance(2 * SECOND);
        source.cpu = cpuReading(new CpuTicks(150, 400), new CpuTicks(90, 200), new CpuTicks(60, 200));
        source.disks = List.of(disk("disk0", 8_000, 1_000), disk("disk1", 800, 500));
        source.interfaces = List.of(nic("eth0", "Fake Ethernet", 30_000, 2_600), nic("wlan0", "Fake Wi-Fi", 1_000, 0));

        SystemSnapshot third = sampler.sample();

        assertThat(third.cpu())
                .contains(new CpuUsage(100.0, List.of(OptionalDouble.of(100.0), OptionalDouble.of(100.0))));
        assertThat(third.diskRates()).contains(new DiskRates(2_100.0, 250.0));
        assertThat(third.network().orElseThrow().total()).contains(new NetworkRates(10_500.0, 300.0));
        assertThat(third.network().orElseThrow().interfaces())
                .containsExactly(
                        new NetworkInterfaceUsage(
                                "eth0",
                                "Fake Ethernet",
                                DeviceLayer.PHYSICAL,
                                Optional.of(new NetworkRates(10_000.0, 300.0))),
                        new NetworkInterfaceUsage(
                                "wlan0",
                                "Fake Wi-Fi",
                                DeviceLayer.PHYSICAL,
                                Optional.of(new NetworkRates(500.0, 0.0))));
    }

    @Test
    void aCounterResetIsASkippedSampleAndAGapInTheHistory() {
        source.disks = List.of(disk("disk0", 50_000, 50_000));
        sampler.sample();
        advance(SECOND);
        source.disks = List.of(disk("disk0", 51_000, 50_500));
        sampler.sample();
        advance(SECOND);
        source.disks = List.of(disk("disk0", 200, 100));

        SystemSnapshot afterReset = sampler.sample();

        assertThat(afterReset.diskRates()).isEmpty();

        advance(SECOND);
        source.disks = List.of(disk("disk0", 700, 100));

        SystemSnapshot recovered = sampler.sample();

        assertThat(recovered.diskRates()).contains(new DiskRates(500.0, 0.0));
        assertThat(recovered.history().diskReadBytesPerSecond())
                .isEqualTo(MetricHistory.of(Double.NaN, 1_000.0, Double.NaN, 500.0));
        assertThat(recovered.history().diskWriteBytesPerSecond())
                .isEqualTo(MetricHistory.of(Double.NaN, 500.0, Double.NaN, 0.0));
    }

    @Test
    void cpuUsageReturnsOnTheReadingAfterACounterReset() {
        source.cpu = cpuReading(new CpuTicks(500, 900), new CpuTicks(250, 450), new CpuTicks(250, 450));
        sampler.sample();
        source.cpu = cpuReading(new CpuTicks(5, 9), new CpuTicks(2, 4), new CpuTicks(3, 5));

        assertThat(sampler.sample().cpu()).isEmpty();

        source.cpu = cpuReading(new CpuTicks(55, 109), new CpuTicks(52, 54), new CpuTicks(3, 55));

        SystemSnapshot recovered = sampler.sample();

        // Compared with the reading taken after the reset, not with the one before it.
        assertThat(recovered.cpu())
                .contains(new CpuUsage(50.0, List.of(OptionalDouble.of(0.0), OptionalDouble.of(100.0))));
        assertThat(recovered.history().cpuTotalPercent()).isEqualTo(MetricHistory.of(Double.NaN, Double.NaN, 50.0));
    }

    @Test
    void aReadingIsDatedAtTheMidpointOfTheTimeTheReadTook() {
        sampler.sample();
        advance(SECOND);
        // The read starts one second after the first reading and itself takes one second, so its
        // midpoint is 1.5 s after the first reading. Dating it at the start would give 3000
        // bytes per second, dating it at the end 1500.
        source.duringDiskRead = () -> advance(SECOND);
        source.disks = List.of(disk("disk0", 3_000, 1_500));

        assertThat(sampler.sample().diskRates()).contains(new DiskRates(2_000.0, 1_000.0));
    }

    @Test
    void aDiskFoundAgainUnderANewEpochHasNoRateInsteadOfASpike() {
        source.disks = List.of(new DiskCounters("disk0", DeviceLayer.PHYSICAL, 1, 1_000, 1_000));
        sampler.sample();
        advance(SECOND);
        // Same name, but the platform no longer vouches that it is the same device: its much
        // larger counters must not be read as one second of traffic.
        source.disks = List.of(new DiskCounters("disk0", DeviceLayer.PHYSICAL, 2, 900_000_000, 900_000_000));

        assertThat(sampler.sample().diskRates()).isEmpty();

        advance(SECOND);
        source.disks = List.of(new DiskCounters("disk0", DeviceLayer.PHYSICAL, 2, 900_004_000, 900_000_000));

        SystemSnapshot next = sampler.sample();

        assertThat(next.diskRates()).contains(new DiskRates(4_000.0, 0.0));
        assertThat(next.history().diskReadBytesPerSecond())
                .isEqualTo(MetricHistory.of(Double.NaN, Double.NaN, 4_000.0));
    }

    @Test
    void aLogicalDiskOnTopOfAPhysicalOneIsNotAddedToTheTotal() {
        source.disks = List.of(
                new DiskCounters("disk0", DeviceLayer.PHYSICAL, 1, 0, 0),
                new DiskCounters("virtual0", DeviceLayer.LOGICAL, 2, 0, 0));
        sampler.sample();
        advance(SECOND);
        // Everything written to the virtual disk is also written to the disk that holds it.
        source.disks = List.of(
                new DiskCounters("disk0", DeviceLayer.PHYSICAL, 1, 500, 7_000),
                new DiskCounters("virtual0", DeviceLayer.LOGICAL, 2, 500, 6_000));

        assertThat(sampler.sample().diskRates()).contains(new DiskRates(500.0, 7_000.0));
    }

    @Test
    void theNetworkTotalIsThePhysicalInterfacesWhileEveryInterfaceKeepsItsOwnRates() {
        source.interfaces = List.of(
                nic("wifi", "Fake Wi-Fi", 0, 0),
                new NetworkCounters("wifi-filter", "Fake Wi-Fi filter driver", DeviceLayer.LOGICAL, 2, 0, 0),
                new NetworkCounters("vpn", "Fake VPN", DeviceLayer.LOGICAL, 3, 0, 0));
        sampler.sample();
        advance(SECOND);
        source.interfaces = List.of(
                nic("wifi", "Fake Wi-Fi", 10_000, 2_000),
                new NetworkCounters("wifi-filter", "Fake Wi-Fi filter driver", DeviceLayer.LOGICAL, 2, 10_000, 2_000),
                new NetworkCounters("vpn", "Fake VPN", DeviceLayer.LOGICAL, 3, 8_000, 1_500));

        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.network().orElseThrow().total()).contains(new NetworkRates(10_000.0, 2_000.0));
        assertThat(snapshot.network().orElseThrow().interfaces())
                .containsExactly(
                        new NetworkInterfaceUsage(
                                "wifi",
                                "Fake Wi-Fi",
                                DeviceLayer.PHYSICAL,
                                Optional.of(new NetworkRates(10_000.0, 2_000.0))),
                        new NetworkInterfaceUsage(
                                "wifi-filter",
                                "Fake Wi-Fi filter driver",
                                DeviceLayer.LOGICAL,
                                Optional.of(new NetworkRates(10_000.0, 2_000.0))),
                        new NetworkInterfaceUsage(
                                "vpn",
                                "Fake VPN",
                                DeviceLayer.LOGICAL,
                                Optional.of(new NetworkRates(8_000.0, 1_500.0))));
    }

    @Test
    void anInterfaceOfUnknownLayerLeavesTheTotalAbsentButNotTheInterfaceRates() {
        source.interfaces = List.of(
                new NetworkCounters("eth0", "Fake Ethernet", DeviceLayer.UNKNOWN, 1, 0, 0),
                new NetworkCounters("br0", "Fake bridge", DeviceLayer.UNKNOWN, 2, 0, 0));
        sampler.sample();
        advance(SECOND);
        source.interfaces = List.of(
                new NetworkCounters("eth0", "Fake Ethernet", DeviceLayer.UNKNOWN, 1, 4_000, 0),
                new NetworkCounters("br0", "Fake bridge", DeviceLayer.UNKNOWN, 2, 4_000, 0));

        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.network().orElseThrow().total()).isEmpty();
        assertThat(snapshot.network().orElseThrow().interfaces())
                .extracting(NetworkInterfaceUsage::rates)
                .containsExactly(
                        Optional.of(new NetworkRates(4_000.0, 0.0)), Optional.of(new NetworkRates(4_000.0, 0.0)));
        assertThat(snapshot.history().networkDownloadBytesPerSecond())
                .isEqualTo(MetricHistory.of(Double.NaN, Double.NaN));
    }

    @Test
    void aVanishedInterfaceSkipsTheTotalButNotTheOthers() {
        source.interfaces = List.of(nic("eth0", "Fake Ethernet", 0, 0), nic("usb0", "Fake USB", 9_000, 0));
        sampler.sample();
        advance(SECOND);
        source.interfaces = List.of(nic("eth0", "Fake Ethernet", 300, 100));

        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.network().orElseThrow().total()).isEmpty();
        assertThat(snapshot.network().orElseThrow().interfaces())
                .containsExactly(new NetworkInterfaceUsage(
                        "eth0", "Fake Ethernet", DeviceLayer.PHYSICAL, Optional.of(new NetworkRates(300.0, 100.0))));
    }

    @Test
    void aSourceThatThrowsLeavesOnlyItsOwnMetricUnavailable() {
        source.fail(Metric.MEMORY, new IllegalStateException("memory query failed"));

        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.memory()).isEmpty();
        assertThat(snapshot.processor()).isPresent();
        assertThat(snapshot.operatingSystem()).isPresent();
        assertThat(snapshot.fileSystems()).isPresent();
        assertThat(snapshot.network()).isPresent();
        assertThat(snapshot.activity()).isPresent();
    }

    @Test
    void everyMetricIsIsolatedFromAFailureOfItsSource() {
        for (Metric metric : Metric.values()) {
            source.fail(metric, new IllegalStateException(metric + " query failed"));
        }

        SystemSnapshot snapshot = sampler.sample();

        assertThat(snapshot.processor()).isEmpty();
        assertThat(snapshot.operatingSystem()).isEmpty();
        assertThat(snapshot.cpu()).isEmpty();
        assertThat(snapshot.memory()).isEmpty();
        assertThat(snapshot.fileSystems()).isEmpty();
        assertThat(snapshot.diskRates()).isEmpty();
        assertThat(snapshot.network()).isEmpty();
        assertThat(snapshot.activity()).isEmpty();
    }

    @Test
    void aRepeatedFailureIsLoggedOnceAndTheMetricReturnsWhenTheSourceRecovers() {
        try (LogCapture log = new LogCapture(SystemSampler.class)) {
            source.fail(Metric.MEMORY, new IllegalStateException("memory query failed"));
            for (int cycle = 0; cycle < 10; cycle++) {
                assertThat(sampler.sample().memory()).isEmpty();
            }

            assertThat(log.warnings()).hasSize(1);
            assertThat(log.warnings().get(0)).contains("Memory reading");

            source.recover(Metric.MEMORY);

            assertThat(sampler.sample().memory()).contains(source.memory);

            source.fail(Metric.MEMORY, new IllegalStateException("memory query failed again"));
            sampler.sample();

            assertThat(log.warnings()).hasSize(1);
        }
    }

    @Test
    void eachKindOfFailureIsLoggedOnce() {
        try (LogCapture log = new LogCapture(SystemSampler.class)) {
            source.fail(Metric.MEMORY, new IllegalStateException("query failed"));
            source.fail(Metric.ACTIVITY, new IllegalStateException("query failed"));
            sampler.sample();
            sampler.sample();
            source.fail(Metric.MEMORY, new UnsupportedOperationException("not on this system"));
            sampler.sample();
            sampler.sample();

            assertThat(log.warnings()).hasSize(3);
        }
    }

    @Test
    void anErrorFromTheSourceIsNotTreatedAsAFailedReading() {
        source.fail(Metric.CPU, new UnsatisfiedLinkError("library not found"));

        assertThatThrownBy(sampler::sample).isInstanceOf(UnsatisfiedLinkError.class);
    }

    @Test
    void aSourceThatReturnsNothingLeavesTheMetricUnavailable() {
        source.memory = null;

        assertThat(sampler.sample().memory()).isEmpty();
    }

    @Test
    void aDeviceListedTwiceMakesTheReadingUnusable() {
        sampler.sample();
        advance(SECOND);
        source.disks = List.of(disk("disk0", 100, 0), disk("disk0", 900, 0));

        assertThat(sampler.sample().diskRates()).isEmpty();
    }

    @Test
    void afterAFailedReadingTheRateSpansBackToTheLastGoodOne() {
        source.disks = List.of(disk("disk0", 0, 0));
        source.cpu = cpuReading(new CpuTicks(0, 0), new CpuTicks(0, 0), new CpuTicks(0, 0));
        sampler.sample();
        advance(SECOND);
        source.fail(Metric.DISKS, new IllegalStateException("disk query failed"));
        source.fail(Metric.CPU, new IllegalStateException("cpu query failed"));

        SystemSnapshot failed = sampler.sample();

        assertThat(failed.diskRates()).isEmpty();
        assertThat(failed.cpu()).isEmpty();

        advance(SECOND);
        source.recover(Metric.DISKS);
        source.recover(Metric.CPU);
        source.disks = List.of(disk("disk0", 6_000, 0));
        source.cpu = cpuReading(new CpuTicks(100, 400), new CpuTicks(50, 200), new CpuTicks(50, 200));

        SystemSnapshot recovered = sampler.sample();

        // 6000 bytes over the two seconds since the last good reading, not over one.
        assertThat(recovered.diskRates()).contains(new DiskRates(3_000.0, 0.0));
        assertThat(recovered.cpu())
                .contains(new CpuUsage(75.0, List.of(OptionalDouble.of(75.0), OptionalDouble.of(75.0))));
    }

    @Test
    void staticFactsAreReadUntilAvailableAndThenKept() {
        source.fail(Metric.PROCESSOR, new IllegalStateException("not ready"));

        assertThat(sampler.sample().processor()).isEmpty();
        assertThat(sampler.sample().processor()).isEmpty();

        source.recover(Metric.PROCESSOR);

        assertThat(sampler.sample().processor()).contains(source.processorInfo);
        assertThat(sampler.sample().processor()).contains(source.processorInfo);
        assertThat(source.reads(Metric.PROCESSOR)).isEqualTo(3);
        assertThat(source.reads(Metric.OPERATING_SYSTEM)).isEqualTo(1);
    }

    @Test
    void historiesNeverExceedTheirCapacityAndKeepTheNewestSamples() {
        SystemHistory history = null;
        for (int cycle = 1; cycle <= 12; cycle++) {
            advance(SECOND);
            source.cpu = cpuReading(
                    new CpuTicks(0, cycle * 100L), new CpuTicks(0, cycle * 50L), new CpuTicks(0, cycle * 50L));
            source.memory = new MemorySample(8 * GIB, 8 * GIB - cycle, Optional.empty());
            source.disks = List.of(disk("disk0", cycle * cycle * 1_000L, cycle * 10L));
            source.interfaces = List.of(nic("eth0", "Fake Ethernet", cycle * 2_000L, cycle * 20L));

            history = sampler.sample().history();

            int expectedSize = Math.min(cycle, 5);
            assertThat(history.cpuTotalPercent().size()).isEqualTo(expectedSize);
            assertThat(history.memoryUsedBytes().size()).isEqualTo(expectedSize);
            assertThat(history.diskReadBytesPerSecond().size()).isEqualTo(expectedSize);
            assertThat(history.diskWriteBytesPerSecond().size()).isEqualTo(expectedSize);
            assertThat(history.networkDownloadBytesPerSecond().size()).isEqualTo(expectedSize);
            assertThat(history.networkUploadBytesPerSecond().size()).isEqualTo(expectedSize);
            assertThat(history.cpuPerProcessorPercent())
                    .hasSize(2)
                    .allSatisfy(perProcessor -> assertThat(perProcessor.size()).isEqualTo(expectedSize));
        }

        // The read counter is cycle squared, so the rate grows by 2000 each cycle: cycles 8 to 12.
        assertThat(history.memoryUsedBytes()).isEqualTo(MetricHistory.of(8, 9, 10, 11, 12));
        assertThat(history.diskReadBytesPerSecond())
                .isEqualTo(MetricHistory.of(15_000, 17_000, 19_000, 21_000, 23_000));
        assertThat(history.networkUploadBytesPerSecond()).isEqualTo(MetricHistory.of(20, 20, 20, 20, 20));
        assertThat(history.cpuTotalPercent()).isEqualTo(MetricHistory.of(100, 100, 100, 100, 100));
    }

    @Test
    void everyHistoryGetsOneEntryPerCycleEvenWithoutAValue() {
        source.fail(Metric.MEMORY, new IllegalStateException("memory query failed"));
        sampler.sample();
        advance(SECOND);
        source.recover(Metric.MEMORY);
        source.cpu = cpuReading(new CpuTicks(50, 100), new CpuTicks(50, 50), new CpuTicks(0, 50));

        SystemHistory history = sampler.sample().history();

        assertThat(history.memoryUsedBytes()).isEqualTo(MetricHistory.of(Double.NaN, 2.0 * GIB));
        assertThat(history.cpuTotalPercent()).isEqualTo(MetricHistory.of(Double.NaN, 50.0));
        assertThat(history.cpuPerProcessorPercent())
                .containsExactly(MetricHistory.of(Double.NaN, 0.0), MetricHistory.of(Double.NaN, 100.0));
        assertThat(history.diskReadBytesPerSecond()).isEqualTo(MetricHistory.of(Double.NaN, 0.0));
        assertThat(history.networkDownloadBytesPerSecond()).isEqualTo(MetricHistory.of(Double.NaN, 0.0));
    }

    @Test
    void perProcessorHistoriesStartWithTheFirstCpuReading() {
        source.fail(Metric.CPU, new IllegalStateException("cpu query failed"));
        sampler.sample();
        source.recover(Metric.CPU);
        sampler.sample();
        source.cpu = cpuReading(new CpuTicks(50, 100), new CpuTicks(50, 50), new CpuTicks(0, 50));

        SystemHistory history = sampler.sample().history();

        // The processor count is unknown during the failed first cycle, so these start a cycle late.
        assertThat(history.cpuTotalPercent()).isEqualTo(MetricHistory.of(Double.NaN, Double.NaN, 50.0));
        assertThat(history.cpuPerProcessorPercent())
                .containsExactly(MetricHistory.of(Double.NaN, 0.0), MetricHistory.of(Double.NaN, 100.0));
    }

    @Test
    void perProcessorHistoriesStartOverWhenTheProcessorCountChanges() {
        sampler.sample();
        advance(SECOND);
        source.cpu = cpuReading(new CpuTicks(50, 100), new CpuTicks(25, 50), new CpuTicks(25, 50));
        sampler.sample();
        advance(SECOND);
        source.cpu =
                cpuReading(new CpuTicks(100, 300), new CpuTicks(50, 100), new CpuTicks(50, 100), new CpuTicks(0, 100));

        SystemHistory history = sampler.sample().history();

        assertThat(history.cpuTotalPercent()).isEqualTo(MetricHistory.of(Double.NaN, 50.0, 75.0));
        assertThat(history.cpuPerProcessorPercent())
                .containsExactly(
                        MetricHistory.of(Double.NaN), MetricHistory.of(Double.NaN), MetricHistory.of(Double.NaN));
    }

    private void advance(long nanos) {
        nowNanos += nanos;
    }

    private static CpuSample cpuReading(CpuTicks total, CpuTicks... perProcessor) {
        return new CpuSample(total, List.of(perProcessor));
    }
}
