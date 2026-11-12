package prism.srm.ui.format;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomState;
import prism.srm.domain.OomView;

/**
 * The words in which OOM Guard's state is said wherever it is shown: on its page, on the Overview and
 * in the status bar (UI-1, UI-5, plan O11). One place, so the three never disagree. Every percentage
 * is whole, in the notation of the formatter's locale.
 */
public final class OomWords {

    /** The tone of a sentence about memory pressure: only a real condition takes a color. */
    public enum Tone {
        NONE,
        WARNING,
        ERROR
    }

    /** A sentence and its tone. */
    public record Line(String text, Tone tone) {
        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(tone, "tone");
        }
    }

    private final ValueFormatter formatter;

    public OomWords(ValueFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    /** "Normal", "Warning", "Critical", "Emergency", or "Off" while monitoring is off. */
    public static String state(OomView view) {
        return view.monitoring() ? name(view.state()) : "Off";
    }

    public static String name(OomState state) {
        return switch (state) {
            case NORMAL -> "Normal";
            case WARNING -> "Warning";
            case CRITICAL -> "Critical";
            case EMERGENCY -> "Emergency";
        };
    }

    /** The tone of a state: WARNING and CRITICAL warn, EMERGENCY is an error; off or NORMAL none. */
    public static Tone tone(OomView view) {
        if (!view.monitoring()) {
            return Tone.NONE;
        }
        return switch (view.state()) {
            case NORMAL -> Tone.NONE;
            case WARNING, CRITICAL -> Tone.WARNING;
            case EMERGENCY -> Tone.ERROR;
        };
    }

    /** "4.1 GiB, 13 %"; empty when there is no reading. */
    public Optional<String> available(OomView view) {
        return view.reading().map(this::available);
    }

    public String available(OomReading reading) {
        return formatter.bytes(reading.availableBytes()).text() + ", " + percent(reading);
    }

    private String percent(OomReading reading) {
        return formatter.wholePercent(reading.availablePercent()).text();
    }

    /**
     * The fact on the Overview: "Normal", "Warning, 13 % available", "Warning, reading unavailable",
     * "Off", or "Not monitoring" once system monitoring has ended.
     */
    public String overview(OomView view) {
        if (view.monitorEnded()) {
            return "Not monitoring";
        }
        if (!view.monitoring() || view.state() == OomState.NORMAL) {
            return state(view);
        }
        return state(view)
                + view.reading()
                        .map(reading -> ", " + percent(reading) + " available")
                        .orElse(", reading unavailable");
    }

    /**
     * The sentence of the status bar: only from WARNING up and only while OOM Guard monitors; empty
     * otherwise, so no indicator stands there for nothing (plan O11).
     */
    public Optional<Line> pressure(OomView view) {
        if (!view.monitoring() || view.monitorEnded() || view.state() == OomState.NORMAL) {
            return Optional.empty();
        }
        String level = name(view.state()).toLowerCase(java.util.Locale.ROOT);
        String text = view.reading()
                .map(reading -> "Memory pressure " + level + ": " + percent(reading) + " available")
                .orElse("Memory pressure " + level + ", reading unavailable");
        return Optional.of(new Line(text, view.state() == OomState.EMERGENCY ? Tone.ERROR : Tone.WARNING));
    }
}
