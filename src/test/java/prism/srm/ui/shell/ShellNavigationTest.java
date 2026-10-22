package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.OverviewPage;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformancePage;
import prism.srm.ui.performance.PerformanceSnapshots;
import prism.srm.ui.processes.ProcessListings;
import prism.srm.ui.processes.ProcessesPage;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * What selecting a destination does to the window, read from a real scene: which page is in the
 * page area, what stays as it is, and which snapshot the page shows.
 */
@Tag("ui-snapshot")
class ShellNavigationTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    @Test
    void overviewProcessesAndPerformanceHaveAPageAndTheOtherDestinationsAreStillDisabled() {
        List<Object> seen = inShell(shell -> {
            List<Object> facts = new ArrayList<>();
            facts.add(page(shell).getClass());
            for (Destination destination : Destination.values()) {
                facts.add(sidebar(shell).entry(destination).isDisable());
            }
            return facts;
        });

        assertThat(seen).containsExactly(OverviewPage.class, false, false, false, true, true, true, true);
    }

    @Test
    void selectingProcessesPutsItsPageIntoThePageAreaAndComingBackShowsTheSamePage() {
        List<Object> seen = inShell(shell -> {
            select(shell, Destination.PROCESSES);
            Node processes = page(shell);
            select(shell, Destination.PERFORMANCE);
            select(shell, Destination.PROCESSES);
            return List.of(processes.getClass(), page(shell) == processes, selected(shell));
        });

        assertThat(seen).containsExactly(ProcessesPage.class, true, Destination.PROCESSES);
    }

    @Test
    void selectingPerformancePutsItsPageIntoThePageAreaAndOverviewBringsTheFirstPageBack() {
        List<Object> seen = inShell(shell -> {
            Node overview = page(shell);
            select(shell, Destination.PERFORMANCE);
            Node performance = page(shell);
            select(shell, Destination.OVERVIEW);
            Node overviewAgain = page(shell);
            select(shell, Destination.PERFORMANCE);
            return List.of(
                    performance.getClass(), overviewAgain == overview, page(shell) == performance, selected(shell));
        });

        // The pages are built once: going back shows the same nodes, not new ones.
        assertThat(seen).containsExactly(PerformancePage.class, true, true, Destination.PERFORMANCE);
    }

    /** Navigation, page area and status bar are the same nodes after a change of page. */
    @Test
    void changingThePageRebuildsNothingOfTheShell() {
        List<Boolean> same = inShell(shell -> {
            Node left = shell.getLeft();
            Node center = shell.getCenter();
            Node bottom = shell.getBottom();
            select(shell, Destination.PERFORMANCE);
            select(shell, Destination.OVERVIEW);
            return List.of(shell.getLeft() == left, shell.getCenter() == center, shell.getBottom() == bottom);
        });

        assertThat(same).containsExactly(true, true, true);
    }

    /** The arrow key goes through the destinations that have a page, in their order. */
    @Test
    void theArrowKeyInTheNavigationOpensTheProcessesPageAndThenThePerformancePage() {
        List<Object> shown = inShell(shell -> {
            List<Object> pages = new ArrayList<>();
            for (Destination from : new Destination[] {Destination.OVERVIEW, Destination.PROCESSES}) {
                Node entry = sidebar(shell).entry(from);
                Event.fireEvent(entry, key(KeyEvent.KEY_PRESSED, KeyCode.DOWN));
                Event.fireEvent(entry, key(KeyEvent.KEY_RELEASED, KeyCode.DOWN));
                pages.add(page(shell).getClass());
            }
            return pages;
        });

        assertThat(shown).containsExactly(ProcessesPage.class, PerformancePage.class);
    }

    /** The page was not on screen while the listings arrived; it must still open with the newest. */
    @Test
    void theProcessesPageOpensWithTheNewestListingAlthoughItWasNotShownWhenThatArrived() {
        List<Object> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showProcesses(ProcessListings.windows(394, 0));
            shell.showProcesses(ProcessListings.windows(120, 1));
            select(shell, Destination.PROCESSES);
            settle(shell);
            return List.of(rows(shell), visibleTexts(shell).contains("120 processes"));
        });

        assertThat(seen).containsExactly(120, true);
    }

    @Test
    void theProcessesPageLeftAndComeBackToShowsTheNewestListingNotTheOneItLastHad() {
        List<Object> seen = inShell(shell -> {
            select(shell, Destination.PROCESSES);
            shell.showProcesses(ProcessListings.windows(394, 0));
            select(shell, Destination.OVERVIEW);
            shell.showProcesses(ProcessListings.windows(50, 1));
            select(shell, Destination.PROCESSES);
            settle(shell);
            return List.of(rows(shell), visibleTexts(shell).contains("50 processes"));
        });

        assertThat(seen).containsExactly(50, true);
    }

    /** A listing changes nothing of the pages of system metrics, and a snapshot nothing of the processes. */
    @Test
    void systemSnapshotsAndListingsOfProcessesEachGoToTheirOwnPages() {
        List<Object> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showProcesses(ProcessListings.windows(394, 0));
            settle(shell);
            boolean overviewHasItsValue = visibleTexts(shell).contains("12.4");
            select(shell, Destination.PROCESSES);
            shell.showSnapshot(Snapshots.extreme());
            settle(shell);
            return List.of(overviewHasItsValue, rows(shell), statusText(shell));
        });

        assertThat(seen).containsExactly(true, 394, "Monitoring");
    }

    @Test
    void whenProcessMonitoringEndsTheProcessesPageSaysSoAndShowsNoProcessesWhicheverPageIsOnScreen() {
        List<Object> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showProcesses(ProcessListings.windows(394, 0));
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            // The Overview goes on; it is the Processes page that has nothing to show.
            boolean overviewGoesOn = "none".equals(message(shell));
            select(shell, Destination.PROCESSES);
            settle(shell);
            return List.of(overviewGoesOn, message(shell), rows(shell), statusText(shell));
        });

        assertThat(seen.get(0)).isEqualTo(true);
        assertThat((String) seen.get(1))
                .startsWith("Process monitoring stopped unexpectedly, so no processes are shown.")
                .contains(LOG_FOLDER);
        assertThat(seen.get(2)).isEqualTo(0);
        assertThat((String) seen.get(3)).startsWith("Monitoring degraded");
    }

    /** A listing that was on its way when the monitor ended must not bring the rows back. */
    @Test
    void aListingThatArrivesAfterProcessMonitoringEndedIsNotShown() {
        List<Object> seen = inShell(shell -> {
            select(shell, Destination.PROCESSES);
            shell.showProcesses(ProcessListings.windows(394, 0));
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            shell.showProcesses(ProcessListings.windows(394, 1));
            settle(shell);
            int onScreen = rows(shell);
            select(shell, Destination.OVERVIEW);
            select(shell, Destination.PROCESSES);
            settle(shell);
            return List.of(onScreen, rows(shell), message(shell).startsWith("Process monitoring stopped"));
        });

        assertThat(seen).containsExactly(0, 0, true);
    }

    /**
     * The process listing does not depend on the system metrics once it runs: the news that
     * system monitoring ended must take neither the rows nor the selection off the page, also
     * before the next listing arrives.
     */
    @Test
    void whenOnlySystemMonitoringEndsTheProcessesPageKeepsItsRowsAndItsSelection() {
        List<Object> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            select(shell, Destination.PROCESSES);
            shell.showProcesses(ProcessListings.windows(394, 0));
            settle(shell);
            TableView<?> table = (TableView<?>) page(shell).lookup(".table-view");
            table.getSelectionModel().select(5);
            Object selected = table.getSelectionModel().getSelectedItem();

            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            settle(shell);
            List<Object> facts = new ArrayList<>();
            facts.add(message(shell));
            facts.add(rows(shell));
            facts.add(table.getSelectionModel().getSelectedItem() == selected);

            shell.showProcesses(ProcessListings.windows(390, 1));
            settle(shell);
            facts.add(rows(shell));
            return facts;
        });

        assertThat(seen).containsExactly("none", 394, true, 390);
    }

    /**
     * Process monitoring starts with a system snapshot. If system monitoring ends before any
     * process was listed, the page must not say "Reading processes" for ever.
     */
    @Test
    void whenSystemMonitoringEndsBeforeAnyListingTheProcessesPageSaysThatItMayGetNone() {
        List<Object> seen = inShell(shell -> {
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            select(shell, Destination.PROCESSES);
            settle(shell);
            String before = message(shell);
            // The monitor had started after all, and its first listing arrives.
            shell.showProcesses(ProcessListings.windows(40, 0));
            settle(shell);
            return List.of(before, message(shell), rows(shell));
        });

        assertThat((String) seen.get(0))
                .startsWith("System monitoring stopped unexpectedly before the processes were listed.")
                .contains(LOG_FOLDER);
        assertThat(seen.get(1)).isEqualTo("none");
        assertThat(seen.get(2)).isEqualTo(40);
    }

    @Test
    void aFailedStartIsSaidOnTheProcessesPageToo() {
        List<Object> seen = inShell(shell -> {
            shell.showFailures(Set.of(MonitoringFailure.STARTUP));
            shell.showProcesses(ProcessListings.windows(40, 0));
            select(shell, Destination.PROCESSES);
            settle(shell);
            return List.of(message(shell), rows(shell));
        });

        assertThat((String) seen.get(0))
                .startsWith("Monitoring could not start")
                .contains(LOG_FOLDER);
        assertThat(seen.get(1)).isEqualTo(0);
    }

    /** The answer to a question of the Processes page reaches it also while another page is shown. */
    @Test
    void theDetailsOfAProcessReachTheProcessesPageWhileAnotherPageIsOnScreen() {
        List<ProcessIdentity> asked = new ArrayList<>();
        List<String> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new Shell(
                        new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, asked::add),
                root -> {
                    Shell shell = (Shell) root;
                    select(shell, Destination.PROCESSES);
                    shell.showProcesses(ProcessListings.windows(40, 0));
                    settle(shell);
                    ((TableView<?>) page(shell).lookup(".table-view"))
                            .getSelectionModel()
                            .select(0);
                    select(shell, Destination.OVERVIEW);
                    shell.showProcessDetails(ProcessDetailsResult.read(new ProcessDetails(
                            asked.get(0), Optional.of("C:/bin/first.exe"), Optional.of("first.exe --flag"))));
                    select(shell, Destination.PROCESSES);
                    settle(shell);
                    return visibleTexts(shell);
                });

        assertThat(asked).hasSize(1);
        assertThat(seen).contains("C:/bin/first.exe", "first.exe --flag");
    }

    /** A page that was not on screen got no snapshots; it must still open with the newest one. */
    @Test
    void aPageOpensWithTheNewestSnapshotAlthoughItWasNotShownWhenThatArrived() {
        List<String> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showSnapshot(PerformanceSnapshots.extreme());
            select(shell, Destination.PERFORMANCE);
            settle(shell);
            return visibleTexts(shell);
        });

        // The total of the newest snapshot, and nothing that says the page is still being read.
        assertThat(seen).contains("100.0").doesNotContain("Reading\u2026");
    }

    @Test
    void aPageLeftAndComeBackToShowsTheNewestSnapshotNotTheOneItLastHad() {
        List<String> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            select(shell, Destination.PERFORMANCE);
            shell.showSnapshot(Snapshots.extreme());
            select(shell, Destination.OVERVIEW);
            settle(shell);
            return visibleTexts(shell);
        });

        // 12.4 % was what the Overview showed when it was left; 100.0 % is current.
        assertThat(seen).contains("100.0").doesNotContain("12.4");
    }

    /** The status bar follows the snapshots whichever page is on screen. */
    @Test
    void theStatusBarSaysMonitoringWhenTheFirstSnapshotArrivesOnThePerformancePage() {
        List<String> status = inShell(shell -> {
            select(shell, Destination.PERFORMANCE);
            String before = statusText(shell);
            shell.showSnapshot(PerformanceSnapshots.live());
            return List.of(before, statusText(shell));
        });

        assertThat(status).containsExactly("Starting monitoring\u2026", "Monitoring");
    }

    /** The page that is not on screen is told as well, so it cannot open with stale values. */
    @Test
    void whenSystemMonitoringEndsBothPagesSaySoWhicheverIsOnScreen() {
        List<String> messages = inShell(shell -> {
            shell.showSnapshot(PerformanceSnapshots.live());
            select(shell, Destination.PERFORMANCE);
            shell.showSnapshot(PerformanceSnapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            settle(shell);
            List<String> seen = new ArrayList<>();
            seen.add(message(shell));
            seen.add(String.valueOf(visibleTexts(shell).contains("12.4")));
            select(shell, Destination.OVERVIEW);
            settle(shell);
            seen.add(message(shell));
            seen.add(String.valueOf(visibleTexts(shell).contains("12.4")));
            return seen;
        });

        assertThat(messages.get(0))
                .startsWith("System monitoring stopped unexpectedly")
                .contains(LOG_FOLDER);
        assertThat(messages.get(1)).isEqualTo("false");
        assertThat(messages.get(2))
                .startsWith("System monitoring stopped unexpectedly")
                .contains(LOG_FOLDER);
        assertThat(messages.get(3)).isEqualTo("false");
    }

    /**
     * A snapshot from before the end of monitoring must not come back when a page is opened, and
     * one that arrives late must not either.
     */
    @Test
    void afterSystemMonitoringEndedNoPageOpensWithValues() {
        List<String> seen = inShell(shell -> {
            shell.showSnapshot(PerformanceSnapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            shell.showSnapshot(PerformanceSnapshots.live());
            select(shell, Destination.PERFORMANCE);
            settle(shell);
            return visibleTexts(shell);
        });

        assertThat(seen).contains("Unavailable").doesNotContain("12.4");
    }

    @Test
    void aFailedStartIsSaidOnThePerformancePageToo() {
        String message = inShell(shell -> {
            shell.showFailures(Set.of(MonitoringFailure.STARTUP));
            select(shell, Destination.PERFORMANCE);
            settle(shell);
            return message(shell);
        });

        assertThat(message).startsWith("Monitoring could not start").contains(LOG_FOLDER);
    }

    private static <T> T inShell(Function<Shell, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new Shell(
                        new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {}),
                root -> scenario.apply((Shell) root));
    }

    private static Sidebar sidebar(Shell shell) {
        return (Sidebar) shell.getLeft();
    }

    private static Destination selected(Shell shell) {
        return sidebar(shell).selected();
    }

    private static void select(Shell shell, Destination destination) {
        sidebar(shell).entry(destination).fire();
    }

    private static Node page(Shell shell) {
        return ((ScrollPane) shell.getCenter()).getContent();
    }

    /** The number of rows of the process table of the page on screen. */
    private static int rows(Shell shell) {
        return ((TableView<?>) page(shell).lookup(".table-view")).getItems().size();
    }

    private static String statusText(Shell shell) {
        return ((Label) shell.lookup(".status-text")).getText();
    }

    /** The sentence at the top of the page on screen, or "none". */
    private static String message(Shell shell) {
        Label message = (Label) page(shell).lookup(".page-error");
        return message.isVisible() ? message.getText() : "none";
    }

    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
    }

    /** The texts of the labels a user can see on the page on screen. */
    private static List<String> visibleTexts(Shell shell) {
        List<String> texts = new ArrayList<>();
        collectVisibleTexts(page(shell), texts);
        return texts;
    }

    private static void collectVisibleTexts(Node node, List<String> texts) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Label label) {
            texts.add(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectVisibleTexts(child, texts);
            }
        }
    }

    private static KeyEvent key(EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, "", "", code, false, false, false, false);
    }
}
