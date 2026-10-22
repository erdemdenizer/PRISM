package prism.srm.platform.oshi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Keeps the OSHI objects of one kind of device (disks, interfaces, file stores) between polls
 * and tells, for each device, whether its counters continue those of the previous poll.
 *
 * <p>Between enumerations a poll refreshes the held objects in place. The devices are enumerated
 * again when the enumeration interval has passed, so that a device attached later appears; on
 * the poll right after a held device was dropped, so that a device that is in fact still there
 * is not missing for longer than one poll; and on every poll for as long as an enumeration finds
 * nothing. An interval of zero enumerates on every poll
 * and never refreshes single objects, for systems where OSHI refreshes one object by repeating
 * the whole enumeration.
 *
 * <p>Every held device has an epoch. The epoch stays the same for as long as the list can vouch
 * that the object still describes the same device, and a device gets a new one whenever that
 * chain breaks:
 *
 * <ul>
 *   <li>it is found for the first time;
 *   <li>its refresh failed, so it was dropped, and an enumeration finds it again;
 *   <li>a refresh changed its identity, which means the object now reads another device (on
 *       Windows an interface index can be given to another adapter when one is disabled and
 *       enabled again);
 *   <li>an enumeration finds a device whose identity matches none of the held ones, for example
 *       another disk under the same name.
 * </ul>
 *
 * <p>Used by one thread at a time.
 */
final class DeviceList<T> {

    /** A device with current values and the epoch of its counters. */
    record Held<T>(T device, long epoch) {}

    private record Entry<T>(T device, Object identity, long epoch) {}

    private final Supplier<List<T>> enumerate;
    private final Predicate<T> refresh;
    private final Function<T, Object> identity;
    private final long enumerationIntervalNanos;
    private final LongSupplier nanoClock;

    private List<Entry<T>> entries = new ArrayList<>();
    private boolean enumerationDue = true;
    private long enumeratedAtNanos;
    private long nextEpoch = 1;

    /**
     * @param enumerate queries the system for the devices; the objects it returns are current
     * @param refresh updates one held object in place and returns false if the device is gone
     * @param identity the facts that make a device the same device; compared with {@code equals}
     * @param enumerationInterval how long held objects are refreshed before the devices are
     *     enumerated again; zero to enumerate on every poll
     */
    DeviceList(
            Supplier<List<T>> enumerate,
            Predicate<T> refresh,
            Function<T, Object> identity,
            Duration enumerationInterval,
            LongSupplier nanoClock) {
        this.enumerate = enumerate;
        this.refresh = refresh;
        this.identity = identity;
        this.enumerationIntervalNanos = enumerationInterval.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Returns the devices with current values. */
    List<Held<T>> poll() {
        long now = nanoClock.getAsLong();
        if (enumerationDue || now - enumeratedAtNanos >= enumerationIntervalNanos) {
            entries = enumerateKeepingEpochs();
            // With nothing held there is nothing to refresh, so only another enumeration can
            // find devices. OSHI also reports a failed disk query on Windows as an empty list.
            enumerationDue = entries.isEmpty();
            enumeratedAtNanos = now;
        } else {
            // A device that no longer refreshes, or that refreshed into something else, is
            // dropped from this poll. Whether it is gone or only needs to be found again is
            // settled by enumerating on the next poll, where it would get a new epoch.
            enumerationDue = entries.removeIf(
                    entry -> !refresh.test(entry.device()) || !entry.identity().equals(identity.apply(entry.device())));
        }
        List<Held<T>> held = new ArrayList<>(entries.size());
        for (Entry<T> entry : entries) {
            held.add(new Held<>(entry.device(), entry.epoch()));
        }
        return List.copyOf(held);
    }

    /** Builds the new list completely before it replaces the old one, so a failure changes nothing. */
    private List<Entry<T>> enumerateKeepingEpochs() {
        Map<Object, Long> heldEpochs = new HashMap<>();
        for (Entry<T> entry : entries) {
            heldEpochs.put(entry.identity(), entry.epoch());
        }
        List<Entry<T>> found = new ArrayList<>();
        long epoch = nextEpoch;
        for (T device : enumerate.get()) {
            Object deviceIdentity = identity.apply(device);
            Long heldEpoch = heldEpochs.remove(deviceIdentity);
            found.add(new Entry<>(device, deviceIdentity, heldEpoch != null ? heldEpoch : epoch++));
        }
        nextEpoch = epoch;
        return found;
    }
}
