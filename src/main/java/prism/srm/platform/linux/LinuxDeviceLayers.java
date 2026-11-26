package prism.srm.platform.linux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.DeviceLayer;
import prism.srm.platform.DiskLayerSource;
import prism.srm.platform.NetworkLayerSource;

/**
 * Disk and network layers on Linux, from where sysfs places each device.
 *
 * <p>The kernel puts a class device that was registered without a parent device under {@code
 * /sys/devices/virtual}: "If we have no parent, we live in "virtual"" (drivers/base/core.c,
 * get_device_parent,
 * https://github.com/torvalds/linux/blob/master/drivers/base/core.c). Devices implemented in
 * software have no parent device: the loopback interface, bridges, bonds, VLANs, veth and tun
 * interfaces; loop, device-mapper, md and zram disks. Their traffic is counted again on the
 * device underneath, or never reaches hardware, so they are {@link DeviceLayer#LOGICAL}. A device
 * with a parent sits below that parent in {@code /sys/devices} (a PCI, USB, virtio or VMBus device)
 * and is {@link DeviceLayer#PHYSICAL}: for the system it runs on, that is the hardware.
 *
 * <p>The class directories are read as the kernel's sysfs rules ask: each entry of {@code
 * /sys/class/net} and {@code /sys/class/block} is a link that is resolved to its real path in
 * {@code /sys/devices}; the {@code device} link inside a class device is not used ("Accessing
 * /sys/class/net/eth0/device is a bug in the application",
 * https://www.kernel.org/doc/html/latest/admin-guide/sysfs-rules.html). {@code /sys/class/block}
 * lists disks and partitions together, which that document also says.
 *
 * <p>Names: a network entry has the interface name. A block entry has the kernel's device name with
 * every {@code /} replaced by {@code !} (lib/kobject.c, kobject_set_name_vargs,
 * https://github.com/torvalds/linux/blob/master/lib/kobject.c), and the device node is that name
 * under {@code /dev}, which is the name the metrics library gives a disk ({@code /dev/sda}).
 *
 * <p>Reads files only; no root is needed and nothing is written. An entry that vanishes between
 * the listing and its resolution is left out, and is {@link DeviceLayer#UNKNOWN} for the caller. A
 * class directory that cannot be listed throws, so that the caller asks again.
 */
public final class LinuxDeviceLayers implements DiskLayerSource, NetworkLayerSource {

    private final Path sys;

    /** Reads the running system's {@code /sys}. */
    public LinuxDeviceLayers() {
        this(Path.of("/sys"));
    }

    LinuxDeviceLayers(Path sys) {
        this.sys = sys;
    }

    @Override
    public Map<String, DeviceLayer> diskLayers() {
        Map<String, DeviceLayer> layers = new HashMap<>();
        for (Map.Entry<String, DeviceLayer> entry : classify("block").entrySet()) {
            layers.put("/dev/" + entry.getKey().replace('!', '/'), entry.getValue());
        }
        return layers;
    }

    @Override
    public Map<String, DeviceLayer> networkLayers() {
        return classify("net");
    }

    /** The layer of every entry of {@code /sys/class/<subsystem>}, by entry name. */
    private Map<String, DeviceLayer> classify(String subsystem) {
        Path devices = sys.resolve("devices");
        Map<String, DeviceLayer> layers = new HashMap<>();
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(sys.resolve("class").resolve(subsystem))) {
            for (Path entry : entries) {
                Path real;
                try {
                    real = entry.toRealPath();
                } catch (IOException gone) {
                    // Removed since the listing (an unplugged disk, a deleted interface): not classified.
                    continue;
                }
                layer(devices, real)
                        .ifPresent(layer -> layers.put(entry.getFileName().toString(), layer));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("/sys/class/" + subsystem + " cannot be read", e);
        }
        return layers;
    }

    /**
     * The layer a resolved class device has, or empty when the path is not in {@code /sys/devices}
     * at all and so says nothing about the device.
     *
     * @param devices the {@code /sys/devices} directory of the same sysfs
     * @param real the class device's path with every link resolved
     */
    static Optional<DeviceLayer> layer(Path devices, Path real) {
        if (!real.startsWith(devices)) {
            return Optional.empty();
        }
        return Optional.of(real.startsWith(devices.resolve("virtual")) ? DeviceLayer.LOGICAL : DeviceLayer.PHYSICAL);
    }
}
