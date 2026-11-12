package prism.srm.domain;

/**
 * The appearance preference of the application: whether it follows the appearance of the
 * operating system or is always light or always dark.
 *
 * <p>A value type and nothing more. How a mode is applied to the window belongs to the
 * composition root; how it is persisted belongs to the application settings (UI-7, SET-1).
 */
public enum ThemeMode {
    /** Light or dark as the operating system prefers at the moment, following its changes. */
    SYSTEM,
    /** Always light, whatever the operating system prefers. */
    LIGHT,
    /** Always dark, whatever the operating system prefers. */
    DARK
}
