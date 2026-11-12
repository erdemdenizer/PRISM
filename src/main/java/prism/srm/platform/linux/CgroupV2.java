package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsers of the cgroup v2 and procfs texts milestone 12 reads (plan Rev 2.1 §2.2, S2). Pure functions of their input:
 * no file is opened here, and every malformed text answers empty rather than a guess.
 *
 * <ul>
 *   <li>{@code /proc/self/mountinfo}: proc_pid_mountinfo(5); the cgroup v2 mount is the line whose file system type,
 *       after the {@code " - "} separator, is {@code cgroup2}; the mount point is field 5 of at least six before it,
 *       with octal escapes.
 *   <li>{@code /proc/<pid>/cgroup}: cgroups(7); on the unified hierarchy the one line {@code 0::<path>}.
 *   <li>{@code cgroup.controllers}, {@code cgroup.subtree_control}: space-separated controller names.
 *   <li>{@code cpu.max}: {@code "$MAX $PERIOD"}, {@code $MAX} a number or {@code max} (kernel "Control Group v2").
 *   <li>{@code memory.max}, {@code memory.high}: a number of bytes or {@code max}.
 *   <li>{@code cgroup.procs}: one PID per line.
 * </ul>
 * https://docs.kernel.org/admin-guide/cgroup-v2.html https://man7.org/linux/man-pages/man7/cgroups.7.html
 * https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html
 */
final class CgroupV2 {

    /** The kernel accepts a cpu.max period from 1 ms to 1 s (systemd.resource-control(5): "[1ms, 1000ms]"). */
    static final long LEAST_PERIOD_USEC = 1_000;

    static final long MOST_PERIOD_USEC = 1_000_000;

    /** The largest PID the kernel can hand out on a 64-bit system (PID_MAX_LIMIT, 4 x 1024 x 1024). */
    static final int MOST_PID = 4_194_304;

    private static final Pattern CONTROLLER = Pattern.compile("[a-z_]{1,32}");

    private CgroupV2() {}

    /** A cpu.max value: no quota ({@code max}), or a quota per period, both in microseconds. */
    record CpuMax(OptionalLong quotaUsec, long periodUsec) {

        boolean unlimited() {
            return quotaUsec.isEmpty();
        }

        /** The text the kernel shows for it. */
        String text() {
            return (quotaUsec.isPresent() ? Long.toString(quotaUsec.getAsLong()) : "max") + " " + periodUsec;
        }
    }

