package prism.srm.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The network interfaces and the total over the physical ones.
 *
 * @param interfaces every interface, physical or not, with its own rates
 * @param total download (receive) and upload (send) rate summed over the {@link
 *     DeviceLayer#PHYSICAL} interfaces, which is the traffic that crossed the machine's hardware
 *     adapters. Absent when the sample was skipped, when there is no physical interface, or when
 *     the layer of an interface is unknown, because the sum could then count traffic twice.
 */
public record NetworkUsage(List<NetworkInterfaceUsage> interfaces, Optional<NetworkRates> total) {

    public NetworkUsage {
        interfaces = List.copyOf(interfaces);
        Objects.requireNonNull(total, "total");
    }
}
