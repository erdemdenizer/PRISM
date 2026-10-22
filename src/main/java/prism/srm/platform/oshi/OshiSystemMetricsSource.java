package prism.srm.platform.oshi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongSupplier;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.OSVersionInfo;
import oshi.util.Constants;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SwapUsage;
import prism.srm.domain.SystemActivity;
import prism.srm.platform.DiskLayerSource;
import prism.srm.platform.HostSystem;
import prism.srm.platform.SystemMetricsSource;
import prism.srm.platform.oshi.DeviceList.Held;

/**
 * System metrics read through OSHI, from the long-lived OSHI objects that {@link OshiHost} owns.
 *
 * <p>CPU time is OSHI's default on every system: time spent in each processor state. The Windows
 * option {@code oshi.os.windows.cpu.utility}, which would replace it with the frequency-adjusted
 * "processor utility" that Task Manager shows, is left off so that the metric means the same on
 * all systems.
 */
final class OshiSystemMetricsSource implements SystemMetricsSource {

    /*
     * Measured on Windows 11 with 3 disks, 3 volumes and 36 listed interfaces: enumerating takes
     * about 80 ms for disks, 27 ms for file stores and 22 ms for interfaces, while refreshing an
     * object already held takes 1 to 2 ms per disk and about 0.25 ms per interface. The price of
     * holding objects is that a device attached later appears only after this interval.
     */
    private static final Duration DEVICE_ENUMERATION_INTERVAL = Duration.ofSeconds(10);

    private final HardwareAbstractionLayer hardware;
    private final OperatingSystem os;
    private final HostSystem system;
    private final DiskLayerCache diskLayers;
    private final DeviceList<OSFileStore> fileStores;
    private final DeviceList<HWDiskStore> disks;
    private final DeviceList<NetworkIF> interfaces;

    // Fetched on first use, not in the constructor, so that a failure stays with its own metric
    // and is retried on the next reading.
    private CentralProcessor processor;
    private GlobalMemory memory;

    OshiSystemMetricsSource(
            HardwareAbstractionLayer hardware,
            OperatingSystem os,
            HostSystem system,
            DiskLayerSource diskLayerSource,
            LongSupplier nanoClock) {
        this.hardware = hardware;
        this.os = os;
        this.system = system;
        this.diskLayers = new DiskLayerCache(diskLayerSource);
        fileStores = new DeviceList<>(
                () -> os.getFileSystem().getFileStores(true),
                OSFileStore::updateAttributes,
                store -> Arrays.asList(store.getVolume(), store.getMount(), store.getUUID()),
                DEVICE_ENUMERATION_INTERVAL,
                nanoClock);
        disks = new DeviceList<>(
                hardware::getDiskStores,
                HWDiskStore::updateAttributes,
                disk -> Arrays.asList(disk.getName(), disk.getModel(), disk.getSerial(), disk.getSize()),
                diskEnumerationInterval(system),
                nanoClock);
        interfaces = new DeviceList<>(
                hardware::getNetworkIFs,
                NetworkIF::updateAttributes,
                // The alias and the type are read again from the system on every refresh, unlike
                // the rest; a change in them shows that the object now reads another interface.
                networkInterface -> Arrays.asList(
                        networkInterface.getName(),
                        networkInterface.getIndex(),
                        networkInterface.getMacaddr(),
                        networkInterface.getIfAlias(),
                        networkInterface.getIfType()),
                DEVICE_ENUMERATION_INTERVAL,
                nanoClock);
    }

    @Override
    public ProcessorInfo processorInfo() {
        CentralProcessor cpu = centralProcessor();
        return new ProcessorInfo(
                cpu.getLogicalProcessorCount(),
                positive(cpu.getPhysicalProcessorCount()),
                positive(cpu.getPhysicalPackageCount()),
                text(cpu.getProcessorIdentifier().getName()));
    }

    @Override
    public OperatingSystemInfo operatingSystem() {
        OSVersionInfo version = os.getVersionInfo();
        return new OperatingSystemInfo(os.getFamily(), text(version.getVersion()), text(version.getBuildNumber()));
    }

    @Override
    public CpuSample cpuTicks() {
        CentralProcessor cpu = centralProcessor();
        List<CpuTicks> perProcessor = new ArrayList<>();
        for (long[] ticks : cpu.getProcessorCpuLoadTicks()) {
            perProcessor.add(toCpuTicks(ticks));
        }
        return new CpuSample(toCpuTicks(cpu.getSystemCpuLoadTicks()), perProcessor);
    }

    @Override
    public MemorySample memory() {
        GlobalMemory physical = globalMemory();
        VirtualMemory virtual = physical.getVirtualMemory();
        long swapTotal = virtual.getSwapTotal();
        Optional<SwapUsage> swap =
                swapTotal > 0 ? Optional.of(new SwapUsage(swapTotal, virtual.getSwapUsed())) : Optional.empty();
        return new MemorySample(physical.getTotal(), physical.getAvailable(), swap);
    }

