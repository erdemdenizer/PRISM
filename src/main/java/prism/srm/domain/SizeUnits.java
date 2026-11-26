package prism.srm.domain;

/**
 * The units in which byte sizes and rates are shown, chosen on the Settings page and kept between
 * runs (SET-1).
 */
public enum SizeUnits {
    /** Binary (IEC) units: 1 KiB = 1,024 B, then MiB, GiB and up. The default. */
    BINARY,
    /** Decimal (SI) units: 1 KB = 1,000 B, then MB, GB and up. */
    DECIMAL
}
