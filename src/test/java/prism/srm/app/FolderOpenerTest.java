package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivitySeverity;

class FolderOpenerTest {

    @TempDir
    Path temp;

    private final ActivityLog activity = new ActivityLog(10, () -> Instant.EPOCH);

    private Optional<String> outcomeOf(Path folder, Consumer<Path> show) throws Exception {
        CompletableFuture<Optional<String>> outcome = new CompletableFuture<>();
        try (FolderOpener opener = new FolderOpener(show, outcome::complete, activity)) {
            opener.open(folder);
            return outcome.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFolderThatExistsIsHandedToTheFileManagerOffTheCallersThread() throws Exception {
        List<Path> shown = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());

        Optional<String> outcome = outcomeOf(temp, folder -> {
            shown.add(folder);
            threads.add(Thread.currentThread().getName());
        });

        assertThat(outcome).isEmpty();
        assertThat(shown).containsExactly(temp);
        assertThat(threads).containsExactly("srm-open-folder");
    }

    @Test
    void aFolderThatDoesNotExistIsSaidAndNothingIsOpened() throws Exception {
        List<Path> shown = Collections.synchronizedList(new ArrayList<>());
        Path missing = temp.resolve("settings");

        Optional<String> outcome = outcomeOf(missing, shown::add);

        assertThat(outcome.orElseThrow()).contains("does not exist yet").contains(missing.toString());
        assertThat(shown).isEmpty();
        assertThat(Files.exists(missing)).as("looking creates nothing").isFalse();
    }

    @Test
    void aFailureOfTheSystemIsSaidAndLogged() throws Exception {
        Optional<String> outcome = outcomeOf(temp, folder -> {
            throw new IllegalStateException("no file manager");
        });

        assertThat(outcome.orElseThrow()).contains("could not open");
        assertThat(activity.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.severity()).isEqualTo(ActivitySeverity.WARNING);
            assertThat(entry.message()).contains(temp.toString());
        });
    }

    /** Two clicks while the file manager is slow do not pile up; a third thread is never started. */
    @Test
    void requestsDoNotPileUpBehindOneInFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Optional<String>> outcomes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ended = new CountDownLatch(2);
        try (FolderOpener opener = new FolderOpener(
                uri -> {
                    entered.countDown();
                    awaitQuietly(release);
                },
                outcome -> {
                    outcomes.add(outcome);
                    ended.countDown();
                },
                activity)) {
            opener.open(temp);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 5; i++) {
                opener.open(temp);
            }
            release.countDown();
            assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outcomes).hasSize(2);
    }

    @Test
    void aRequestAfterCloseIsDroppedWithoutAFailure() throws InterruptedException {
        CountDownLatch answered = new CountDownLatch(1);
        FolderOpener opener = new FolderOpener(uri -> {}, outcome -> answered.countDown(), activity);
        opener.close();

        opener.open(temp);

        // The opener answers on its own thread: an immediate look would pass whatever it does.
        assertThat(answered.await(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
