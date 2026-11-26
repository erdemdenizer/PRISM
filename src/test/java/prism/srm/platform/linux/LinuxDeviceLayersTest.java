package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.domain.DeviceLayer;

/** Where sysfs places a device decides its layer (drivers/base/core.c: no parent, "virtual"). */
class LinuxDeviceLayersTest {

    private static final Path DEVICES = Path.of("/sys/devices");

    @Test
    void aDeviceWithoutAParentDeviceIsLogical() {
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/virtual/net/lo")))
                .contains(DeviceLayer.LOGICAL);
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/virtual/block/loop0")))
                .contains(DeviceLayer.LOGICAL);
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/virtual/block/dm-0")))
                .contains(DeviceLayer.LOGICAL);
    }

    @Test
    void aDeviceBelowABusDeviceIsPhysical() {
        // Bus device names hold colons on Linux (pci0000:00), which a Windows path refuses; these
        // paths keep the layout without them, and the Linux-only tests below use real names.
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/pci0000/0000.1f.6/net/enp0s31f6")))
                .contains(DeviceLayer.PHYSICAL);
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/LNXSYSTM/VMBUS/host0/block/sda")))
                .contains(DeviceLayer.PHYSICAL);
    }

    @Test
    void aPathOutsideTheDeviceTreeSaysNothing() {
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/class/net/eth0")))
                .isEmpty();
        // "virtual" only counts as the top directory of the device tree.
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/pci0000/virtual/net/eth1")))
                .contains(DeviceLayer.PHYSICAL);
    }

    /** A small sysfs of links, as the kernel lays it out; links need a system that makes them freely. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theClassEntriesAreResolvedAndBlockNamesBecomeDeviceNodes(@TempDir Path sys) throws IOException {
        Path devices = sys.resolve("devices");
        link(sys, "class/net/lo", devices.resolve("virtual/net/lo"));
        link(sys, "class/net/eth0", devices.resolve("pci0000:00/0000:00:03.0/net/eth0"));
        link(sys, "class/block/sda", devices.resolve("pci0000:00/0000:00:04.0/host0/block/sda"));
        link(sys, "class/block/loop0", devices.resolve("virtual/block/loop0"));
        link(sys, "class/block/cciss!c0d0", devices.resolve("pci0000:00/0000:00:05.0/block/cciss!c0d0"));
        // An entry whose device has gone between the listing and the lookup.
        Files.createSymbolicLink(sys.resolve("class/net/gone0"), devices.resolve("virtual/net/gone0"));

        LinuxDeviceLayers layers = new LinuxDeviceLayers(sys);

        assertThat(layers.networkLayers())
                .containsExactlyInAnyOrderEntriesOf(Map.of("lo", DeviceLayer.LOGICAL, "eth0", DeviceLayer.PHYSICAL));
        assertThat(layers.diskLayers())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "/dev/sda", DeviceLayer.PHYSICAL,
                        "/dev/loop0", DeviceLayer.LOGICAL,
                        "/dev/cciss/c0d0", DeviceLayer.PHYSICAL));
    }

    @Test
    void aClassDirectoryThatCannotBeReadIsAFailureNotAnEmptyAnswer(@TempDir Path sys) {
        LinuxDeviceLayers layers = new LinuxDeviceLayers(sys);

        assertThatThrownBy(layers::networkLayers).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(layers::diskLayers).isInstanceOf(UncheckedIOException.class);
    }

    /** The running system: the loopback interface is software everywhere. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theRunningSystemHasALogicalLoopbackAndNamesDisksAsDeviceNodes() {
        LinuxDeviceLayers layers = new LinuxDeviceLayers();

        assertThat(layers.networkLayers()).containsEntry("lo", DeviceLayer.LOGICAL);
        assertThat(layers.diskLayers().keySet()).allMatch(name -> name.startsWith("/dev/"));
    }

    private static void link(Path sys, String entry, Path target) throws IOException {
        Files.createDirectories(target);
        Path link = sys.resolve(entry);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
    }
}
