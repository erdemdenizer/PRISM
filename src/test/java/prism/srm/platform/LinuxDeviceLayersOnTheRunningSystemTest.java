package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.NetworkCounters;

/**
 * On the running Linux system, through the platform the application uses: every disk and interface
 * the metrics library lists gets a layer, so the totals of the Overview can be known. Before the
 * Linux source of layers every one was UNKNOWN, and the Overview's Disk and Network said
 * "Unavailable" on every Linux machine.
 *
 * <p>A system with a bond or bridge over a hardware port would rightly make that interface UNKNOWN
 * (LinuxDeviceLayers); the hosted CI runners and the validation VM have none.
 */
@EnabledOnOs(OS.LINUX)
class LinuxDeviceLayersOnTheRunningSystemTest {

    @Test
    void everyListedDiskAndInterfaceHasAKnownLayer() {
        SystemMetricsSource source = HostPlatform.create().systemMetricsSource();

        List<DiskCounters> disks = source.diskCounters();
        List<NetworkCounters> interfaces = source.networkCounters();

        assertThat(disks).as("a running Linux system lists a disk").isNotEmpty();
        assertThat(disks)
                .allSatisfy(disk -> assertThat(disk.layer())
                        .as("layer of %s", disk.device())
                        .isNotEqualTo(DeviceLayer.UNKNOWN));
        assertThat(interfaces).as("a running Linux system lists an interface").isNotEmpty();
        assertThat(interfaces)
                .allSatisfy(networkInterface -> assertThat(networkInterface.layer())
                        .as("layer of %s", networkInterface.name())
                        .isNotEqualTo(DeviceLayer.UNKNOWN));
        assertThat(disks).anySatisfy(disk -> assertThat(disk.layer()).isEqualTo(DeviceLayer.PHYSICAL));
        assertThat(interfaces)
                .anySatisfy(
                        networkInterface -> assertThat(networkInterface.layer()).isEqualTo(DeviceLayer.PHYSICAL));
    }
}
