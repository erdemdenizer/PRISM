package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.process;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * The rows of the process table against listings: which rows stay, come and go, what the list is
 * told about it, and what happens to the selection. Needs no JavaFX toolkit.
 */
class ProcessTableModelTest {

    private final ProcessTableModel model = new ProcessTableModel();

    /** Every change the list of rows announced, in order: what a table would have to react to. */
    private final List<String> changes = new ArrayList<>();

    /** How often the list announced anything; one announcement can hold several changes. */
    private int announcements;

    /** How many rows the list announced as taken out, and as put in. */
    private int rowsRemoved;

    private int rowsAdded;

    @BeforeEach
    void recordWhatTheListAnnounces() {
        model.rows().addListener((ListChangeListener<ProcessRow>) change -> {
            announcements++;
            while (change.next()) {
                if (change.wasPermutated()) {
                    changes.add("permutation");
                } else if (change.wasReplaced()) {
                    changes.add("replaced " + change.getRemovedSize() + " by " + change.getAddedSize());
                } else if (change.wasRemoved()) {
                    changes.add("removed " + change.getRemovedSize());
                    rowsRemoved += change.getRemovedSize();
                } else if (change.wasAdded()) {
                    changes.add("added " + change.getAddedSize());
                    rowsAdded += change.getAddedSize();
                }
            }
        });
    }

    // --- Rows follow the listings by identity -------------------------------------------------

    @Test
    void anEmptyListingGivesNoRows() {
        model.update(List.of());

        assertThat(model.rows()).isEmpty();
        assertThat(model.total()).isZero();
        assertThat(changes).isEmpty();
    }

    @Test
    void oneProcessGivesOneRowWithItsValues() {
        ProcessSnapshot only = process(42, "only.exe").cpu(1.5).build();

        model.update(List.of(only));

        assertThat(model.rows()).hasSize(1);
        assertThat(model.rows().get(0).identity()).isEqualTo(only.identity());
        assertThat(model.rows().get(0).snapshot()).isEqualTo(only);
        assertThat(model.total()).isEqualTo(1);
    }

    @Test
    void aProcessThatIsStillThereKeepsItsRowAndGetsTheNewValues() {
        model.update(
                List.of(process(10, "a").cpu(1).build(), process(20, "b").cpu(2).build()));
        ProcessRow a = row(10);
        ProcessRow b = row(20);
        changes.clear();

        model.update(List.of(
                process(10, "a").cpu(7).memory(99).build(),
                process(20, "b").cpu(2).build()));

        assertThat(row(10)).isSameAs(a);
        assertThat(row(20)).isSameAs(b);
        assertThat(a.snapshot().cpuPercent()).hasValue(7);
        assertThat(a.snapshot().residentBytes()).hasValue(99);
        // The list was not touched: the table keeps its cells, its selection and its place.
        assertThat(changes).isEmpty();
    }

    /** A row whose process did nothing is not even told: its cells have nothing to redraw. */
    @Test
    void aRowIsNotSetAgainWhenItsSnapshotIsEqualToTheOneItHolds() {
        model.update(
                List.of(process(10, "a").cpu(1).build(), process(20, "b").cpu(2).build()));
        List<Long> told = new ArrayList<>();
        for (ProcessRow row : model.rows()) {
            row.snapshotProperty()
                    .addListener((observable, before, after) ->
                            told.add(row.identity().pid()));
        }

        model.update(
                List.of(process(10, "a").cpu(1).build(), process(20, "b").cpu(3).build()));

        assertThat(told).containsExactly(20L);
    }

    @Test
    void aNewProcessGetsARowAndTheOthersKeepTheirs() {
        model.update(List.of(process(10, "a").build(), process(30, "c").build()));
        ProcessRow a = row(10);
        ProcessRow c = row(30);
        changes.clear();

        model.update(List.of(
                process(10, "a").build(),
                process(20, "b").build(),
                process(30, "c").build()));

        assertThat(pids()).containsExactly(10L, 20L, 30L);
        assertThat(row(10)).isSameAs(a);
        assertThat(row(30)).isSameAs(c);
        // One row was added and the rows were put in order; no row was replaced.
        assertThat(changes).containsExactly("added 1", "permutation");
    }

    @Test
    void aProcessThatExitedLosesItsRowAndNothingOfItIsKept() {
        model.update(List.of(
                process(10, "a").build(),
                process(20, "b").build(),
                process(30, "c").build()));
        ProcessRow b = row(20);
        changes.clear();

        model.update(List.of(process(10, "a").build(), process(30, "c").build()));

        assertThat(pids()).containsExactly(10L, 30L);
        assertThat(model.total()).isEqualTo(2);
        assertThat(model.isRunning(b)).isFalse();
        assertThat(changes).containsExactly("removed 1");
    }

