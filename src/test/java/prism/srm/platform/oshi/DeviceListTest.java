package prism.srm.platform.oshi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.platform.oshi.DeviceList.Held;

class DeviceListTest {

    private static final long SECOND = 1_000_000_000L;

    /** Stands in for an OSHI device object: a fixed name and facts that a refresh may change. */
    private static final class Device {
        final String name;
        String identity;

        Device(String name, String identity) {
            this.name = name;
            this.identity = identity;
        }
    }

    /** What is attached to the fake system: name to identity. Each enumeration returns new objects. */
    private final Map<String, String> attached = new HashMap<>(Map.of("a", "serial-a", "b", "serial-b"));

    /** Devices whose refresh reports that they are gone. */
    private final Set<String> failsToRefresh = new HashSet<>();

    private final Map<String, String> identityAfterRefresh = new HashMap<>();
    private final List<String> refreshed = new ArrayList<>();
    private int enumerations;
    private boolean enumerationFails;
    private long nowNanos = 500 * SECOND;

    private final DeviceList<Device> devices = newList(Duration.ofSeconds(10));

    @Test
    void theFirstPollEnumeratesWithoutRefreshingAndGivesEveryDeviceItsOwnEpoch() {
        Map<String, Long> epochs = epochsByName(devices.poll());

        assertThat(epochs).containsOnlyKeys("a", "b");
        assertThat(epochs.get("a")).isNotEqualTo(epochs.get("b"));
        assertThat(enumerations).isEqualTo(1);
        assertThat(refreshed).isEmpty();
    }

    @Test
    void laterPollsRefreshTheHeldDevicesAndKeepTheirEpochs() {
        Map<String, Long> first = epochsByName(devices.poll());
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).isEqualTo(first);
        assertThat(enumerations).isEqualTo(1);
        assertThat(refreshed).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void anEnumerationKeepsTheEpochOfADeviceWithTheSameIdentityAlthoughItsObjectIsNew() {
        List<Held<Device>> first = devices.poll();
        nowNanos += 10 * SECOND;

        List<Held<Device>> second = devices.poll();

        assertThat(enumerations).isEqualTo(2);
        assertThat(epochsByName(second)).isEqualTo(epochsByName(first));
        assertThat(second.get(0).device()).isNotSameAs(first.get(0).device());
    }

    @Test
    void aDeviceAttachedLaterAppearsAfterTheEnumerationIntervalWithANewEpoch() {
        Map<String, Long> first = epochsByName(devices.poll());
        attached.put("c", "serial-c");
        nowNanos += 9 * SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b");

        nowNanos += SECOND;
        Map<String, Long> afterEnumeration = epochsByName(devices.poll());

        assertThat(afterEnumeration).containsAllEntriesOf(first).containsKey("c");
        assertThat(afterEnumeration.get("c")).isNotIn(first.values());
    }

    @Test
    void aDeviceThatIsGoneIsDroppedAtOnceAndTheNextPollEnumeratesToConfirm() {
        Map<String, Long> first = epochsByName(devices.poll());
        attached.remove("a");
        failsToRefresh.add("a");
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("b");
        assertThat(enumerations).isEqualTo(1);

        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnly(Map.entry("b", first.get("b")));
        assertThat(enumerations).as("enumerated right after the drop").isEqualTo(2);

        refreshed.clear();
        nowNanos += SECOND;
        devices.poll();

        assertThat(enumerations).as("then back to refreshing").isEqualTo(2);
        assertThat(refreshed).containsExactly("b");
    }

    @Test
    void aDeviceThatFailedToRefreshButIsStillThereIsBackOnTheNextPollUnderANewEpoch() {
        // Without the immediate enumeration it would stay unlisted for the rest of the
        // enumeration interval, and a total over the remaining devices would be too low.
        Map<String, Long> first = epochsByName(devices.poll());
        failsToRefresh.add("a");
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("b");

        failsToRefresh.clear();
        nowNanos += SECOND;
        Map<String, Long> next = epochsByName(devices.poll());

        assertThat(next).containsOnlyKeys("a", "b");
        assertThat(next.get("b")).isEqualTo(first.get("b"));
        assertThat(next.get("a")).isNotIn(first.values());
    }

    @Test
    void aDeviceWhoseRefreshReadsAnotherDeviceIsDroppedAndComesBackUnderANewEpoch() {
        // What happens on Windows when an adapter is disabled and enabled again and its
        // interface index now belongs to another adapter: the refresh succeeds, but the facts
        // it brings back are those of the other one.
        Map<String, Long> first = epochsByName(devices.poll());
        identityAfterRefresh.put("a", "alias-of-another-adapter");
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("b");

        identityAfterRefresh.clear();
        nowNanos += SECOND;
        Map<String, Long> next = epochsByName(devices.poll());

        assertThat(next.get("a")).isNotIn(first.values());
        assertThat(next.get("b")).isEqualTo(first.get("b"));
    }

