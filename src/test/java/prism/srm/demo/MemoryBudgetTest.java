package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** DEMO-2: never above the smaller of 1 GiB and a quarter of physical memory. */
class MemoryBudgetTest {

    @Test
    void theAbsoluteCapWinsOnALargeMachine() {
        assertThat(MemoryBudget.cap(32 * Quantities.GIB)).hasValue(Quantities.GIB);
        assertThat(MemoryBudget.cap(Long.MAX_VALUE)).hasValue(Quantities.GIB);
    }

    @Test
    void aQuarterOfPhysicalMemoryWinsOnASmallMachine() {
        assertThat(MemoryBudget.cap(2 * Quantities.GIB)).hasValue(512 * Quantities.MIB);
        assertThat(MemoryBudget.cap(1 * Quantities.GIB)).hasValue(256 * Quantities.MIB);
        assertThat(MemoryBudget.cap(100)).hasValue(25);
    }

    @Test
    void theTwoBoundsMeetAtFourGibibytes() {
        assertThat(MemoryBudget.cap(4 * Quantities.GIB)).hasValue(Quantities.GIB);
        assertThat(MemoryBudget.cap(4 * Quantities.GIB - 4)).hasValue(Quantities.GIB - 1);
        assertThat(MemoryBudget.cap(4 * Quantities.GIB + 4)).hasValue(Quantities.GIB);
    }

    @Test
    void anUnknownPhysicalMemorySizeGivesNoCap() {
        assertThat(MemoryBudget.cap(0)).isEmpty();
        assertThat(MemoryBudget.cap(-1)).isEmpty();
        assertThat(MemoryBudget.cap(Long.MIN_VALUE)).isEmpty();
    }
}
