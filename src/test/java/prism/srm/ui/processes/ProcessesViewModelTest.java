package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.MIB;
import static prism.srm.ui.processes.ProcessListings.identity;
import static prism.srm.ui.processes.ProcessListings.process;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.LatestValueRelay;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.PageStatus.PageState;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * What the Processes page shows as a whole: its states, its count, and above all the details of
 * the selected process, which are asked for on demand and may be answered late. Needs no JavaFX
 * toolkit.
 */
class ProcessesViewModelTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";
    private static final String DASH = "\u2013";
    private static final String READING = "Reading\u2026";

    /** Every request for details the model made, in order. Nothing is read: a test answers. */
    private final List<ProcessIdentity> asked = new ArrayList<>();

    private final ProcessesViewModel model =
            new ProcessesViewModel(new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, asked::add);

    // --- States of the page ------------------------------------------------------------------

    @Test
    void beforeTheFirstListingThePageSaysThatProcessesAreBeingRead() {
        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LOADING);
        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo("Reading processes\u2026");
        assertThat(model.countProperty().get()).isEmpty();
        assertThat(model.details().titleProperty().get()).isEqualTo("No process selected");
        assertThat(asked).isEmpty();
    }

    @Test
    void aListingMakesThePageLiveAndIsCounted() {
        model.update(ProcessListings.windows(1234, 0));

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LIVE);
        assertThat(model.rows()).hasSize(1234);
        assertThat(model.countProperty().get()).isEqualTo("1,234 processes");
    }

    @Test
    void oneProcessIsCountedInTheSingular() {
        model.update(List.of(process(10, "only").build()));

        assertThat(model.countProperty().get()).isEqualTo("1 process");
    }

    @Test
    void aListingWithoutProcessesSaysThatNoneIsReported() {
        model.update(List.of());

        assertThat(model.whenEmptyProperty().get()).isEqualTo("No process is reported.");
        assertThat(model.countProperty().get()).isEqualTo("0 processes");
    }

    @Test
    void aFilterShowsHowManyOfAllProcessesItLetsThrough() {
        model.update(ProcessListings.windows(394, 0));

        model.filter("chrome");

        assertThat(model.rows()).hasSize(22);
        assertThat(model.countProperty().get()).isEqualTo("22 of 394 processes");
    }

    @Test
    void aFilterThatLeavesNothingSaysSoAndIsNotMistakenForNoProcesses() {
        model.update(ProcessListings.windows(394, 0));

        model.filter("no-such-process");

        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo("No process matches the filter.");
        assertThat(model.countProperty().get()).isEqualTo("0 of 394 processes");
    }

    @Test
    void theCountFollowsTheListingsWhileAFilterIsSet() {
        model.update(ProcessListings.windows(394, 0));
        model.filter("chrome");

        model.update(ProcessListings.windows(200, 1));

        assertThat(model.countProperty().get()).isEqualTo("12 of 200 processes");
    }

    /** Typing in the filter before anything was listed must not say that nothing matches. */
    @Test
    void aFilterTypedBeforeTheFirstListingLeavesThePageReading() {
        model.filter("chrome");

        assertThat(model.whenEmptyProperty().get()).isEqualTo("Reading processes\u2026");
        assertThat(model.countProperty().get()).isEmpty();

        model.update(ProcessListings.windows(394, 0));
        assertThat(model.countProperty().get()).isEqualTo("22 of 394 processes");
    }

    @Test
    void aFailedStartIsSaidAndNoProcessIsShown() {
        model.startupFailed();

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("Monitoring could not start")
                .contains(LOG_FOLDER);
        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo("Unavailable");
    }

    /** Rows left in place would look current. */
    @Test
    void whenProcessMonitoringEndsTheRowsTheSelectionAndTheCountAreTakenAway() {
        model.update(ProcessListings.windows(394, 0));
        model.select(model.rows().get(5));

        model.monitoringStopped();

        assertThat(model.status().errorProperty().get())
                .startsWith("Process monitoring stopped unexpectedly, so no processes are shown.")
                .contains(LOG_FOLDER);
        assertThat(model.rows()).isEmpty();
        assertThat(model.selected()).isNull();
        assertThat(model.countProperty().get()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo("Unavailable");
        // Monitoring ended; the selected process did not exit, and the pane does not say it did.
        assertThat(model.details().titleProperty().get()).isEqualTo("No process selected");
    }

    @Test
    void systemMonitoringThatEndedBeforeAnyListingIsSaidAndAListingThatStillArrivesIsShown() {
        model.processesNotListed();

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("System monitoring stopped unexpectedly before the processes were listed.")
                .contains(LOG_FOLDER);

        model.update(ProcessListings.windows(40, 0));

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LIVE);
        assertThat(model.status().errorProperty().get()).isEmpty();
        assertThat(model.rows()).hasSize(40);
    }

    // --- Details on demand ---------------------------------------------------------------------

    @Test
    void nothingIsAskedForUntilAProcessIsSelected() {
        model.update(ProcessListings.windows(394, 0));
        model.update(ProcessListings.windows(394, 1));
        model.filter("chrome");
        model.sortBy(List.of(new SortKey(ProcessColumn.CPU, true)));

        assertThat(asked).isEmpty();
    }

    @Test
    void selectingAProcessAsksForItsDetailsOnceAndSaysReadingUntilTheyArrive() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));

        model.select(row(20));

        assertThat(asked).containsExactly(identity(20));
        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 20");
        assertThat(model.details().processShownProperty().get()).isTrue();
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);
        assertThat(model.details().path().placeholderProperty().get()).isTrue();
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(READING);
    }

    /** The details are not part of a listing: however many arrive, they are asked for once. */
    @Test
    void listingsSortsAndSelectingTheSameRowAgainAskNothingMore() {
        model.update(ProcessListings.windows(394, 0));
        ProcessRow selected = model.rows().get(7);
        model.select(selected);

        for (int tick = 1; tick <= 50; tick++) {
            model.update(ProcessListings.windows(394, tick));
        }
        model.sortBy(List.of(new SortKey(ProcessColumn.MEMORY, true)));
        model.select(selected);

        assertThat(asked).containsExactly(selected.identity());
    }

    @Test
    void theAnswerForTheSelectedProcessIsShown() {
        model.update(List.of(process(10, "a.exe").build()));
        model.select(row(10));

        model.showDetails(read(identity(10), "C:\\bin\\a.exe", "a.exe --flag"));

        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\a.exe");
        assertThat(model.details().path().placeholderProperty().get()).isFalse();
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo("a.exe --flag");
    }

    /** The user moved on while the read ran: the late answer must not appear under another process. */
    @Test
    void anAnswerForAProcessThatIsNoLongerSelectedIsDropped() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(10));
        model.select(row(20));

        model.showDetails(read(identity(10), "C:\\bin\\a.exe", "a.exe"));

        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(READING);

        model.showDetails(read(identity(20), "C:\\bin\\b.exe", "b.exe"));
        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\b.exe");
    }

    /**
     * The selected process exited and another one got its PID before the answer arrived. The
     * answer is for the old identity. It must not be shown for the new process, selected or not.
     */
    @Test
    void anAnswerForAnEarlierProcessOfTheSamePidIsNeverShownForTheNewOne() {
        model.update(List.of(process(20, "old.exe").build()));
        model.select(row(20));
        ProcessIdentity oldIdentity = row(20).identity();

        model.update(
                List.of(process(20, "new.exe").startedSecondsAfterBoot(9_000).build()));
        // A row with that PID is in the table again, and still the pane says the process exited.
        assertThat(model.selected()).isNull();
        assertThat(model.details().titleProperty().get()).isEqualTo("old.exe");
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 20 has exited");
        model.select(row(20));
        ProcessIdentity newIdentity = row(20).identity();
        assertThat(asked).containsExactly(oldIdentity, newIdentity);

        model.showDetails(read(oldIdentity, "C:\\bin\\old.exe", "old.exe --secret"));

        assertThat(model.details().titleProperty().get()).isEqualTo("new.exe");
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(READING);

        model.showDetails(read(newIdentity, "C:\\bin\\new.exe", "new.exe"));
        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\new.exe");
    }

    /**
     * The likeliest late answer after a PID changed hands: the old process is gone, so its read
     * says so. Shown for the new holder of the PID, it would call a running process exited.
     */
    @Test
    void aLateAnswerThatTheOldProcessIsGoneOrCouldNotBeReadIsNotShownForTheNewHolderOfThePid() {
        model.update(List.of(process(20, "old.exe").build()));
        model.select(row(20));
        ProcessIdentity oldIdentity = row(20).identity();
        model.update(
                List.of(process(20, "new.exe").startedSecondsAfterBoot(9_000).build()));
        model.select(row(20));

        model.showDetails(ProcessDetailsResult.gone(oldIdentity));
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(READING);

        model.showDetails(ProcessDetailsResult.failed(oldIdentity));
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(READING);
    }

    @Test
    void aLateAnswerOfAnyKindForAnotherProcessOrForNoSelectionChangesNothing() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(10));
        model.select(row(20));

        model.showDetails(ProcessDetailsResult.gone(identity(10)));
        model.showDetails(ProcessDetailsResult.failed(identity(10)));
        assertThat(model.details().path().textProperty().get()).isEqualTo(READING);

        model.select(null);
        model.showDetails(ProcessDetailsResult.gone(identity(20)));
        model.showDetails(ProcessDetailsResult.failed(identity(20)));
        assertThat(model.details().path().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(DASH);
    }

    /** Path and command line are asked for once; the listings that follow must not wipe the answer. */
    @Test
    void theAnswerStaysThroughTheListingsThatFollowAndNothingIsAskedAgain() {
        model.update(List.of(process(10, "a.exe").cpu(1).build()));
        model.select(row(10));
        model.showDetails(read(identity(10), "C:\\bin\\a.exe", "a.exe --flag"));

        model.update(List.of(process(10, "a.exe").cpu(7).memory(999).build()));
        model.update(List.of(process(10, "a.exe").cpu(2).io(4096, 0).build()));

        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\a.exe");
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo("a.exe --flag");
        assertThat(model.details().ioRead().textProperty().get()).isEqualTo("4.0 KiB/s");
        assertThat(asked).containsExactly(identity(10));
    }

    /**
     * The results travel to the JavaFX Application Thread through a relay that keeps the newest
     * only. Reads are answered in the order they were asked for, so the newest result is the one
     * for the newest selection, and dropping the older one loses nothing the pane would show.
     */
    @Test
    void ofTwoAnswersThatArriveTogetherTheNewestIsTheOneForTheSelectedProcess() {
        List<Runnable> waiting = new ArrayList<>();
        LatestValueRelay<ProcessDetailsResult> relay = new LatestValueRelay<>(waiting::add, model::showDetails);
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(10));
        model.select(row(20));

        relay.offer(read(identity(10), "C:\\bin\\a.exe", "a.exe"));
        relay.offer(read(identity(20), "C:\\bin\\b.exe", "b.exe"));
        assertThat(waiting).hasSize(1);
        waiting.forEach(Runnable::run);

        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\b.exe");
    }

    @Test
    void anAnswerThatArrivesWhenNothingIsSelectedIsDropped() {
        model.update(List.of(process(10, "a.exe").build()));
        model.select(row(10));
        model.select(null);

        model.showDetails(read(identity(10), "C:\\bin\\a.exe", "a.exe"));

        assertThat(model.details().titleProperty().get()).isEqualTo("No process selected");
        assertThat(model.details().path().textProperty().get()).isEqualTo(DASH);
    }

    @Test
    void anAnswerThatArrivesAfterTheProcessExitedIsDropped() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(20));
        model.update(List.of(process(10, "a.exe").build()));

        model.showDetails(read(identity(20), "C:\\bin\\b.exe", "b.exe"));

        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 20 has exited");
        assertThat(model.details().path().textProperty().get()).isEqualTo(DASH);
    }

    @Test
    void comingBackToAProcessAsksAgainAndAnEarlierAnswerForItIsStillItsAnswer() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(10));
        model.select(row(20));
        model.select(row(10));
        assertThat(asked).containsExactly(identity(10), identity(20), identity(10));

        // The answer to the first question arrives now: same identity, so it is right.
        model.showDetails(read(identity(10), "C:\\bin\\a.exe", "a.exe"));

        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\a.exe");
    }

    /**
     * Found on Windows: a script handed to a shell on its command line has line breaks. Shown as
     * they are, the one line of the pane became seven and the table lost their height.
     */
    @Test
    void aCommandLineWithLineBreaksAndTabsIsShownOnOneLine() {
        model.update(List.of(process(10, "pwsh").build()));
        model.select(row(10));

        model.showDetails(
                read(identity(10), "C:\\bin\\pwsh.exe", "pwsh -Command $a = 1\r\n$b = 2\n\twhile ($true) {}"));

        assertThat(model.details().commandLine().textProperty().get())
                .isEqualTo("pwsh -Command $a = 1  $b = 2  while ($true) {}")
                .doesNotContain("\n")
                .doesNotContain("\r")
                .doesNotContain("\t");
        assertThat(model.details().path().textProperty().get()).isEqualTo("C:\\bin\\pwsh.exe");
    }

    /** A text without such characters is the very text the system gave: nothing is copied or changed. */
    @Test
    void aCommandLineWithoutLineBreaksIsShownAsItIs() {
        String commandLine = "\"C:\\Program Files\\App\\app.exe\"  --flag=\u00e7 --name \"two  blanks\"";

        assertThat(ProcessDetailsModel.onOneLine(commandLine)).isSameAs(commandLine);
        assertThat(ProcessDetailsModel.onOneLine("")).isEmpty();
    }

    @Test
    void aPathOrCommandLineTheSystemDoesNotGiveSaysSoAndIsNotEmpty() {
        model.update(List.of(process(4, "System").build()));
        model.select(row(4));

        model.showDetails(
                ProcessDetailsResult.read(new ProcessDetails(identity(4), Optional.empty(), Optional.of("cmd"))));

        assertThat(model.details().path().textProperty().get())
                .isEqualTo("Not available: the system does not report it, or access was denied.");
        assertThat(model.details().path().placeholderProperty().get()).isTrue();
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo("cmd");
        assertThat(model.details().commandLine().placeholderProperty().get()).isFalse();
    }

    @Test
    void aProcessThatVanishedBeforeTheReadSaysThatItHasExited() {
        model.update(List.of(process(10, "a.exe").build()));
        model.select(row(10));

        model.showDetails(ProcessDetailsResult.gone(identity(10)));

        assertThat(model.details().path().textProperty().get()).isEqualTo("The process has exited.");
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo("The process has exited.");
        assertThat(model.details().path().placeholderProperty().get()).isTrue();
    }

    @Test
    void aReadThatFailedSaysSoAndNamesTheLogFolder() {
        model.update(List.of(process(10, "a.exe").build()));
        model.select(row(10));

        model.showDetails(ProcessDetailsResult.failed(identity(10)));

        assertThat(model.details().path().textProperty().get())
                .isEqualTo("Could not be read. The log file in " + LOG_FOLDER + " has the details.");
        assertThat(model.details().commandLine().placeholderProperty().get()).isTrue();
    }

    // --- The values of the selected process -------------------------------------------------------

    @Test
    void theDetailsShowTheValuesThatHaveNoColumnAndFollowTheListings() {
        model.update(List.of(process(10, "a.exe")
                .parent(4)
                .privateMemory(16 * MIB)
                .virtualSize(2048 * MIB)
                .io(1024, 2048)
                .build()));
        model.select(row(10));

        assertThat(model.details().started().textProperty().get()).isEqualTo("2026-10-06 06:00:10");
        assertThat(model.details().parentPid().textProperty().get()).isEqualTo("4");
        assertThat(model.details().privateMemory().textProperty().get()).isEqualTo("16.0 MiB");
        assertThat(model.details().virtualSize().textProperty().get()).isEqualTo("2.0 GiB");
        assertThat(model.details().ioRead().textProperty().get()).isEqualTo("1.0 KiB/s");
        assertThat(model.details().ioWrite().textProperty().get()).isEqualTo("2.0 KiB/s");

        model.update(List.of(process(10, "a.exe")
                .parent(4)
                .privateMemory(32 * MIB)
                .virtualSize(2048 * MIB)
                .io(0, 0)
                .build()));

        assertThat(model.details().privateMemory().textProperty().get()).isEqualTo("32.0 MiB");
        assertThat(model.details().ioRead().textProperty().get()).isEqualTo("0 B/s");
        assertThat(asked).hasSize(1);
    }

    @Test
    void valuesTheSystemDoesNotGiveAreDashesInTheDetailsToo() {
        model.update(List.of(process(588, "").nothingElse().build()));
        model.select(row(588));

        assertThat(model.details().titleProperty().get()).isEqualTo(DASH);
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 588");
        assertThat(model.details().parentPid().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().parentPid().placeholderProperty().get()).isTrue();
        assertThat(model.details().privateMemory().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().virtualSize().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().ioRead().textProperty().get()).isEqualTo(DASH);
        // The start time is part of what makes it this process: it is always there.
        assertThat(model.details().started().textProperty().get()).isEqualTo("2026-10-06 06:09:48");
        assertThat(model.details().started().placeholderProperty().get()).isFalse();
    }

    // --- Selection leaves ---------------------------------------------------------------------------

    @Test
    void whenTheSelectedProcessExitsThePaneSaysSoAndShowsNoValuesOfIt() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(20));
        model.showDetails(read(identity(20), "C:\\bin\\b.exe", "b.exe"));

        model.update(List.of(process(10, "a.exe").build()));

        assertThat(model.selected()).isNull();
        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 20 has exited");
        assertThat(model.details().processShownProperty().get()).isFalse();
        assertThat(model.details().path().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().commandLine().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().started().textProperty().get()).isEqualTo(DASH);
        assertThat(model.details().privateMemory().textProperty().get()).isEqualTo(DASH);
    }

    @Test
    void aProcessWithoutANameThatExitsIsNamedByItsPid() {
        model.update(List.of(process(588, "").build()));
        model.select(row(588));

        model.update(List.of());

        assertThat(model.details().titleProperty().get()).isEqualTo("PID 588 has exited");
        assertThat(model.details().pidProperty().get()).isEmpty();
    }

    /** The note stays until the user selects something: a later listing does not wipe it. */
    @Test
    void theNoteThatTheProcessExitedStaysThroughLaterListings() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        model.select(row(20));
        model.update(List.of(process(10, "a.exe").build()));

        model.update(List.of(process(10, "a.exe").cpu(5).build()));
        model.select(null);

        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().pidProperty().get()).isEqualTo("PID 20 has exited");

        model.select(row(10));
        assertThat(model.details().titleProperty().get()).isEqualTo("a.exe");
    }

    @Test
    void whenTheFilterTakesTheSelectedRowOutThePaneShowsNothingAndDoesNotSayItExited() {
        model.update(List.of(
                process(10, "chrome.exe").build(), process(20, "svchost.exe").build()));
        model.select(row(20));

        model.filter("chrome");

        assertThat(model.selected()).isNull();
        assertThat(model.details().titleProperty().get()).isEqualTo("No process selected");
        assertThat(model.details().path().textProperty().get()).isEqualTo(DASH);
    }

    /** An exited row must not go on feeding the pane, nor a deselected one. */
    @Test
    void aRowThatIsNoLongerShownInThePaneDoesNotChangeIt() {
        model.update(List.of(process(10, "a.exe").build(), process(20, "b.exe").build()));
        ProcessRow first = row(10);
        model.select(first);
        model.select(row(20));

        model.update(List.of(
                process(10, "a-renamed.exe").privateMemory(999 * MIB).build(),
                process(20, "b.exe").build()));

        assertThat(model.details().titleProperty().get()).isEqualTo("b.exe");
        assertThat(model.details().privateMemory().textProperty().get()).isEqualTo("16.0 MiB");
    }

    private ProcessRow row(long pid) {
        return model.rows().stream()
                .filter(row -> row.identity().pid() == pid)
                .findFirst()
                .orElseThrow();
    }

    private static ProcessDetailsResult read(ProcessIdentity identity, String path, String commandLine) {
        return ProcessDetailsResult.read(new ProcessDetails(identity, Optional.of(path), Optional.of(commandLine)));
    }
}
