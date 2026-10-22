package prism.srm.domain;

import java.util.List;
import java.util.OptionalDouble;

/**
 * CPU utilization between two consecutive readings.
 *
 * @param totalPercent utilization of the whole machine, 0 to 100
 * @param perProcessorPercent utilization of each logical processor, 0 to 100, in the system's
 *     processor order; an entry is empty when that processor's sample was skipped
 */
public record CpuUsage(double totalPercent, List<OptionalDouble> perProcessorPercent) {

    public CpuUsage {
        requirePercent(totalPercent);
        perProcessorPercent = List.copyOf(perProcessorPercent);
        for (OptionalDouble percent : perProcessorPercent) {
            if (percent.isPresent()) {
                requirePercent(percent.getAsDouble());
            }
        }
    }

    private static void requirePercent(double percent) {
        // Written so that NaN is rejected too.
        if (!(percent >= 0 && percent <= 100)) {
            throw new IllegalArgumentException("Expected a percentage from 0 to 100: " + percent);
        }
    }
}
