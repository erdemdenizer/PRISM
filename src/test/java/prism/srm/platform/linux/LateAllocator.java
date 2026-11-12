package prism.srm.platform.linux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A disposable, test-owned process for the memory-limit integration tests (milestone 12, T-L4, T-L5): it waits for a
 * line on stdin, then allocates the given number of MiB of direct memory in 16 MiB chunks, touching every page so that
 * the kernel charges it to the cgroup the process is in at that moment, prints its progress and how long the allocation
 * took, and holds the memory until it is killed or a bound passes. Memory charged before a move stays with the old
 * cgroup, so the tests allocate only after the adoption. It never allocates more than asked, and never more than 1 GiB.
 *
 * <p>Arguments: MiB, the hold in milliseconds, and optionally {@code sibling}: then, after "go" and before allocating,
 * it starts a {@code sleep} that inherits its cgroup, a second member of the scope that an OOM kill of the allocator
 * must leave alone.
 */
public final class LateAllocator {

    private static final int CHUNK = 16 << 20;
    private static final int PAGE = 4096;

    private LateAllocator() {}

    public static void main(String[] arguments) throws Exception {
        int mib = Math.min(Integer.parseInt(arguments[0]), 1024);
        long holdMillis = arguments.length > 1 ? Long.parseLong(arguments[1]) : 120_000;
        boolean sibling = arguments.length > 2 && arguments[2].equals("sibling");
        System.out.println("event=ready pid=" + ProcessHandle.current().pid());
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (in.readLine() == null) {
            return;
        }
        if (sibling) {
            Process sleeper = new ProcessBuilder("sleep", "300").start();
            System.out.println("event=sibling pid=" + sleeper.pid());
            System.out.flush();
        }
        List<ByteBuffer> held = new ArrayList<>();
        long allocated = 0;
        long target = (long) mib << 20;
        long start = System.nanoTime();
        while (allocated < target) {
            ByteBuffer chunk = ByteBuffer.allocateDirect(CHUNK);
            for (int i = 0; i < CHUNK; i += PAGE) {
                chunk.put(i, (byte) 1);
            }
            held.add(chunk);
            allocated += CHUNK;
            System.out.println("event=allocated mib=" + (allocated >> 20));
            System.out.flush();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("event=done mib=" + (allocated >> 20) + " chunks=" + held.size() + " millis=" + millis);
        System.out.flush();
        Thread.sleep(holdMillis);
    }
}
