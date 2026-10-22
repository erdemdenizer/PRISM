package prism.srm.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import prism.srm.domain.DeviceLayer;

/**
 * Turns successive readings of per-device byte counters (disks, network interfaces) into rates.
 * Each device has two counters, called inbound (read, received) and outbound (written, sent).
 *
 * <p>A device has rates when the previous reading holds the same device, that is the same name
 * and the same epoch, and neither counter went backwards. A reset, a wrap, a device that was
 * found again or one that was replaced under its name therefore has no rate for one reading.
 *
 * <p>The total is the sum over the {@link DeviceLayer#PHYSICAL} devices and nothing else, because
 * a logical device counts traffic that a device below it counts again. It exists only when all of
 * the following hold; otherwise it is skipped rather than reported too low or too high:
 *
 * <ul>
 *   <li>no device of the reading is {@link DeviceLayer#UNKNOWN}: such a device may or may not
 *       repeat the traffic of another, so no set can be shown to count every byte once;
 *   <li>there is at least one physical device: a source that sees none measured nothing, which
 *       is not the same as a rate of zero;
 *   <li>the physical devices are the same as in the previous reading and each of them has rates:
 *       otherwise part of the traffic of the interval is unknown.
 * </ul>
 *
 * <p>Logical devices never affect the total, whatever happens to them. Only the latest reading
 * is kept. Used by one thread at a time.
 */
final class DeviceRateTracker {

    /** Cumulative byte counters of one device, with what the platform says about the device. */
    record Counters(DeviceLayer layer, long epoch, long inbound, long outbound) {}

    /** Bytes per second. */
    record Rates(double inbound, double outbound) {}

    /**
     * @param perDevice the devices of the current reading that have rates
     * @param total the sum over the physical devices, when it is known
     */
    record Result(Map<String, Rates> perDevice, Optional<Rates> total) {}

    private Map<String, Counters> previous = Map.of();
    private boolean hasPrevious;
    private long previousNanos;

    /**
     * @param current the counters by device name
     * @param nowNanos monotonic time at which the counters were read
     */
    Result update(Map<String, Counters> current, long nowNanos) {
        Map<String, Rates> perDevice = new LinkedHashMap<>();
        long elapsedNanos = nowNanos - previousNanos;
        boolean comparable = hasPrevious && elapsedNanos > 0;
        boolean totalKnown = comparable;
        boolean anyPhysical = false;
        double inboundSum = 0;
        double outboundSum = 0;

        for (Map.Entry<String, Counters> device : current.entrySet()) {
            Counters now = device.getValue();
            Counters before = previous.get(device.getKey());
            Optional<Rates> rates = comparable ? ratesBetween(before, now, elapsedNanos) : Optional.empty();
            rates.ifPresent(value -> perDevice.put(device.getKey(), value));

            if (now.layer() == DeviceLayer.UNKNOWN) {
                totalKnown = false;
            } else if (now.layer() == DeviceLayer.PHYSICAL) {
                anyPhysical = true;
                if (rates.isPresent() && before.layer() == DeviceLayer.PHYSICAL) {
                    inboundSum += rates.get().inbound();
                    outboundSum += rates.get().outbound();
                } else {
                    totalKnown = false;
                }
            }
        }
        for (Map.Entry<String, Counters> device : previous.entrySet()) {
            // A physical device that is gone, or no longer physical, took traffic with it.
            Counters now = current.get(device.getKey());
            if (device.getValue().layer() == DeviceLayer.PHYSICAL
                    && (now == null || now.layer() != DeviceLayer.PHYSICAL)) {
                totalKnown = false;
            }
        }

        previous = Map.copyOf(current);
        hasPrevious = true;
        previousNanos = nowNanos;
        Optional<Rates> total =
                totalKnown && anyPhysical ? Optional.of(new Rates(inboundSum, outboundSum)) : Optional.empty();
        return new Result(perDevice, total);
    }

    private static Optional<Rates> ratesBetween(Counters before, Counters now, long elapsedNanos) {
        if (before == null || before.epoch() != now.epoch()) {
            return Optional.empty();
        }
        OptionalDouble inbound = RateCalculator.perSecond(before.inbound(), now.inbound(), elapsedNanos);
        OptionalDouble outbound = RateCalculator.perSecond(before.outbound(), now.outbound(), elapsedNanos);
        if (inbound.isEmpty() || outbound.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Rates(inbound.getAsDouble(), outbound.getAsDouble()));
    }
}
