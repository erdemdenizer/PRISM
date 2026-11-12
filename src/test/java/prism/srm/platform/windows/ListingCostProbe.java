package prism.srm.platform.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Splits the cost of one thread listing (milestone 9, plan §8.3): the system-wide Toolhelp
 * snapshot, and the walk through every thread of the system with Thread32Next, which is what a
 * Windows suspension pays twice at least. Not part of the build; run by hand:
 *
 * <pre>./mvnw -q test -Dtest=ListingCostProbe</pre>
 */
@EnabledOnOs(OS.WINDOWS)
class ListingCostProbe {

    @Test
    void measure() {
        JnaWin32 win32 = new JnaWin32();
        int pid = (int) ProcessHandle.current().pid();
        for (int i = 0; i < 5; i++) {
            win32.threadsOf(pid);
        }
        long snapshotNanos = 0;
        long walkNanos = 0;
        int threads = 0;
        int rounds = 20;
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
            long taken = System.nanoTime();
            snapshotNanos += taken - start;
            threads = 0;
            try {
                Tlhelp32.THREADENTRY32 entry = new Tlhelp32.THREADENTRY32();
                boolean more = Kernel32.INSTANCE.Thread32First(snapshot, entry);
                while (more) {
                    threads++;
                    more = Kernel32.INSTANCE.Thread32Next(snapshot, entry);
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(snapshot);
            }
            walkNanos += System.nanoTime() - taken;
        }
        System.out.printf(
                Locale.ROOT,
                "listing-cost threads=%d snapshot=%.2f ms walk=%.2f ms (%.2f us per thread) (mean of %d)%n",
                threads,
                snapshotNanos / 1e6 / rounds,
                walkNanos / 1e6 / rounds,
                walkNanos / 1e3 / rounds / Math.max(1, threads),
                rounds);
    }
}
