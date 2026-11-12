package prism.srm.demo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Memory the workload holds on purpose: native memory outside the Java heap, so that the amount
 * does not depend on the heap size of the JVM, taken in chunks and touched page by page so that the
 * operating system commits it and the resident memory of the process rises by that amount. It is
 * strongly held until {@link #close()} and released all at once.
 *
 * <p>The cap of DEMO-2 is enforced here as well as on the command line: an instance above the cap
 * cannot be created.
 */
final class MemoryWorkload implements AutoCloseable {

    /** Allocates one block of native memory. It is the one call that the system may refuse. */
    @FunctionalInterface
    interface ChunkAllocator {
        MemorySegment allocate(long bytes);
    }

    /**
     * What an allocation came to.
     *
     * @param requestedBytes what was asked for
     * @param allocatedBytes what is held now: all of it, or nothing after a failure
     * @param chunks the chunks allocated
     * @param millis how long it took
     * @param failure why it stopped, when it did
     */
    record Allocation(long requestedBytes, long allocatedBytes, int chunks, long millis, Optional<String> failure) {
        boolean failed() {
            return failure.isPresent();
        }
    }

    static final long DEFAULT_CHUNK_BYTES = 16 * Quantities.MIB;
    static final long PAGE_BYTES = 4096;

    /** Combined with the number of the page, so that no two pages hold the same content. */
    private static final long PAGE_STAMP = 0x5052_4953_4D5F_5047L;

    private final long requestedBytes;
    private final long chunkBytes;
    private final ChunkAllocator allocator;
    private final Runnable release;
    private final List<MemorySegment> segments = new ArrayList<>();
    private long allocatedBytes;
    private long pagesTouched;
    private boolean released;

    MemoryWorkload(long requestedBytes, long capBytes, long chunkBytes, ChunkAllocator allocator, Runnable release) {
        if (requestedBytes < 0) {
            throw new IllegalArgumentException("memory cannot be negative");
        }
        if (requestedBytes > capBytes) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%s requested, above the safety cap of %s",
                    Quantities.formatBytes(requestedBytes),
                    Quantities.formatBytes(Math.max(0, capBytes))));
        }
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunk size must be positive");
        }
        this.requestedBytes = requestedBytes;
        this.chunkBytes = chunkBytes;
        this.allocator = allocator;
        this.release = release;
    }

    /**
     * Native memory from a confined arena, allocated and released on the thread that creates it.
     * The arena is independent of the heap and of the direct-memory limit of the JVM.
     */
    static MemoryWorkload offHeap(long requestedBytes, long capBytes) {
        Arena arena = Arena.ofConfined();
        return new MemoryWorkload(requestedBytes, capBytes, DEFAULT_CHUNK_BYTES, arena::allocate, arena::close);
    }

    /** Allocates and touches the requested memory chunk by chunk. After a failure nothing is held. */
    Allocation allocate() {
        long start = System.nanoTime();
        int chunks = 0;
        while (allocatedBytes < requestedBytes) {
            long size = Math.min(chunkBytes, requestedBytes - allocatedBytes);
            MemorySegment segment;
            try {
                segment = allocator.allocate(size);
            } catch (OutOfMemoryError e) {
                // The system refused a native allocation: that is what a memory limit on this process
                // looks like from the inside, and reporting it is the point of the workload (DEMO-3).
                // The heap is untouched, so the program is in a known state. It gives back what it
                // held and the run ends with the report; it never goes on as if nothing happened.
                long held = allocatedBytes;
                release();
                return new Allocation(
                        requestedBytes, held, chunks, elapsedMillis(start), Optional.of(describe(e, size, chunks + 1)));
            }
            touch(segment);
            segments.add(segment);
            allocatedBytes += size;
            chunks++;
        }
        return new Allocation(requestedBytes, allocatedBytes, chunks, elapsedMillis(start), Optional.empty());
    }

    long allocatedBytes() {
        return allocatedBytes;
    }

    /** Gives the memory back. Safe to call more than once; must be called on the creating thread. */
    @Override
    public void close() {
        release();
    }

    private void release() {
        if (released) {
            return;
        }
        released = true;
        segments.clear();
        allocatedBytes = 0;
        release.run();
    }

    /**
     * Writes every page, so that it is resident whatever the allocator did with the contents, and
     * writes a different value on every page. Windows combines identical pages of a process into one
     * shared page (memory combining): with the same byte on every page, 256 MiB of private memory
     * shrank to nothing in the system's accounting within a minute while the working set stayed,
     * measured on Windows 11. A page that holds its own number cannot be combined with another.
     */
    private void touch(MemorySegment segment) {
        long size = segment.byteSize();
        for (long offset = 0; offset < size; offset += PAGE_BYTES) {
            pagesTouched++;
            if (offset + Long.BYTES <= size) {
                segment.set(ValueLayout.JAVA_LONG_UNALIGNED, offset, PAGE_STAMP ^ pagesTouched);
            } else {
                segment.set(ValueLayout.JAVA_BYTE, offset, (byte) pagesTouched);
            }
        }
    }

    private static String describe(OutOfMemoryError error, long chunkBytes, int chunk) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return String.format(
                Locale.ROOT,
                "chunk %d of %s refused by the system: %s",
                chunk,
                Quantities.formatBytes(chunkBytes),
                message);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
