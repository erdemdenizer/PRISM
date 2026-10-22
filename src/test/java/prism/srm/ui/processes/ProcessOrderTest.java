package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.GIB;
import static prism.srm.ui.processes.ProcessListings.KIB;
import static prism.srm.ui.processes.ProcessListings.MIB;
import static prism.srm.ui.processes.ProcessListings.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/** The order of the rows for every column: by value, not by text, and the same every time. */
class ProcessOrderTest {

    @Test
    void namesAreSortedWithoutRegardToCase() {
        assertThat(sorted(
                        ProcessColumn.NAME,
                        false,
                        process(1, "svchost.exe").build(),
                        process(2, "Chrome.exe").build(),
                        process(3, "audiodg.exe").build(),
                        process(4, "Zoom.exe").build()))
                .containsExactly(3L, 2L, 1L, 4L);
    }

    /** As text, "10" comes before "9". As a number it does not. */
    @Test
    void pidsAreSortedAsNumbers() {
        assertThat(sorted(
                        ProcessColumn.PID,
                        false,
                        process(10, "a").build(),
                        process(9, "b").build(),
                        process(1000, "c").build(),
                        process(200, "d").build()))
                .containsExactly(9L, 10L, 200L, 1000L);
    }

    @Test
    void cpuIsSortedAsANumber() {
        assertThat(sorted(
                        ProcessColumn.CPU,
                        true,
                        process(1, "a").cpu(9.5).build(),
                        process(2, "b").cpu(100).build(),
                        process(3, "c").cpu(0).build(),
                        process(4, "d").cpu(10.25).build()))
                .containsExactly(2L, 4L, 1L, 3L);
    }

    /** As text, "900.0 KiB" comes after "1.0 GiB". As a size it is the smallest of the three. */
    @Test
    void memoryIsSortedByItsSizeWhateverUnitItIsShownIn() {
        assertThat(sorted(
                        ProcessColumn.MEMORY,
                        false,
                        process(1, "a").memory(GIB).build(),
                        process(2, "b").memory(900 * KIB).build(),
                        process(3, "c").memory(2 * MIB).build()))
                .containsExactly(2L, 3L, 1L);
    }

    @Test
    void ioIsSortedByTheSumOfItsTwoRates() {
        assertThat(sorted(
                        ProcessColumn.IO,
                        true,
                        process(1, "a").io(100, 0).build(),
                        process(2, "b").io(60, 60).build(),
                        process(3, "c").io(0, 110).build()))
                .containsExactly(2L, 3L, 1L);
    }

    @Test
    void threadsAndPrioritiesAreSortedAsNumbersAlsoWhenNegative() {
        assertThat(sorted(
                        ProcessColumn.THREADS,
                        false,
                        process(1, "a").threads(100).build(),
                        process(2, "b").threads(9).build(),
                        process(3, "c").threads(1234).build()))
                .containsExactly(2L, 1L, 3L);
        assertThat(sorted(
                        ProcessColumn.PRIORITY,
                        false,
                        process(1, "a").priority(8).build(),
                        process(2, "b").priority(-20).build(),
                        process(3, "c").priority(13).build()))
                .containsExactly(2L, 1L, 3L);
    }

    @Test
    void usersAndStatesAreSortedByTheirNames() {
        assertThat(sorted(
                        ProcessColumn.USER,
                        false,
                        process(1, "a").user("root").build(),
                        process(2, "b").user("Alice").build(),
                        process(3, "c").user("bob").build()))
                .containsExactly(2L, 3L, 1L);
        assertThat(sorted(
                        ProcessColumn.STATE,
                        false,
                        process(1, "a").state(ProcessState.ZOMBIE).build(),
                        process(2, "b").state(ProcessState.RUNNING).build(),
                        process(3, "c").state(ProcessState.SLEEPING).build()))
                .containsExactly(2L, 3L, 1L);
    }

    /** A value that is missing is not the smallest value and not the largest: it comes last. */
    @ParameterizedTest
    @EnumSource(
            value = ProcessColumn.class,
            names = {"PID"},
            mode = EnumSource.Mode.EXCLUDE)
    void aProcessWithoutAValueComesLastWhicheverWayTheColumnIsSorted(ProcessColumn column) {
        ProcessSnapshot small = valued(1, "alpha", 1, MIB, 10, "alice", 2, 4, ProcessState.RUNNING)
                .build();
        ProcessSnapshot large = valued(2, "omega", 90, GIB, 9000, "root", 60, 13, ProcessState.ZOMBIE)
                .build();
        ProcessSnapshot missing = process(3, "").nothingElse().build();

        assertThat(sorted(column, false, missing, large, small)).containsExactly(1L, 2L, 3L);
        assertThat(sorted(column, true, missing, small, large)).containsExactly(2L, 1L, 3L);
    }

    @Test
    void processesWithoutAValueKeepTheOrderOfTheirPidsAmongThemselves() {
        assertThat(sorted(
                        ProcessColumn.CPU,
                        true,
                        process(30, "c").noCpu().build(),
                        process(10, "a").noCpu().build(),
                        process(20, "b").cpu(1).build(),
                        process(5, "d").noCpu().build()))
                .containsExactly(20L, 5L, 10L, 30L);
    }

