package prism.srm.ui.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OomSignalStatus;
import prism.srm.ui.format.CapabilityWords;

/**
 * What the Settings page says about this system (UI-7, CAP-1, CAP-5): one row per function, from the
 * capabilities the application reports. It knows no operating system and adds no claim of its own.
 * Plain data, on the JavaFX Application Thread.
 */
final class SettingsViewModel {

    /**
     * One function of the summary.
     *
     * @param function what it is about
     * @param level the level, in words
     * @param detail the mechanism behind it, or why it is not offered
     * @param available false for a function this system does not offer
     * @param known false while what the function is on this system is still being read
     */
    record Row(String function, String level, String detail, boolean available, boolean known) {

        /** A row of a function whose capability has been reported. */
        Row(String function, String level, String detail, boolean available) {
            this(function, level, detail, available, true);
        }

        /** A row in its place while its capability is still being read: it claims nothing. */
        static Row reading(String function) {
            return new Row(function, READING, "", false, false);
        }
    }

    /** The level of a row that is still being read. */
    static final String READING = "Reading…";

    private static final List<String> ACTIONS =
            List.of("End task", "Force kill", "Suspend", "Resume", "Change priority");
    private static final List<String> LIMITS = List.of("CPU limit", "Memory limit");
    private static final String OOM_READINGS = "OOM Guard readings";

    private static final String VALIDATED = "Validated";
    private static final String NOT_VALIDATED = "Not validated";

    private static final String NO_REASON = "Not offered on this system; the log says why.";

    private ControlProfile profile;
    private LimitsSnapshot limits;
    private OomSignalStatus signal;
    private String startupFailure;

    void showProfile(ControlProfile value) {
        profile = value;
    }

    void showLimits(LimitsSnapshot value) {
        limits = value;
    }

    /** @return whether this changes the summary; OOM Guard reports about once a second */
    boolean showSignal(OomSignalStatus value) {
        boolean changed = signal != value;
        signal = value;
        return changed;
    }

    /** Monitoring did not start, so what it would have reported never comes. */
    void showStartupFailure(String logFolder) {
        startupFailure = "Prism could not read what this system offers, because monitoring did not start. The log file"
                + " in " + logFolder + " says why.";
    }

    /**
     * A sentence for what is not known: never to come, because monitoring did not start, or still being
     * read while no source has reported. Once one has, the rows still being read say so in their
     * place (LS-07), and the sentence goes.
     */
    Optional<String> status() {
        if (profile != null && limits != null && signal != null) {
            return Optional.empty();
        }
        if (startupFailure != null) {
            return Optional.of(startupFailure);
        }
        if (profile == null && limits == null && signal == null) {
            return Optional.of("Reading what this system offers.");
        }
        return Optional.empty();
    }

    /**
     * Every row of the summary in its fixed order: those known, and in their place those still being
     * read, so the summary does not move as its sources report. After a failed start only the known
     * rows: the others never come.
     */
    List<Row> summary() {
        List<Row> rows = new ArrayList<>();
        boolean waiting = startupFailure == null;
        if (profile != null) {
            rows.addAll(actionRows());
        } else if (waiting) {
            ACTIONS.forEach(function -> rows.add(Row.reading(function)));
        }
        if (limits != null) {
            rows.add(limit(LIMITS.get(0), limits.cpu()));
            rows.add(limit(LIMITS.get(1), limits.memory()));
        } else if (waiting) {
            LIMITS.forEach(function -> rows.add(Row.reading(function)));
        }
        if (signal != null) {
            rows.add(oomReadings(signal));
        } else if (waiting) {
            rows.add(Row.reading(OOM_READINGS));
        }
        return List.copyOf(rows);
    }

    /** Every function the summary can name, for the width of its column. */
    static List<String> functions() {
        List<String> all = new ArrayList<>(ACTIONS);
        all.addAll(LIMITS);
        all.add(OOM_READINGS);
        return List.copyOf(all);
    }

    /** Every level the summary can show, for the width of its column. */
    static List<String> levels() {
        List<String> all = new ArrayList<>();
        for (prism.srm.domain.CapabilityLevel level : prism.srm.domain.CapabilityLevel.values()) {
            all.add(CapabilityWords.level(level));
        }
        all.add(VALIDATED);
        all.add(NOT_VALIDATED);
        all.add(READING);
        return List.copyOf(all);
    }

    /** The rows known so far, in a fixed order. */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (profile != null) {
            rows.addAll(actionRows());
        }
        if (limits != null) {
            rows.add(limit(LIMITS.get(0), limits.cpu()));
            rows.add(limit(LIMITS.get(1), limits.memory()));
        }
        if (signal != null) {
            rows.add(oomReadings(signal));
        }
        return List.copyOf(rows);
    }

    private List<Row> actionRows() {
        return List.of(
                action(ACTIONS.get(0), ControlAction.END_TASK),
                action(ACTIONS.get(1), ControlAction.FORCE_KILL),
                action(ACTIONS.get(2), ControlAction.SUSPEND),
                action(ACTIONS.get(3), ControlAction.RESUME),
                action(ACTIONS.get(4), ControlAction.CHANGE_PRIORITY));
    }

    private Row action(String function, ControlAction action) {
        Capability capability = profile.capabilities().getOrDefault(action, Capability.UNAVAILABLE);
        return new Row(
                function,
                CapabilityWords.level(capability.level()),
                capability.mechanism().map(CapabilityWords::mechanism).orElse(NO_REASON),
                capability.available());
    }

    private static Row limit(String function, LimitCapability capability) {
        String detail = capability
                .mechanism()
                .map(CapabilityWords::mechanism)
                .or(() -> capability.reason().map(SettingsViewModel::sentence))
                .orElse(NO_REASON);
        return new Row(function, CapabilityWords.level(capability.level()), detail, capability.available());
    }

    private static Row oomReadings(OomSignalStatus status) {
        return switch (status) {
            case VALIDATED ->
                new Row(
                        OOM_READINGS,
                        VALIDATED,
                        "OOM Guard may act on the memory readings of this system, as far as its switches allow.",
                        true);
            case UNVALIDATED ->
                new Row(
                        OOM_READINGS,
                        NOT_VALIDATED,
                        "OOM Guard warns and logs here and takes no automatic action.",
                        false);
        };
    }

    /** A reason is written to follow a colon elsewhere; here it stands alone. */
    private static String sentence(String reason) {
        if (reason.isEmpty()) {
            return NO_REASON;
        }
        String capitalized = reason.substring(0, 1).toUpperCase(Locale.ROOT) + reason.substring(1);
        return capitalized.endsWith(".") ? capitalized : capitalized + ".";
    }
}