    @Override
    public List<FileSystemUsage> fileSystems() {
        List<FileSystemUsage> usage = new ArrayList<>();
        for (Held<OSFileStore> held : fileStores.poll()) {
            OSFileStore store = held.device();
            usage.add(new FileSystemUsage(
                    store.getName(), store.getMount(), store.getTotalSpace(), store.getUsableSpace()));
        }
        return usage;
    }

    @Override
    public List<DiskCounters> diskCounters() {
        List<Held<HWDiskStore>> held = disks.poll();
        Set<String> heldKeys = new HashSet<>();
        for (Held<HWDiskStore> disk : held) {
            heldKeys.add(disk.device().getName() + '#' + disk.epoch());
        }
        // Without disks there is nothing to classify, and no reason to ask the system.
        Map<String, DeviceLayer> layers = held.isEmpty() ? Map.of() : diskLayers.layersFor(heldKeys);
        List<DiskCounters> counters = new ArrayList<>();
        for (Held<HWDiskStore> disk : held) {
            String name = disk.device().getName();
            counters.add(new DiskCounters(
                    name,
                    layers.getOrDefault(name, DeviceLayer.UNKNOWN),
                    disk.epoch(),
                    disk.device().getReadBytes(),
                    disk.device().getWriteBytes()));
        }
        return counters;
    }

    @Override
    public List<NetworkCounters> networkCounters() {
        List<NetworkCounters> counters = new ArrayList<>();
        for (Held<NetworkIF> held : interfaces.poll()) {
            NetworkIF networkInterface = held.device();
            counters.add(new NetworkCounters(
                    networkInterface.getName(),
                    networkInterface.getDisplayName(),
                    networkLayer(system, networkInterface.isConnectorPresent()),
                    held.epoch(),
                    networkInterface.getBytesRecv(),
                    networkInterface.getBytesSent()));
        }
        return counters;
    }

    @Override
    public SystemActivity activity() {
        return new SystemActivity(Duration.ofSeconds(os.getSystemUptime()), os.getProcessCount());
    }

    private CentralProcessor centralProcessor() {
        if (processor == null) {
            processor = hardware.getProcessor();
        }
        return processor;
    }

    private GlobalMemory globalMemory() {
        if (memory == null) {
            memory = hardware.getMemory();
        }
        return memory;
    }

    /**
     * Splits OSHI's tick array, indexed by {@link TickType}, the way OSHI's own load calculation
     * does: every state counts toward the total, and idle is the idle and I/O wait states.
     */
    static CpuTicks toCpuTicks(long[] ticks) {
        long total = 0;
        for (long value : ticks) {
            total += value;
        }
        return new CpuTicks(ticks[TickType.IDLE.getIndex()] + ticks[TickType.IOWAIT.getIndex()], total);
    }

    /**
     * Derives an interface's layer from OSHI's connector flag, where that flag is a fact about
     * hardware.
     *
     * <p>On Windows the flag is {@code ConnectorPresent} of {@code MIB_IF_ROW2}. Documented: it
     * is "set if there is a physical network adapter", and the adapter's driver declares it.
     * https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_if_row2
     *
     * <p>Observed on the development machine, not documented: of 60 interface rows only the
     * Wi-Fi and the two Ethernet adapters have the flag; no filter driver, tunnel, WAN miniport or
     * virtual adapter has it, and it agrees with Windows' own {@code HardwareInterface} flag for
     * every adapter. A driver that declared it wrongly would put its traffic on the wrong side of
     * the total. Known case: a Bluetooth network adapter reports no connector, so traffic
     * tethered over Bluetooth is not in the total.
     *
     * <p>On Linux OSHI fills the flag from the link state ({@code carrier}), which says nothing
     * about hardware, and on macOS OSHI does not provide it. On those systems the layer is
     * unknown until a source that knows it is added.
     */
    static DeviceLayer networkLayer(HostSystem system, boolean connectorPresent) {
        if (system != HostSystem.WINDOWS) {
            return DeviceLayer.UNKNOWN;
        }
        return connectorPresent ? DeviceLayer.PHYSICAL : DeviceLayer.LOGICAL;
    }

    /**
     * Chooses how long held disk objects are refreshed before the disks are enumerated again.
     *
     * <p>On Linux, OSHI refreshes one disk by running the complete enumeration again and picking
     * that disk out of it, so refreshing every held disk would repeat the enumeration once per
     * disk. An interval of zero enumerates once per poll instead, which does that work once.
     * Whether this is cheap enough at the monitoring cadence has not been measured on Linux.
     * Everywhere else held objects are refreshed, which is measured on Windows and not measured
     * on macOS.
     */
    static Duration diskEnumerationInterval(HostSystem system) {
        return system == HostSystem.LINUX ? Duration.ZERO : DEVICE_ENUMERATION_INTERVAL;
    }

    /** A count that is not positive is one the system did not report. */
    static OptionalInt positive(int count) {
        return count > 0 ? OptionalInt.of(count) : OptionalInt.empty();
    }

    /** OSHI returns an empty string or the word "unknown" for a text it could not determine. */
    static Optional<String> text(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String stripped = value.strip();
        return stripped.isEmpty() || Constants.UNKNOWN.equals(stripped) ? Optional.empty() : Optional.of(stripped);
    }
}
