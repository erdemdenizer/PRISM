package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import prism.srm.app.MonitoringRuntime.State;
import prism.srm.application.ActivityLog;
import prism.srm.application.FakeProcessController;
import prism.srm.application.FakeProcessSource;
import prism.srm.application.FakeSystemMetricsSource;
import prism.srm.application.FakeSystemMetricsSource.Metric;
import prism.srm.application.LogCapture;
import prism.srm.application.ProcessMonitor;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;
import prism.srm.domain.SystemSnapshot;

/**
 * Runs the runtime with its real executors and threads against fake sources. Waiting is done on
 * latches only: a positive expectation gets a generous timeout that is never reached when the
 * code works, a negative one ("it has not stopped yet") a short window.
 */
class MonitoringRuntimeTest {

    private static final long PATIENCE_SECONDS = 10;
    private static final long SHORT_WINDOW_MILLIS = 200;
    private static final Duration GENEROUS_BOUND = Duration.ofSeconds(PATIENCE_SECONDS);
    private static final Duration TIGHT_BOUND = Duration.ofMillis(100);

    private final FakeSystemMetricsSource system = new FakeSystemMetricsSource();
    private final FakeProcessSource processes = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(100, () -> Instant.EPOCH);
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final List<String> uncaughtThreads = new CopyOnWriteArrayList<>();
    /** Every call of the failure callback, in order: each holds all failures so far. */
    private final List<Set<MonitoringFailure>> reported = new CopyOnWriteArrayList<>();
    /** Counted down once the end of process monitoring was handed on. */
    private final CountDownLatch processMonitoringStopped = new CountDownLatch(1);

    /** Every listing of the processes the runtime handed on, in order. */
    private final List<List<ProcessSnapshot>> processListings = new CopyOnWriteArrayList<>();
    /** Every result of a read of process details the runtime handed on, in order. */
    private final List<ProcessDetailsResult> detailsResults = new CopyOnWriteArrayList<>();

    private final AtomicInteger startupFailures = new AtomicInteger();
    private final CountDownLatch startupFailed = new CountDownLatch(1);

    private MonitoringRuntime runtime;

    /** The suspension journal of the runtime, as on the running system; the shutdown closes it. */
    @org.junit.jupiter.api.extension.RegisterExtension
    final prism.srm.application.JournalFolder journalFolder = new prism.srm.application.JournalFolder();

