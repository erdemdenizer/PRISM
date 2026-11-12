package prism.srm.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.Typefaces;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.shell.Shell;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Opens the window with a forced theme for a person to look at, since there is no switch in
 * the user interface before the Settings page: {@code ./mvnw -q -Pui-snapshots test
 * -Dtest=ThemeProbe -Dprobe.theme=DARK} (or LIGHT, or SYSTEM) shows the shell for the number of
 * seconds in {@code -Dprobe.seconds} (default 20) and closes it. Change the appearance of the
 * operating system meanwhile: a forced mode must not follow it, SYSTEM must.
 *
 * <p>Not a test of anything: it only opens the window, and it runs only when asked for.
 */
@Tag("ui-snapshot")
class ThemeProbe {

    @Test
    void showsTheWindowInTheModeAskedFor() throws InterruptedException {
        String mode = System.getProperty("probe.theme");
        assumeTrue(mode != null, "set -Dprobe.theme=SYSTEM|LIGHT|DARK");
        int seconds = Integer.getInteger("probe.seconds", 20);
        CountDownLatch closed = new CountDownLatch(1);
        SnapshotHarness.inspect(Theme.LIGHT, 16, 16, javafx.scene.layout.StackPane::new, ignored -> {
            Stage stage = new Stage();
            Shell shell = new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, 60, "C:\\logs", identity -> {});
            shell.showFailures(java.util.Set.of(MonitoringFailure.STARTUP));
            MainWindow.prepare(stage, shell, Typefaces.load().bundled());
            ThemeController theme = new ThemeController(stage.getScene());
            theme.modeProperty().set(ThemeMode.valueOf(mode));
            stage.setTitle(stage.getTitle() + " (theme probe: " + mode + ")");
            stage.setOnHidden(event -> {
                theme.close();
                closed.countDown();
            });
            stage.show();
            Thread closer = new Thread(() -> {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.runLater(stage::close);
            });
            closer.setDaemon(true);
            closer.start();
            return List.of();
        });
        closed.await(seconds + 10L, TimeUnit.SECONDS);
    }
}
