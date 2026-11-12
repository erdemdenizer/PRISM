package prism.srm.platform;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.PriorityLevel;

/**
 * The nice value of each level as PRODUCT_SPEC CTL-6 gives it, written here and not taken from the
 * controllers under test, and a reading of a process's nice value with {@code ps} (see {@link
 * Ps}). For the Linux and macOS tests on the real system only: they start from the value the
 * process actually has, whatever the runner gave it, rather than assuming 0.
 */
public final class SpecNice {

    /** CTL-6, Linux and macOS column. */
    public static final Map<PriorityLevel, Integer> NICE = Map.of(
            PriorityLevel.LOW, 19,
            PriorityLevel.BELOW_NORMAL, 10,
            PriorityLevel.NORMAL, 0,
            PriorityLevel.ABOVE_NORMAL, -5,
            PriorityLevel.HIGH, -10);

    private SpecNice() {}

    /** The levels from the highest priority (lowest nice) to the lowest. */
    public static List<PriorityLevel> byNice() {
        return NICE.keySet().stream().sorted(Comparator.comparing(NICE::get)).toList();
    }

    /** The level of the given nice value, if it is one of the five. */
    public static Optional<PriorityLevel> levelOf(int nice) {
        return NICE.entrySet().stream()
                .filter(entry -> entry.getValue() == nice)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * The level a test lowers a process to from the nice value it starts with: the next one of a lower
     * priority, but never High, so that a level of a higher priority is always left to raise to. From
     * 19 there is none.
     */
    public static Optional<PriorityLevel> lowerThan(int nice) {
        int from = Math.max(nice, NICE.get(PriorityLevel.HIGH));
        return byNice().stream().filter(level -> NICE.get(level) > from).findFirst();
    }

    /** The next level of a higher priority (a lower nice value) than the one given, if there is one. */
    public static Optional<PriorityLevel> higherThan(int nice) {
        return byNice().reversed().stream()
                .filter(level -> NICE.get(level) < nice)
                .findFirst();
    }

    /**
     * The nice value {@code ps} reports for the process. procps on Linux prints "-" for a real-time
     * process, which no test here reads.
     */
    public static int byPs(long pid) throws IOException, InterruptedException {
        return Integer.parseInt(Ps.column(pid, "nice"));
    }
}
