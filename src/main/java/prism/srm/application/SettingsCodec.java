package prism.srm.application;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import prism.srm.domain.AppSettings;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.ThemeMode;

/**
 * The form of the settings file (ADR-0014): the header {@code prism-settings 1}, one {@code key=value}
 * line per setting with the value URL-encoded, and an end line with the number of settings and a
 * CRC-32 of everything before it, as the suspension journal has. Independent of the default locale.
 */
final class SettingsCodec {

    static final int VERSION = 1;
    static final String HEADER_PREFIX = "prism-settings ";

    static final String MONITORING = "oom.monitoring";
    static final String WARNING = "oom.warning.percent";
    static final String CRITICAL = "oom.critical.percent";
    static final String EMERGENCY = "oom.emergency.percent";
    static final String FLOOR = "oom.emergency.floor.bytes";
    static final String PRIORITY = "oom.auto.priority";
    static final String SUSPEND = "oom.auto.suspend";
    static final String TERMINATE = "oom.auto.terminate";
    static final String APPEARANCE = "appearance";

    static final List<String> KEYS =
            List.of(MONITORING, WARNING, CRITICAL, EMERGENCY, FLOOR, PRIORITY, SUSPEND, TERMINATE, APPEARANCE);

    /** What a file held. */
    enum Status {
        /** It read as a settings file of this version. */
        READ,
        /** It did not: a wrong header, end line, count or checksum, or a line that is no setting. */
        CORRUPT,
        /** It is a settings file of a later version, which this Prism does not read or overwrite. */
        NEWER
    }

    /**
     * What decoding found.
     *
     * @param settings what the file says, with each missing or unreadable value at its default
     * @param problems what was not taken as written, one sentence each, for the log
     */
    record Decoded(Status status, AppSettings settings, List<String> problems) {
        Decoded {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(settings, "settings");
            problems = List.copyOf(problems);
        }
    }

    private SettingsCodec() {}

