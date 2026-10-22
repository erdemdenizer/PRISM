package prism.srm.platform;

import java.util.Locale;

/**
 * The operating system family Prism is running on. Used inside the platform packages to choose
 * an implementation; code above them asks for capabilities and never sees this type.
 */
public enum HostSystem {
    WINDOWS,
    LINUX,
    MACOS,
    OTHER;

    /** Returns the family of the running system, from the {@code os.name} system property. */
    static HostSystem current() {
        return fromOsName(System.getProperty("os.name", ""));
    }

    static HostSystem fromOsName(String osName) {
        // Locale.ROOT: under a Turkish default locale "WINDOWS" would lower-case to "wındows".
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("linux")) {
            return LINUX;
        }
        if (name.startsWith("mac")) {
            return MACOS;
        }
        return OTHER;
    }
}
