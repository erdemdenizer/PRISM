package prism.srm.platform.linux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.DeviceLayer;
import prism.srm.platform.DiskLayerSource;
import prism.srm.platform.NetworkLayerSource;

/**
 * Disk and network layers on Linux, from where sysfs places each device.
 *
 * <p><b>The rule.</b> The kernel puts a class device that was registered without a parent device
 * under {@code /sys/devices/virtual}: "If we have no parent, we live in "virtual""
 * (drivers/base/core.c, get_device_parent,
 * https://github.com/torvalds/linux/blob/master/drivers/base/core.c). The software devices whose
 * bytes are counted again on a device underneath have no parent: bridges, bonds, VLANs, macvlan,
 * veth, tun and WireGuard interfaces; loop, device-mapper (LVM, LUKS, dm-multipath), md, zram and
 * nbd disks. Those are {@link DeviceLayer#LOGICAL}. A device registered below a bus device (PCI,
 * USB, virtio, VMBus, SCSI) is {@link DeviceLayer#PHYSICAL}.
 *
 * <p><b>Exceptions, from the kernel source.</b>
 *
 * <ul>
 *   <li>A native multipath NVMe disk ({@code nvmeXnY}) is the "head" over its controller paths and
 *       is added below the NVMe subsystem device, which has no parent of its own, so it lands in
 *       {@code /sys/devices/virtual/nvme-subsystem/}. Its paths are hidden disks without a device
 *       node, so the head is the one disk that counts the drive's I/O (drivers/nvme/host/core.c
 *       nvme_init_subsystem, multipath.c nvme_mpath_set_live; block/genhd.c for hidden disks).
 *       It is {@link DeviceLayer#PHYSICAL}.
 *   <li>A software interface with a hardware port enslaved to it (a bond, bridge or team: the port
 *       has a {@code master} link to it, net/core/dev.c) is {@link DeviceLayer#UNKNOWN}. Such
 *       ports usually have no address, and the interface listing the metrics library uses shows
 *       only interfaces with one, so their traffic would be in no listed physical interface and
 *       the total would be too low without saying so. Unknown keeps the total from being shown.
 * </ul>
 *
 * <p><b>What the network rule relies on.</b> Some hardware interfaces stack on other hardware
 * interfaces and add their bytes up: net_failover, DSA user ports, and Hyper-V's netvsc with its
 * SR-IOV VF. Their lower interfaces get no address ({@code IFF_NO_ADDRCONF}) and so are not
 * listed, which keeps the total from counting the bytes twice. Interfaces without a MAC address
 * (ppp, raw-IP modems) are not listed by the library either, so their traffic is in no total.
 *
 * <p><b>Reading.</b> As the kernel's sysfs rules ask, each entry of {@code /sys/class/net} and
 * {@code /sys/class/block} is a link resolved to its real path in {@code /sys/devices}; the class
 * device's {@code device} link is not used ("Accessing /sys/class/net/eth0/device is a bug in the
 * application", https://www.kernel.org/doc/html/latest/admin-guide/sysfs-rules.html). A disk is
 * keyed by its device node, {@code /dev/} and the {@code DEVNAME} of its {@code uevent} file, the
 * name udev gives the node and the metrics library gives the disk ({@code /dev/sda}); a block
 * device without {@code DEVNAME} has no node and is left out. An interface is keyed by its name.
 *
 * <p>Reads files only; no root is needed and nothing is written. An entry that vanishes during
 * the reading is left out, and is {@link DeviceLayer#UNKNOWN} for the caller. A class directory that
 * cannot be listed throws, so that the caller asks again.
 */
public final class LinuxDeviceLayers implements DiskLayerSource, NetworkLayerSource {

    private static final String DEVNAME = "DEVNAME=";

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
        for (Map.Entry<Path, DeviceLayer> entry : classify("block").entrySet()) {
            deviceName(entry.getKey()).ifPresent(name -> layers.put("/dev/" + name, entry.getValue()));
        }
        return layers;
    }

    @Override
    public Map<String, DeviceLayer> networkLayers() {
        Map<Path, DeviceLayer> classified = classify("net");
        Map<String, DeviceLayer> layers = new HashMap<>();
        for (Map.Entry<Path, DeviceLayer> entry : classified.entrySet()) {
            layers.put(nameOf(entry.getKey()), entry.getValue());
        }
        // A hardware port enslaved to a software interface leaves that interface's total unknown.
        for (Map.Entry<Path, DeviceLayer> entry : classified.entrySet()) {
            if (entry.getValue() == DeviceLayer.PHYSICAL) {
                masterOf(entry.getKey()).ifPresent(master -> {
                    if (layers.get(master) == DeviceLayer.LOGICAL) {
                        layers.put(master, DeviceLayer.UNKNOWN);
                    }
                });
            }
        }
        return layers;
    }

    /** The layer of every entry of {@code /sys/class/<subsystem>}, by the entry's path. */
    private Map<Path, DeviceLayer> classify(String subsystem) {
        Path devices = sys.resolve("devices");
        Map<Path, DeviceLayer> layers = new LinkedHashMap<>();
        Path directory = sys.resolve("class").resolve(subsystem);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Path real;
                try {
                    real = entry.toRealPath();
                } catch (IOException gone) {
                    // Removed since the listing (an unplugged disk, a deleted interface): not classified.
                    continue;
                }
                layer(devices, real).ifPresent(layer -> layers.put(entry, layer));
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw new UncheckedIOException(directory + " cannot be read", asIo(e));
        }
        return layers;
    }

    private static IOException asIo(Exception e) {
        return e instanceof DirectoryIteratorException iteration ? iteration.getCause() : (IOException) e;
    }

    /**
     * The layer a resolved class device has, or empty when the path is not in {@code /sys/devices}
     * at all and so says nothing about the device (a plain file such as {@code bonding_masters}).
     *
     * @param devices the {@code /sys/devices} directory of the same sysfs
     * @param real the class device's path with every link resolved
     */
    static Optional<DeviceLayer> layer(Path devices, Path real) {
        if (!real.startsWith(devices)) {
            return Optional.empty();
        }
        Path virtual = devices.resolve("virtual");
        if (real.startsWith(virtual.resolve("nvme-subsystem"))) {
            return Optional.of(DeviceLayer.PHYSICAL);
        }
        return Optional.of(real.startsWith(virtual) ? DeviceLayer.LOGICAL : DeviceLayer.PHYSICAL);
    }

    /** The {@code DEVNAME} of a block device's {@code uevent}; empty when it has no device node. */
    private static Optional<String> deviceName(Path entry) {
        List<String> lines;
        try {
            lines = Files.readAllLines(entry.resolve("uevent"));
        } catch (IOException gone) {
            return Optional.empty();
        }
        return lines.stream()
                .filter(line -> line.startsWith(DEVNAME) && line.length() > DEVNAME.length())
                .map(line -> line.substring(DEVNAME.length()))
                .findFirst();
    }

    /** The name of the interface a port's {@code master} link points to, if it has one. */
    private static Optional<String> masterOf(Path entry) {
        Path link = entry.resolve("master");
        if (!Files.isSymbolicLink(link)) {
            return Optional.empty();
        }
        try {
            return Optional.of(nameOf(link.toRealPath()));
        } catch (IOException gone) {
            return Optional.empty();
        }
    }

    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
}
