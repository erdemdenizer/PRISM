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

    // Bus device names hold colons on Linux (pci0000:00), which a Windows path refuses; these paths
    // keep the layout without them, and the Linux-only tests below use real names.
    @Test
    void aDeviceBelowABusDeviceIsPhysical() {
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/pci0000/0000.1f.6/net/enp0s31f6")))
                .contains(DeviceLayer.PHYSICAL);
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/LNXSYSTM/VMBUS/host0/block/sda")))
                .contains(DeviceLayer.PHYSICAL);
    }

    /** The head of a native multipath NVMe disk counts the drive's I/O; its paths have no node. */
    @Test
    void aMultipathNvmeHeadIsPhysicalAlthoughItLivesUnderVirtual() {
        assertThat(LinuxDeviceLayers.layer(
                        DEVICES, Path.of("/sys/devices/virtual/nvme-subsystem/nvme-subsys0/nvme0n1")))
                .contains(DeviceLayer.PHYSICAL);
    }

    @Test
    void aPathOutsideTheDeviceTreeSaysNothing() {
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/class/net/bonding_masters")))
                .isEmpty();
        // "virtual" only counts as the top directory of the device tree.
        assertThat(LinuxDeviceLayers.layer(DEVICES, Path.of("/sys/devices/pci0000/virtual/net/eth1")))
                .contains(DeviceLayer.PHYSICAL);
    }

    /** A small sysfs of links, as the kernel lays it out; links need a system that makes them freely. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void disksAreNamedByTheirDeviceNodeAndOnlyThoseWithANodeAreClassified(@TempDir Path sys) throws IOException {
        Path devices = sys.resolve("devices");
        block(sys, "sda", devices.resolve("pci0000:00/0000:00:04.0/host0/target0:0:0/0:0:0:0/block/sda"), "sda");
        block(sys, "loop0", devices.resolve("virtual/block/loop0"), "loop0");
        block(sys, "cciss!c0d0", devices.resolve("pci0000:00/0000:00:05.0/block/cciss!c0d0"), "cciss/c0d0");
        block(sys, "nvme0n1", devices.resolve("virtual/nvme-subsystem/nvme-subsys0/nvme0n1"), "nvme0n1");
        // A multipath path: hidden, without a device node, so no DEVNAME.
        block(sys, "nvme0c0n1", devices.resolve("pci0000:00/0000:00:06.0/nvme/nvme0/nvme0c0n1"), null);
        // An entry whose device has gone between the listing and the lookup.
        Files.createSymbolicLink(sys.resolve("class/block/gone0"), devices.resolve("virtual/block/gone0"));

        assertThat(new LinuxDeviceLayers(sys).diskLayers())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "/dev/sda", DeviceLayer.PHYSICAL,
                        "/dev/loop0", DeviceLayer.LOGICAL,
                        "/dev/cciss/c0d0", DeviceLayer.PHYSICAL,
                        "/dev/nvme0n1", DeviceLayer.PHYSICAL));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aSoftwareInterfaceWithAHardwarePortIsUnknownAndOneOverSoftwarePortsStaysLogical(@TempDir Path sys)
            throws IOException {
        Path devices = sys.resolve("devices");
        Path eth0 = net(sys, "eth0", devices.resolve("pci0000:00/0000:00:03.0/net/eth0"));
        net(sys, "eth1", devices.resolve("pci0000:00/0000:00:03.1/net/eth1"));
        Path bond0 = net(sys, "bond0", devices.resolve("virtual/net/bond0"));
        Path docker0 = net(sys, "docker0", devices.resolve("virtual/net/docker0"));
        Path veth = net(sys, "veth1a2b", devices.resolve("virtual/net/veth1a2b"));
        net(sys, "lo", devices.resolve("virtual/net/lo"));
        // Ports have a "master" link to the interface they are enslaved to (net/core/dev.c).
        Files.createSymbolicLink(eth0.resolve("master"), bond0);
        Files.createSymbolicLink(veth.resolve("master"), docker0);
        // A plain file in the class directory, as bonding_masters is.
        Files.writeString(sys.resolve("class/net/bonding_masters"), "bond0\n");

        assertThat(new LinuxDeviceLayers(sys).networkLayers())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "eth0", DeviceLayer.PHYSICAL,
                        "eth1", DeviceLayer.PHYSICAL,
                        "bond0", DeviceLayer.UNKNOWN,
                        "docker0", DeviceLayer.LOGICAL,
                        "veth1a2b", DeviceLayer.LOGICAL,
                        "lo", DeviceLayer.LOGICAL));
    }

    @Test
    void aClassDirectoryThatCannotBeReadIsAFailureNotAnEmptyAnswer(@TempDir Path sys) {
        LinuxDeviceLayers layers = new LinuxDeviceLayers(sys);

        assertThatThrownBy(layers::networkLayers).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(layers::diskLayers).isInstanceOf(UncheckedIOException.class);
    }

    private static Path net(Path sys, String name, Path target) throws IOException {
        Files.createDirectories(target);
        Path link = sys.resolve("class/net").resolve(name);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
        return target;
    }

    private static void block(Path sys, String entry, Path target, String devname) throws IOException {
        Files.createDirectories(target);
        Files.writeString(
                target.resolve("uevent"),
                "MAJOR=8\nMINOR=0\n" + (devname == null ? "" : "DEVNAME=" + devname + "\n") + "DEVTYPE=disk\n");
        Path link = sys.resolve("class/block").resolve(entry);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
    }
}
