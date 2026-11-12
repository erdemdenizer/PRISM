package prism.srm.platform.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.platform.linux.LibC;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link LinuxNative} on the running system, through JNA and the C library. Each method makes the
 * call its name says; the errno of a failed call is read at once with {@link
 * Native#getLastError()}, which JNA keeps for the calling thread right after each call.
 *
 * <p><b>pidfd.</b> {@code pidfd_open} (Linux 5.3) and {@code pidfd_send_signal} (Linux 5.1) are
 * made through {@code syscall(2)}: glibc has wrappers only since 2.36, and Ubuntu 22.04, for one,
 * has 2.35. The numbers are those of the kernel's system call tables, 434 and 424, which are the
 * same on x86_64 and aarch64; on any other architecture they are not guessed, and the calls are
 * not made.
 * https://man7.org/linux/man-pages/man2/pidfd_open.2.html
 * https://man7.org/linux/man-pages/man2/pidfd_send_signal.2.html
 * https://github.com/torvalds/linux/blob/master/arch/x86/entry/syscalls/syscall_64.tbl
 * https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/unistd.h
 *
 * <p><b>Priority.</b> {@code setpriority(PRIO_PROCESS, who, prio)} with {@code PRIO_PROCESS} = 0
 * (include/uapi/linux/resource.h); on Linux {@code who} names one thread.
 * https://man7.org/linux/man-pages/man2/setpriority.2.html
 *
 * <p>{@code close} is jna-platform's ({@code LibC}); the other calls are mapped here, since
 * jna-platform does not map them.
 *
 * <p><b>Clock ticks.</b> {@code sysconf(_SC_CLK_TCK)}, where {@code _SC_CLK_TCK} is the third
 * value of glibc's enumeration in bits/confname.h, 2.
 * https://man7.org/linux/man-pages/man3/sysconf.3.html
 */
final class JnaLinux implements LinuxNative {

    /** The architectures whose system call numbers and signal numbers Prism has verified, as {@code os.arch} names them. */
    static final Set<String> VERIFIED_ARCHITECTURES = Set.of("amd64", "aarch64");

    static final long SYS_PIDFD_SEND_SIGNAL = 424;
    static final long SYS_PIDFD_OPEN = 434;
    private static final int PRIO_PROCESS = 0;
    private static final int SC_CLK_TCK = 2;

    /** The C library calls; loaded on first use, so the class can be created where there is none. */
    interface Libc extends Library {

        Libc INSTANCE = Native.load("c", Libc.class);

        /** {@code long syscall(long number, ...)}: varargs, so JNA passes the fixed argument count. */
        long syscall(long number, Object... arguments);

        int setpriority(int which, int who, int prio);

        NativeLong sysconf(int name);

        int getpid();
    }

    private final String architecture;

    JnaLinux() {
        this(System.getProperty("os.arch", ""));
    }

    JnaLinux(String architecture) {
        this.architecture = architecture.toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<String> pidfdUnreachable() {
        return VERIFIED_ARCHITECTURES.contains(architecture)
                ? Optional.empty()
                : Optional.of("the system call numbers of the architecture " + architecture + " are not verified");
    }

    @Override
    public Call pidfdOpen(int pid) {
        long fd = Libc.INSTANCE.syscall(SYS_PIDFD_OPEN, Long.valueOf(pid), Long.valueOf(0));
        return fd < 0 ? Call.failed(Native.getLastError()) : Call.ok(fd);
    }

    @Override
    public Call pidfdSendSignal(int fd, int signal) {
        long result = Libc.INSTANCE.syscall(
                SYS_PIDFD_SEND_SIGNAL, Long.valueOf(fd), Long.valueOf(signal), null, Long.valueOf(0));
        return result < 0 ? Call.failed(Native.getLastError()) : Call.ok(result);
    }

    @Override
    public Call close(int fd) {
        return LibC.INSTANCE.close(fd) < 0 ? Call.failed(Native.getLastError()) : Call.ok(0);
    }

    @Override
    public Call setPriority(int tid, int nice) {
        return Libc.INSTANCE.setpriority(PRIO_PROCESS, tid, nice) < 0 ? Call.failed(Native.getLastError()) : Call.ok(0);
    }

    @Override
    public long clockTicksPerSecond() {
        return Libc.INSTANCE.sysconf(SC_CLK_TCK).longValue();
    }

    @Override
    public int ownPid() {
        return Libc.INSTANCE.getpid();
    }
}
