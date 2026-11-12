package prism.srm.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a Linux native-limit record says about the systemd scope that holds the process (milestone 12, plan Rev 2.1
 * §4.1): the ownership evidence a later session needs before it may ever change that scope. The unit name alone is
 * no proof; the invocation id, which systemd assigns afresh each time a unit starts, ties the record to one run of
 * that unit. A provenance without an invocation id or a cgroup path is incomplete, and no operation that changes a
 * scope may rely on it.
 *
 * <p>Only values a record could have been written with are accepted: a unit name of the characters systemd allows,
 * ending in {@code .scope}, at most 255 characters; an invocation id of 32 lowercase hexadecimal digits, not all zero;
 * an absolute cgroup path without {@code .} or {@code ..} segments, empty segments, whitespace or control characters,
 * at most 4096 characters. A value read from a file that breaks these rules makes the record unreadable.
 *
 * @param unit the scope's unit name
 * @param invocationId the scope's invocation id as systemd reported it; empty when it was never read
 * @param cgroupPath the scope's control group, relative to the cgroup v2 root; empty when it was never read
 */
public record ScopeProvenance(String unit, Optional<String> invocationId, Optional<String> cgroupPath) {

    /** systemd's unit name characters (systemd.unit(5)): ASCII letters, digits, ":", "-", "_", ".", "\". */
    private static final Pattern UNIT = Pattern.compile("[A-Za-z0-9:_.\\\\-]{1,249}\\.scope");

    private static final Pattern INVOCATION_ID = Pattern.compile("[0-9a-f]{32}");

    public static final int MOST_UNIT_LENGTH = 255;
    public static final int MOST_CGROUP_PATH_LENGTH = 4096;

    public ScopeProvenance {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(cgroupPath, "cgroupPath");
        if (!validUnit(unit)) {
            throw new IllegalArgumentException("Not a scope unit name: " + unit);
        }
        if (invocationId.isPresent() && !validInvocationId(invocationId.get())) {
            throw new IllegalArgumentException("Not an invocation id: " + invocationId.get());
        }
        if (cgroupPath.isPresent() && !validCgroupPath(cgroupPath.get())) {
            throw new IllegalArgumentException("Not a cgroup path: " + cgroupPath.get());
        }
    }

    /** Only the unit name is known. */
    public static ScopeProvenance unitOnly(String unit) {
        return new ScopeProvenance(unit, Optional.empty(), Optional.empty());
    }

    /** Whether everything a future ownership proof needs is recorded: the unit, its invocation id and its cgroup. */
    public boolean complete() {
        return invocationId.isPresent() && cgroupPath.isPresent();
    }

    public static boolean validUnit(String unit) {
        return unit.length() <= MOST_UNIT_LENGTH && UNIT.matcher(unit).matches();
    }

    public static boolean validInvocationId(String id) {
        return INVOCATION_ID.matcher(id).matches() && !id.chars().allMatch(c -> c == '0');
    }

    public static boolean validCgroupPath(String path) {
        if (path.isEmpty() || path.length() > MOST_CGROUP_PATH_LENGTH || path.charAt(0) != '/') {
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

    /** The provenance in words, for messages: the unit, and which evidence is missing. */
    public String describe() {
        if (complete()) {
            return unit;
        }
        StringBuilder missing = new StringBuilder();
        if (invocationId.isEmpty()) {
            missing.append("invocation id");
        }
        if (cgroupPath.isEmpty()) {
            missing.append(missing.isEmpty() ? "" : " and ").append("cgroup path");
        }
        return unit + " (" + missing.toString().toLowerCase(Locale.ROOT) + " not recorded)";
    }
}