    @AfterEach
    void shutDownTheRuntime() throws InterruptedException {
        if (runtime != null) {
            runtime.shutdown();
            // So that no thread of this test is still running, and logging, during the next one.
            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void startsBothMonitorsAndDeliversSystemSnapshots() throws InterruptedException {
        CountDownLatch snapshots = new CountDownLatch(2);
        CountDownLatch listings = new CountDownLatch(1);
        processes.duringRead = listings::countDown;
        runtime = runtime(this::fakeSources, snapshot -> snapshots.countDown(), GENEROUS_BOUND);

        runtime.start();

        assertThat(snapshots.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listings.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.state()).isEqualTo(State.RUNNING);
        assertThat(messages(ActivitySeverity.INFO))
                .containsExactlyInAnyOrder(
                        "Monitoring started: system metrics every 1 s.",
                        "Process monitoring started: every 2 s, 2 logical processors.");
        assertThat(startupFailures).hasValue(0);
    }

    @Test
    void everyThreadIsNamedAndOnlyTheShutdownThreadKeepsTheProcessAlive() throws InterruptedException {
        AtomicReference<Thread> startupThread = new AtomicReference<>();
        AtomicReference<Thread> systemThread = new AtomicReference<>();
        AtomicReference<Thread> processThread = new AtomicReference<>();
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch listed = new CountDownLatch(1);
        processes.duringRead = () -> {
            processThread.set(Thread.currentThread());
            listed.countDown();
        };
        runtime = runtime(
                () -> {
                    startupThread.set(Thread.currentThread());
                    return fakeSources();
                },
                snapshot -> {
                    systemThread.set(Thread.currentThread());
                    sampled.countDown();
                },
                GENEROUS_BOUND);

        runtime.start();

        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(startupThread.get().getName()).isEqualTo("srm-startup");
        assertThat(startupThread.get().isDaemon()).isTrue();
        assertThat(systemThread.get().getName()).isEqualTo("srm-system-monitor");
        assertThat(systemThread.get().isDaemon()).isTrue();
        assertThat(processThread.get().getName()).isEqualTo("srm-process-monitor");
        assertThat(processThread.get().isDaemon()).isTrue();
    }

    /** The listings reach whoever shows them: one snapshot per process, on the process thread. */
    @Test
    void theProcessesOfEveryListingAreHandedOn() throws InterruptedException {
        ProcessIdentity only = FakeProcessSource.identity(4_242, 10);
        processes.list(List.of(FakeProcessSource.reading(only, 100, 0, 0)));
        AtomicReference<Thread> handedOnBy = new AtomicReference<>();
        List<List<ProcessSnapshot>> handedOn = new CopyOnWriteArrayList<>();
        CountDownLatch listed = new CountDownLatch(2);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        listing -> {
                            handedOn.add(listing);
                            handedOnBy.set(Thread.currentThread());
                            listed.countDown();
                        },
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));

        runtime.start();

        assertThat(listed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // The first listing and the one two seconds later: each has the process, once.
        assertThat(handedOn.get(0)).extracting(ProcessSnapshot::identity).containsExactly(only);
        assertThat(handedOn.get(1)).extracting(ProcessSnapshot::identity).containsExactly(only);
        // Only the second can have a share of the processor: it takes two readings to compute one.
        assertThat(handedOn.get(0).get(0).cpuPercent()).isEmpty();
        assertThat(handedOn.get(1).get(0).cpuPercent()).isPresent();
        assertThat(handedOnBy.get().getName()).isEqualTo("srm-process-monitor");
        assertThat(uncaught).isEmpty();
    }

    /** PROC-5: path and command line are read when asked for, on a thread that is not a monitor's. */
    @Test
    void theDetailsOfAProcessAreReadOnTheirOwnThreadAndHandedOn() throws InterruptedException {
        ProcessIdentity asked = FakeProcessSource.identity(4_242, 10);
        ProcessDetails details = new ProcessDetails(asked, Optional.of("tool.exe"), Optional.of("tool.exe --flag"));
        AtomicReference<Thread> readBy = new AtomicReference<>();
        processes.onDetails = identity -> {
            readBy.set(Thread.currentThread());
            return Optional.of(details);
        };
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        List<ProcessDetailsResult> results = new CopyOnWriteArrayList<>();
        runtime = runtimeWithDetails(snapshot -> sampled.countDown(), result -> {
            results.add(result);
            answered.countDown();
        });
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.requestProcessDetails(asked);

        assertThat(answered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(results).containsExactly(ProcessDetailsResult.read(details));
        assertThat(readBy.get().getName()).isEqualTo("srm-process-details");
        assertThat(readBy.get().isDaemon()).isTrue();
        assertThat(readBy.get()).isNotSameAs(Thread.currentThread());
        assertThat(uncaught).isEmpty();
    }

    /**
     * A user walking through the table asks for one process after another. While one read runs,
     * only the newest of the requests that wait is kept: the others were for rows the selection
     * has left, and nothing queues up.
     */
    @Test
    void whileOneReadRunsOnlyTheNewestWaitingRequestIsKept() throws InterruptedException {
        CountDownLatch firstReadRuns = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        processes.onDetails = identity -> {
            if (identity.pid() == 1) {
                firstReadRuns.countDown();
                awaitUninterruptibly(releaseFirstRead);
            }
            return Optional.empty();
        };
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch twoAnswers = new CountDownLatch(2);
        CountDownLatch threeAnswers = new CountDownLatch(3);
        List<ProcessDetailsResult> results = new CopyOnWriteArrayList<>();
        runtime = runtimeWithDetails(snapshot -> sampled.countDown(), result -> {
            results.add(result);
            twoAnswers.countDown();
            threeAnswers.countDown();
        });
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));
        assertThat(firstReadRuns.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessDetails(FakeProcessSource.identity(2, 10));
        runtime.requestProcessDetails(FakeProcessSource.identity(3, 10));
        runtime.requestProcessDetails(FakeProcessSource.identity(4, 10));
        releaseFirstRead.countDown();
        assertThat(twoAnswers.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // The reads are served in order, so once two are answered nothing of before is left.
        runtime.requestProcessDetails(FakeProcessSource.identity(5, 10));

        assertThat(threeAnswers.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(results).extracting(result -> result.identity().pid()).containsExactly(1L, 4L, 5L);
    }

    /** No process is listed while monitoring does not run, so a request then is about nothing. */
    @Test
    void aRequestForDetailsBeforeMonitoringRunsOrAfterShutdownDoesNothing() throws InterruptedException {
        AtomicInteger reads = new AtomicInteger();
        processes.onDetails = identity -> {
            reads.incrementAndGet();
            return Optional.empty();
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);

        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));

        assertThat(reads).hasValue(0);
        assertThat(detailsResults).isEmpty();
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(uncaught).isEmpty();
    }

    /** After a shutdown the executor is closed: a request must be dropped without a word, on any thread. */
    @Test
    void aRequestForDetailsAfterARuntimeThatRanWasShutDownIsDroppedWithoutAWord() throws InterruptedException {
        AtomicInteger reads = new AtomicInteger();
        processes.onDetails = identity -> {
            reads.incrementAndGet();
            return Optional.empty();
        };
        CountDownLatch sampled = new CountDownLatch(1);
        runtime = runtime(this::fakeSources, snapshot -> sampled.countDown(), GENEROUS_BOUND);
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));

