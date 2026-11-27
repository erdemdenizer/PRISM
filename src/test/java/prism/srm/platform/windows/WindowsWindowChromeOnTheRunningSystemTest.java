package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome.Result;

/**
 * The real native path on a shown stage (ADR-0019): the build check, the lookup of the stage's window by
 * the Glass class name and its title, and the four attributes set and reset. The other tests use a fake;
 * a JavaFX update that renames the window class shows here as NOT_FOUND. Needs a display, so it runs with
 * the UI snapshots ({@code -Pui-snapshots}), on Windows 11 only. It prints how long one attempt took on
 * the JavaFX Application Thread.
 */
@Tag("ui-snapshot")
@EnabledOnOs(OS.WINDOWS)
class WindowsWindowChromeOnTheRunningSystemTest {

    private static final ChromeColors DARK = new ChromeColors(true, 0x16181D, 0xE6E8EE, 0x2A2E37);

    @BeforeAll
    static void toolkit() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("the JavaFX toolkit started")
                .isTrue();
        Platform.setImplicitExit(false);
    }

    @Test
    void theFrameOfAShownStageIsColoredAndGivenBack() throws Exception {
        WindowsWindowChrome chrome = new WindowsWindowChrome();
        // As the application does: prepared off the JavaFX Application Thread.
        if (!chrome.available()) {
            org.junit.jupiter.api.Assumptions.abort("not Windows 11: " + chrome.detail());
        }
        String title = "PRISM chrome probe " + ProcessHandle.current().pid();
        CompletableFuture<String> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.setTitle(title);
            stage.setScene(new Scene(new StackPane(), 320, 200));
            stage.show();
            attempt(chrome, stage, title, 0, 0, done);
        });
        String outcome = done.get(20, TimeUnit.SECONDS);
        System.out.println("title-bar: " + outcome);

        assertThat(outcome).startsWith("applied after").contains("reset APPLIED");
    }

    /** Tries on later turns, as TitleBarTint does: the native title is set a moment after the show. */
    private static void attempt(
            WindowsWindowChrome chrome,
            Stage stage,
            String title,
            int tries,
            long slowest,
            CompletableFuture<String> done) {
        long start = System.nanoTime();
        Result applied = chrome.apply(title, DARK);
        long took = Math.max(slowest, System.nanoTime() - start);
        if (applied == Result.NOT_FOUND && tries < 40) {
            new Thread(() -> {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        Platform.runLater(() -> attempt(chrome, stage, title, tries + 1, took, done));
                    })
                    .start();
            return;
        }
        if (applied != Result.APPLIED) {
            stage.close();
            done.complete(applied + " after " + (tries + 1) + " tries: " + chrome.detail());
            return;
        }
        long resetStart = System.nanoTime();
        Result reset = chrome.reset(title);
        long resetTook = System.nanoTime() - resetStart;
        stage.close();
        done.complete(String.format(
                Locale.ROOT,
                "applied after %d tries, slowest attempt %.2f ms; reset %s in %.2f ms",
                tries + 1,
                took / 1e6,
                reset,
                resetTook / 1e6));
    }
}
