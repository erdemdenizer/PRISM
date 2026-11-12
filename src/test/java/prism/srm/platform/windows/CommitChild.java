package prism.srm.platform.windows;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;

/**
 * A disposable child of the native-limit tests (SAFE-6) that commits memory only when told to, so
 * that a commit limit can be set before it allocates: it reads {@code alloc <MiB>} lines and
 * answers {@code allocated=<MiB> failed=<true|false>}, allocating in 16 MiB chunks and touching one
 * byte of every page with a distinct value (page combining, milestone 6). {@code free} releases
 * everything, {@code exit} ends it. Catching the allocation failure is the point of this program:
 * it is what a commit limit looks like from inside (DEMO-3).
 */
public final class CommitChild {

    private static final long CHUNK = 16L << 20;
    private static final long PAGE = 4096;

    private CommitChild() {}

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        Arena arena = Arena.ofShared();
        System.out.println("ready");
        System.out.flush();
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("exit")) {
                break;
            }
            if (line.equals("free")) {
                arena.close();
                arena = Arena.ofShared();
                System.out.println("freed");
            } else if (line.startsWith("alloc ")) {
                long wanted = Long.parseLong(line.substring("alloc ".length())) << 20;
                long allocated = 0;
                boolean failed = false;
                try {
                    while (allocated < wanted) {
                        var chunk = arena.allocate(CHUNK);
                        for (long at = 0; at < CHUNK; at += PAGE) {
                            chunk.set(java.lang.foreign.ValueLayout.JAVA_LONG, at, allocated + at);
                        }
                        allocated += CHUNK;
                    }
                } catch (OutOfMemoryError refused) {
                    failed = true;
                }
                System.out.println("allocated=" + (allocated >> 20) + " failed=" + failed);
            }
            System.out.flush();
        }
        arena.close();
    }
}
