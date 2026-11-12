package prism.srm.platform.windows;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.platform.DemoProcess;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.ThreadHolder;

/**
 * Where the time of a Windows suspend goes (milestone 9 external review, item 1): the system-wide
 * Toolhelp snapshot, the walk over every thread of the system, and the share of the walk that is
 * JNA's (marshalling a {@code THREADENTRY32} Structure through an interface mapping) rather than
 * Windows' own {@code Thread32Next}. Then the suspend of the demo workload as Prism makes it (two
 * listings at least) against one made from a single listing, with the same documented calls. The
 * same again with thousands of extra threads in the system. Not part of the build; run by hand:
 *
 * <pre>./mvnw -q test -Dtest=SuspendPathProfileProbe -Dprobe.extraThreads=5000</pre>
 *
 * Acts on the demo workload and the thread holder it starts, and on nothing else (SAFE-6).
 */
@EnabledOnOs(OS.WINDOWS)
class SuspendPathProfileProbe {

    private static final int ROUNDS = 30;

    /** {@code Thread32First}/{@code Thread32Next} with the entry as plain memory: no Structure marshalling. */
    interface RawToolhelp extends Library {
        RawToolhelp INSTANCE = Native.load("kernel32", RawToolhelp.class);

        boolean Thread32First(HANDLE snapshot, Pointer entry);

        boolean Thread32Next(HANDLE snapshot, Pointer entry);
    }

    /** The same through JNA direct mapping, the cheapest JNA call there is: what is left is Windows' own time. */
    static final class DirectToolhelp {
        static {
            Native.register("kernel32");
        }

        private DirectToolhelp() {}

        static native boolean Thread32First(Pointer snapshot, Pointer entry);

        static native boolean Thread32Next(Pointer snapshot, Pointer entry);
    }

    /** THREADENTRY32: dwSize, cntUsage, th32ThreadID, th32OwnerProcessID, tpBasePri, tpDeltaPri, dwFlags (tlhelp32.h). */
    private static final int ENTRY_SIZE = 28;

    private static final int OWNER_OFFSET = 12;

