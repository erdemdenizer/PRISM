package prism.srm.platform.linux;

import java.util.Optional;

/**
 * The fields of {@code /proc/<pid>/stat} (or of a thread's {@code /proc/<pid>/task/<tid>/stat})
 * that process control reads. The second field, the name, stands in parentheses and may itself hold
 * spaces and parentheses, so the fields after it are counted from the last closing parenthesis.
 * https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html
 *
 * @param name field 2, without its parentheses
 * @param state field 3: R, S, D, Z, T (stopped by a signal), t (tracing stop), X, I and others
 * @param flags field 9, the kernel's PF_* flags
 * @param nice field 19, the nice value, -20 to 19
 * @param startTicks field 22, the start time in clock ticks after boot
 */
record ProcStat(String name, char state, long flags, int nice, long startTicks) {

    /** The kernel thread flag, PF_KTHREAD in include/linux/sched.h (the same value since Linux 2.6.27). */
    static final long PF_KTHREAD = 0x00200000L;

    /** Reads the fields from the contents of a stat file; empty if they do not have its form. */
    static Optional<ProcStat> parse(String contents) {
        int open = contents.indexOf('(');
        int close = contents.lastIndexOf(')');
        if (open < 0 || close < open) {
            return Optional.empty();
        }
        String[] rest = contents.substring(close + 1).trim().split("\\s+");
        // rest[0] is field 3; field n is rest[n - 3].
        if (rest.length < 20 || rest[0].length() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProcStat(
                    contents.substring(open + 1, close),
                    rest[0].charAt(0),
                    Long.parseUnsignedLong(rest[6]),
                    Integer.parseInt(rest[16]),
                    Long.parseUnsignedLong(rest[19])));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    /** The process has ended: a zombie that is not yet collected (Z), or dead (X). */
    boolean ended() {
        return state == 'Z' || state == 'X';
    }

    /** The process is stopped already, by a signal (T) or by a tracer (t). */
    boolean stopped() {
        return state == 'T' || state == 't';
    }

    boolean kernelThread() {
        return (flags & PF_KTHREAD) != 0;
    }
}