    /** Only the read rate is known: the sum is not, and the process counts as having no value. */
    @Test
    void anIoRateOfWhichOnlyOneHalfIsKnownIsNoValue() {
        assertThat(sorted(
                        ProcessColumn.IO,
                        true,
                        process(1, "a").ioReadOnly(1_000_000).build(),
                        process(2, "b").io(1, 1).build()))
                .containsExactly(2L, 1L);
    }

    @Test
    void equalValuesAreOrderedByPidAndThenByStartTimeInEitherDirection() {
        ProcessSnapshot later =
                process(7, "same").startedSecondsAfterBoot(500).cpu(3).build();
        ProcessSnapshot earlier =
                process(7, "same").startedSecondsAfterBoot(100).cpu(3).build();
        ProcessSnapshot other = process(3, "same").cpu(3).build();

        for (boolean descending : new boolean[] {false, true}) {
            List<ProcessRow> rows = rows(later, other, earlier);
            rows.sort(ProcessOrder.comparator(List.of(new SortKey(ProcessColumn.CPU, descending))));

            assertThat(rows).extracting(row -> row.snapshot()).containsExactly(other, earlier, later);
        }
    }

    @Test
    void aSecondColumnOrdersTheRowsThatAreEqualInTheFirst() {
        List<ProcessRow> rows = rows(
                process(1, "chrome.exe").memory(100).build(),
                process(2, "chrome.exe").memory(300).build(),
                process(3, "audiodg.exe").memory(50).build(),
                process(4, "chrome.exe").memory(200).build());

        rows.sort(ProcessOrder.comparator(
                List.of(new SortKey(ProcessColumn.NAME, false), new SortKey(ProcessColumn.MEMORY, true))));

        assertThat(pids(rows)).containsExactly(3L, 2L, 4L, 1L);
    }

    /** Whatever order the rows arrive in, sorting gives one order: no two rows compare as equal. */
    @ParameterizedTest
    @EnumSource(ProcessColumn.class)
    void theOrderIsTheSameWhateverOrderTheRowsWereInBefore(ProcessColumn column) {
        List<ProcessSnapshot> listing = new ArrayList<>(ProcessListings.windows(400, 3));
        // Processes without any value, and processes with a state, next to those of the listing.
        for (int extra = 0; extra < 6; extra++) {
            listing.add(process(900_000 + extra, extra % 2 == 0 ? "" : "bare")
                    .nothingElse()
                    .build());
        }
        for (ProcessState state : ProcessState.values()) {
            listing.add(process(910_000 + state.ordinal(), "with-state")
                    .state(state)
                    .build());
        }
        Comparator<ProcessRow> order = ProcessOrder.comparator(List.of(new SortKey(column, true)));
        List<ProcessRow> reference = rows(listing.toArray(ProcessSnapshot[]::new));
        reference.sort(order);

        Random random = new Random(20261006);
        for (int round = 0; round < 5; round++) {
            List<ProcessRow> shuffled = new ArrayList<>(reference);
            Collections.shuffle(shuffled, random);
            shuffled.sort(order);

            assertThat(shuffled).containsExactlyElementsOf(reference);
        }
        for (int index = 1; index < reference.size(); index++) {
            assertThat(order.compare(reference.get(index - 1), reference.get(index)))
                    .isNegative();
        }
    }

    @Test
    void theIoRateOfTheColumnIsReadPlusWriteAndAbsentUnlessBothAreKnown() {
        assertThat(ProcessColumn.ioBytesPerSecond(process(1, "a").io(1024, 512).build()))
                .hasValue(1536);
        assertThat(ProcessColumn.ioBytesPerSecond(process(1, "a").io(0, 0).build()))
                .hasValue(0);
        assertThat(ProcessColumn.ioBytesPerSecond(process(1, "a").noIo().build()))
                .isEmpty();
        assertThat(ProcessColumn.ioBytesPerSecond(
                        process(1, "a").ioReadOnly(1024).build()))
                .isEmpty();
    }

    @Test
    void theColumnsAreTheNineOfTheSpecificationInItsOrderAndTheIoColumnIsNotCalledDisk() {
        assertThat(ProcessColumn.values())
                .extracting(ProcessColumn::title)
                .containsExactly("Name", "PID", "CPU", "Memory", "I/O", "User", "Threads", "Priority", "State");
    }

    private static ProcessListings.Sample valued(
            long pid,
            String name,
            double cpu,
            long memory,
            double io,
            String user,
            int threads,
            int priority,
            ProcessState state) {
        return process(pid, name)
                .cpu(cpu)
                .memory(memory)
                .io(io, io)
                .user(user)
                .threads(threads)
                .priority(priority)
                .state(state);
    }

    private static List<Long> sorted(ProcessColumn column, boolean descending, ProcessSnapshot... processes) {
        List<ProcessRow> rows = rows(processes);
        rows.sort(ProcessOrder.comparator(List.of(new SortKey(column, descending))));
        return pids(rows);
    }

    private static List<ProcessRow> rows(ProcessSnapshot... processes) {
        List<ProcessRow> rows = new ArrayList<>();
        for (ProcessSnapshot process : processes) {
            rows.add(new ProcessRow(process));
        }
        return rows;
    }

    private static List<Long> pids(List<ProcessRow> rows) {
        return rows.stream().map(row -> row.identity().pid()).toList();
    }
}
