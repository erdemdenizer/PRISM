package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ThemeMode;
import prism.srm.platform.WindowChrome.Result;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab v2: the lookup of a window's frame, driven on the JavaFX Application Thread with a frame that
 * answers what the test says. The retries go through {@code Platform.runLater} and then a 50 ms
 * {@code PauseTransition}, so the toolkit runs; the test waits on a latch the frame counts down, never on
 * a sleep, and reads the result on a later turn of the thread, after the attempt that ended the lookup.
 * No window is shown: the lookup is given the title the way the class reads it from a stage.
 */
@Tag("ui-snapshot")
class TitleBarTintSceneTest {

    private static final long WAIT_SECONDS = 10;

    @Test
    void aWindowNeverFoundIsLookedForTwentyTimesAndReportedOnce() throws InterruptedException {
        Run run = lookUp(TitleBarTint.ATTEMPTS, call -> Result.NOT_FOUND, "PRISM never found");

        assertThat(run.calls()).hasSize(20);
        assertThat(run.reports())
                .containsExactly("the window \"PRISM never found\" keeps the system's frame after 20 attempts"
                        + " (NOT_FOUND): fake frame");
    }

    /** Found on the seventh attempt, the second of those that wait 50 ms: the lookup ends, nothing is reported. */
    @Test
    void aWindowFoundAfterTheQuickAttemptsEndsTheLookupWithoutAReport() throws InterruptedException {
        Run run = lookUp(7, call -> call < 7 ? Result.NOT_FOUND : Result.APPLIED, "PRISM found late");

        assertThat(run.calls()).hasSize(7);
        assertThat(run.reports()).isEmpty();
    }

    /** A frame the system refuses is not looked for again, and it is reported at once. */
    @Test
    void aRefusedFrameIsReportedAfterOneAttempt() throws InterruptedException {
        Run run = lookUp(1, call -> Result.FAILED, "PRISM refused");

        assertThat(run.calls()).hasSize(1);
        assertThat(run.reports())
                .containsExactly(
                        "the window \"PRISM refused\" keeps the system's frame after 1 attempts (FAILED): fake frame");
    }

