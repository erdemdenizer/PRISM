package prism.srm.domain;

import java.util.Objects;

/**
 * The application-wide settings that persist between runs (SET-1): those of OOM Guard and the
 * appearance (UI-7).
 */
public record AppSettings(OomSettings oom, ThemeMode appearance) {

    public static final AppSettings DEFAULTS = new AppSettings(OomSettings.DEFAULTS, ThemeMode.SYSTEM);

    public AppSettings {
        Objects.requireNonNull(oom, "oom");
        Objects.requireNonNull(appearance, "appearance");
    }

    public AppSettings withOom(OomSettings value) {
        return new AppSettings(value, appearance);
    }

    public AppSettings withAppearance(ThemeMode value) {
        return new AppSettings(oom, value);
    }
}