    static byte[] encode(AppSettings settings) {
        OomThresholds thresholds = settings.oom().thresholds();
        OomSwitches switches = settings.oom().switches();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MONITORING, Boolean.toString(switches.monitoring()));
        values.put(WARNING, Integer.toString(thresholds.warningPercent()));
        values.put(CRITICAL, Integer.toString(thresholds.criticalPercent()));
        values.put(EMERGENCY, Integer.toString(thresholds.emergencyPercent()));
        values.put(FLOOR, Long.toString(thresholds.emergencyFloorBytes()));
        values.put(PRIORITY, Boolean.toString(switches.priorityReduction()));
        values.put(SUSPEND, Boolean.toString(switches.autoSuspend()));
        values.put(TERMINATE, Boolean.toString(switches.termination()));
        values.put(APPEARANCE, settings.appearance().name());
        StringBuilder text = new StringBuilder(HEADER_PREFIX).append(VERSION).append('\n');
        for (Map.Entry<String, String> entry : values.entrySet()) {
            text.append(entry.getKey())
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .append('\n');
        }
        CRC32 crc = new CRC32();
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        crc.update(body);
        text.append("end ")
                .append(values.size())
                .append(' ')
                .append(Long.toHexString(crc.getValue()))
                .append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Decoded decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int firstLine = text.indexOf('\n');
        String header = firstLine < 0 ? text : text.substring(0, firstLine);
        if (!header.startsWith(HEADER_PREFIX)) {
            return corrupt("the file does not begin as a settings file");
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(HEADER_PREFIX.length()));
        } catch (NumberFormatException e) {
            return corrupt("the version of the file cannot be read");
        }
        if (version > VERSION) {
            return new Decoded(
                    Status.NEWER,
                    AppSettings.DEFAULTS,
                    List.of("the file is of version " + version + ", newer than this Prism reads (" + VERSION + ")"));
        }
        if (version != VERSION) {
            return corrupt("the file is of unknown version " + version);
        }
        int end = text.lastIndexOf("\nend ");
        if (end < 0 || !text.endsWith("\n")) {
            return corrupt("the file has no end line");
        }
        String body = text.substring(0, end + 1);
        String[] tail = text.substring(end + 1).strip().split(" ");
        List<String> lines = body.lines().skip(1).toList();
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        if (tail.length != 3
                || !tail[1].equals(Integer.toString(lines.size()))
                || !tail[2].equals(Long.toHexString(crc.getValue()))) {
            return corrupt("the end line does not match what is written before it");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                return corrupt("a line is not a setting");
            }
            try {
                values.put(
                        line.substring(0, equals),
                        URLDecoder.decode(line.substring(equals + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return corrupt("a value cannot be decoded");
            }
        }
        List<String> problems = new ArrayList<>();
        AppSettings settings = settingsOf(values, problems);
        return new Decoded(Status.READ, settings, problems);
    }

    private static Decoded corrupt(String problem) {
        return new Decoded(Status.CORRUPT, AppSettings.DEFAULTS, List.of(problem));
    }

    /** The settings the values say; every value that is missing or cannot be read takes its default. */
    private static AppSettings settingsOf(Map<String, String> values, List<String> problems) {
        for (String key : values.keySet()) {
            if (!KEYS.contains(key)) {
                problems.add("the unknown setting " + key + " is ignored");
            }
        }
        OomSwitches defaults = OomSwitches.DEFAULTS;
        OomSwitches switches = new OomSwitches(
                flag(values, MONITORING, defaults.monitoring(), problems),
                flag(values, PRIORITY, defaults.priorityReduction(), problems),
                flag(values, SUSPEND, defaults.autoSuspend(), problems),
                // SET-2: only the literal "true" turns termination on; nothing is derived for it.
                "true".equals(values.get(TERMINATE)));
        if (values.containsKey(TERMINATE)
                && !"true".equals(values.get(TERMINATE))
                && !"false".equals(values.get(TERMINATE))) {
            problems.add(TERMINATE + " is not true or false: emergency termination stays off");
        }
        return new AppSettings(new OomSettings(thresholds(values, problems), switches), appearance(values, problems));
    }

    private static boolean flag(Map<String, String> values, String key, boolean byDefault, List<String> problems) {
        String value = values.get(key);
        if (value == null) {
            return byDefault;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                problems.add(key + " is not true or false: the default is used");
                yield byDefault;
            }
        };
    }

    private static OomThresholds thresholds(Map<String, String> values, List<String> problems) {
        OomThresholds defaults = OomThresholds.DEFAULTS;
        long floor = defaults.emergencyFloorBytes();
        String floorText = values.get(FLOOR);
        if (floorText != null) {
            try {
                long read = Long.parseLong(floorText);
                if (read >= 0) {
                    floor = read;
                } else {
                    problems.add(FLOOR + " is negative: the default is used");
                }
            } catch (NumberFormatException e) {
                problems.add(FLOOR + " is not a number: the default is used");
            }
        }
        int warning = defaults.warningPercent();
        int critical = defaults.criticalPercent();
        int emergency = defaults.emergencyPercent();
        if (values.containsKey(WARNING) || values.containsKey(CRITICAL) || values.containsKey(EMERGENCY)) {
            try {
                int w = percent(values, WARNING, warning);
                int c = percent(values, CRITICAL, critical);
                int e = percent(values, EMERGENCY, emergency);
                if (OomThresholds.valid(w, c, e, floor)) {
                    warning = w;
                    critical = c;
                    emergency = e;
                } else {
                    problems.add("the thresholds " + w + ", " + c + " and " + e
                            + " % are not in order between 1 and " + OomThresholds.MOST_PERCENT
                            + ": all three defaults are used");
                }
            } catch (NumberFormatException e) {
                problems.add("a threshold is not a whole number: all three defaults are used");
            }
        }
        return new OomThresholds(warning, critical, emergency, floor);
    }

    private static int percent(Map<String, String> values, String key, int byDefault) {
        String value = values.get(key);
        return value == null ? byDefault : Integer.parseInt(value);
    }

    private static ThemeMode appearance(Map<String, String> values, List<String> problems) {
        String value = values.get(APPEARANCE);
        if (value == null) {
            return ThemeMode.SYSTEM;
        }
        for (ThemeMode mode : ThemeMode.values()) {
            if (mode.name().equals(value.toUpperCase(Locale.ROOT))) {
                return mode;
            }
        }
        problems.add(APPEARANCE + " " + value + " is unknown: the system appearance is used");
        return ThemeMode.SYSTEM;
    }
}
