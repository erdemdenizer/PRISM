package prism.srm.platform;

import java.util.Map;
import prism.srm.domain.DeviceLayer;

/**
 * Tells which network interfaces are hardware and which are logical layers in software, on
 * systems where the cross-platform metrics library gives no flag that means it. On Windows the
 * library's connector flag is used instead and this is not asked.
 */
public interface NetworkLayerSource {

    /**
     * Returns the layer of every interface the system can classify, keyed by the interface name
     * used in {@link prism.srm.domain.NetworkCounters}. An interface that is not in the map is
     * {@link DeviceLayer#UNKNOWN}.
     *
     * <p>Queries the operating system: never call it on the JavaFX Application Thread. A query
     * that fails throws a {@link RuntimeException}.
     */
    Map<String, DeviceLayer> networkLayers();
}
