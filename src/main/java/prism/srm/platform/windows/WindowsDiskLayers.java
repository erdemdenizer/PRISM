package prism.srm.platform.windows;

import com.sun.jna.platform.win32.COM.WbemcliUtil.WmiQuery;
import com.sun.jna.platform.win32.COM.WbemcliUtil.WmiResult;
import java.util.HashMap;
import java.util.Map;
import oshi.util.platform.windows.WmiQueryHandler;
import oshi.util.platform.windows.WmiUtil;
import prism.srm.domain.DeviceLayer;
import prism.srm.platform.DiskLayerSource;

/**
 * Disk layers on Windows, from the bus type that the Windows Storage Management API reports for
 * each disk.
 *
 * <p>Windows presents a mounted virtual hard disk as a disk of its own, with its own transfer
 * counters, while the same transfers are also counted on the disk that holds the image file. The
 * bus type is the documented property that tells such a disk from hardware.
 *
 * <p>Class {@code MSFT_Disk}, namespace {@code Root\Microsoft\Windows\Storage}, properties {@code
 * Number} (UInt32, "the operating system's number for the disk") and {@code BusType} (UInt16):
 * https://learn.microsoft.com/en-us/windows-hardware/drivers/storage/msft-disk
 *
 * <p>The query is made through OSHI's WMI handler, which initializes COM for the calling thread
 * and releases what it acquires. It needs no elevation. The handler is a utility class of OSHI,
 * not part of the API OSHI marks as public, so an OSHI upgrade has to re-check this class.
 *
 * <p>The handler reports a failed or timed-out query as an empty result. A running Windows
 * system has at least one disk, so an empty result is treated as a failed query and thrown,
 * which makes the caller ask again instead of remembering "no disk is classified".
 */
public final class WindowsDiskLayers implements DiskLayerSource {

    private static final String NAMESPACE = "ROOT\\Microsoft\\Windows\\Storage";
    private static final String DISK_CLASS = "MSFT_Disk";

    /**
     * OSHI's own default is to wait without limit. The query runs on the sampling thread, so a
     * WMI service that hangs must not hold up monitoring for longer than this.
     */
    private static final int QUERY_TIMEOUT_MILLIS = 5_000;

    /** The constant names are the WMI property names; WMI compares them without regard to case. */
    enum DiskProperty {
        NUMBER,
        BUSTYPE
    }

    @Override
    public Map<String, DeviceLayer> diskLayers() {
        WmiQueryHandler wmi = WmiQueryHandler.createInstance();
        if (wmi == null) {
            // OSHI returns null when it cannot create its handler.
            throw new IllegalStateException("WMI is not available");
        }
        wmi.setWmiTimeout(QUERY_TIMEOUT_MILLIS);
        WmiResult<DiskProperty> disks = wmi.queryWMI(new WmiQuery<>(NAMESPACE, DISK_CLASS, DiskProperty.class));
        if (disks.getResultCount() == 0) {
            throw new IllegalStateException("The MSFT_Disk query failed, timed out or returned no disk");
        }
        Map<String, DeviceLayer> layers = new HashMap<>();
        for (int i = 0; i < disks.getResultCount(); i++) {
            if (disks.getValue(DiskProperty.NUMBER, i) == null) {
                // The helper below reads a missing number as 0, which would label disk 0.
                continue;
            }
            long number = WmiUtil.getUint32asLong(disks, DiskProperty.NUMBER, i);
            int busType = WmiUtil.getUint16(disks, DiskProperty.BUSTYPE, i);
            layers.put(deviceName(number), layerOfBusType(busType));
        }
        return layers;
    }

    /**
     * Returns the device name under which the disk counters report the disk with the given number.
     *
     * <p>Documented: physical disk N is opened as {@code \\.\PhysicalDriveN}.
     * https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file#win32-device-namespaces
     *
     * <p>Observed, not documented: {@code Win32_DiskDrive}, where OSHI takes the name from,
     * writes it in upper case, and OSHI itself relies on that spelling. If the spelling ever
     * differed, the disk would not be found in the map and would stay {@link
     * DeviceLayer#UNKNOWN}, which removes the total but never miscounts.
     */
    static String deviceName(long diskNumber) {
        return "\\\\.\\PHYSICALDRIVE" + diskNumber;
    }

    /**
     * Maps a {@code MSFT_Disk.BusType} value to a layer.
     *
     * <p>The MSFT_Disk page lists the values 0 to 17. They are the values of {@code
     * STORAGE_BUS_TYPE}, which continues with SCM (18) and UFS (19); the Storage module that
     * ships with Windows names 18 and 19 the same way for this property.
     * https://learn.microsoft.com/en-us/windows/win32/api/winioctl/ne-winioctl-storage_bus_type
     *
     * <ul>
     *   <li>A hardware bus is a physical disk: SCSI (1), ATAPI (2), ATA (3), IEEE 1394 (4), SSA
     *       (5), Fibre Channel (6), USB (7), RAID (8), iSCSI (9), SAS (10), SATA (11), SD (12),
     *       MMC (13), NVMe (17), SCM (18), UFS (19). An iSCSI disk is remote, so none of the
     *       local disks counts its transfers.
     *   <li>File Backed Virtual (15) is a disk image stored in a file on another disk: logical.
     *   <li>Everything else is unknown: Unknown (0), Virtual (14, "reserved for system use"),
     *       any other value, and Storage Spaces (16). For a storage space it has not been
     *       established whether the member disks are also listed, and the answer decides whether
     *       the space is a layer above them or the only place its transfers are counted.
     * </ul>
     */
    static DeviceLayer layerOfBusType(int busType) {
        return switch (busType) {
            case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 17, 18, 19 -> DeviceLayer.PHYSICAL;
            case 15 -> DeviceLayer.LOGICAL;
            default -> DeviceLayer.UNKNOWN;
        };
    }
}
