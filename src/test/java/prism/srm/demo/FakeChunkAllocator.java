package prism.srm.demo;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands out heap-backed segments and can refuse a given chunk the way the system refuses a native
 * allocation, so the failure path is tested without taking real memory from the machine.
 */
final class FakeChunkAllocator implements MemoryWorkload.ChunkAllocator {

    final List<Long> requestedSizes = new ArrayList<>();
    final List<MemorySegment> segments = new ArrayList<>();
    int released;

    private int failAtChunk = Integer.MAX_VALUE;
    private OutOfMemoryError failure;

    FakeChunkAllocator failingAt(int chunk, OutOfMemoryError error) {
        failAtChunk = chunk;
        failure = error;
        return this;
    }

    @Override
    public MemorySegment allocate(long bytes) {
        requestedSizes.add(bytes);
        if (requestedSizes.size() == failAtChunk) {
            throw failure;
        }
        MemorySegment segment = MemorySegment.ofArray(new byte[Math.toIntExact(bytes)]);
        segments.add(segment);
        return segment;
    }

    void release() {
        released++;
    }

    MemoryWorkload workload(long requestedBytes, long capBytes, long chunkBytes) {
        return new MemoryWorkload(requestedBytes, capBytes, chunkBytes, this, this::release);
    }
}
