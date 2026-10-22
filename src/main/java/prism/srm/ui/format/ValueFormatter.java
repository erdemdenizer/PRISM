package prism.srm.ui.format;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns measured values into the text the views show.
 *
 * <p>Numbers follow the locale given at construction (decimal separator, grouping); nothing here
 * reads the default locale. Unit symbols are the same in every locale. Sizes use binary units
 * (1 KiB = 1024 B) with one decimal, and the unit is chosen after rounding, so a value is never
 * shown as "1024.0 KiB".
 */
public final class ValueFormatter {

    private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
    private static final double KIBI = 1024;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

    private final Locale locale;

    /** @param locale the locale whose number notation is used */
    public ValueFormatter(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /** Formats a percentage, 0 to 100, with one decimal: "12.4 %". */
    public Quantity percent(double percent) {
        return new Quantity(oneDecimal(percent), "%");
    }

    /** Formats a percentage, 0 to 100, without decimals, for where room is scarce: "12 %". */
    public Quantity wholePercent(double percent) {
        return new Quantity(String.format(locale, "%d", Math.round(percent)), "%");
    }

    /** Formats a size in bytes: "512 B", "11.2 GiB". */
    public Quantity bytes(long bytes) {
        return scaled(bytes, "");
    }

    /** Formats a rate in bytes per second: "0 B/s", "1.2 MiB/s". */
    public Quantity bytesPerSecond(double bytesPerSecond) {
        return scaled(bytesPerSecond, "/s");
    }

    /** Formats a whole number with the grouping of the locale: "1,234". */
    public String count(long count) {
        return String.format(locale, "%,d", count);
    }

    /**
     * Formats a time span as days and a clock: "3 d 04:12:55", or "04:12:55" below one day. The
     * same in every locale.
     */
    public String uptime(Duration uptime) {
        long days = uptime.toDays();
        String clock = String.format(
                Locale.ROOT, "%02d:%02d:%02d", uptime.toHoursPart(), uptime.toMinutesPart(), uptime.toSecondsPart());
        return days == 0 ? clock : String.format(Locale.ROOT, "%d d %s", days, clock);
    }

    /**
     * Formats a moment as the date and the time of day it was in a time zone:
     * "2026-10-06 14:03:22". The same in every locale.
     */
    public String dateTime(Instant moment, ZoneId zone) {
        return DATE_TIME.format(moment.atZone(zone));
    }

    private Quantity scaled(double value, String suffix) {
        int unit = 0;
        double scaled = value;
        // Compared after rounding to what is shown: 1023.96 KiB is 1.0 MiB, and 1023.6 B is 1.0 KiB.
        while (unit < BYTE_UNITS.length - 1 && asShown(scaled, unit) >= KIBI) {
            scaled /= KIBI;
            unit++;
        }
        String number = unit == 0 ? String.format(locale, "%d", Math.round(scaled)) : oneDecimal(scaled);
        return new Quantity(number, BYTE_UNITS[unit] + suffix);
    }

    /** Bytes are shown whole, every larger unit with one decimal. */
    private static double asShown(double value, int unit) {
        return unit == 0 ? Math.round(value) : Math.round(value * 10) / 10.0;
    }

    private String oneDecimal(double value) {
        return String.format(locale, "%.1f", value);
    }
}