    @Test
    void measure() throws Exception {
        int extra = Integer.getInteger("probe.extraThreads", 0);
        Process holder = extra > 0 ? ThreadHolder.start(extra, 600) : null;
        try (DemoProcess demo = DemoProcess.start("--cpu", "50", "--workers", "8", "--duration", "300s")) {
            JnaWin32 win32 = new JnaWin32();
            int pid = (int) demo.identity().pid();
            for (int i = 0; i < 10; i++) {
                win32.threadsOf(pid);
                walkRaw(pid);
                walkDirect(pid);
            }
            int system = countThreads();
            int own = win32.threadsOf(pid).threadIds().size();

            double snapshot = median(() -> {
                long start = System.nanoTime();
                HANDLE handle = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
                Kernel32.INSTANCE.CloseHandle(handle);
                return System.nanoTime() - start;
            });
            double structure = median(() -> {
                long start = System.nanoTime();
                win32.threadsOf(pid);
                return System.nanoTime() - start;
            });
            double raw = median(() -> {
                long start = System.nanoTime();
                walkRaw(pid);
                return System.nanoTime() - start;
            });
            double direct = median(() -> {
                long start = System.nanoTime();
                walkDirect(pid);
                return System.nanoTime() - start;
            });

            WindowsProcessController controller = new WindowsProcessController();
            for (int i = 0; i < 5; i++) {
                SuspendResult warm = controller.suspend(demo.identity());
                controller.resume(warm.suspension().orElseThrow());
            }
            long[] suspends = new long[ROUNDS];
            long[] resumes = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                long start = System.nanoTime();
                SuspendResult result = controller.suspend(demo.identity());
                long taken = System.nanoTime();
                controller.resume(result.suspension().orElseThrow());
                resumes[i] = System.nanoTime() - taken;
                suspends[i] = taken - start;
                Thread.sleep(50);
            }
            long[] single = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                single[i] = singleListingSuspendAndResume(win32, pid);
                Thread.sleep(50);
            }
            Arrays.sort(suspends);
            Arrays.sort(resumes);
            Arrays.sort(single);
            System.out.printf(
                    Locale.ROOT,
                    "suspend-path: extraThreads=%d systemThreads=%d targetThreads=%d | snapshot %.2f ms |"
                            + " listing: structure %.2f ms, raw pointer %.2f ms, direct %.2f ms (%.2f/%.2f/%.2f us per"
                            + " thread) | suspend as built median %.2f p95 %.2f ms | resume median %.3f ms |"
                            + " one-listing suspend+resume median %.2f p95 %.2f ms%n",
                    extra,
                    system,
                    own,
                    snapshot / 1e6,
                    structure / 1e6,
                    raw / 1e6,
                    direct / 1e6,
                    (structure - snapshot) / 1e3 / system,
                    (raw - snapshot) / 1e3 / system,
                    (direct - snapshot) / 1e3 / system,
                    suspends[ROUNDS / 2] / 1e6,
                    suspends[ROUNDS * 95 / 100] / 1e6,
                    resumes[ROUNDS / 2] / 1e6,
                    single[ROUNDS / 2] / 1e6,
                    single[ROUNDS * 95 / 100] / 1e6);
        } finally {
            if (holder != null) {
                holder.destroyForcibly();
            }
        }
    }

    /**
     * A suspend from one listing: the same documented calls as Prism's (OpenThread, the owner check,
     * SuspendThread), without the second listing that finds threads created meanwhile; then each is
     * resumed and closed. Returns the time of the suspend part.
     */
    private static long singleListingSuspendAndResume(JnaWin32 win32, int pid) {
        long start = System.nanoTime();
        List<Long> held = new ArrayList<>();
        for (int threadId : win32.threadsOf(pid).threadIds()) {
            Win32.Opened opened = win32.openThread(WindowsSuspensions.THREAD_RIGHTS, threadId);
            if (!opened.ok()) {
                continue;
            }
            if (win32.processIdOfThread(opened.handle()) != pid
                    || !win32.suspendThread(opened.handle()).ok()) {
                win32.close(opened.handle());
                continue;
            }
            held.add(opened.handle());
        }
        long taken = System.nanoTime() - start;
        for (long handle : held) {
            win32.resumeThread(handle);
            win32.close(handle);
        }
        return taken;
    }

    private static int countThreads() {
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
        try (Memory entry = new Memory(ENTRY_SIZE)) {
            entry.setInt(0, ENTRY_SIZE);
            int count = 0;
            boolean more = RawToolhelp.INSTANCE.Thread32First(snapshot, entry);
            while (more) {
                count++;
                more = RawToolhelp.INSTANCE.Thread32Next(snapshot, entry);
            }
            return count;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    private static int walkRaw(int pid) {
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
        try (Memory entry = new Memory(ENTRY_SIZE)) {
            entry.setInt(0, ENTRY_SIZE);
            int mine = 0;
            boolean more = RawToolhelp.INSTANCE.Thread32First(snapshot, entry);
            while (more) {
                if (entry.getInt(OWNER_OFFSET) == pid) {
                    mine++;
                }
                more = RawToolhelp.INSTANCE.Thread32Next(snapshot, entry);
            }
            return mine;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    private static int walkDirect(int pid) {
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
        try (Memory entry = new Memory(ENTRY_SIZE)) {
            entry.setInt(0, ENTRY_SIZE);
            Pointer handle = snapshot.getPointer();
            int mine = 0;
            boolean more = DirectToolhelp.Thread32First(handle, entry);
            while (more) {
                if (entry.getInt(OWNER_OFFSET) == pid) {
                    mine++;
                }
                more = DirectToolhelp.Thread32Next(handle, entry);
            }
            return mine;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    private static double median(LongSupplier sample) {
        long[] values = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            values[i] = sample.getAsLong();
        }
        Arrays.sort(values);
        return values[ROUNDS / 2];
    }
}
