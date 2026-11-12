package prism.srm.platform.linux;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ScopeProvenance;

/**
 * The name of the systemd scope Prism would ask for to limit one process (milestone 12, plan Rev 2.1 §2.1):
 * {@code prism-srm-limit-<pid>-<start time in milliseconds>.scope}, derived from the process identity alone, so that a
 * later session can find the scope by it. One identity has exactly one name and one name exactly one identity: no
 * leading zeros, no sign, nothing else around it.
 *
 * <p>A name is a lookup key, never proof of ownership: anyone may create a unit of that name (plan Rev 2.1 §4.1).
 */
final class ScopeNames {

    static final String PREFIX = "prism-srm-limit-";
    static final String SUFFIX = ".scope";

    private static final Pattern NAME =
            Pattern.compile("prism-srm-limit-([1-9][0-9]{0,6})-(0|[1-9][0-9]{0,17})\\.scope");

    private ScopeNames() {}

    /**
     * The name for an identity; empty for an identity no Linux process can have (PID 0 or above the kernel's
     * PID_MAX_LIMIT, a start time before the epoch, or one not a whole number of milliseconds).
     */
    static Optional<String> of(ProcessIdentity identity) {
        long pid = identity.pid();
        Instant start = identity.startTime();
        if (pid < 1 || pid > CgroupV2.MOST_PID || start.getEpochSecond() < 0 || start.getNano() % 1_000_000 != 0) {
            return Optional.empty();
        }
        long millis;
        try {
            millis = start.toEpochMilli();
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        String name = PREFIX + pid + "-" + millis + SUFFIX;
        return ScopeProvenance.validUnit(name) ? Optional.of(name) : Optional.empty();
    }

    /** The identity a name stands for; empty for anything that is not exactly such a name. */
    static Optional<ProcessIdentity> parse(String name) {
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long pid = Long.parseLong(matcher.group(1));
        if (pid > CgroupV2.MOST_PID) {
            return Optional.empty();
        }
        long millis;
        try {
            millis = Long.parseLong(matcher.group(2));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
        return Optional.of(new ProcessIdentity(pid, Instant.ofEpochMilli(millis)));
    }

    /**
     * The Prism scope a cgroup path is inside: the first segment that is a Prism scope name, so a cgroup below such a
     * scope counts too, whose limits still apply to it; empty when there is none.
     */
    static Optional<String> enclosingPrismScope(String cgroupPath) {
        for (String segment : cgroupPath.split("/", -1)) {
            if (parse(segment).isPresent()) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }
}