    /** All exits of a listing are announced together, and so are all arrivals, and the new order. */
    @Test
    void manyProcessesComingAndGoingAtOnceAreThreeAnnouncementsOfTheList() {
        model.sortBy(List.of(new SortKey(ProcessColumn.NAME, false)));
        model.update(ProcessListings.windows(400, 0));
        changes.clear();
        announcements = 0;
        rowsRemoved = 0;
        rowsAdded = 0;
        List<ProcessSnapshot> next = new ArrayList<>(ProcessListings.windows(400, 1));
        next.removeIf(process -> process.identity().pid() % 28 == 0);
        for (int extra = 0; extra < 25; extra++) {
            next.add(process(500_000 + extra, "new-" + extra).build());
        }

        model.update(next);

        assertThat(model.rows()).hasSize(next.size());
        assertThat(announcements).isEqualTo(3);
        // One announcement names every place a row left, one all rows that came, one the new order.
        assertThat(rowsRemoved).isEqualTo(57);
        assertThat(rowsAdded).isEqualTo(25);
        assertThat(changes.stream().filter("permutation"::equals)).hasSize(1);
        assertThat(changes).noneMatch(change -> change.startsWith("replaced"));
    }

    /** The same PID with another start time is another process: a new row, never the old one. */
    @Test
    void aPidThatAnotherProcessTookOverGetsANewRow() {
        model.update(List.of(process(10, "old").cpu(50).build()));
        ProcessRow old = row(10);

        model.update(List.of(
                process(10, "new").startedSecondsAfterBoot(9_000).noCpu().build()));

        ProcessRow now = row(10);
        assertThat(now).isNotSameAs(old);
        assertThat(now.identity()).isNotEqualTo(old.identity());
        assertThat(now.snapshot().name()).isEqualTo("new");
        // Nothing of the old process passed to the new one.
        assertThat(now.snapshot().cpuPercent()).isEmpty();
        assertThat(model.isRunning(old)).isFalse();
        assertThat(model.total()).isEqualTo(1);
        // The values of the old row were not overwritten with those of another process.
        assertThat(old.snapshot().name()).isEqualTo("old");
    }

    @Test
    void bothProcessesAreShownWhileOnePidIsListedWithTwoStartTimes() {
        model.update(List.of(
                process(10, "first").build(),
                process(10, "second").startedSecondsAfterBoot(9_000).build()));

        assertThat(model.rows()).extracting(row -> row.snapshot().name()).containsExactly("first", "second");
    }

    @Test
    void anIdentityListedTwiceIsShownOnceWithTheFirstOfItsSnapshots() {
        model.update(List.of(process(10, "first").build(), process(10, "again").build()));

        assertThat(model.rows()).hasSize(1);
        assertThat(model.rows().get(0).snapshot().name()).isEqualTo("first");

        model.update(List.of(
                process(10, "first-later").build(), process(10, "again-later").build()));

        assertThat(model.rows()).hasSize(1);
        assertThat(model.rows().get(0).snapshot().name()).isEqualTo("first-later");
    }

    @Test
    void rowsAreKeptForRunningProcessesOnlyHoweverManyListingsPass() {
        Map<ProcessRow, Boolean> everSeen = new IdentityHashMap<>();
        for (int tick = 0; tick < 300; tick++) {
            // Every listing has 50 processes, and each time five of them are new.
            List<ProcessSnapshot> listing = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                listing.add(process(1000 + tick * 5L + index, "p").build());
            }
            model.update(listing);
            model.rows().forEach(row -> everSeen.put(row, true));
        }