        assertThat(reads).hasValue(0);
        assertThat(detailsResults).isEmpty();
        assertThat(uncaught).isEmpty();
    }

    /**
     * An Error in a read is never caught. It ends the thread of the read and reaches the handler
     * for uncaught failures; whoever waits is told that the read failed; and the next request is
     * read on a new thread of the same name.
     */
    @Test
    void anErrorInAReadOfDetailsIsNotCaughtAndLaterRequestsAreStillRead() throws InterruptedException {
        LinkageError fatal = new LinkageError("simulated");
        List<String> readBy = new CopyOnWriteArrayList<>();
        processes.onDetails = identity -> {
            readBy.add(Thread.currentThread().getName());
            if (identity.pid() == 1) {
                throw fatal;
            }
            return Optional.empty();
        };
        CountDownLatch sampled = new CountDownLatch(1);
        CountDownLatch firstAnswer = new CountDownLatch(1);
        CountDownLatch secondAnswer = new CountDownLatch(2);
        CountDownLatch handled = new CountDownLatch(1);
        List<ProcessDetailsResult> results = new CopyOnWriteArrayList<>();
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> sampled.countDown(),
                        processListings::add,
                        result -> {
                            results.add(result);
                            firstAnswer.countDown();
                            secondAnswer.countDown();
                        },
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                GENEROUS_BOUND,
                (thread, failure) -> {
                    uncaught.add(failure);
                    uncaughtThreads.add(thread.getName());
                    handled.countDown();
                });
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));
        assertThat(firstAnswer.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(handled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessDetails(FakeProcessSource.identity(2, 10));

        assertThat(secondAnswer.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(uncaught).containsExactly(fatal);
        assertThat(uncaughtThreads).containsExactly("srm-process-details");
        assertThat(results)
                .containsExactly(
                        ProcessDetailsResult.failed(FakeProcessSource.identity(1, 10)),
                        ProcessDetailsResult.gone(FakeProcessSource.identity(2, 10)));
        assertThat(readBy).containsExactly("srm-process-details", "srm-process-details");
        // Monitoring itself is not what failed.
        assertThat(reported).isEmpty();
        assertThat(runtime.state()).isEqualTo(State.RUNNING);
    }

    /** Nobody is left to show the result, so a read in flight is told to stop at once. */
    @Test
    void shutdownInterruptsAReadOfDetailsInFlightAndDoesNotWaitForIt() throws InterruptedException {
        CountDownLatch readRuns = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        processes.onDetails = identity -> {
            readRuns.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return Optional.empty();
        };
        CountDownLatch sampled = new CountDownLatch(1);
        runtime = runtimeWithDetails(snapshot -> sampled.countDown(), detailsResults::add);
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));
        assertThat(readRuns.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(messages(ActivitySeverity.WARNING)).isEmpty();
    }

    /** A native read does not answer an interrupt. The shutdown still ends within its time. */
    @Test
    void aReadOfDetailsThatDoesNotReturnDoesNotHoldTheShutdownBeyondItsTime() throws InterruptedException {
        CountDownLatch readRuns = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        processes.onDetails = identity -> {
            readRuns.countDown();
            awaitUninterruptibly(release);
            return Optional.empty();
        };
        CountDownLatch sampled = new CountDownLatch(1);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> sampled.countDown(),
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                TIGHT_BOUND,
                (thread, failure) -> uncaught.add(failure));
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessDetails(FakeProcessSource.identity(1, 10));
        assertThat(readRuns.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        try {
            runtime.shutdown();

            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.state()).isEqualTo(State.STOPPED);
            assertThat(messages(ActivitySeverity.WARNING))
                    .contains("A read of process details did not end within the shutdown time and was interrupted"
                            + " (thread srm-process-details).");
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownStopsMonitoringAndEndsBothExecutors() throws InterruptedException {
        CountDownLatch sampled = new CountDownLatch(1);
        runtime = runtime(this::fakeSources, snapshot -> sampled.countDown(), GENEROUS_BOUND);
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.state()).isEqualTo(State.STOPPED);
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(messages(ActivitySeverity.INFO))
                .contains("Shutdown started.", "Shutdown complete: monitoring and its executors are stopped.");
        assertThat(messages(ActivitySeverity.WARNING)).isEmpty();
        assertThat(messages(ActivitySeverity.ERROR)).isEmpty();
        // Monitors that end because they were stopped have not failed.
        assertThat(reported).isEmpty();
        assertThat(runtime.failures()).isEmpty();
    }

    @Test
    void aSecondShutdownDoesNothing() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();
        runtime.shutdown();

        assertThat(messages(ActivitySeverity.INFO).stream().filter("Shutdown started."::equals))
                .hasSize(1);
        assertThat(runtime.state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aRuntimeThatWasNeverStartedShutsDownAndCannotBeStartedAfterwards() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThatIllegalStateException().isThrownBy(runtime::start);
        assertThat(system.reads(Metric.CPU)).isZero();
    }

    @Test
    void aRuntimeIsStartedOnce() {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();

        assertThatIllegalStateException().isThrownBy(runtime::start);
    }

    /**
     * The window is closed while the platform is still being created. Start-up finishes creating
     * it, finds the shutdown, and starts nothing: no sample is ever taken, and nobody is told that
     * monitoring failed, because it did not.
     */
    @Test
    void closingDuringStartupStartsNoMonitor() throws InterruptedException {
        CountDownLatch creatingPlatform = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime = runtime(
                () -> {
                    creatingPlatform.countDown();
                    awaitUninterruptibly(release);
                    return fakeSources();
                },
                snapshot -> {},
                GENEROUS_BOUND);
        runtime.start();
        assertThat(creatingPlatform.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();
        assertThat(runtime.awaitStopped(SHORT_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
                .as("shutdown finished while start-up was still creating the platform")
                .isFalse();
        release.countDown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(system.reads(Metric.CPU)).isZero();
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(startupFailures).hasValue(0);
        assertThat(messages(ActivitySeverity.WARNING)).isEmpty();
        assertThat(messages(ActivitySeverity.ERROR)).isEmpty();
    }

    /** A platform that never answers must not keep the process from exiting. */
    @Test
    void aStartupThatHangsIsInterruptedWhenTheShutdownTimeIsUsedUp() throws InterruptedException {
        CountDownLatch creatingPlatform = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        runtime = runtime(
                () -> {
                    creatingPlatform.countDown();
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                    }
                    throw new IllegalStateException("start-up was interrupted");
                },
                snapshot -> {},
                TIGHT_BOUND);
        runtime.start();
        assertThat(creatingPlatform.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(messages(ActivitySeverity.WARNING))
                .containsExactly(
                        "Start-up did not end within the shutdown time and was interrupted (thread srm-startup).");
        assertThat(runtime.executorsTerminated()).isTrue();
        // The window is closing: there is nobody to tell that monitoring did not start.
        assertThat(startupFailures).hasValue(0);
    }

    @Test
    void aPlatformThatCannotBeCreatedIsRecordedAndReportedOnce() throws InterruptedException {
        runtime = runtime(
                () -> {
                    throw new IllegalStateException("no platform");
                },
                snapshot -> {},
                GENEROUS_BOUND);

        runtime.start();

        assertThat(startupFailed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.state()).isEqualTo(State.FAILED);
        assertThat(messages(ActivitySeverity.ERROR)).containsExactly("Monitoring could not start.");
        assertThat(uncaught).isEmpty();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(startupFailures).hasValue(1);
    }

    /**
     * An Error is never caught: it ends the start-up thread and arrives at the handler for
     * uncaught failures. The user interface is still told, so it does not show "Reading" for ever.
     */
    @Test
    void anErrorDuringStartupIsNotCaughtAndTheFailureIsStillReported() throws InterruptedException {
        LinkageError fatal = new LinkageError("simulated");
        CountDownLatch handled = new CountDownLatch(1);
        runtime = new MonitoringRuntime(
                () -> {
                    throw fatal;
                },
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                GENEROUS_BOUND,
                (thread, failure) -> {
                    uncaught.add(failure);
                    uncaughtThreads.add(thread.getName());
                    handled.countDown();
                });

        runtime.start();

        assertThat(handled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(uncaught).containsExactly(fatal);
        assertThat(uncaughtThreads).containsExactly("srm-startup");
        assertThat(startupFailed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.state()).isEqualTo(State.FAILED);
        assertThat(messages(ActivitySeverity.ERROR))
                .containsExactly("Monitoring could not start: a fatal error ended start-up.");

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(startupFailures).hasValue(1);
    }

    /**
     * The CPU share of a process is computed against the number of logical processors. While the
     * system does not report it, the system is monitored and the processes are not; this is said
     * once, however many cycles pass.
     */
    @Test
    void withoutTheProcessorCountOnlyTheSystemIsMonitored() throws InterruptedException {
        system.fail(Metric.PROCESSOR, new IllegalStateException("no processor information"));
        AtomicInteger listings = new AtomicInteger();
        processes.duringRead = listings::incrementAndGet;
        CountDownLatch snapshots = new CountDownLatch(3);
        runtime = runtime(this::fakeSources, snapshot -> snapshots.countDown(), GENEROUS_BOUND);

        runtime.start();

        assertThat(snapshots.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.state()).isEqualTo(State.RUNNING);
        assertThat(messages(ActivitySeverity.WARNING))
                .containsExactly("Process monitoring has not started: the number of logical processors could not be"
                        + " read. It starts when the number is known.");
        assertThat(messages(ActivitySeverity.INFO)).containsExactly("Monitoring started: system metrics every 1 s.");
        assertThat(startupFailures).hasValue(0);
        // Two system cycles have completed; a process monitor would have listed at once.
        assertThat(listings).hasValue(0);
    }

    /** A read that fails at first must not cost process monitoring for the whole session. */
    @Test
    void processMonitoringStartsOnceTheProcessorCountIsKnown() throws InterruptedException {
        system.fail(Metric.PROCESSOR, new IllegalStateException("no processor information yet"));
        AtomicInteger cycles = new AtomicInteger();
        // Runs on the sampling thread, which is the thread that reads what the fake holds.
        system.onCpuReading = () -> {
            if (cycles.incrementAndGet() == 2) {
                system.recover(Metric.PROCESSOR);
            }
        };
        CountDownLatch listed = new CountDownLatch(1);
        CountDownLatch snapshotAfterListing = new CountDownLatch(1);
        processes.duringRead = listed::countDown;
        runtime = runtime(
                this::fakeSources,
                snapshot -> {
                    if (listed.getCount() == 0) {
                        snapshotAfterListing.countDown();
                    }
                },
                GENEROUS_BOUND);

        runtime.start();

        assertThat(listed.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // One more system cycle, so that the cycle which started the listing has ended.
        assertThat(snapshotAfterListing.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        assertThat(messages(ActivitySeverity.WARNING)).hasSize(1);
        assertThat(messages(ActivitySeverity.INFO))
                .containsExactly(
                        "Monitoring started: system metrics every 1 s.",
                        "Process monitoring started: every 2 s, 2 logical processors.");
    }

    /** LIFE-1: shutdown waits for work in flight. */
    @Test
    void shutdownWaitsForACycleInFlight() throws InterruptedException {
        CountDownLatch cycleEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        system.onCpuReading = () -> {
            cycleEntered.countDown();
            awaitUninterruptibly(release);
        };
        runtime = runtime(this::fakeSources, snapshot -> delivered.incrementAndGet(), GENEROUS_BOUND);
        runtime.start();
        assertThat(cycleEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();
        assertThat(runtime.awaitStopped(SHORT_WINDOW_MILLIS, TimeUnit.MILLISECONDS))
                .as("shutdown finished while a sampling cycle was still running")
                .isFalse();
        release.countDown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // The cycle was allowed to finish and deliver; it was not interrupted.
        assertThat(delivered).hasValue(1);
        assertThat(messages(ActivitySeverity.WARNING)).isEmpty();
        assertThat(runtime.executorsTerminated()).isTrue();
    }

    /** LIFE-1: shutdown is bounded in time. A cycle that does not return is interrupted. */
    @Test
    void aCycleThatDoesNotReturnIsInterruptedWhenTheShutdownTimeIsUsedUp() throws InterruptedException {
        CountDownLatch cycleEntered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        system.onCpuReading = () -> {
            cycleEntered.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new IllegalStateException("the cycle was interrupted", e);
            }
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, TIGHT_BOUND);
        runtime.start();
        assertThat(cycleEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(messages(ActivitySeverity.WARNING))
                .containsExactly(
                        "A sampling cycle did not end within the shutdown time and was interrupted (thread srm-system-monitor).");
        // Running out of time is warned about; it is not a step that failed.
        assertThat(messages(ActivitySeverity.INFO))
                .contains("Shutdown complete: monitoring and its executors are stopped.");
    }

    /**
     * An Error ends system monitoring after it has run for a while. The runtime learns of it from
     * the monitor's termination listener, records it once and hands it on; process monitoring,
     * on its own executor, is not affected.
     */
    @Test
    void systemMonitoringThatEndsOnItsOwnIsReportedOnceAndProcessMonitoringGoesOn() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        AtomicInteger cycles = new AtomicInteger();
        system.onCpuReading = () -> {
            if (cycles.incrementAndGet() == 3) {
                throw fatal;
            }
        };
        CountDownLatch systemEnded = new CountDownLatch(1);
        CountDownLatch listingAfterTheEnd = new CountDownLatch(1);
        processes.duringRead = () -> {
            if (systemEnded.getCount() == 0) {
                listingAfterTheEnd.countDown();
            }
        };
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        allSoFar -> {
                            failed(allSoFar);
                            systemEnded.countDown();
                        }),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));

        try (LogCapture monitorLog = new LogCapture(SystemMonitor.class)) {
            runtime.start();

            assertThat(systemEnded.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(listingAfterTheEnd.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            // The monitor itself logged the Error; it was not caught on the way to the runtime.
            assertThat(monitorLog.errors()).containsExactly("System monitoring stopped by a fatal error");
        }
        assertThat(reported).containsExactly(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        assertThat(runtime.failures()).containsExactly(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        // The runtime itself still runs: it is the monitor that has ended.
        assertThat(runtime.state()).isEqualTo(State.RUNNING);
        assertThat(startupFailures).hasValue(0);

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.executorsTerminated()).isTrue();
        // Shutting down afterwards reports nothing more.
        assertThat(reported).hasSize(1);
        // The end is recorded on the sampling thread after it was handed on. Now that the
        // executors have terminated, that thread is through with it.
        assertThat(messages(ActivitySeverity.ERROR))
                .containsExactly("System monitoring stopped unexpectedly and does not resume in this session. The"
                        + " error that ended it is logged before this line.");
        assertThat(cycles).hasValue(3);
    }

    /**
     * The very first cycle ends system monitoring, while start-up is still on its way from
     * "starting" to "running". The end must not be lost in that gap, and since process
     * monitoring starts from a system snapshot, the record says that it never started.
     */
    @Test
    void systemMonitoringThatEndsInItsFirstCycleIsStillReported() throws InterruptedException {
        system.onCpuReading = () -> {
            throw new OutOfMemoryError("simulated");
        };
        AtomicInteger listings = new AtomicInteger();
        processes.duringRead = listings::incrementAndGet;
        CountDownLatch systemEnded = new CountDownLatch(1);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        allSoFar -> {
                            failed(allSoFar);
                            systemEnded.countDown();
                        }),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));

        runtime.start();

        assertThat(systemEnded.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(reported).containsExactly(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(messages(ActivitySeverity.ERROR))
                .containsExactly("System monitoring stopped unexpectedly and does not resume in this session. The"
                        + " error that ended it is logged before this line. Process monitoring had not started"
                        + " yet and now cannot start.");
        assertThat(listings).hasValue(0);
        assertThat(startupFailures).hasValue(0);
    }

    /** The other way round: the process listing ends, the system is still sampled. */
    @Test
    void processMonitoringThatEndsOnItsOwnIsReportedOnceAndSystemMonitoringGoesOn() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        AtomicInteger listings = new AtomicInteger();
        processes.duringRead = () -> {
            if (listings.incrementAndGet() == 2) {
                throw fatal;
            }
        };
        CountDownLatch processesEnded = new CountDownLatch(1);
        CountDownLatch snapshotsAfterTheEnd = new CountDownLatch(2);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {
                            if (processesEnded.getCount() == 0) {
                                snapshotsAfterTheEnd.countDown();
                            }
                        },
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        allSoFar -> {
                            failed(allSoFar);
                            processesEnded.countDown();
                        }),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));

        try (LogCapture monitorLog = new LogCapture(ProcessMonitor.class)) {
            runtime.start();

            assertThat(processesEnded.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(snapshotsAfterTheEnd.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(monitorLog.errors()).containsExactly("Process monitoring stopped by a fatal error");
        }
        assertThat(reported).containsExactly(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
        assertThat(runtime.failures()).containsExactly(MonitoringFailure.PROCESS_MONITORING_STOPPED);
        assertThat(runtime.state()).isEqualTo(State.RUNNING);

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // Recorded on the sampling thread after the hand-on; the executors have terminated now.
        assertThat(messages(ActivitySeverity.ERROR))
                .containsExactly("Process monitoring stopped unexpectedly and does not resume in this session. The"
                        + " error that ended it is logged before this line.");
        assertThat(listings).hasValue(2);
    }

    /** Each message holds everything that has failed, so the newest alone says it all. */
    @Test
    void whenBothMonitorsEndTheLastReportNamesBoth() throws InterruptedException {
        AtomicInteger listings = new AtomicInteger();
        CountDownLatch processesEnded = new CountDownLatch(1);
        processes.duringRead = () -> {
            if (listings.incrementAndGet() == 1) {
                throw new OutOfMemoryError("simulated");
            }
        };
        // The system monitor ends in the first cycle after the process listing has ended.
        system.onCpuReading = () -> {
            if (processesEnded.getCount() == 0) {
                throw new OutOfMemoryError("simulated");
            }
        };
        CountDownLatch bothEnded = new CountDownLatch(2);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        allSoFar -> {
                            failed(allSoFar);
                            if (allSoFar.contains(MonitoringFailure.PROCESS_MONITORING_STOPPED)) {
                                processesEnded.countDown();
                            }
                            bothEnded.countDown();
                        }),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));

        runtime.start();

        assertThat(bothEnded.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(reported)
                .containsExactly(
                        Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED),
                        Set.of(
                                MonitoringFailure.PROCESS_MONITORING_STOPPED,
                                MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        assertThat(runtime.failures())
                .containsExactlyInAnyOrder(
                        MonitoringFailure.PROCESS_MONITORING_STOPPED, MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        // Each end is recorded on its sampling thread, after it was handed on. Once the executors
        // have terminated, both threads are through with it.
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(messages(ActivitySeverity.ERROR)).hasSize(2);
    }

    /** One end, one record, one report, however often the news arrives. */
    @Test
    void theSameEndReportedAgainIsNeitherRecordedNorHandedOnAgain() throws InterruptedException {
        CountDownLatch sampled = new CountDownLatch(1);
        runtime = runtime(this::fakeSources, snapshot -> sampled.countDown(), GENEROUS_BOUND);
        runtime.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        runtime.monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        runtime.monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED);

        assertThat(reported).containsExactly(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        assertThat(messages(ActivitySeverity.ERROR)).hasSize(1);
    }

    /**
     * The last cycle is still running when the window is closed, and it ends with an Error. The
     * application is shutting down: there is nothing left to tell the user about monitoring.
     */
    @Test
    void aMonitorThatEndsDuringShutdownIsNotReportedAsAFailure() throws Exception {
        CountDownLatch cycleEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        system.onCpuReading = () -> {
            cycleEntered.countDown();
            awaitUninterruptibly(release);
            throw new OutOfMemoryError("simulated");
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);

        try (LogCapture monitorLog = new LogCapture(SystemMonitor.class)) {
            runtime.start();
            assertThat(cycleEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

            runtime.shutdown();
            release.countDown();

            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            // The Error was thrown and logged by the monitor as always.
            assertThat(monitorLog.errors()).containsExactly("System monitoring stopped by a fatal error");
        }
        assertThat(reported).isEmpty();
        assertThat(runtime.failures()).isEmpty();
        assertThat(messages(ActivitySeverity.ERROR)).isEmpty();
    }

    @Test
    void anEndBeforeMonitoringRunsOrAfterShutdownIsIgnored() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);

        runtime.monitorEnded(MonitoringFailure.PROCESS_MONITORING_STOPPED);
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED);

        assertThat(reported).isEmpty();
        assertThat(runtime.failures()).isEmpty();
        assertThat(messages(ActivitySeverity.ERROR)).isEmpty();
    }

    // --- Actions on processes ---

    private static final ProcessIdentity DEMO = FakeProcessSource.identity(4_242, 10);
    private static final long OWN_PID = 1_000_003;

    private ProcessControlResult nextResult() throws InterruptedException {
        ProcessControlResult result = controlResults.poll(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(result).as("a result arrived").isNotNull();
        return result;
    }

    private void awaitCapabilities() throws InterruptedException {
        assertThat(capabilitiesSeen.poll(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(new prism.srm.domain.ControlProfile(controller.capabilities, controller.listedPriorities));
    }

    @Test
    void actionsRunOnTheirOwnNamedDaemonThreadAndTheCapabilitiesArriveOnce() throws InterruptedException {
        AtomicReference<Thread> actedOn = new AtomicReference<>();
        controller.terminate = target -> {
            actedOn.set(Thread.currentThread());
            return prism.srm.domain.ControlOutcome.success("started");
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();

        runtime.requestProcessAction(ControlRequest.of(ControlAction.FORCE_KILL, DEMO, "java"));

        assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(actedOn.get().getName()).isEqualTo("srm-process-control");
        assertThat(actedOn.get().isDaemon()).isTrue();
        assertThat(capabilitiesSeen).as("the capabilities are reported once").isEmpty();
        assertThat(messages(ActivitySeverity.INFO))
                .contains(
                        "Force kill: Force termination requested for java (PID 4242). The table shows when it has ended. [started]");
    }

    /** Whoever asks gets an answer, also while nothing can be done: before the start and after the shutdown. */
    @Test
    void aRequestWhileMonitoringDoesNotRunIsAnsweredAtOnceAndNothingIsDone() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);

        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));

        assertThat(controlResults.poll().result()).isEqualTo(OperationResult.UNSUPPORTED);
        runtime.start();
        awaitCapabilities();
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));
        assertThat(controlResults.poll().text())
                .isEqualTo("Nothing was done to java (PID 4242). Prism is not monitoring processes.");
        assertThat(controller.calls).isEmpty();
    }

    /** PROC-6: a process Prism holds suspended is shown so in the next listing, from Prism's own record. */
    @Test
    void aProcessPrismSuspendedIsMarkedInTheListingsThatFollow() throws InterruptedException {
        processes.list(List.of(FakeProcessSource.reading(DEMO, 100, 0, 0)));
        CountDownLatch marked = new CountDownLatch(1);
        runtime = new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshot -> {},
                        listing -> {
                            if (listing.stream()
                                    .anyMatch(p -> p.state().equals(Optional.of(ProcessState.SUSPENDED_BY_PRISM)))) {
                                marked.countDown();
                            }
                        },
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                GENEROUS_BOUND,
                (thread, failure) -> uncaught.add(failure));
        runtime.start();
        awaitCapabilities();

        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));

        assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(marked.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.calls)
                .as("the listings ask the system nothing about it")
                .containsExactly("facts 4242", "suspend 4242");
    }

    /** LIFE-1, LIFE-2: the shutdown lets the control thread finish and leaves what the user suspended. */
    @Test
    void theShutdownLeavesTheUsersSuspensionInPlaceAndStopsTheControlThread() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));
        assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(controller.calls).doesNotContain("resume 4242");
        assertThat(controller.suspensions.get(0).closes).isEqualTo(1);
        assertThat(messages(ActivitySeverity.WARNING))
                .contains(
                        "Left suspended, as the user asked: java (PID 4242). They stay suspended after Prism closes.");
        assertThat(runtime.executorsTerminated()).isTrue();
        assertThat(messages(ActivitySeverity.INFO))
                .contains("Shutdown complete: monitoring and its executors are stopped.");
    }

    /** An action that arrived before the shutdown is finished before the suspensions are finalized. */
    @Test
    void anActionInFlightWhenTheShutdownBeginsComesToRestFirst() throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.priority = (target, level) -> {
            inside.countDown();
            awaitUninterruptibly(release);
            return prism.srm.domain.ControlOutcome.success("set");
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();
        runtime.requestProcessAction(ControlRequest.priority(DEMO, "java", prism.srm.domain.PriorityLevel.LOW));
        assertThat(inside.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        runtime.shutdown();
        runtime.requestProcessAction(
                ControlRequest.of(ControlAction.FORCE_KILL, FakeProcessSource.identity(77, 1), "x"));
        release.countDown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(List.of(nextResult(), nextResult()))
                .extracting(ProcessControlResult::result)
                .containsExactlyInAnyOrder(OperationResult.SUCCESS, OperationResult.UNSUPPORTED);
        assertThat(controller.calls).doesNotContain("terminate 77");
        assertThat(runtime.executorsTerminated()).isTrue();
    }

    @Test
    void requestsForManyProcessesAreAllAnswered() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();

        for (int pid = 1; pid <= 20; pid++) {
            runtime.requestProcessAction(
                    ControlRequest.of(ControlAction.END_TASK, FakeProcessSource.identity(pid * 4L, 1), "p" + pid));
        }

        for (int i = 0; i < 20; i++) {
            assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        }
    }

    /**
     * A full queue answers at once and drops nothing it took: every accepted request is answered
     * once the queue moves, and every process can be asked again.
     */
    @Test
    void aFullQueueAnswersAtOnceAndEveryAcceptedRequestIsAnsweredLater() throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.close = target -> {
            if (target.pid() == 4) {
                inside.countDown();
                awaitUninterruptibly(release);
            }
            return prism.srm.domain.ControlOutcome.success("posted");
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();
        runtime.requestProcessAction(
                ControlRequest.of(ControlAction.END_TASK, FakeProcessSource.identity(4, 1), "first"));
        assertThat(inside.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        int beyond = 5;
        for (int i = 2; i <= 1 + MonitoringRuntime.CONTROL_QUEUE + beyond; i++) {
            runtime.requestProcessAction(
                    ControlRequest.of(ControlAction.END_TASK, FakeProcessSource.identity(i * 4L, 1), "p" + i));
        }
        List<ProcessControlResult> atOnce = new java.util.ArrayList<>();
        for (int i = 0; i < beyond; i++) {
            atOnce.add(nextResult());
        }
        assertThat(atOnce)
                .allSatisfy(result -> assertThat(result.explanation()).isEqualTo("Too many actions are waiting."));
        assertThat(controlResults)
                .as("nothing else is answered while the queue waits")
                .isEmpty();

        release.countDown();
        for (int i = 0; i < 1 + MonitoringRuntime.CONTROL_QUEUE; i++) {
            assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        }
        ProcessIdentity refusedBefore = atOnce.get(0).identity();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.END_TASK, refusedBefore, "again"));
        assertThat(nextResult().result())
                .as("a refused process is not left locked")
                .isEqualTo(OperationResult.SUCCESS);
    }

    /**
     * An action stuck in the system while it holds the suspensions: the shutdown still ends
     * within its bound, says what it could not do, and does not close what the user suspended.
     */
    @Test
    void aShutdownWithAnActionStuckInTheSystemEndsWithinItsBoundAndSaysWhatItLeft() throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime = runtime(this::fakeSources, snapshot -> {}, TIGHT_BOUND);
        runtime.start();
        awaitCapabilities();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));
        assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        controller.suspend = target -> {
            inside.countDown();
            awaitUninterruptibly(release);
            return prism.srm.platform.SuspendResult.failed(
                    prism.srm.domain.ControlOutcome.of(OperationResult.NATIVE_ERROR, "stuck"));
        };
        runtime.requestProcessAction(
                ControlRequest.of(ControlAction.SUSPEND, FakeProcessSource.identity(77, 1), "stuck"));
        assertThat(inside.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        try {
            long began = System.nanoTime();
            runtime.shutdown();

            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(3));
            assertThat(messages(ActivitySeverity.WARNING))
                    .anySatisfy(message -> assertThat(message).contains("srm-process-control"))
                    .contains("Suspensions were not finalized: an action still held them.");
            assertThat(controller.suspensions.get(0).closes)
                    .as("what the user suspended is left as it is")
                    .isZero();
        } finally {
            release.countDown();
        }
    }

    /**
     * Process monitoring ends for good while Prism holds a suspension the user made: only Resume
     * is still taken, it releases the suspension through what Prism kept of it, and the held list
     * says so.
     */
    @Test
    void aSuspensionPrismHoldsIsStillReleasedAfterProcessMonitoringEnded() throws Exception {
        CountDownLatch firstListing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger listings = new AtomicInteger();
        processes.duringRead = () -> {
            if (listings.incrementAndGet() == 2) {
                firstListing.countDown();
                awaitUninterruptibly(release);
                throw new OutOfMemoryError("simulated");
            }
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        try (LogCapture monitorLog = new LogCapture(ProcessMonitor.class)) {
            runtime.start();
            awaitCapabilities();
            runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));
            assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
            assertThat(firstListing.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            awaitFailure(MonitoringFailure.PROCESS_MONITORING_STOPPED);
            assertThat(monitorLog.errors()).containsExactly("Process monitoring stopped by a fatal error");
        }
        assertThat(lastHeld()).containsExactly(new prism.srm.domain.SuspendedProcess(DEMO, "java"));

        for (ControlAction action : List.of(ControlAction.END_TASK, ControlAction.FORCE_KILL, ControlAction.SUSPEND)) {
            runtime.requestProcessAction(ControlRequest.of(action, DEMO, "java"));
            assertThat(nextResult().text())
                    .as(action.name())
                    .isEqualTo("Nothing was done to java (PID 4242). Prism is not monitoring processes.");
        }
        runtime.requestProcessAction(ControlRequest.priority(DEMO, "java", prism.srm.domain.PriorityLevel.LOW));
        assertThat(nextResult().result()).isEqualTo(OperationResult.UNSUPPORTED);

        runtime.requestProcessAction(ControlRequest.of(ControlAction.RESUME, DEMO, "java"));
        ProcessControlResult resumed = nextResult();
        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller.suspensions.get(0).resumes).isEqualTo(1);
        assertThat(lastHeld()).isEmpty();

        runtime.requestProcessAction(ControlRequest.of(ControlAction.RESUME, DEMO, "java"));
        assertThat(nextResult().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.suspensions.get(0).resumes)
                .as("not taken back twice")
                .isEqualTo(1);
        assertThat(controller.calls)
                .filteredOn(call -> call.startsWith("suspend"))
                .hasSize(1);
    }

    /** LIFE-2 also after monitoring ended: closing Prism leaves a suspension the user made in place. */
    @Test
    void aSuspensionTheUserMadeStaysWhenPrismClosesAfterProcessMonitoringEnded() throws Exception {
        CountDownLatch firstListing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger listings = new AtomicInteger();
        processes.duringRead = () -> {
            if (listings.incrementAndGet() == 2) {
                firstListing.countDown();
                awaitUninterruptibly(release);
                throw new OutOfMemoryError("simulated");
            }
        };
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        try (LogCapture monitorLog = new LogCapture(ProcessMonitor.class)) {
            runtime.start();
            awaitCapabilities();
            runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "java"));
            assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
            assertThat(firstListing.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            awaitFailure(MonitoringFailure.PROCESS_MONITORING_STOPPED);
            assertThat(monitorLog.errors()).hasSize(1);
        }

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.calls).doesNotContain("resume 4242");
        assertThat(controller.suspensions.get(0).closes)
                .as("its handles are closed, the threads stay suspended")
                .isEqualTo(1);
    }

    private void awaitFailure(MonitoringFailure failure) throws InterruptedException {
        assertThat(failure).isEqualTo(MonitoringFailure.PROCESS_MONITORING_STOPPED);
        assertThat(processMonitoringStopped.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
    }

    private List<prism.srm.domain.SuspendedProcess> lastHeld() {
        List<prism.srm.domain.SuspendedProcess> last = null;
        for (List<prism.srm.domain.SuspendedProcess> held : heldSeen) {
            last = held;
        }
        assertThat(last).as("a list was handed over").isNotNull();
        return last;
    }

    private final FakeProcessController controller = new FakeProcessController();
    /** Every result of an action, in order; a test waits on it with poll. */
    private final LinkedBlockingQueue<ProcessControlResult> controlResults = new LinkedBlockingQueue<>();
    /** Every list of the processes Prism holds suspended, as the coordinator handed them over. */
    private final LinkedBlockingQueue<List<prism.srm.domain.SuspendedProcess>> heldSeen = new LinkedBlockingQueue<>();
    /** Every report of the capabilities. */
    private final LinkedBlockingQueue<prism.srm.domain.ControlProfile> capabilitiesSeen = new LinkedBlockingQueue<>();

    private MonitoringRuntime runtime(
            Supplier<PlatformSources> platform, Consumer<SystemSnapshot> snapshots, Duration shutdownBound) {
        return new MonitoringRuntime(
                platform,
                System::nanoTime,
                activity,
                listeners(
                        snapshots,
                        processListings::add,
                        detailsResults::add,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                shutdownBound,
                (thread, failure) -> {
                    uncaught.add(failure);
                    uncaughtThreads.add(thread.getName());
                });
    }

    /** A runtime with a generous shutdown time that hands the results of detail reads to a test. */
    private MonitoringRuntime runtimeWithDetails(
            Consumer<SystemSnapshot> snapshots, Consumer<ProcessDetailsResult> details) {
        return new MonitoringRuntime(
                this::fakeSources,
                System::nanoTime,
                activity,
                listeners(
                        snapshots,
                        processListings::add,
                        details,
                        controlResults::add,
                        capabilitiesSeen::add,
                        heldSeen::add,
                        this::failed),
                GENEROUS_BOUND,
                (thread, failure) -> {
                    uncaught.add(failure);
                    uncaughtThreads.add(thread.getName());
                });
    }

    /** The receivers of the runtime as the tests before milestone 9 gave them; limits and recovery go nowhere. */
    private static MonitoringRuntime.Listeners listeners(
            Consumer<SystemSnapshot> snapshots,
            Consumer<List<ProcessSnapshot>> processSnapshots,
            Consumer<ProcessDetailsResult> processDetails,
            Consumer<ProcessControlResult> controlResults,
            Consumer<prism.srm.domain.ControlProfile> controlProfile,
            Consumer<List<prism.srm.domain.SuspendedProcess>> heldSuspensions,
            Consumer<Set<MonitoringFailure>> onFailure) {
        return new MonitoringRuntime.Listeners(
                snapshots,
                processSnapshots,
                processDetails,
                controlResults,
                controlProfile,
                heldSuspensions,
                onFailure,
                limits -> {},
                result -> {},
                recovered -> {},
                check -> {},
                result -> {});
    }

    private PlatformSources fakeSources() {
        // Not a PID any test acts on, whatever PID the test's own process has.
        return new PlatformSources(system, processes, controller, OWN_PID, Optional.of(journalFolder.path()), "test-1");
    }

    private void failed(Set<MonitoringFailure> allSoFar) {
        reported.add(allSoFar);
        if (allSoFar.contains(MonitoringFailure.PROCESS_MONITORING_STOPPED)) {
            processMonitoringStopped.countDown();
        }
        if (allSoFar.contains(MonitoringFailure.STARTUP)) {
            startupFailures.incrementAndGet();
            startupFailed.countDown();
        }
    }

    private List<String> messages(ActivitySeverity severity) {
        return activity.entries().stream()
                .filter(entry -> entry.severity() == severity)
                .map(ActivityEntry::message)
                .toList();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                        return;
                    }
                    throw new IllegalStateException("The test never released the latch");
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Only the end of process monitoring narrows the actions: the processes are still listed without system samples. */
    @Test
    void theEndOfSystemMonitoringAloneLeavesEveryActionAvailable() throws InterruptedException {
        runtime = runtime(this::fakeSources, snapshot -> {}, GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();

        runtime.monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        runtime.requestProcessAction(ControlRequest.of(ControlAction.FORCE_KILL, DEMO, "java"));

        assertThat(nextResult().result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller.calls).contains("terminate 4242");
    }

    /** What the controller could not set up reaches the Logs page once, as a warning, after monitoring started. */
    @Test
    void whatTheControllerFoundMissingIsLoggedOnceAsAWarning() throws InterruptedException {
        controller.limitations = List.of("Process control: pidfd signalling is unavailable (test).");
        CountDownLatch cycles = new CountDownLatch(3);
        runtime = runtime(this::fakeSources, snapshot -> cycles.countDown(), GENEROUS_BOUND);
        runtime.start();
        awaitCapabilities();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (messages(ActivitySeverity.WARNING).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        // Later monitoring cycles add nothing: once, at the start.
        assertThat(cycles.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(messages(ActivitySeverity.WARNING))
                .containsExactly("Process control: pidfd signalling is unavailable (test).");
    }
}
