package prism.srm.platform;

import java.util.Map;
import prism.srm.domain.DeviceLayer;

/**
 * Tells which disks are hardware and which are logical layers on top of other disks. This is
 * knowledge the cross-platform metrics library does not provide, so each operating system
 * supplies it in its own way.
 */
public interface DiskLayerSource {

    /**
     * Returns the layer of every disk the system can classify, keyed by the device name used in
     * {@link prism.srm.domain.DiskCounters}. A disk that is not in the map is {@link
     * DeviceLayer#UNKNOWN}.
     *
     * <p>Queries the operating system: never call it on the JavaFX Application Thread. A query
     * that fails throws a {@link RuntimeException}.
     */
    Map<String, DeviceLayer> diskLayers();
}
