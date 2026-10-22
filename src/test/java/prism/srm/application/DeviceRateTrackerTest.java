package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import prism.srm.application.DeviceRateTracker.Counters;
import prism.srm.application.DeviceRateTracker.Rates;
import prism.srm.application.DeviceRateTracker.Result;
import prism.srm.domain.DeviceLayer;

/**
 * The tracker knows nothing about operating systems: every test describes a device topology with
 * names, layers and counters only.
 */
class DeviceRateTrackerTest {

    private static final long SECOND = 1_000_000_000L;

    private final DeviceRateTracker tracker = new DeviceRateTracker();

    @Test
    void theFirstReadingHasNoRates() {
        Result result = tracker.update(Map.of("a", physical(1_000, 2_000)), 5 * SECOND);

        assertThat(result.perDevice()).isEmpty();
        assertThat(result.total()).isEmpty();
    }

    @Test
    void ratesComeFromTheCounterIncreaseOverTheElapsedTime() {
        tracker.update(Map.of("a", physical(1_000, 2_000), "b", physical(0, 0)), 10 * SECOND);

        Result result = tracker.update(Map.of("a", physical(5_000, 2_400), "b", physical(600, 0)), 12 * SECOND);

        assertThat(result.perDevice())
                .containsOnly(Map.entry("a", new Rates(2_000.0, 200.0)), Map.entry("b", new Rates(300.0, 0.0)));
        assertThat(result.total()).contains(new Rates(2_300.0, 200.0));
    }

    @Test
    void aReadingWithoutDevicesHasNoTotalInsteadOfARateOfZero() {
        tracker.update(Map.of(), SECOND);

        assertThat(tracker.update(Map.of(), 2 * SECOND).total()).isEmpty();
    }

    @Test
    void aZeroIntervalHasNoRates() {
        tracker.update(Map.of("a", physical(1_000, 1_000)), 3 * SECOND);

        Result result = tracker.update(Map.of("a", physical(2_000, 2_000)), 3 * SECOND);

        assertThat(result.perDevice()).isEmpty();
        assertThat(result.total()).isEmpty();
    }

    @Test
    void aCounterResetSkipsThatDeviceAndTheTotalForOneReading() {
        tracker.update(Map.of("a", physical(9_000, 9_000), "b", physical(100, 100)), SECOND);

        Result reset = tracker.update(Map.of("a", physical(50, 9_500), "b", physical(300, 100)), 2 * SECOND);

        assertThat(reset.perDevice()).containsOnly(Map.entry("b", new Rates(200.0, 0.0)));
        assertThat(reset.total()).isEmpty();

        Result next = tracker.update(Map.of("a", physical(150, 9_500), "b", physical(300, 100)), 3 * SECOND);

        assertThat(next.perDevice().get("a")).isEqualTo(new Rates(100.0, 0.0));
        assertThat(next.total()).contains(new Rates(100.0, 0.0));
    }

    @Test
    void aResetOfTheOutboundCounterAloneAlsoSkipsTheDevice() {
        tracker.update(Map.of("a", physical(100, 9_000)), SECOND);

        Result reset = tracker.update(Map.of("a", physical(200, 10)), 2 * SECOND);

        assertThat(reset.perDevice()).isEmpty();
        assertThat(reset.total()).isEmpty();
    }

    @Test
    void theClockMayStartAtZeroOrBeNegative() {
        // System.nanoTime() has an arbitrary origin; only differences mean anything.
        tracker.update(Map.of("a", physical(0, 0)), -3 * SECOND);
        tracker.update(Map.of("a", physical(1_000, 0)), -2 * SECOND);

        Result acrossZero = tracker.update(Map.of("a", physical(5_000, 0)), 0);

        assertThat(acrossZero.total()).contains(new Rates(2_000.0, 0.0));
        assertThat(tracker.update(Map.of("a", physical(6_000, 0)), SECOND).total())
                .contains(new Rates(1_000.0, 0.0));
    }

    @Test
    void theClockMayWrapAround() {
        tracker.update(Map.of("a", physical(0, 0)), Long.MAX_VALUE - SECOND / 2 + 1);

        Result wrapped = tracker.update(Map.of("a", physical(3_000, 0)), Long.MIN_VALUE + SECOND / 2);

        assertThat(wrapped.total()).contains(new Rates(3_000.0, 0.0));
    }

