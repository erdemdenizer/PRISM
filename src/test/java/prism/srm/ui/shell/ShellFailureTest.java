package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MonitoringFailure;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * What the window shows when a monitor has ended, read from a real scene: the status line, the
 * message on the Overview, and whether values are still on the page.
 */
@Tag("ui-snapshot")
class ShellFailureTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    /** What a user can read in the window. */
    private record Seen(
            String status, boolean statusIsAFailure, String statusTooltip, String message, long unavailable) {}

    @Test
    void whileBothMonitorsRunTheStatusIsMonitoringAndNothingIsMarkedAsFailed() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("Monitoring");
        assertThat(seen.statusIsAFailure()).isFalse();
        assertThat(seen.statusTooltip()).isNull();
        assertThat(seen.message()).isNull();
        assertThat(seen.unavailable()).isZero();
    }

    /** The Overview has nothing to do with the process listing: it stays as it is. */
    @Test
    void whenProcessMonitoringEndsTheOverviewStaysAndTheStatusSaysDegraded() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            return read(shell);
        });

        assertThat(seen.status())
                .isEqualTo(
                        "Monitoring degraded: process monitoring stopped unexpectedly. Restart Prism to restore it.");
        assertThat(seen.statusIsAFailure()).isTrue();
        assertThat(seen.statusTooltip()).contains(LOG_FOLDER);
        assertThat(seen.message()).isNull();
        assertThat(seen.unavailable()).isZero();
    }

    /** The next snapshot must not put "Monitoring" back. */
    @Test
    void degradedStaysDegradedWhileSnapshotsKeepArriving() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            shell.showSnapshot(Snapshots.extreme());
            shell.showSnapshot(Snapshots.live());
            return read(shell);
        });

        assertThat(seen.status())
                .isEqualTo(
                        "Monitoring degraded: process monitoring stopped unexpectedly. Restart Prism to restore it.");
        assertThat(seen.unavailable()).isZero();
    }

    @Test
    void whenSystemMonitoringEndsTheOverviewSaysSoAndShowsNoValues() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("System monitoring stopped unexpectedly");
        assertThat(seen.statusIsAFailure()).isTrue();
        assertThat(seen.message())
                .startsWith("System monitoring stopped unexpectedly")
                .contains(LOG_FOLDER);
        // Four panels and three facts.
        assertThat(seen.unavailable()).isEqualTo(7);
    }

    /**
     * A snapshot was already on its way to the JavaFX Application Thread when the monitor ended,
     * and arrives after the news. It must not bring the values back.
     */
    @Test
    void aSnapshotThatArrivesAfterSystemMonitoringEndedIsNotShown() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            shell.showSnapshot(Snapshots.live());
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("System monitoring stopped unexpectedly");
        assertThat(seen.message()).startsWith("System monitoring stopped unexpectedly");
        assertThat(seen.unavailable()).isEqualTo(7);
    }

    @Test
    void whenBothMonitorsHaveEndedTheStatusSaysThatMonitoringStopped() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            shell.showFailures(
                    Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED, MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("Monitoring stopped unexpectedly");
        assertThat(seen.message()).startsWith("System monitoring stopped unexpectedly");
    }

    /** What has failed stays failed, also if a later message names less than an earlier one. */
    @Test
    void aFailureOnceShownIsNotTakenBack() {
        Seen seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(
                    Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED, MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            shell.showFailures(Set.of());
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("Monitoring stopped unexpectedly");
        assertThat(seen.unavailable()).isEqualTo(7);
    }

    @Test
    void tellingTheShellTheSameFailureAgainChangesNothing() {
        List<Seen> seen = inShell(shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            Seen first = read(shell);
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            return List.of(first, read(shell));
        });

        assertThat(seen.get(1)).isEqualTo(seen.get(0));
    }

    @Test
    void aFailedStartIsSaidAsBefore() {
        Seen seen = inShell(shell -> {
            shell.showFailures(Set.of(MonitoringFailure.STARTUP));
            return read(shell);
        });

        assertThat(seen.status()).isEqualTo("Monitoring could not start");
        assertThat(seen.statusIsAFailure()).isTrue();
        assertThat(seen.message()).startsWith("Monitoring could not start");
        assertThat(seen.unavailable()).isEqualTo(7);
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

    private static Seen read(Shell shell) {
        shell.applyCss();
        shell.layout();
        Label status = (Label) shell.lookup(".status-text");
        Label message = (Label) shell.lookup(".page-error");
        List<Label> labels = new ArrayList<>();
        collectVisibleLabels(shell, labels);
        return new Seen(
                status.getText(),
                status.getPseudoClassStates().stream()
                        .anyMatch(state -> state.getPseudoClassName().equals("failed")),
                status.getTooltip() == null ? null : status.getTooltip().getText(),
                message.isVisible() ? message.getText() : null,
                labels.stream()
                        .filter(label -> "Unavailable".equals(label.getText()))
                        .count());
    }

    /** Labels a user can see: a hidden ancestor hides what is inside it. */
    private static void collectVisibleLabels(Node node, List<Label> labels) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Label label) {
            labels.add(label);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectVisibleLabels(child, labels);
            }
        }
    }
}
