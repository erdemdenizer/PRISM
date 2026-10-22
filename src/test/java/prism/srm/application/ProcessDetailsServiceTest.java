package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessDetailsResult.Outcome;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.platform.ProcessSource;

/**
 * The on-demand read of process details against a hand-written source. The executor is the
 * test's: a request is run when the test says so, on the test's thread.
 */
class ProcessDetailsServiceTest {

    private static final Instant START = Instant.parse("2026-10-06T08:00:00Z");
    private static final ProcessIdentity PROCESS = new ProcessIdentity(4_242, START);

    /** A source that answers a request for details as the test decides and counts the requests. */
    private static final class DetailsSource implements ProcessSource {

        Function<ProcessIdentity, Optional<ProcessDetails>> answer = identity -> Optional.empty();
        final List<ProcessIdentity> asked = new ArrayList<>();

        @Override
        public List<ProcessReading> processes() {
            throw new AssertionError("Reading details must never list all processes");
        }

        @Override
        public Optional<ProcessDetails> details(ProcessIdentity identity) {
            asked.add(identity);
            return answer.apply(identity);
        }
    }

    /** Keeps what it is given until the test runs it. */
    private static final class HeldExecutor implements Executor {

        final List<Runnable> held = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            held.add(task);
        }

        void runAll() {
            List<Runnable> tasks = List.copyOf(held);
            held.clear();
            tasks.forEach(Runnable::run);
        }
    }

    private final DetailsSource source = new DetailsSource();
    private final HeldExecutor executor = new HeldExecutor();
    private final List<ProcessDetailsResult> results = new ArrayList<>();
    private final ProcessDetailsService service = new ProcessDetailsService(source, executor, results::add);

    /** The caller is the JavaFX Application Thread: nothing may be read before the executor runs. */
    @Test
    void aRequestReadsNothingOnTheCallingThread() {
        service.request(PROCESS);

        assertThat(source.asked).isEmpty();
        assertThat(results).isEmpty();
        assertThat(executor.held).hasSize(1);
    }

    @Test
    void theDetailsOfAProcessThatIsThereAreHandedToTheListener() {
        ProcessDetails details =
                new ProcessDetails(PROCESS, Optional.of("C:\\bin\\tool.exe"), Optional.of("tool.exe --flag"));
        source.answer = identity -> Optional.of(details);

        service.request(PROCESS);
        executor.runAll();

        assertThat(source.asked).containsExactly(PROCESS);
        assertThat(results).containsExactly(ProcessDetailsResult.read(details));
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.READ);
    }

    /** Access denied is not a failure: the process was read, and what could not be read is absent. */
    @Test
    void detailsWithoutPathAndCommandLineAreStillARead() {
        source.answer = identity -> Optional.of(new ProcessDetails(identity, Optional.empty(), Optional.empty()));

        service.request(PROCESS);
        executor.runAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.READ);
        assertThat(results.get(0).details().orElseThrow().executablePath()).isEmpty();
        assertThat(results.get(0).details().orElseThrow().commandLine()).isEmpty();
    }

    /** The source answers empty for a process that exited and for a PID in other hands. */
    @Test
    void aProcessThatVanishedGivesAResultThatSaysSoAndNamesTheProcessAskedFor() {
        source.answer = identity -> Optional.empty();

        service.request(PROCESS);
        executor.runAll();

        assertThat(results).containsExactly(ProcessDetailsResult.gone(PROCESS));
        assertThat(results.get(0).identity()).isEqualTo(PROCESS);
        assertThat(results.get(0).details()).isEmpty();
    }

    /**
     * A source must answer empty when the PID is in other hands. Should one answer with the
     * details of the new holder instead, the result still names the process asked for and says
     * that it is gone: whoever waits is answered, and nobody is shown another process's details.
     */
    @Test
    void detailsOfAnotherProcessThanTheOneAskedForAreNotHandedOn() {
        ProcessIdentity newHolder = new ProcessIdentity(PROCESS.pid(), START.plusSeconds(30));
        source.answer = identity ->
                Optional.of(new ProcessDetails(newHolder, Optional.of("C:/bin/other.exe"), Optional.of("other")));

        service.request(PROCESS);
        executor.runAll();

        assertThat(results).containsExactly(ProcessDetailsResult.gone(PROCESS));
    }

    @Test
    void aSourceThatThrowsGivesAFailedResultAndIsLoggedOncePerKind() {
        source.answer = identity -> {
            throw new IllegalStateException("simulated native failure");
        };

        try (LogCapture log = new LogCapture(ProcessDetailsService.class)) {
            service.request(PROCESS);
            service.request(PROCESS);
            service.request(new ProcessIdentity(7, START));
            executor.runAll();

            assertThat(results)
                    .containsExactly(
                            ProcessDetailsResult.failed(PROCESS),
                            ProcessDetailsResult.failed(PROCESS),
                            ProcessDetailsResult.failed(new ProcessIdentity(7, START)));
            assertThat(log.warnings())
                    .containsExactly(
                            "Reading the details of a process failed; further failures of this kind are not logged");
        }
    }

    @Test
    void aFailedReadDoesNotStopLaterReads() {
        source.answer = identity -> {
            throw new IllegalStateException("simulated");
        };
        service.request(PROCESS);
        executor.runAll();

        ProcessDetails details = new ProcessDetails(PROCESS, Optional.of("C:\\bin\\tool.exe"), Optional.empty());
        source.answer = identity -> Optional.of(details);
        service.request(PROCESS);
        executor.runAll();

        assertThat(results).containsExactly(ProcessDetailsResult.failed(PROCESS), ProcessDetailsResult.read(details));
    }

    /**
     * An Error is never caught: it leaves the read as it is. Whoever waits for the answer is
     * still told that the read failed, so that nothing says "Reading" for ever.
     */
    @Test
    void anErrorIsNotCaughtAndTheListenerIsStillTold() {
        LinkageError fatal = new LinkageError("simulated");
        source.answer = identity -> {
            throw fatal;
        };

        service.request(PROCESS);

        assertThatThrownBy(executor::runAll).isSameAs(fatal);
        assertThat(results).containsExactly(ProcessDetailsResult.failed(PROCESS));
    }

    @Test
    void aListenerThatThrowsIsLoggedAndDoesNotEndTheReads() {
        List<ProcessDetailsResult> delivered = new ArrayList<>();
        ProcessDetailsService throwing = new ProcessDetailsService(source, executor, result -> {
            delivered.add(result);
            throw new IllegalStateException("simulated listener failure");
        });

        try (LogCapture log = new LogCapture(ProcessDetailsService.class)) {
            throwing.request(PROCESS);
            throwing.request(PROCESS);
            executor.runAll();

            assertThat(delivered).hasSize(2);
            assertThat(log.warnings())
                    .containsExactly(
                            "The listener for process details failed; further failures of this kind are not logged");
        }
    }

    /** A listener that throws must not take the place of an Error that is on its way out. */
    @Test
    void aListenerThatThrowsDoesNotHideAnError() {
        LinkageError fatal = new LinkageError("simulated");
        source.answer = identity -> {
            throw fatal;
        };
        ProcessDetailsService throwing = new ProcessDetailsService(source, executor, result -> {
            throw new IllegalStateException("simulated listener failure");
        });

        try (LogCapture log = new LogCapture(ProcessDetailsService.class)) {
            throwing.request(PROCESS);

            assertThatThrownBy(executor::runAll).isSameAs(fatal);
            assertThat(log.warnings()).hasSize(1);
        }
    }

    /** Requests are served in the order given; which of them are dropped is the executor's rule. */
    @Test
    void everyRequestTheExecutorRunsGetsItsOwnResultInOrder() {
        ProcessIdentity first = new ProcessIdentity(1, START);
        ProcessIdentity second = new ProcessIdentity(2, START);
        source.answer = identity ->
                Optional.of(new ProcessDetails(identity, Optional.of("path-" + identity.pid()), Optional.empty()));

        service.request(first);
        service.request(second);
        executor.runAll();

        assertThat(results).extracting(ProcessDetailsResult::identity).containsExactly(first, second);
        assertThat(results.get(1).details().orElseThrow().executablePath()).contains("path-2");
    }
}
