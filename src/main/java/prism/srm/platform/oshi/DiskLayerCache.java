package prism.srm.platform.oshi;

import java.util.Map;
import java.util.Set;
import prism.srm.domain.DeviceLayer;
import prism.srm.platform.DiskLayerSource;

/**
 * Remembers the answer of a {@link DiskLayerSource} for as long as the set of disks stays the
 * same, so that the system is asked when a disk appears, goes or is replaced and not on every
 * poll. Used by one thread at a time.
 */
final class DiskLayerCache {

    private final DiskLayerSource source;
    private Map<String, DeviceLayer> layers = Map.of();
    private Set<String> classified;

    DiskLayerCache(DiskLayerSource source) {
        this.source = source;
    }

    /**
     * Returns the layers by device name.
     *
     * @param disks one key per disk that changes whenever the disk does, such as name and epoch
     * @throws RuntimeException if the source had to be asked and failed; nothing is remembered
     *     then, so the next call asks again
     */
    Map<String, DeviceLayer> layersFor(Set<String> disks) {
        if (!disks.equals(classified)) {
            layers = Map.copyOf(source.diskLayers());
            classified = Set.copyOf(disks);
        }
        return layers;
    }
}
