package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A network interface and its current rates.
 *
 * @param layer only {@link DeviceLayer#PHYSICAL} interfaces count toward the total of {@link
 *     NetworkUsage}
 * @param rates absent when the interface was seen for the first time or its sample was skipped
 */
public record NetworkInterfaceUsage(String name, String displayName, DeviceLayer layer, Optional<NetworkRates> rates) {

    public NetworkInterfaceUsage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(rates, "rates");
    }
}
