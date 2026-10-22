package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static prism.srm.application.FakeProcessSource.identity;
import static prism.srm.application.FakeProcessSource.reading;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;

class ProcessSamplerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLISECOND = 1_000_000L;
    private static final ProcessIdentity APP = identity(4_000, 60);

    private final FakeProcessSource source = new FakeProcessSource();
    private long nowNanos = 1_000 * SECOND;

    /** A machine with eight logical processors, unless a test builds its own sampler. */
    private final ProcessSampler sampler = newSampler(8);

    @Test
    void aProcessSeenForTheFirstTimeHasNoCpuValueAndNoIoRates() {
        source.processes = List.of(reading(APP, 50_000, 1_000_000, 1_000_000));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.cpuPercent()).isEmpty();
        assertThat(snapshot.ioReadBytesPerSecond()).isEmpty();
        assertThat(snapshot.ioWriteBytesPerSecond()).isEmpty();
    }

    @Test
    void theValuesOfAReadingThatAreNotRatesPassThroughUnchanged() {
        source.processes = List.of(reading(APP, 50_000, 0, 0));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.identity()).isEqualTo(APP);
        assertThat(snapshot.name()).isEqualTo("process-4000");
        assertThat(snapshot.parentPid()).hasValue(1);
        assertThat(snapshot.state()).contains(ProcessState.RUNNING);
        assertThat(snapshot.residentBytes()).hasValue(200_000_000);
        assertThat(snapshot.privateResidentBytes()).hasValue(150_000_000);
        assertThat(snapshot.virtualBytes()).hasValue(900_000_000);
        assertThat(snapshot.user()).contains("alice");
        assertThat(snapshot.threadCount()).hasValue(12);
        assertThat(snapshot.priority()).hasValue(8);
    }

    @Test
    void oneBusyThreadOnEightProcessorsIsAnEighthOfTheMachine() {
        // Two seconds of processor time in two seconds: one processor kept busy.
        assertThat(cpuAfter(2 * SECOND, 2_000)).hasValue(12.5);
    }

    @Test
    void aMultiThreadedProcessIsDividedByTheLogicalProcessorCount() {
        // 6.4 s of processor time in 2 s is a per-processor load of 3.2, far above 1.0. On the
        // whole-machine scale that is 3.2 of 8 processors.
        assertThat(cpuAfter(2 * SECOND, 6_400)).hasValue(40.0);
    }

    @Test
    void aProcessThatKeepsEveryProcessorBusyIsAtOneHundredPercent() {
        assertThat(cpuAfter(2 * SECOND, 16_000)).hasValue(100.0);
    }

    @Test
    void anIdleProcessIsAtZeroPercentWhichIsNotTheSameAsNoValue() {
        assertThat(cpuAfter(2 * SECOND, 0)).hasValue(0.0);
    }

    @Test
    void aValueThatSamplingPushesSlightlyAboveTheCapacityIsTheCapacity() {
        // 30 ms more than eight processors can supply in 2 s: clock-tick accounting, not an error.
        assertThat(cpuAfter(2 * SECOND, 16_030)).hasValue(100.0);
    }

    @Test
    void moreProcessorTimeThanTheMachineCouldSupplyIsNotAValue() {
        assertThat(cpuAfter(2 * SECOND, 40_000)).isEmpty();
    }

    @Test
    void aCpuCounterThatWentBackwardsIsNotAValueAndTheNextReadingIsValidAgain() {
        source.processes = List.of(reading(APP, 50_000, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 100, 0, 0));

        assertThat(only(sampler.sample()).cpuPercent()).isEmpty();

        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 4_100, 0, 0));

        assertThat(only(sampler.sample()).cpuPercent()).hasValue(25.0);
    }

    @Test
    void twoReadingsAtTheSameInstantGiveNoValueEvenForAnIdleOrBarelyActiveProcess() {
        // The processor time used is small enough to be possible, so only the missing interval
        // can be the reason: without that rule these would be 0/0 and "100 %".
        assertThat(cpuAfter(0, 0)).as("idle").isEmpty();
        assertThat(newSamplerCpuAfter(0, 10)).as("10 ms used").isEmpty();
    }

    @Test
    void aClockThatWentBackwardsGivesNoValueInsteadOfANegativeOne() {
        assertThat(cpuAfter(-10 * MILLISECOND, 5)).isEmpty();
    }

    @Test
    void withoutACpuCounterThereIsNoCpuValue() {
        source.processes = List.of(reading(APP, OptionalLong.empty(), OptionalLong.of(0), OptionalLong.of(0)));
        sampler.sample();
        advance(2 * SECOND);

        assertThat(only(sampler.sample()).cpuPercent()).isEmpty();
    }

    @Test
    void aCounterThatAppearsOnlyInTheSecondReadingHasNothingToBeComparedWith() {
        source.processes = List.of(reading(APP, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 9_000, 9_000, 9_000));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.cpuPercent()).isEmpty();
        assertThat(snapshot.ioReadBytesPerSecond()).isEmpty();
        assertThat(snapshot.ioWriteBytesPerSecond()).isEmpty();
    }

    @Test
    void theReadingIsDatedAtTheMidpointOfTheTimeTheReadTook() {
        ProcessSampler twoProcessors = newSampler(2);
        source.processes = List.of(reading(APP, 0, 0, 0));
        twoProcessors.sample();
        advance(2 * SECOND);
        // The second read starts 2 s after the first and takes 1 s, so its midpoint is 2.5 s
        // later. 2.5 s of processor time on 2 processors is then 50 %. Dating the reading at its
        // start would give 62.5 %, at its end 41.7 %.
        source.duringRead = () -> advance(SECOND);
        source.processes = List.of(reading(APP, 2_500, 5_000, 0));

        ProcessSnapshot snapshot = only(twoProcessors.sample());

        assertThat(snapshot.cpuPercent()).hasValue(50.0);
        assertThat(snapshot.ioReadBytesPerSecond()).hasValue(2_000.0);
    }

    @Test
    void theTimeThePreviousReadTookCountsToo() {
        ProcessSampler twoProcessors = newSampler(2);
        // Both reads take 1 s and start 3 s apart, so their midpoints are 3 s apart.
        source.duringRead = () -> advance(SECOND);
        source.processes = List.of(reading(APP, 0, 0, 0));
        twoProcessors.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 3_000, 0, 0));

        // 3 s of processor time over 3 s on 2 processors. Measured from the start of the
        // previous read it would be 42.9 %, from its end 60 %.
        assertThat(only(twoProcessors.sample()).cpuPercent()).hasValue(50.0);
    }

    @Test
    void theLongestPossibleIntervalReachesBackToTheStartOfThePreviousRead() {
        ProcessSampler oneProcessor = newSampler(1);
        source.duringRead = () -> advance(SECOND);
        source.processes = List.of(reading(APP, 0, 0, 0));
        oneProcessor.sample();
        advance(2 * SECOND);
        // From the start of the first read to the end of the second, 4 s passed. 3.9 s of
        // processor time is therefore possible on one processor, although the midpoints are only
        // 3 s apart. Measured from the end of the first read it would be rejected as impossible.
        source.processes = List.of(reading(APP, 3_900, 0, 0));

        assertThat(only(oneProcessor.sample()).cpuPercent()).hasValue(100.0);
    }

    @Test
    void aLongReadWidensWhatIsPossibleInsteadOfDiscardingABusyProcess() {
        ProcessSampler oneProcessor = newSampler(1);
        source.processes = List.of(reading(APP, 0, 0, 0));
        oneProcessor.sample();
        advance(2 * SECOND);
        // The counter may have been taken at the very end of the 1 s read: up to 3 s of
        // processor time is possible on one processor, although the midpoints are 2.5 s apart.
        source.duringRead = () -> advance(SECOND);
        source.processes = List.of(reading(APP, 2_900, 0, 0));

        assertThat(only(oneProcessor.sample()).cpuPercent()).hasValue(100.0);
    }

    @Test
    void ioRatesComeFromTheCounterIncreaseOverTheElapsedTime() {
        source.processes = List.of(reading(APP, 0, 1_000, 500));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 0, 9_000, 500));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.ioReadBytesPerSecond()).hasValue(4_000.0);
        assertThat(snapshot.ioWriteBytesPerSecond()).hasValue(0.0);
    }

    @Test
    void anIoCounterResetOrWrapGivesNoRateForThatCounterOnly() {
        long justBelowWrap = 0xFFFF_FFFFL - 100;
        source.processes = List.of(reading(APP, 0, justBelowWrap, 1_000));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 0, 400, 3_000));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.ioReadBytesPerSecond()).isEmpty();
        assertThat(snapshot.ioWriteBytesPerSecond()).hasValue(1_000.0);
    }

    @Test
    void anIoCounterTheSystemDoesNotProvideStaysAbsentAndIsNotARateOfZero() {
        source.processes = List.of(reading(APP, OptionalLong.of(0), OptionalLong.empty(), OptionalLong.of(100)));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, OptionalLong.of(0), OptionalLong.empty(), OptionalLong.of(300)));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.ioReadBytesPerSecond()).isEmpty();
        assertThat(snapshot.ioWriteBytesPerSecond()).hasValue(100.0);
    }

    @Test
    void aNewProcessUnderAReusedPidStartsWithoutHistory() {
        ProcessIdentity old = identity(4_000, 60);
        ProcessIdentity reused = identity(4_000, 75);
        source.processes = List.of(reading(old, 10_000, 10_000, 10_000));
        sampler.sample();
        advance(2 * SECOND);
        // Same PID, later start time. Its counters are higher than the old process's, so
        // comparing by PID would report a rate that no process ever had.
        source.processes = List.of(reading(reused, 14_000, 90_000, 90_000));

        ProcessSnapshot first = only(sampler.sample());

        assertThat(first.identity()).isEqualTo(reused);
        assertThat(first.cpuPercent()).isEmpty();
        assertThat(first.ioReadBytesPerSecond()).isEmpty();
        assertThat(first.ioWriteBytesPerSecond()).isEmpty();

        advance(2 * SECOND);
        source.processes = List.of(reading(reused, 16_000, 94_000, 90_000));

        ProcessSnapshot second = only(sampler.sample());

        assertThat(second.cpuPercent()).hasValue(12.5);
        assertThat(second.ioReadBytesPerSecond()).hasValue(2_000.0);
    }

    @Test
    void whenOneListingHoldsTheOldAndTheNewProcessOfAPidEachIsJudgedByItsOwnIdentity() {
        ProcessIdentity old = identity(4_000, 60);
        ProcessIdentity reused = identity(4_000, 75);
        source.processes = List.of(reading(old, 10_000, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        // The listing caught the old process on its way out and the new one already there.
        source.processes = List.of(reading(old, 12_000, 0, 0), reading(reused, 500, 0, 0));

        List<ProcessSnapshot> snapshots = sampler.sample();

        assertThat(snapshots).extracting(ProcessSnapshot::identity).containsExactly(old, reused);
        assertThat(snapshots)
                .extracting(ProcessSnapshot::cpuPercent)
                .containsExactly(OptionalDouble.of(12.5), OptionalDouble.empty());
    }

    @Test
    void anIdentityListedTwiceIsReportedTwiceAndItsFirstListingIsTheBaselineForTheNextCycle() {
        source.processes = List.of(reading(APP, 10_000, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 12_000, 0, 0), reading(APP, 13_000, 0, 0));

        List<ProcessSnapshot> twice = sampler.sample();

        assertThat(twice)
                .extracting(ProcessSnapshot::cpuPercent)
                .containsExactly(OptionalDouble.of(12.5), OptionalDouble.of(18.75));

        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 16_000, 0, 0));

        // 4000 ms used since the first listing of the previous cycle (12 000), not 3000 since the
        // second (13 000).
        assertThat(only(sampler.sample()).cpuPercent()).hasValue(25.0);
    }

    @Test
    void aProcessThatExitedIsNoLongerInTheSnapshot() {
        ProcessIdentity other = identity(5_000, 90);
        source.processes = List.of(reading(APP, 0, 0, 0), reading(other, 0, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of(reading(other, 1_000, 0, 0));

        List<ProcessSnapshot> snapshots = sampler.sample();

        assertThat(snapshots).extracting(ProcessSnapshot::identity).containsExactly(other);
    }

    @Test
    void nothingIsRetainedForAProcessThatLeftTheReading() {
        source.processes = List.of(reading(APP, 10_000, 10_000, 10_000));
        sampler.sample();
        advance(2 * SECOND);
        source.processes = List.of();
        sampler.sample();
        advance(2 * SECOND);
        // If its old counters had been kept, this would be a valid pair and produce rates.
        source.processes = List.of(reading(APP, 12_000, 30_000, 30_000));

        ProcessSnapshot snapshot = only(sampler.sample());

        assertThat(snapshot.cpuPercent()).isEmpty();
        assertThat(snapshot.ioReadBytesPerSecond()).isEmpty();
        assertThat(snapshot.ioWriteBytesPerSecond()).isEmpty();
    }

    @Test
    void eachProcessIsComparedWithItsOwnPreviousReadingWhileOthersComeAndGo() {
        ProcessIdentity busy = identity(5_000, 90);
        ProcessIdentity started = identity(6_000, 1_000);
        source.processes = List.of(reading(APP, 100_000, 0, 0), reading(busy, 7_000, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        source.processes =
                List.of(reading(APP, 100_000, 0, 0), reading(busy, 15_000, 0, 0), reading(started, 500, 0, 0));

        List<ProcessSnapshot> snapshots = sampler.sample();

        assertThat(snapshots)
                .extracting(ProcessSnapshot::cpuPercent)
                .containsExactly(OptionalDouble.of(0.0), OptionalDouble.of(50.0), OptionalDouble.empty());

        advance(2 * SECOND);
        // The idle one exits; the two others keep their own histories.
        source.processes = List.of(reading(busy, 19_000, 0, 0), reading(started, 2_500, 0, 0));

        assertThat(sampler.sample())
                .extracting(ProcessSnapshot::cpuPercent)
                .containsExactly(OptionalDouble.of(25.0), OptionalDouble.of(12.5));
    }

    @Test
    void whenTheProcessesCannotBeListedTheNextRatesSpanBackToTheLastGoodReading() {
        source.processes = List.of(reading(APP, 0, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        source.failure = new IllegalStateException("listing failed");

        assertThatIllegalStateException().isThrownBy(sampler::sample);

        source.failure = null;
        advance(2 * SECOND);
        source.processes = List.of(reading(APP, 4_000, 8_000, 0));

        ProcessSnapshot snapshot = only(sampler.sample());

        // 4 s of processor time over the 4 s since the last good reading, not over 2 s.
        assertThat(snapshot.cpuPercent()).hasValue(12.5);
        assertThat(snapshot.ioReadBytesPerSecond()).hasValue(2_000.0);
    }

    @Test
    void anErrorFromTheSourceIsNotTurnedIntoAFailedReading() {
        source.failure = new UnsatisfiedLinkError("library not found");

        assertThatThrownBy(sampler::sample).isInstanceOf(UnsatisfiedLinkError.class);
    }

    @Test
    void theSnapshotListCannotBeModified() {
        source.processes = List.of(reading(APP, 0, 0, 0));
        List<ProcessSnapshot> snapshots = sampler.sample();

        assertThatThrownBy(() -> snapshots.add(snapshots.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theSamplerNeverReadsProcessDetails() {
        // FakeProcessSource.details fails the test if it is ever called.
        source.processes = List.of(reading(APP, 0, 0, 0));
        sampler.sample();
        advance(2 * SECOND);
        sampler.sample();

        assertThat(source.listings).isEqualTo(2);
    }

    @Test
    void aMachineHasAtLeastOneLogicalProcessor() {
        assertThatIllegalArgumentException().isThrownBy(() -> newSampler(0));
    }

    /** Samples a process twice, {@code elapsedNanos} apart, with the given processor time in between. */
    private OptionalDouble cpuAfter(long elapsedNanos, long cpuMillisUsed) {
        return cpuAfter(sampler, elapsedNanos, cpuMillisUsed);
    }

    /** The same on a fresh sampler, for a second case within one test. */
    private OptionalDouble newSamplerCpuAfter(long elapsedNanos, long cpuMillisUsed) {
        return cpuAfter(newSampler(8), elapsedNanos, cpuMillisUsed);
    }

    private OptionalDouble cpuAfter(ProcessSampler target, long elapsedNanos, long cpuMillisUsed) {
        source.processes = List.of(reading(APP, 50_000, 0, 0));
        target.sample();
        advance(elapsedNanos);
        source.processes = List.of(reading(APP, 50_000 + cpuMillisUsed, 0, 0));
        return only(target.sample()).cpuPercent();
    }

    private ProcessSampler newSampler(int logicalProcessors) {
        return new ProcessSampler(source, () -> nowNanos, logicalProcessors);
    }

    private void advance(long nanos) {
        nowNanos += nanos;
    }

    private static ProcessSnapshot only(List<ProcessSnapshot> snapshots) {
        assertThat(snapshots).hasSize(1);
        return snapshots.get(0);
    }
}