    @Test
    void anotherDeviceUnderTheSameNameGetsANewEpochAtTheEnumeration() {
        Map<String, Long> first = epochsByName(devices.poll());
        attached.put("a", "serial-of-a-replacement");
        nowNanos += 10 * SECOND;

        Map<String, Long> afterEnumeration = epochsByName(devices.poll());

        assertThat(afterEnumeration.get("a")).isNotIn(first.values());
        assertThat(afterEnumeration.get("b")).isEqualTo(first.get("b"));
    }

    @Test
    void theEnumerationIntervalIsMeasuredCorrectlyWhenTheClockIsNegativeOrWraps() {
        // System.nanoTime() has an arbitrary origin and may wrap; only differences mean anything.
        nowNanos = Long.MAX_VALUE - 4 * SECOND;
        devices.poll();
        attached.put("c", "serial-c");
        nowNanos += 9 * SECOND;

        assertThat(nowNanos).isNegative();
        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b");
        assertThat(enumerations).isEqualTo(1);

        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void aFailedEnumerationIsTriedAgainOnTheNextPoll() {
        enumerationFails = true;

        assertThatIllegalStateException().isThrownBy(devices::poll);

        enumerationFails = false;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b");
    }

    @Test
    void aFailedReenumerationKeepsTheHeldDevicesAndTheirEpochsForTheRetry() {
        Map<String, Long> first = epochsByName(devices.poll());
        nowNanos += 10 * SECOND;
        enumerationFails = true;

        assertThatIllegalStateException().isThrownBy(devices::poll);

        enumerationFails = false;
        attached.remove("a");

        assertThat(epochsByName(devices.poll())).containsOnly(Map.entry("b", first.get("b")));
        assertThat(enumerations).isEqualTo(2);
    }

    @Test
    void anEnumerationThatFailsRightAfterADropIsStillDueOnTheFollowingPoll() {
        devices.poll();
        failsToRefresh.add("a");
        nowNanos += SECOND;
        devices.poll();
        failsToRefresh.clear();
        enumerationFails = true;
        nowNanos += SECOND;

        assertThatIllegalStateException().isThrownBy(devices::poll);

        enumerationFails = false;
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b");
    }

    @Test
    void anEnumerationThatFindsNothingIsRepeatedOnTheNextPoll() {
        // OSHI reports a failed disk query on Windows as "no disks". Waiting a whole enumeration
        // interval for the next attempt would leave the metric without devices for that long.
        Map<String, String> reallyAttached = new HashMap<>(attached);
        attached.clear();

        assertThat(devices.poll()).isEmpty();

        attached.putAll(reallyAttached);
        nowNanos += SECOND;

        assertThat(epochsByName(devices.poll())).containsOnlyKeys("a", "b");
        assertThat(enumerations).isEqualTo(2);
    }

    @Test
    void theReturnedListIsNotAffectedByLaterPolls() {
        List<Held<Device>> first = devices.poll();
        failsToRefresh.add("a");
        failsToRefresh.add("b");
        nowNanos += SECOND;
        devices.poll();

        assertThat(first).hasSize(2);
    }

    @Test
    void withAnIntervalOfZeroEveryPollEnumeratesAndNoDeviceIsRefreshedOnItsOwn() {
        DeviceList<Device> everyPoll = newList(Duration.ZERO);
        Map<String, Long> first = epochsByName(everyPoll.poll());
        nowNanos += SECOND;

        Map<String, Long> second = epochsByName(everyPoll.poll());

        assertThat(enumerations).isEqualTo(2);
        assertThat(refreshed).isEmpty();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void withAnIntervalOfZeroAReplacedOrNewDeviceIsSeenOnTheNextPoll() {
        DeviceList<Device> everyPoll = newList(Duration.ZERO);
        Map<String, Long> first = epochsByName(everyPoll.poll());
        attached.put("a", "serial-of-a-replacement");
        attached.put("c", "serial-c");
        attached.remove("b");
        nowNanos += SECOND;

        Map<String, Long> second = epochsByName(everyPoll.poll());

        assertThat(second).containsOnlyKeys("a", "c");
        assertThat(second.values()).doesNotContainAnyElementsOf(first.values());
    }

    private DeviceList<Device> newList(Duration enumerationInterval) {
        return new DeviceList<>(
                () -> {
                    if (enumerationFails) {
                        throw new IllegalStateException("enumeration failed");
                    }
                    enumerations++;
                    List<Device> found = new ArrayList<>();
                    attached.forEach((name, identity) -> found.add(new Device(name, identity)));
                    return found;
                },
                device -> {
                    refreshed.add(device.name);
                    if (identityAfterRefresh.containsKey(device.name)) {
                        device.identity = identityAfterRefresh.get(device.name);
                    }
                    return !failsToRefresh.contains(device.name);
                },
                device -> List.of(device.name, device.identity),
                enumerationInterval,
                () -> nowNanos);
    }

    private static Map<String, Long> epochsByName(List<Held<Device>> held) {
        Map<String, Long> epochs = new HashMap<>();
        for (Held<Device> device : held) {
            assertThat(epochs.put(device.device().name, device.epoch()))
                    .as("device listed twice")
                    .isNull();
        }
        return epochs;
    }
}