        assertThat(everSeen).hasSizeGreaterThan(1500);
        assertThat(model.rows()).hasSize(50);
        assertThat(model.total()).isEqualTo(50);
        assertThat(everSeen.keySet().stream().filter(model::isRunning)).hasSize(50);
    }

    @Test
    void aLargeListingIsShownInFullAndUpdatedInPlace() {
        model.update(ProcessListings.windows(1000, 0));
        List<ProcessRow> before = List.copyOf(model.rows());
        changes.clear();

        model.update(ProcessListings.windows(1000, 1));

        assertThat(model.rows()).hasSize(1000);
        assertThat(model.rows()).containsExactlyElementsOf(before);
        assertThat(changes).isEmpty();
    }

    @Test
    void clearingDropsEveryRow() {
        model.update(ProcessListings.windows(40, 0));
        model.select(model.rows().get(3));

        model.clear();

        assertThat(model.rows()).isEmpty();
        assertThat(model.total()).isZero();
        assertThat(model.selected()).isNull();
    }

    // --- Order -----------------------------------------------------------------------------------

    @Test
    void withoutAColumnToSortByTheRowsAreInTheOrderOfTheirPids() {
        model.update(List.of(
                process(30, "a").build(),
                process(10, "c").build(),
                process(20, "b").build()));

        assertThat(pids()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void theRowsStayInOrderWhenAListingChangesTheValuesTheyAreSortedBy() {
        model.sortBy(List.of(new SortKey(ProcessColumn.CPU, true)));
        model.update(List.of(
                process(10, "a").cpu(1).build(),
                process(20, "b").cpu(5).build(),
                process(30, "c").cpu(3).build()));
        assertThat(pids()).containsExactly(20L, 30L, 10L);
        changes.clear();

        model.update(List.of(
                process(10, "a").cpu(9).build(),
                process(20, "b").cpu(5).build(),
                process(30, "c").cpu(3).build()));

        assertThat(pids()).containsExactly(10L, 20L, 30L);
        // The rows changed places; none was taken out or put in.
        assertThat(changes).containsExactly("permutation");
    }

    @Test
    void aListingThatLeavesTheOrderAsItIsDoesNotTouchTheList() {
        model.sortBy(List.of(new SortKey(ProcessColumn.CPU, true)));
        model.update(List.of(
                process(10, "a").cpu(1).build(),
                process(20, "b").cpu(5).build(),
                process(30, "c").cpu(3).build()));
        changes.clear();

        model.update(List.of(
                process(10, "a").cpu(2).build(),
                process(20, "b").cpu(6).build(),
                process(30, "c").cpu(4).build()));

        assertThat(pids()).containsExactly(20L, 30L, 10L);
        assertThat(changes).isEmpty();
    }

    @Test
    void sortingByAnotherColumnPutsTheSameRowsInAnotherOrder() {
        model.update(List.of(
                process(10, "b").memory(300).build(),
                process(20, "a").memory(100).build(),
                process(30, "c").memory(200).build()));
        List<ProcessRow> before = List.copyOf(model.rows());
        changes.clear();

        model.sortBy(List.of(new SortKey(ProcessColumn.MEMORY, false)));

        assertThat(pids()).containsExactly(20L, 30L, 10L);
        assertThat(model.rows()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(changes).containsExactly("permutation");
    }

    @Test
    void aNewProcessArrivesAtItsPlaceInTheOrder() {
        model.sortBy(List.of(new SortKey(ProcessColumn.NAME, false)));
        model.update(List.of(process(10, "alpha").build(), process(30, "gamma").build()));

        model.update(List.of(
                process(10, "alpha").build(),
                process(30, "gamma").build(),
                process(5, "beta").build()));

        assertThat(model.rows()).extracting(row -> row.snapshot().name()).containsExactly("alpha", "beta", "gamma");
    }

    // --- Filter ------------------------------------------------------------------------------------

    @Test
    void theFilterShowsTheMatchingRowsAndKeepsCountOfAll() {
        model.update(List.of(
                process(10, "chrome.exe").build(),
                process(20, "svchost.exe").build(),
                process(30, "Chrome Helper").build()));
        ProcessRow chrome = row(10);
        changes.clear();

        model.filter(ProcessFilter.of("chrome"));

        assertThat(pids()).containsExactly(10L, 30L);
        assertThat(model.total()).isEqualTo(3);
        // The rows that stay are the same rows; only the other one was taken out.
        assertThat(row(10)).isSameAs(chrome);
        assertThat(changes).containsExactly("removed 1");
    }

    @Test
    void takingTheFilterAwayBringsTheSameRowsBack() {
        model.update(List.of(
                process(10, "chrome.exe").build(), process(20, "svchost.exe").build()));
        ProcessRow svchost = row(20);
        model.filter(ProcessFilter.of("chrome"));

        model.filter(ProcessFilter.NONE);

        assertThat(pids()).containsExactly(10L, 20L);
        assertThat(row(20)).isSameAs(svchost);
    }

    @Test
    void aRowThatWasFilteredOutStillGetsTheNewValuesAndIsCurrentWhenItComesBack() {
        model.update(List.of(
                process(10, "chrome.exe").build(),
                process(20, "svchost.exe").cpu(1).build()));
        model.filter(ProcessFilter.of("chrome"));

        model.update(List.of(
                process(10, "chrome.exe").build(),
                process(20, "svchost.exe").cpu(8).build()));
        model.filter(ProcessFilter.NONE);

        assertThat(row(20).snapshot().cpuPercent()).hasValue(8);
    }

    @Test
    void aProcessThatStartsWhileTheFilterIsSetIsShownOnlyIfItMatches() {
        model.filter(ProcessFilter.of("chrome"));
        model.update(List.of(process(10, "chrome.exe").build()));

        model.update(List.of(
                process(10, "chrome.exe").build(),
                process(20, "svchost.exe").build(),
                process(30, "chrome.exe").build()));

        assertThat(pids()).containsExactly(10L, 30L);
        assertThat(model.total()).isEqualTo(3);
    }

    /** A process can change its user while it runs; the filter looks at what the row holds now. */
    @Test
    void aRowLeavesAndComesBackAsItsValuesStopAndStartMatchingTheFilter() {
        model.filter(ProcessFilter.of("alice"));
        model.update(List.of(process(10, "a").user("alice").build()));
        ProcessRow row = row(10);

        model.update(List.of(process(10, "a").user("root").build()));
        assertThat(model.rows()).isEmpty();

        model.update(List.of(process(10, "a").user("alice").build()));
        assertThat(model.rows()).containsExactly(row);
    }

    @Test
    void settingTheSameFilterAgainChangesNothing() {
        model.update(ProcessListings.windows(40, 0));
        model.filter(ProcessFilter.of("chrome"));
        changes.clear();

        model.filter(ProcessFilter.of("  chrome "));

        assertThat(changes).isEmpty();
    }

    // --- Selection -------------------------------------------------------------------------------

    @Test
    void theSelectionStaysOnItsRowThroughListingsSortsAndAFilterThatKeepsIt() {
        model.update(ProcessListings.windows(100, 0));
        ProcessRow selected = row(40);
        model.select(selected);

        model.update(ProcessListings.windows(100, 1));
        model.sortBy(List.of(new SortKey(ProcessColumn.CPU, true)));
        model.update(ProcessListings.windows(100, 2));
        model.filter(ProcessFilter.of(selected.snapshot().name()));
        model.sortBy(List.of());

        assertThat(model.selected()).isSameAs(selected);
        assertThat(model.rows()).contains(selected);
    }

    @Test
    void whenTheSelectedProcessExitsNothingIsSelected() {
        model.update(List.of(
                process(10, "a").build(),
                process(20, "b").build(),
                process(30, "c").build()));
        ProcessRow selected = row(20);
        model.select(selected);

        model.update(List.of(process(10, "a").build(), process(30, "c").build()));

        assertThat(model.selected()).isNull();
        assertThat(model.isRunning(selected)).isFalse();
    }

    /** PID alone would say that the selected process is still there. Its identity says it is not. */
    @Test
    void theSelectionDoesNotPassToAProcessThatTookOverThePid() {
        model.update(List.of(process(10, "a").build(), process(20, "old").build()));
        model.select(row(20));

        model.update(List.of(
                process(10, "a").build(),
                process(20, "new").startedSecondsAfterBoot(9_000).build()));

        assertThat(model.selected()).isNull();
        assertThat(row(20).snapshot().name()).isEqualTo("new");
    }

    @Test
    void whenTheFilterTakesTheSelectedRowOutNothingIsSelectedAndItsProcessStillRuns() {
        model.update(List.of(
                process(10, "chrome.exe").build(), process(20, "svchost.exe").build()));
        ProcessRow selected = row(20);
        model.select(selected);

        model.filter(ProcessFilter.of("chrome"));

        assertThat(model.selected()).isNull();
        assertThat(model.isRunning(selected)).isTrue();

        // It does not come back selected: the selection never appears on its own.
        model.filter(ProcessFilter.NONE);
        assertThat(model.selected()).isNull();
    }

    @Test
    void aRowThatIsNotInTheTableCannotBeSelected() {
        model.update(List.of(
                process(10, "chrome.exe").build(), process(20, "svchost.exe").build()));
        ProcessRow filteredOut = row(20);
        model.filter(ProcessFilter.of("chrome"));
        model.select(filteredOut);
        assertThat(model.selected()).isNull();

        model.filter(ProcessFilter.NONE);
        model.update(List.of(process(10, "chrome.exe").build()));
        model.select(filteredOut);
        assertThat(model.selected()).isNull();
    }

    @Test
    void selectingNothingClearsTheSelection() {
        model.update(List.of(process(10, "a").build()));
        model.select(row(10));

        model.select(null);

        assertThat(model.selected()).isNull();
    }

    private ProcessRow row(long pid) {
        return model.rows().stream()
                .filter(row -> row.identity().pid() == pid)
                .findFirst()
                .orElseThrow();
    }

    private List<Long> pids() {
        return model.rows().stream().map(row -> row.identity().pid()).toList();
    }
}
