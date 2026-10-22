package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Validation and immutability of the system reading and snapshot records. */
class SystemRecordsTest {

    @Test
    void cpuTicksNeedIdleWithinTotal() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuTicks(11, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuTicks(-1, 10));
        assertThat(new CpuTicks(10, 10).idle()).isEqualTo(10);
    }

    @Test
    void cpuUsageIsAPercentage() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuUsage(100.1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuUsage(-0.1, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new CpuUsage(Double.NaN, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CpuUsage(50, List.of(OptionalDouble.of(50), OptionalDouble.of(101))));
        assertThat(new CpuUsage(100, List.of(OptionalDouble.empty(), OptionalDouble.of(0))).totalPercent())
                .isEqualTo(100);
    }

    @Test
    void ratesAreFiniteAndNotNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DiskRates(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiskRates(0, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiskRates(Double.POSITIVE_INFINITY, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new NetworkRates(0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new NetworkRates(Double.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new NetworkRates(0, Double.POSITIVE_INFINITY));
    }

    @Test
    void countersAndSizesAreNotNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DiskCounters("disk0", DeviceLayer.PHYSICAL, 1, -1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DiskCounters("disk0", DeviceLayer.PHYSICAL, 1, 0, -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NetworkCounters("eth0", "Ethernet", DeviceLayer.PHYSICAL, 1, -1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NetworkCounters("eth0", "Ethernet", DeviceLayer.PHYSICAL, 1, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new FileSystemUsage("root", "/", -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FileSystemUsage("root", "/", 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemActivity(Duration.ofSeconds(-1), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SystemActivity(Duration.ZERO, -1));
    }

    @Test
    void processorCountsArePositiveWhenPresent() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessorInfo(0, OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessorInfo(4, OptionalInt.of(0), OptionalInt.empty(), Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessorInfo(4, OptionalInt.empty(), OptionalInt.of(0), Optional.empty()));
        assertThat(new ProcessorInfo(4, OptionalInt.empty(), OptionalInt.empty(), Optional.empty())
                        .logicalProcessorCount())
                .isEqualTo(4);
    }

    @Test
    void anOperatingSystemHasAName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OperatingSystemInfo(" ", Optional.empty(), Optional.empty()));
    }

    @Test
    void aCpuSampleKeepsItsOwnCopyOfTheProcessorList() {
        List<CpuTicks> perProcessor = new ArrayList<>(List.of(new CpuTicks(1, 2)));
        CpuSample sample = new CpuSample(new CpuTicks(1, 2), perProcessor);

        perProcessor.add(new CpuTicks(3, 4));

        assertThat(sample.perProcessor()).containsExactly(new CpuTicks(1, 2));
        assertThatThrownBy(() -> sample.perProcessor().add(new CpuTicks(5, 6)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSnapshotKeepsItsOwnCopyOfTheFileSystemList() {
        List<FileSystemUsage> fileSystems = new ArrayList<>(List.of(new FileSystemUsage("root", "/", 100, 40)));
        SystemSnapshot snapshot = new SystemSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fileSystems),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                emptyHistory());

        fileSystems.clear();

        assertThat(snapshot.fileSystems().orElseThrow()).containsExactly(new FileSystemUsage("root", "/", 100, 40));
    }

    @Test
    void networkUsageAndHistoryKeepTheirOwnCopiesOfTheirLists() {
        List<NetworkInterfaceUsage> interfaces = new ArrayList<>(
                List.of(new NetworkInterfaceUsage("eth0", "Ethernet", DeviceLayer.PHYSICAL, Optional.empty())));
        List<MetricHistory> perProcessor = new ArrayList<>(List.of(MetricHistory.of(1.0)));
        NetworkUsage usage = new NetworkUsage(interfaces, Optional.empty());
        SystemHistory history = new SystemHistory(
                MetricHistory.empty(),
                perProcessor,
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty());

        interfaces.clear();
        perProcessor.clear();

        assertThat(usage.interfaces()).hasSize(1);
        assertThat(history.cpuPerProcessorPercent()).containsExactly(MetricHistory.of(1.0));
    }

    private static SystemHistory emptyHistory() {
        return new SystemHistory(
                MetricHistory.empty(),
                List.of(),
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty(),
                MetricHistory.empty());
    }
}
