package prism.srm.platform.oshi;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import prism.srm.domain.DeviceLayer;

/**
 * Remembers the answer of a source of device layers (a {@link prism.srm.platform.DiskLayerSource}
 * or a {@link prism.srm.platform.NetworkLayerSource}) for as long as the set of devices stays the
 * same, so that the system is asked when a device appears, goes or is replaced and not on every
 * poll. Used by one thread at a time.
 */
final class DeviceLayerCache {

    private final Supplier<Map<String, DeviceLayer>> source;
    private Map<String, DeviceLayer> layers = Map.of();
    private Set<String> classified;

    DeviceLayerCache(Supplier<Map<String, DeviceLayer>> source) {
        this.source = source;
    }

    /**
     * Returns the layers by device name.
     *
     * @param devices one key per device that changes whenever the device does, such as name and
     *     epoch
     * @throws RuntimeException if the source had to be asked and failed; nothing is remembered
     *     then, so the next call asks again
     */
    Map<String, DeviceLayer> layersFor(Set<String> devices) {
        if (!devices.equals(classified)) {
            layers = Map.copyOf(source.get());
            classified = Set.copyOf(devices);
        }
        return layers;
    }
}