    /** Two windows left without colors: forty attempts, and still the one report of the first. */
    @Test
    void onlyTheFirstWindowLeftWithoutColorsIsReported() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        FakeWindowChrome chrome =
                new FakeWindowChrome(counting(2 * TitleBarTint.ATTEMPTS, done, call -> Result.NOT_FOUND));
        List<String> reports = Collections.synchronizedList(new ArrayList<>());
        TitleBarTint tint = onFxThread(() -> {
            TitleBarTint created = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.DARK), reports::add);
            created.refresh(frame("PRISM first"), true);
            created.refresh(frame("PRISM second"), true);
            return created;
        });

        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("40 attempts made")
                .isTrue();
        List<Object> seen = onFxThread(() -> {
            tint.close();
            return List.of(chrome.callsFor("PRISM first"), chrome.callsFor("PRISM second"), List.copyOf(reports));
        });

        assertThat(seen.get(0)).isEqualTo(20L);
        assertThat(seen.get(1)).isEqualTo(20L);
        assertThat((List<?>) seen.get(2)).hasSize(1);
    }

    /** A window that closes while it is looked for ends the lookup: no further attempt, no report. */
    @Test
    void aWindowThatGoesAwayEndsTheLookup() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        FakeWindowChrome chrome = new FakeWindowChrome(call -> Result.NOT_FOUND);
        List<String> reports = Collections.synchronizedList(new ArrayList<>());
        int[] reads = {0};
        TitleBarTint tint = onFxThread(() -> {
            TitleBarTint created = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.LIGHT), reports::add);
            created.refresh(
                    new TitleBarTint.Frame(
                            () -> {
                                reads[0]++;
                                if (reads[0] <= 3) {
                                    return "PRISM closing";
                                }
                                done.countDown();
                                return null;
                            },
                            () -> true),
                    true);
            return created;
        });

        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the title read once more")
                .isTrue();
        List<Object> seen = onFxThread(() -> {
            tint.close();
            return List.of(chrome.calls().size(), List.copyOf(reports), reads[0]);
        });

        assertThat(seen).containsExactly(3, List.of(), 4);
    }

    /**
     * Focus changes tint the caption text only when the look changes: losing focus dims it, a second loss
     * changes nothing and makes no call, regaining focus restores it; a theme change applies again even
     * when the look is the same, since what the frame shows is then not trusted.
     */
    @Test
    void aFocusChangeTintsOnlyWhenTheLookChanges() {
        FakeWindowChrome chrome = new FakeWindowChrome(call -> Result.APPLIED);
        boolean[] active = {true};
        List<String> calls = onFxThread(() -> {
            TitleBarTint tint = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.DARK), message -> {});
            TitleBarTint.Frame frame = new TitleBarTint.Frame(() -> "PRISM focus", () -> active[0]);
            tint.refresh(frame, true);
            tint.refresh(frame, false);
            active[0] = false;
            tint.refresh(frame, false);
            tint.refresh(frame, false);
            active[0] = true;
            tint.refresh(frame, false);
            tint.refresh(frame, true);
            tint.close();
            return chrome.calls().stream()
                    .filter(call -> call.contains(" PRISM focus"))
                    .toList();
        });

        assertThat(calls)
                .containsExactly(
                        "apply PRISM focus dark 16181d eceef2",
                        "apply PRISM focus dark 16181d 8f98a8",
                        "apply PRISM focus dark 16181d eceef2",
                        "apply PRISM focus dark 16181d eceef2");
    }

    /**
     * A focus change while the window is still looked for starts no second lookup: the running one takes
     * it at its next attempt, which applies the colors wanted then.
     */
    @Test
    void aChangeWhileTheWindowIsLookedForIsTakenByTheNextAttempt() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        FakeWindowChrome chrome =
                new FakeWindowChrome(counting(3, done, call -> call < 3 ? Result.NOT_FOUND : Result.APPLIED));
        boolean[] active = {true};
        TitleBarTint tint = onFxThread(() -> {
            TitleBarTint created = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.LIGHT), message -> {});
            TitleBarTint.Frame frame = new TitleBarTint.Frame(() -> "PRISM late", () -> active[0]);
            created.refresh(frame, true);
            active[0] = false;
            created.refresh(frame, false);
            return created;
        });

        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("3 attempts made")
                .isTrue();
        List<String> calls = onFxThread(() -> {
            tint.close();
            return chrome.calls().stream()
                    .filter(call -> call.contains(" PRISM late"))
                    .toList();
        });

        assertThat(calls)
                .containsExactly(
                        "apply PRISM late light eef0f3 15181e",
                        "apply PRISM late light eef0f3 5c6474",
                        "apply PRISM late light eef0f3 5c6474");
    }

    /**
     * A window left without colors is not looked for again at each focus change, twenty attempts each
     * time; a theme change looks for it again.
     */
    @Test
    void aWindowLeftWithoutColorsIsLookedForAgainOnAThemeChangeOnly() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        FakeWindowChrome chrome = new FakeWindowChrome(counting(TitleBarTint.ATTEMPTS, done, call -> Result.NOT_FOUND));
        boolean[] active = {true};
        boolean[] showing = {true};
        TitleBarTint.Frame frame = new TitleBarTint.Frame(() -> showing[0] ? "PRISM lost" : null, () -> active[0]);
        TitleBarTint tint = onFxThread(() -> {
            TitleBarTint created = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.DARK), message -> {});
            created.refresh(frame, true);
            return created;
        });

        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("20 attempts made")
                .isTrue();
        List<Long> counts = onFxThread(() -> {
            long lost = chrome.callsFor("PRISM lost");
            active[0] = false;
            tint.refresh(frame, false);
            long afterFocus = chrome.callsFor("PRISM lost");
            tint.refresh(frame, true);
            long afterTheme = chrome.callsFor("PRISM lost");
            // The new lookup ends at its next attempt: the window is gone.
            showing[0] = false;
            tint.close();
            return List.of(lost, afterFocus, afterTheme);
        });

        assertThat(counts).containsExactly(20L, 20L, 21L);
    }

    // ---- helpers ----

    /** A frame found by this title, of the active window. */
    private static TitleBarTint.Frame frame(String title) {
        return new TitleBarTint.Frame(() -> title, () -> true);
    }

    /** What one lookup did. */
    private record Run(List<String> calls, List<String> reports) {}

    /** Looks up one window with these answers and waits until the frame has answered {@code expected} calls. */
    private static Run lookUp(int expected, IntFunction<Result> answers, String title) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        FakeWindowChrome chrome = new FakeWindowChrome(counting(expected, done, answers));
        List<String> reports = Collections.synchronizedList(new ArrayList<>());
        TitleBarTint tint = onFxThread(() -> {
            TitleBarTint created = new TitleBarTint(chrome, new SimpleObjectProperty<>(ThemeMode.DARK), reports::add);
            created.refresh(frame(title), true);
            return created;
        });

        assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("%d attempts made", expected)
                .isTrue();
        // A later turn of the thread: the attempt that counted down has ended, with its report if any.
        return onFxThread(() -> {
            tint.close();
            List<String> titled = chrome.calls().stream()
                    .filter(call -> call.contains(" " + title))
                    .toList();
            return new Run(titled, List.copyOf(reports));
        });
    }

    /** The answers, counting the latch down at the given call. */
    private static IntFunction<Result> counting(int at, CountDownLatch done, IntFunction<Result> answers) {
        return call -> {
            if (call == at) {
                done.countDown();
            }
            return answers.apply(call);
        };
    }

    /** Runs on the JavaFX Application Thread, the toolkit started by the snapshot harness. */
    private static <T> T onFxThread(Supplier<T> action) {
        return SnapshotHarness.inspect(Theme.LIGHT, 1, 1, Region::new, root -> action.get());
    }
}
