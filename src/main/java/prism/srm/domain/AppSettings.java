package prism.srm.domain;

import java.util.Objects;

/**
 * The application-wide settings that persist between runs (SET-1): those of OOM Guard and the
 * appearance (UI-7).
 */
public record AppSettings(OomSettings oom, ThemeMode appearance) {

    public static final AppSettings DEFAULTS = new AppSettings(OomSettings.DEFAULTS, ThemeMode.SYSTEM);

    /**
     * UI lab: the settings of a first launch, when there is no settings file at all. Dark is the lab's first
     * appearance; a saved choice always wins, and a file that cannot be read still gives {@link #DEFAULTS}.
     */
    public static final AppSettings FIRST_RUN = DEFAULTS.withAppearance(ThemeMode.DARK);

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
