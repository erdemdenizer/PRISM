package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryWorkloadTest {

    private static final long MIB = Quantities.MIB;
    private static final long CAP = Quantities.GIB;

    private final FakeChunkAllocator allocator = new FakeChunkAllocator();

    @Test
    void allocatesInChunksAndTouchesEveryPage() {
        MemoryWorkload workload = allocator.workload(10 * MIB, CAP, 4 * MIB);

        MemoryWorkload.Allocation allocation = workload.allocate();

        assertThat(allocation.failed()).isFalse();
        assertThat(allocation.requestedBytes()).isEqualTo(10 * MIB);
        assertThat(allocation.allocatedBytes()).isEqualTo(10 * MIB);
        assertThat(allocation.chunks()).isEqualTo(3);
        assertThat(allocation.millis()).isGreaterThanOrEqualTo(0);
        assertThat(allocator.requestedSizes).containsExactly(4 * MIB, 4 * MIB, 2 * MIB);
        assertThat(workload.allocatedBytes()).isEqualTo(10 * MIB);
        // Every page is written, and no two pages hold the same content (Windows would combine them).
        Set<Long> stamps = new HashSet<>();
        long pages = 0;
        for (MemorySegment segment : allocator.segments) {
            for (long offset = 0; offset < segment.byteSize(); offset += MemoryWorkload.PAGE_BYTES) {
                long stamp = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
                assertThat(stamp).isNotZero();
                assertThat(stamps.add(stamp)).as("page %d is distinct", pages).isTrue();
                pages++;
            }
        }
        assertThat(pages).isEqualTo(10 * MIB / MemoryWorkload.PAGE_BYTES);
        assertThat(allocator.released).isZero();
    }

    @Test
    void nothingIsAllocatedForARequestOfZero() {
        MemoryWorkload workload = allocator.workload(0, CAP, 4 * MIB);
        MemoryWorkload.Allocation allocation = workload.allocate();
        assertThat(allocation.failed()).isFalse();
        assertThat(allocation.chunks()).isZero();
        assertThat(allocator.requestedSizes).isEmpty();
    }

    /** DEMO-2 is enforced here too: a workload above the cap cannot exist, whatever the command line did. */
    @Test
    void aRequestAboveTheCapCannotBeCreated() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.workload(CAP + 1, CAP, 4 * MIB))
                .withMessage("1.00GiB requested, above the safety cap of 1.00GiB");
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.workload(2 * CAP, CAP, 4 * MIB));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.workload(1, 0, 4 * MIB));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.workload(-1, CAP, 4 * MIB));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.workload(MIB, CAP, 0));
        assertThat(allocator.requestedSizes).isEmpty();
        // The cap itself is allowed.
        assertThat(allocator.workload(CAP, CAP, 4 * MIB)).isNotNull();
    }

    /** DEMO-3: a refused allocation is reported with what was held, and nothing stays held. */
    @Test
    void aRefusedChunkEndsTheAllocationWithAReportAndReleasesEverything() {
        allocator.failingAt(3, new OutOfMemoryError("Unable to allocate 2097152 bytes"));
        MemoryWorkload workload = allocator.workload(10 * MIB, CAP, 2 * MIB);

        MemoryWorkload.Allocation allocation = workload.allocate();

        assertThat(allocation.failed()).isTrue();
        assertThat(allocation.allocatedBytes()).isEqualTo(4 * MIB);
        assertThat(allocation.chunks()).isEqualTo(2);
        assertThat(allocation.failure())
                .hasValue("chunk 3 of 2MiB refused by the system: Unable to allocate 2097152 bytes");
        assertThat(workload.allocatedBytes()).isZero();
        assertThat(allocator.released).isEqualTo(1);
        assertThat(allocator.requestedSizes).hasSize(3);

        workload.close();
        assertThat(allocator.released).as("released once, not again on close").isEqualTo(1);
    }

    @Test
    void aFailureWithoutAMessageIsStillDescribed() {
        allocator.failingAt(1, new OutOfMemoryError());
        MemoryWorkload.Allocation allocation = allocator.workload(MIB, CAP, MIB).allocate();
        assertThat(allocation.failure()).hasValue("chunk 1 of 1MiB refused by the system: OutOfMemoryError");
        assertThat(allocation.allocatedBytes()).isZero();
    }

    @Test
    void closeReleasesOnce() {
        MemoryWorkload workload = allocator.workload(4 * MIB, CAP, MIB);
        workload.allocate();
        workload.close();
        workload.close();
        assertThat(allocator.released).isEqualTo(1);
        assertThat(workload.allocatedBytes()).isZero();
    }

    /** The real allocator, with an amount small enough for any machine. */
    @Test
    void nativeMemoryIsAllocatedAndReleased() {
        try (MemoryWorkload workload = MemoryWorkload.offHeap(2 * MIB, CAP)) {
            MemoryWorkload.Allocation allocation = workload.allocate();
            assertThat(allocation.failed()).isFalse();
            assertThat(allocation.allocatedBytes()).isEqualTo(2 * MIB);
            assertThat(allocation.chunks()).isEqualTo(1);
            assertThat(workload.allocatedBytes()).isEqualTo(2 * MIB);
        }
    }
}