    /** The mount point of the cgroup v2 file system, if exactly one is mounted. */
    static Optional<String> unifiedMount(String mountinfo) {
        List<String> found = new ArrayList<>();
        for (String line : mountinfo.split("\n", -1)) {
            int separator = line.indexOf(" - ");
            if (separator < 0) {
                continue;
            }
            String[] after = line.substring(separator + 3).trim().split(" +");
            String[] before = line.substring(0, separator).trim().split(" +");
            if (after.length < 1 || before.length < 6 || !after[0].equals("cgroup2")) {
                continue;
            }
            Optional<String> point = unescape(before[4]);
            if (point.isEmpty()) {
                return Optional.empty();
            }
            found.add(point.get());
        }
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** Whether a cgroup v1 hierarchy is mounted too (a hybrid system): any line of type {@code cgroup}. */
    static boolean v1Mounted(String mountinfo) {
        for (String line : mountinfo.split("\n", -1)) {
            int separator = line.indexOf(" - ");
            if (separator >= 0) {
                String[] after = line.substring(separator + 3).trim().split(" +");
                if (after.length >= 1 && after[0].equals("cgroup")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code /proc/<pid>/cgroup} has a unified-hierarchy line at all (cgroups(7): the {@code 0::} line). */
    static boolean hasUnifiedLine(String procCgroup) {
        return procCgroup.lines().anyMatch(line -> line.startsWith("0::"));
    }

    /** The unified-hierarchy path of a process, from the one {@code 0::} line; empty when absent, repeated or bad. */
    static Optional<String> unifiedPath(String procCgroup) {
        String found = null;
        for (String line : procCgroup.split("\n", -1)) {
            if (line.startsWith("0::")) {
                if (found != null) {
                    return Optional.empty();
                }
                found = line.substring(3);
            }
        }
        if (found == null || !validPath(found)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /** The controller names of a cgroup.controllers or cgroup.subtree_control file; empty when malformed. */
    static Optional<Set<String>> controllers(String text) {
        Set<String> names = new LinkedHashSet<>();
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return Optional.of(Set.of());
        }
        for (String name : trimmed.split("[ \t]+")) {
            if (!CONTROLLER.matcher(name).matches() || !names.add(name)) {
                return Optional.empty();
            }
        }
        return Optional.of(Set.copyOf(names));
    }

    /** A cpu.max value; empty when malformed, non-positive, or a period outside what the kernel accepts. */
    static Optional<CpuMax> cpuMax(String text) {
        String[] parts = text.strip().split(" ", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        OptionalLong period = positive(parts[1]);
        if (period.isEmpty() || period.getAsLong() < LEAST_PERIOD_USEC || period.getAsLong() > MOST_PERIOD_USEC) {
            return Optional.empty();
        }
        if (parts[0].equals("max")) {
            return Optional.of(new CpuMax(OptionalLong.empty(), period.getAsLong()));
        }
        OptionalLong quota = positive(parts[0]);
        if (quota.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CpuMax(quota, period.getAsLong()));
    }

    /**
     * A memory.max or memory.high value: empty inner value for {@code max} (no limit), the bytes otherwise; empty outer
     * value when malformed.
     */
    static Optional<OptionalLong> memoryLimit(String text) {
        String value = text.strip();
        if (value.equals("max")) {
            return Optional.of(OptionalLong.empty());
        }
        if (value.equals("0")) {
            return Optional.of(OptionalLong.of(0));
        }
        OptionalLong bytes = positive(value);
        return bytes.isPresent() ? Optional.of(bytes) : Optional.empty();
    }

    /** The PIDs of a cgroup.procs file, in file order; empty when any line is not a PID or one repeats. */
    static Optional<List<Integer>> procs(String text) {
        List<Integer> pids = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            OptionalLong pid = positive(line);
            if (pid.isEmpty() || pid.getAsLong() > MOST_PID || !seen.add((int) pid.getAsLong())) {
                return Optional.empty();
            }
            pids.add((int) pid.getAsLong());
        }
        return Optional.of(List.copyOf(pids));
    }

    /**
     * Whether a text is an absolute cgroup path Prism may join to a root: no empty, {@code .} or {@code ..} segment, no
     * whitespace or control character.
     */
    static boolean validPath(String path) {
        if (path.isEmpty() || path.charAt(0) != '/' || path.length() > 4096) {
            return false;
        }
        if (path.equals("/")) {
            return true;
        }
        if (path.endsWith("/")) {
            return false;
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c <= ' ' || c == 0x7f) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A decimal number without sign or leading zeros that fits a long and is greater than zero. */
    private static OptionalLong positive(String text) {
        if (text.isEmpty() || text.length() > 19 || text.charAt(0) == '0') {
            return OptionalLong.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return OptionalLong.empty();
            }
        }
        try {
            return OptionalLong.of(Long.parseLong(text));
        } catch (NumberFormatException tooLarge) {
            return OptionalLong.empty();
        }
    }

    /** Undoes the octal escapes of mountinfo ({@code \\040} for a space, and so on); empty when malformed. */
    private static Optional<String> unescape(String field) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 3 >= field.length()) {
                return Optional.empty();
            }
            String digits = field.substring(i + 1, i + 4);
            if (!digits.chars().allMatch(d -> d >= '0' && d <= '7')) {
                return Optional.empty();
            }
            out.append((char) Integer.parseInt(digits, 8));
            i += 3;
        }
        return Optional.of(out.toString());
    }
}
