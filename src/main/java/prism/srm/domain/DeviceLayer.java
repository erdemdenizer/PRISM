package prism.srm.domain;

/**
 * Where a disk or a network interface sits in the system's device stack. The layer decides
 * whether its counters may be added to those of other devices: traffic that passes through a
 * logical device is counted again by the device underneath, so only physical devices form a set
 * in which every byte is counted once.
 */
public enum DeviceLayer {

    /** A hardware device at the bottom of the stack. */
    PHYSICAL,

    /**
     * A device implemented in software, on top of other devices or entirely inside the machine: a
     * filter driver, a virtual adapter, a tunnel, a virtual disk.
     */
    LOGICAL,

    /** The system does not say which of the two it is. */
    UNKNOWN
}