    @Test
    void aVanishedPhysicalDeviceSkipsTheTotalButNotTheRemainingDevices() {
        tracker.update(Map.of("a", physical(0, 0), "gone", physical(7_000, 7_000)), SECOND);

        Result result = tracker.update(Map.of("a", physical(500, 250)), 2 * SECOND);

        assertThat(result.perDevice()).containsOnly(Map.entry("a", new Rates(500.0, 250.0)));
        assertThat(result.total()).isEmpty();
    }

    @Test
    void aNewPhysicalDeviceSkipsTheTotalUntilItHasTwoReadings() {
        tracker.update(Map.of("a", physical(0, 0)), SECOND);

        Result appeared = tracker.update(Map.of("a", physical(100, 0), "new", physical(80_000, 0)), 2 * SECOND);

        assertThat(appeared.perDevice()).containsOnlyKeys("a");
        assertThat(appeared.total()).isEmpty();

        Result next = tracker.update(Map.of("a", physical(200, 0), "new", physical(80_400, 0)), 3 * SECOND);

        assertThat(next.total()).contains(new Rates(500.0, 0.0));
    }

    @Test
    void aDeviceThatReturnsIsNotComparedWithItsReadingFromBeforeItVanished() {
        tracker.update(Map.of("a", physical(1_000, 1_000)), SECOND);
        tracker.update(Map.of(), 2 * SECOND);

        Result returned = tracker.update(Map.of("a", physical(900_000, 900_000)), 3 * SECOND);

        assertThat(returned.perDevice()).isEmpty();
        assertThat(returned.total()).isEmpty();
    }

    @Test
    void aNewEpochUnderTheSameNameHasNoRateForOneReadingInsteadOfASpike() {
        tracker.update(Map.of("eth", device(DeviceLayer.PHYSICAL, 1, 1_000, 1_000)), SECOND);

        // The adapter was disabled and enabled again, or another adapter now answers to the
        // name. The counters are higher, so without the epoch this would be a rate of 900 MB/s.
        Result rediscovered =
                tracker.update(Map.of("eth", device(DeviceLayer.PHYSICAL, 2, 900_000_000, 900_000_000)), 2 * SECOND);

        assertThat(rediscovered.perDevice()).isEmpty();
        assertThat(rediscovered.total()).isEmpty();

        Result next =
                tracker.update(Map.of("eth", device(DeviceLayer.PHYSICAL, 2, 900_002_000, 900_000_500)), 3 * SECOND);

        assertThat(next.total()).contains(new Rates(2_000.0, 500.0));
    }

    @Test
    void filterDriverCopiesOfAnAdapterAreNotAddedToTheTotal() {
        // Windows lists each filter driver bound to an adapter as an interface with the same
        // counters as the adapter.
        tracker.update(
                Map.of("wifi", physical(0, 0), "wifi-filter-1", logical(0, 0), "wifi-filter-2", logical(0, 0)), SECOND);

        Result result = tracker.update(
                Map.of(
                        "wifi", physical(6_000, 1_000),
                        "wifi-filter-1", logical(6_000, 1_000),
                        "wifi-filter-2", logical(6_000, 1_000)),
                2 * SECOND);

        assertThat(result.total()).contains(new Rates(6_000.0, 1_000.0));
        assertThat(result.perDevice()).hasSize(3);
    }

    @Test
    void aTunnelOverAnAdapterIsNotAddedToTheTotalButKeepsItsOwnRates() {
        // The tunnel carries 4000 of the adapter's 5000 bytes; they left the machine once.
        tracker.update(Map.of("eth", physical(0, 0), "vpn", logical(0, 0)), SECOND);

        Result result = tracker.update(Map.of("eth", physical(5_000, 800), "vpn", logical(4_000, 600)), 2 * SECOND);

        assertThat(result.total()).contains(new Rates(5_000.0, 800.0));
        assertThat(result.perDevice().get("vpn")).isEqualTo(new Rates(4_000.0, 600.0));
    }

    @Test
    void aBridgeOverTwoAdaptersCountsTheAdaptersAndNotTheBridge() {
        tracker.update(Map.of("eth0", physical(0, 0), "eth1", physical(0, 0), "bridge", logical(0, 0)), SECOND);

        Result result = tracker.update(
                Map.of("eth0", physical(1_000, 100), "eth1", physical(2_000, 200), "bridge", logical(3_000, 300)),
                2 * SECOND);

        assertThat(result.total()).contains(new Rates(3_000.0, 300.0));
    }

