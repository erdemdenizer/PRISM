package prism.srm.platform.macos;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * The head of {@code struct kinfo_proc} as {@code sysctl(KERN_PROC_PID)} returns it: its first
 * member is {@code struct extern_proc kp_proc}, whose first fields are mapped here, no more. The
 * rest of the structure is not read, and its size is never assumed: the buffer is as long as sysctl
 * says, and this structure lies over its start.
 *
 * <p>From xnu: bsd/sys/sysctl.h ({@code struct kinfo_proc { struct extern_proc kp_proc; ... }}) and
 * bsd/sys/proc.h:
 *
 * <pre>
 * struct extern_proc {
 *     union {
 *         struct { struct proc *__p_forw; struct proc *__p_back; } p_st1;
 *         struct timeval __p_starttime;    // #define p_starttime p_un.__p_starttime
 *     } p_un;
 *     struct vmspace *p_vmspace;
 *     struct sigacts *p_sigacts;
 *     int     p_flag;
 *     char    p_stat;
 *     pid_t   p_pid;
 *     ...
 * </pre>
 *
 * with {@code struct timeval { __darwin_time_t tv_sec; __darwin_suseconds_t tv_usec; }}, where
 * {@code __darwin_time_t} is {@code long} and {@code __darwin_suseconds_t} and {@code pid_t} are
 * {@code __int32_t} (bsd/sys/_types.h, bsd/arm/_types.h). macOS runs only as LP64, on x86_64 and on
 * arm64, so a C {@code long} and a pointer are 8 bytes and natural alignment gives the same layout
 * on both: the union is 16 bytes (two pointers, or a timeval of 8 + 4 bytes padded to 16), then
 * {@code p_vmspace} at 16, {@code p_sigacts} at 24, {@code p_flag} at 32, {@code p_stat} at 36
 * and {@code p_pid} at 40. The union is mapped as its timeval, the member read. The fields are Java
 * {@code long} rather than JNA's {@code NativeLong}, so the layout is that of LP64 on whatever
 * system computes it, and tests check the offsets on any 64-bit system.
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/proc.h
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/sysctl.h
 */
@Structure.FieldOrder({"p_starttime", "p_vmspace", "p_sigacts", "p_flag", "p_stat", "p_pid"})
public final class KinfoProc extends Structure {

    /** {@code struct timeval} on LP64: 8 + 4 bytes, padded to 16. */
    @Structure.FieldOrder({"tv_sec", "tv_usec"})
    public static final class Timeval extends Structure {
        public long tv_sec;
        public int tv_usec;
    }

    public Timeval p_starttime;
    public Pointer p_vmspace;
    public Pointer p_sigacts;
    public int p_flag;
    public byte p_stat;
    public int p_pid;

    /** An empty head, for its size and offsets. */
    public KinfoProc() {
        super();
    }

    /** The head over the start of a buffer sysctl filled; reads it. */
    KinfoProc(Pointer memory) {
        super(memory);
        read();
    }

    /** Where a field lies from the start of the structure, as JNA lays it out; for the layout tests. */
    int offsetOf(String field) {
        return fieldOffset(field);
    }

    long startMillis() {
        return p_starttime.tv_sec * 1000 + p_starttime.tv_usec / 1000;
    }
}
