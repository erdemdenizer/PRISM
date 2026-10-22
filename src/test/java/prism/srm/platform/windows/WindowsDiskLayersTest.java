package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import prism.srm.domain.DeviceLayer;

/**
 * Covers the mapping, which needs no Windows call. The query itself is checked on Windows with
 * {@code HostMonitoringProbe}.
 */
class WindowsDiskLayersTest {

    /** Values of {@code MSFT_Disk.BusType} and {@code STORAGE_BUS_TYPE} as documented by Microsoft. */
    @Test
    void hardwareBusesArePhysical() {
        int[] hardwareBuses = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 17, 18, 19};

        for (int busType : hardwareBuses) {
            assertThat(WindowsDiskLayers.layerOfBusType(busType))
                    .as("bus type %d", busType)
                    .isEqualTo(DeviceLayer.PHYSICAL);
        }
    }

    @Test
    void aFileBackedVirtualDiskIsLogical() {
        assertThat(WindowsDiskLayers.layerOfBusType(15)).isEqualTo(DeviceLayer.LOGICAL);
    }

    @Test
    void whatCannotBeEstablishedIsUnknown() {
        assertThat(WindowsDiskLayers.layerOfBusType(0)).as("Unknown").isEqualTo(DeviceLayer.UNKNOWN);
        assertThat(WindowsDiskLayers.layerOfBusType(14))
                .as("Virtual, reserved for system use")
                .isEqualTo(DeviceLayer.UNKNOWN);
        assertThat(WindowsDiskLayers.layerOfBusType(16)).as("Storage Spaces").isEqualTo(DeviceLayer.UNKNOWN);
        assertThat(WindowsDiskLayers.layerOfBusType(20))
                .as("a value that is not documented for this property")
                .isEqualTo(DeviceLayer.UNKNOWN);
        assertThat(WindowsDiskLayers.layerOfBusType(-1)).isEqualTo(DeviceLayer.UNKNOWN);
    }

    @Test
    void theDeviceNameIsTheWin32PhysicalDriveNameInUpperCase() {
        assertThat(WindowsDiskLayers.deviceName(0)).isEqualTo("\\\\.\\PHYSICALDRIVE0");
        assertThat(WindowsDiskLayers.deviceName(12)).isEqualTo("\\\\.\\PHYSICALDRIVE12");
    }
}
