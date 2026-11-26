package prism.srm.domain;

import java.util.Objects;

/**
 * The application-wide settings that persist between runs (SET-1): those of OOM Guard, the
 * appearance (UI-7) and the units of sizes.
 */
public record AppSettings(OomSettings oom, ThemeMode appearance, SizeUnits sizeUnits) {

    public static final AppSettings DEFAULTS =
            new AppSettings(OomSettings.DEFAULTS, ThemeMode.SYSTEM, SizeUnits.BINARY);

    public AppSettings {
        Objects.requireNonNull(oom, "oom");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(sizeUnits, "sizeUnits");
    }

    public AppSettings withOom(OomSettings value) {
        return new AppSettings(value, appearance, sizeUnits);
    }

    public AppSettings withAppearance(ThemeMode value) {
        return new AppSettings(oom, value, sizeUnits);
    }

    public AppSettings withSizeUnits(SizeUnits value) {
        return new AppSettings(oom, appearance, value);
    }
}
