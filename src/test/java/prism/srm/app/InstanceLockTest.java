package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * One Prism per user profile (milestone 10, final multi-instance audit): the ownership is an
 * operating-system lock, decided before anything is monitored, limited or recovered; a second launch
 * is refused and touches nothing of the first; a killed owner frees the profile; a failure to tell
 * refuses the launch; nothing leaks. Runs on every system (real locks, JVMs this test starts).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InstanceLockTest {

    @TempDir
    Path state;

    private final List<Process> started = new ArrayList<>();
    private final List<InstanceLock> held = new ArrayList<>();

    @AfterEach
    void cleanUp() throws InterruptedException {
        held.forEach(InstanceLock::close);
        for (Process each : started) {
            each.destroyForcibly();
            each.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private InstanceLock acquire() {
        InstanceLock lock = InstanceLock.acquire(state);
        held.add(lock);
        return lock;
    }

    /** A first Prism in a JVM of its own; returns once it has said whether it owns the profile. */
    private Process holder(List<String> said) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                        java,
                        "-Xmx64m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        InstanceLockHolder.class.getName(),
                        state.toString())
                .redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        started.add(process);
        BufferedReader output =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = output.readLine()) != null) {
            if (line.startsWith("held") || line.startsWith("refused")) {
                said.add(line);
                return process;
            }
        }
        said.add("ended");
        return process;
    }

    private Map<String, String> folderState() throws IOException {
        Map<String, String> files = new HashMap<>();
        try (Stream<Path> all = Files.walk(state)) {
            for (Path each : all.filter(Files::isRegularFile).toList()) {
                files.put(state.relativize(each).toString(), Files.size(each) + "@" + Files.getLastModifiedTime(each));
            }
        }
        return files;
    }

    @Test
    void theFirstInstanceOwnsTheProfile() {
        InstanceLock first = acquire();

        assertThat(first.outcome()).isEqualTo(InstanceLock.Outcome.OWNED);
        assertThat(first.owned()).isTrue();
        assertThat(first.refusal()).isEmpty();
        assertThat(state.resolve(InstanceLock.FILE)).exists();
    }

    /** A second launch is refused and says why, while the first keeps the profile. */
    @Test
    void aSecondLaunchIsRefusedAndTheFirstKeepsTheProfile() {
        InstanceLock first = acquire();

        InstanceLock second = acquire();

        assertThat(second.outcome()).isEqualTo(InstanceLock.Outcome.HELD_ELSEWHERE);
        assertThat(second.refusal()).hasValueSatisfying(why -> assertThat(why).contains("already running"));
        assertThat(first.owned()).as("the first is not disturbed").isTrue();
    }

    /**
     * After a refusal in the same process, the system itself still sees the profile owned: another
     * process is refused too. On Windows only: elsewhere closing any channel to the file may end every
     * lock the JVM holds on it, which is why the application asks only once per process.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void afterARefusalInTheSameProcessTheSystemStillSeesTheProfileOwned() throws Exception {
        InstanceLock first = acquire();
        assertThat(acquire().outcome()).isEqualTo(InstanceLock.Outcome.HELD_ELSEWHERE);

        List<String> said = new ArrayList<>();
        holder(said);

        assertThat(said).singleElement().satisfies(line -> assertThat(line).isEqualTo("refused HELD_ELSEWHERE"));
        assertThat(first.owned()).isTrue();
    }

    /**
     * Across processes, as two launches are: a running Prism with its journal owns the profile; a
     * second launch is refused and changes nothing in the folder; killing the first, as Task Manager
     * would, frees the profile for the next launch: the file left behind is no marker.
     */
    @Test
    void anotherRunningPrismKeepsTheProfileAndItsJournalAndAHardKillFreesIt() throws Exception {
        List<String> said = new ArrayList<>();
        Process first = holder(said);
        assertThat(said).singleElement().satisfies(line -> assertThat(line).startsWith("held"));
        Map<String, String> before = folderState();

        InstanceLock second = acquire();

        assertThat(second.outcome()).isEqualTo(InstanceLock.Outcome.HELD_ELSEWHERE);
        assertThat(folderState())
                .as("the first one's journal and lock are untouched")
                .isEqualTo(before);
        assertThat(first.isAlive()).isTrue();

        first.destroyForcibly();
        assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(state.resolve(InstanceLock.FILE))
                .as("the file stays, as a lock file does")
                .exists();
        assertThat(acquire().outcome()).as("a hard kill frees the profile").isEqualTo(InstanceLock.Outcome.OWNED);
    }

    /** Two launches started together, as two clicks: exactly one owns the profile (the OS lock is atomic). */
    @Test
    void twoLaunchesAtOnceHaveExactlyOneWinner() throws Exception {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        Thread a = new Thread(() -> launch(first));
        Thread b = new Thread(() -> launch(second));
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);

        List<String> both = new ArrayList<>(first);
        both.addAll(second);
        assertThat(both).hasSize(2);
        assertThat(both.stream().filter(line -> line.startsWith("held"))).hasSize(1);
        assertThat(both.stream().filter(line -> line.startsWith("refused HELD_ELSEWHERE")))
                .hasSize(1);
    }

    private void launch(List<String> said) {
        try {
            holder(said);
        } catch (IOException e) {
            said.add("failed " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Within one process too (threads racing for the profile): one owner, the rest refused. */
    @Test
    void threadsRacingForTheProfileHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<InstanceLock>> runs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return InstanceLock.acquire(state);
                }));
            }
            go.countDown();
            List<InstanceLock> outcomes = new ArrayList<>();
            for (Future<InstanceLock> each : runs) {
                outcomes.add(each.get(10, TimeUnit.SECONDS));
            }
            held.addAll(outcomes);
            assertThat(outcomes.stream().filter(InstanceLock::owned)).hasSize(1);
            assertThat(outcomes.stream().filter(lock -> lock.outcome() == InstanceLock.Outcome.HELD_ELSEWHERE))
                    .hasSize(15);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A lock file left by an earlier Prism keeps nothing out: only a held lock does. */
    @Test
    void aLockFileLeftBehindIsNoMarker() throws IOException {
        Files.writeString(state.resolve(InstanceLock.FILE), "left by an earlier Prism");

        assertThat(acquire().outcome()).isEqualTo(InstanceLock.Outcome.OWNED);
    }

    /** A launch that fails before monitoring begins lets go of the profile. */
    @Test
    void ownershipLetGoOfOnAFailedStartFreesTheProfile() {
        InstanceLock first = InstanceLock.acquire(state);
        first.close();

        assertThat(first.owned()).isFalse();
        assertThat(acquire().outcome()).isEqualTo(InstanceLock.Outcome.OWNED);
    }

    /** A file that cannot be opened refuses the launch: ownership cannot be proven. */
    @Test
    void aFileThatCannotBeOpenedRefusesTheLaunch() {
        InstanceLock refused = InstanceLock.acquire(state, file -> {
            throw new java.nio.file.AccessDeniedException(file.toString());
        });

        assertThat(refused.outcome()).isEqualTo(InstanceLock.Outcome.FAILED);
        assertThat(refused.owned()).isFalse();
        assertThat(refused.refusal())
                .hasValueSatisfying(
                        why -> assertThat(why).contains("cannot start").contains("AccessDenied"));
    }

    /** A state folder that cannot be made (below a regular file) refuses the launch. */
    @Test
    void aStateFolderThatCannotBeMadeRefusesTheLaunch() throws IOException {
        Path file = Files.writeString(state.resolve("not-a-folder"), "x");

        InstanceLock refused = InstanceLock.acquire(file.resolve("state"));

        assertThat(refused.outcome()).isEqualTo(InstanceLock.Outcome.FAILED);
    }

    /** An opener that keeps the channels it hands out, so that a test can see each one closed. */
    private static final class Recording implements InstanceLock.Opener {
        final List<FileChannel> opened = new ArrayList<>();
        private final InstanceLock.Opener real;

        Recording(InstanceLock.Opener real) {
            this.real = real;
        }

        @Override
        public FileChannel open(Path file) throws IOException {
            FileChannel channel = real.open(file);
            opened.add(channel);
            return channel;
        }
    }

    /** A lock that cannot be asked for (a channel the system will not lock) refuses the launch and closes its channel. */
    @Test
    void aLockThatCannotBeAskedForRefusesTheLaunchAndClosesItsChannel() throws IOException {
        Files.writeString(state.resolve(InstanceLock.FILE), "");
        Recording opener = new Recording(file -> FileChannel.open(file, StandardOpenOption.READ));

        InstanceLock refused = InstanceLock.acquire(state, opener);

        assertThat(refused.outcome()).isEqualTo(InstanceLock.Outcome.FAILED);
        assertThat(opener.opened)
                .singleElement()
                .satisfies(channel -> assertThat(channel.isOpen())
                        .as("no handle is left open")
                        .isFalse());
    }

    /** A refused launch closes the channel it opened, and the owner's letting go closes its own. */
    @Test
    void aRefusedLaunchAndALetGoOwnershipLeaveNoChannelOpen() {
        Recording opener =
                new Recording(file -> FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
        InstanceLock first = InstanceLock.acquire(state, opener);
        InstanceLock second = InstanceLock.acquire(state, opener);
        assertThat(second.outcome()).isEqualTo(InstanceLock.Outcome.HELD_ELSEWHERE);
        assertThat(opener.opened.get(1).isOpen())
                .as("the refused launch's channel")
                .isFalse();
        assertThat(opener.opened.get(0).isOpen())
                .as("the owner's channel, while it owns")
                .isTrue();

        first.close();

        assertThat(opener.opened.get(0).isOpen()).isFalse();
    }

    /**
     * A launch right after the owner is killed: Windows may let go of the lock a moment late, so the
     * launch asks again for a few seconds; here the owner is killed while it waits, and it wins.
     */
    @Test
    void aLaunchJustAfterTheOwnerWasKilledAsksAgainAndWins() throws Exception {
        List<String> said = new ArrayList<>();
        Process first = holder(said);
        assertThat(said).singleElement().satisfies(line -> assertThat(line).startsWith("held"));
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            first.destroyForcibly();
        });
        killer.start();

        InstanceLock launch = InstanceLock.acquireAtLaunch(state);
        held.add(launch);
        killer.join(10_000);

        assertThat(launch.outcome()).isEqualTo(InstanceLock.Outcome.OWNED);
    }

    /** While the owner runs, the launch asks again within its bound and is then refused. */
    @Test
    void aLaunchWhileTheOwnerRunsIsRefusedWithinItsBound() throws Exception {
        List<String> said = new ArrayList<>();
        holder(said);
        long started = System.nanoTime();

        InstanceLock launch = InstanceLock.acquireAtLaunch(state);
        held.add(launch);

        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(launch.outcome()).isEqualTo(InstanceLock.Outcome.HELD_ELSEWHERE);
        assertThat(launch.refusal()).hasValueSatisfying(why -> assertThat(why).contains("still closing"));
        assertThat(millis).as("bounded: about three seconds").isLessThan(10_000);
    }
}