    @Test
    void aVirtualDiskOnAPhysicalDiskIsNotAddedToTheTotal() {
        tracker.update(Map.of("disk", physical(0, 0), "virtual-disk", logical(0, 0)), SECOND);

        Result result = tracker.update(
                Map.of("disk", physical(2_000, 9_000), "virtual-disk", logical(1_500, 8_000)), 2 * SECOND);

        assertThat(result.total()).contains(new Rates(2_000.0, 9_000.0));
    }

    @Test
    void withOnlyLogicalDevicesThereIsNoTotalButEveryDeviceHasRates() {
        tracker.update(Map.of("host-only", logical(0, 0)), SECOND);

        Result result = tracker.update(Map.of("host-only", logical(700, 300)), 2 * SECOND);

        assertThat(result.total()).isEmpty();
        assertThat(result.perDevice()).containsOnly(Map.entry("host-only", new Rates(700.0, 300.0)));
    }

    @Test
    void oneDeviceOfUnknownLayerLeavesTheTotalAbsentBecauseItMayRepeatAnother() {
        tracker.update(Map.of("eth", physical(0, 0), "mystery", unknown(0, 0)), SECOND);

        Result result = tracker.update(Map.of("eth", physical(1_000, 0), "mystery", unknown(1_000, 0)), 2 * SECOND);

        assertThat(result.total()).isEmpty();
        assertThat(result.perDevice()).hasSize(2);
    }

    @Test
    void theTotalReturnsAsSoonAsNoDeviceIsUnknown() {
        tracker.update(Map.of("eth", physical(0, 0), "mystery", unknown(0, 0)), SECOND);

        Result result = tracker.update(Map.of("eth", physical(1_000, 0)), 2 * SECOND);

        assertThat(result.total()).contains(new Rates(1_000.0, 0.0));
    }

    @Test
    void whateverHappensToALogicalDeviceDoesNotDisturbTheTotal() {
        tracker.update(Map.of("eth", physical(0, 0), "vpn", logical(50_000, 50_000)), SECOND);

        Result reset = tracker.update(Map.of("eth", physical(1_000, 0), "vpn", logical(10, 10)), 2 * SECOND);
        assertThat(reset.total()).as("logical device reset").contains(new Rates(1_000.0, 0.0));
        assertThat(reset.perDevice()).containsOnlyKeys("eth");

        Result vanished = tracker.update(Map.of("eth", physical(3_000, 0)), 3 * SECOND);
        assertThat(vanished.total()).as("logical device vanished").contains(new Rates(2_000.0, 0.0));

        Result appeared = tracker.update(Map.of("eth", physical(6_000, 0), "docker", logical(1, 1)), 4 * SECOND);
        assertThat(appeared.total()).as("logical device appeared").contains(new Rates(3_000.0, 0.0));

        Result replaced = tracker.update(
                Map.of("eth", physical(10_000, 0), "docker", device(DeviceLayer.LOGICAL, 9, 500, 500)), 5 * SECOND);
        assertThat(replaced.total()).as("logical device under a new epoch").contains(new Rates(4_000.0, 0.0));
    }

    @Test
    void aDeviceWhoseLayerChangesSkipsTheTotalForOneReading() {
        tracker.update(Map.of("eth", physical(0, 0), "other", physical(0, 0)), SECOND);

        Result demoted = tracker.update(Map.of("eth", physical(1_000, 0), "other", logical(1_000, 0)), 2 * SECOND);
        assertThat(demoted.total()).as("physical became logical").isEmpty();

        Result settled = tracker.update(Map.of("eth", physical(2_000, 0), "other", logical(2_000, 0)), 3 * SECOND);
        assertThat(settled.total()).contains(new Rates(1_000.0, 0.0));

        Result promoted = tracker.update(Map.of("eth", physical(3_000, 0), "other", physical(3_000, 0)), 4 * SECOND);
        assertThat(promoted.total()).as("logical became physical").isEmpty();
    }

    private static Counters physical(long inbound, long outbound) {
        return device(DeviceLayer.PHYSICAL, 1, inbound, outbound);
    }

    private static Counters logical(long inbound, long outbound) {
        return device(DeviceLayer.LOGICAL, 1, inbound, outbound);
    }

    private static Counters unknown(long inbound, long outbound) {
        return device(DeviceLayer.UNKNOWN, 1, inbound, outbound);
    }

    private static Counters device(DeviceLayer layer, long epoch, long inbound, long outbound) {
        return new Counters(layer, epoch, inbound, outbound);
    }
}
