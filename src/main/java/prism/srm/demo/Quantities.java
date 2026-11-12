package prism.srm.demo;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and formatting of the quantities on the command line. Nothing here depends on the default
 * locale: numbers are read and written with a period as the decimal separator on every machine.
 */
final class Quantities {

    static final long MIB = 1L << 20;
    static final long GIB = 1L << 30;

    // The digit counts keep Long.parseLong and Integer.parseInt from overflowing; the unit
    // multiplication is checked separately.
    private static final Pattern MEMORY = Pattern.compile("(\\d{1,15})([A-Za-z]+)");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,15})([smSM])");
    private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(\\.\\d{1,3})?");
    private static final Pattern INTEGER = Pattern.compile("\\d{1,9}");

    private Quantities() {}

    /** A memory size such as {@code 256MiB} or {@code 1GiB}. {@code 0} is the only value without a unit. */
    static long parseMemorySize(String text) {
        String trimmed = text.strip();
        if (trimmed.equals("0")) {
            return 0;
        }
        Matcher matcher = MEMORY.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected <n>MiB or <n>GiB, for example 256MiB");
        }
        long unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "mib" -> MIB;
            case "gib" -> GIB;
            default -> throw new IllegalArgumentException("unknown unit " + matcher.group(2) + ": use MiB or GiB");
        };
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), unit);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("too large to be a memory size", e);
        }
    }

    /** A duration such as {@code 300s} or {@code 5m}. The unit is required. */
    static Duration parseDuration(String text) {
        Matcher matcher = DURATION.matcher(text.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected <n>s or <n>m, for example 300s or 5m");
        }
        long count = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> Duration.ofSeconds(count);
            default -> Duration.ofMinutes(count);
        };
    }

    /** A percentage such as {@code 25} or {@code 6.25}: digits, optionally a period and more digits. */
    static double parsePercent(String text) {
        String trimmed = text.strip();
        if (!PERCENT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("expected a number from 0 to 100, for example 25 or 6.25");
        }
        return Double.parseDouble(trimmed);
    }

    /** A small non-negative whole number, such as a thread count. */
    static int parseCount(String text) {
        String trimmed = text.strip();
        if (!INTEGER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("expected a whole number");
        }
        return Integer.parseInt(trimmed);
    }

    static String formatBytes(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.2fGiB", bytes / (double) GIB);
        }
        if (bytes % MIB == 0) {
            return (bytes / MIB) + "MiB";
        }
        return String.format(Locale.ROOT, "%.1fMiB", bytes / (double) MIB);
    }

    static String formatSeconds(Duration duration) {
        return duration.toSeconds() + "s";
    }

    static String formatPercent(double percent) {
        return String.format(Locale.ROOT, "%.1f", percent);
    }
}
