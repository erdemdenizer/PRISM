package prism.srm.platform.macos;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.mac.SystemB;
import com.sun.jna.platform.unix.LibCAPI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link MacosNative} on the running system, through JNA. Each method makes the call its name
 * says; the errno of a failed call is read at once with {@link Native#getLastError()}, which JNA
 * keeps for the calling thread right after each call.
 *
 * <ul>
 *   <li>sysctl with {@code CTL_KERN} 1, {@code KERN_PROC} 14, {@code KERN_PROC_PID} 1
 *       (bsd/sys/sysctl.h), through jna-platform's {@code SystemB.sysctl}; the documented way to
 *       read one process's entry, which the JDK also uses for its start time:
 *       https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/sysctl.3.html
 *   <li>kill: https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/kill.2.html
 *   <li>setpriority, getpriority, with {@code PRIO_PROCESS} 0 (bsd/sys/resource.h); getpriority
 *       may return -1 as a value, so errno is cleared before it and read after it:
 *       https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/setpriority.2.html
 * </ul>
 */
final class JnaMacos implements MacosNative {

    private static final int CTL_KERN = 1;
    private static final int KERN_PROC = 14;
    private static final int KERN_PROC_PID = 1;
    private static final int PRIO_PROCESS = 0;

    /**
     * The bytes of the fields read: 44, padded to the alignment of the structure, 48. A constant,
     * so that loading this class touches nothing native; {@code KinfoProcTest} ties it to the
     * layout JNA computes.
     */
    static final int HEAD_SIZE = 48;

    /** The C library calls jna-platform does not map; loaded on first use. */
    interface Libc extends Library {

        Libc INSTANCE = Native.load("c", Libc.class);

        int kill(int pid, int signal);

        int setpriority(int which, int who, int prio);

        /**
         * Declared with {@link LastErrorException}: JNA then clears errno right before the call
         * and throws if the call set it, which is what getpriority's -1 needs.
         */
        int getpriority(int which, int who) throws LastErrorException;
    }

    @Override
    public Reading processInfo(int pid) {
        int[] name = {CTL_KERN, KERN_PROC, KERN_PROC_PID, pid};
        // First the length the entry needs; the structure's full size is never assumed.
        LibCAPI.size_t.ByReference length = new LibCAPI.size_t.ByReference();
        if (SystemB.INSTANCE.sysctl(name, name.length, null, length, null, LibCAPI.size_t.ZERO) != 0) {
            return Reading.failed(Native.getLastError(), "sysctl KERN_PROC_PID length");
        }
        // With no buffer xnu answers an estimate with room to spare, never zero.
        long needed = length.longValue();
        if (needed < HEAD_SIZE) {
            return Reading.failed(0, "sysctl KERN_PROC_PID length " + needed + " is shorter than the fields read");
        }
        try (Memory buffer = new Memory(needed)) {
            length.setValue(needed);
            if (SystemB.INSTANCE.sysctl(name, name.length, buffer, length, null, LibCAPI.size_t.ZERO) != 0) {
                return Reading.failed(Native.getLastError(), "sysctl KERN_PROC_PID");
            }
            if (length.longValue() == 0) {
                return Reading.missing();
            }
            if (length.longValue() < HEAD_SIZE) {
                return Reading.failed(0, "sysctl KERN_PROC_PID returned " + length.longValue() + " bytes");
            }
            KinfoProc read = new KinfoProc(buffer);
            return Reading.found(new ProcessInfo(read.p_pid, read.startMillis(), read.p_flag, read.p_stat));
        }
    }

    @Override
    public Call kill(int pid, int signal) {
        return Libc.INSTANCE.kill(pid, signal) != 0 ? Call.failed(Native.getLastError()) : Call.ok(0);
    }

    @Override
    public Call setPriority(int pid, int nice) {
        return Libc.INSTANCE.setpriority(PRIO_PROCESS, pid, nice) != 0
                ? Call.failed(Native.getLastError())
                : Call.ok(0);
    }

    @Override
    public Call getPriority(int pid) {
        try {
            return Call.ok(Libc.INSTANCE.getpriority(PRIO_PROCESS, pid));
        } catch (LastErrorException failed) {
            return Call.failed(failed.getErrorCode());
        }
    }

    @Override
    public Optional<String> executable(int pid) {
        return ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().command())
                .map(command -> Path.of(command).getFileName())
                .map(Path::toString);
    }
}
