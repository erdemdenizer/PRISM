package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Identity, validation and absence in the process records. */
class ProcessRecordsTest {

    private static final Instant START = Instant.parse("2026-10-05T11:14:11.709Z");

    @Test
    void anIdentityIsThePidTogetherWithTheStartTime() {
        ProcessIdentity identity = new ProcessIdentity(4_242, START);

        assertThat(identity)
                .isEqualTo(new ProcessIdentity(4_242, START))
                .hasSameHashCodeAs(new ProcessIdentity(4_242, START));
    }

    @Test
    void theSamePidWithAnotherStartTimeIsAnotherProcess() {
        ProcessIdentity before = new ProcessIdentity(4_242, START);
        ProcessIdentity reused = new ProcessIdentity(4_242, START.plusSeconds(30));

        assertThat(reused).isNotEqualTo(before);
    }

    @Test
    void identitiesAreComparedToTheLastMillisecond() {
        assertThat(new ProcessIdentity(4_242, START.plusMillis(1))).isNotEqualTo(new ProcessIdentity(4_242, START));
    }

    @Test
    void theSameStartTimeWithAnotherPidIsAnotherProcess() {
        assertThat(new ProcessIdentity(4_243, START)).isNotEqualTo(new ProcessIdentity(4_242, START));
    }

    @Test
    void identitiesWorkAsKeysSoThatAReusedPidIsASeparateEntry() {
        Set<ProcessIdentity> seen = new HashSet<>();
        seen.add(new ProcessIdentity(4_242, START));
        seen.add(new ProcessIdentity(4_242, START));
        seen.add(new ProcessIdentity(4_242, START.plusSeconds(30)));

        assertThat(seen).hasSize(2);
    }

    @Test
    void aPidIsNotNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessIdentity(-1, START));
        assertThat(new ProcessIdentity(0, START).pid()).isZero();
    }

    @Test
    void aReadingMayLackEveryValueTheSystemDidNotProvide() {
        ProcessReading reading = new ProcessReading(
                new ProcessIdentity(4, START),
                "",
                OptionalLong.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalLong.empty(),
                OptionalLong.empty());

        assertThat(reading.cpuTimeMillis()).isEmpty();
        assertThat(reading.user()).isEmpty();
        assertThat(reading.state()).isEmpty();
    }

    @Test
    void aReadingRejectsNegativeCountersAndSizes() {
        assertThatIllegalArgumentException().isThrownBy(() -> reading(OptionalLong.of(-1), OptionalLong.of(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> reading(OptionalLong.of(0), OptionalLong.of(-1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessReading(
                        new ProcessIdentity(4, START),
                        "name",
                        OptionalLong.empty(),
                        Optional.of(ProcessState.RUNNING),
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        Optional.empty(),
                        OptionalInt.of(-1),
                        OptionalInt.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()));
    }

    @Test
    void aPriorityMayBeNegativeBecauseSomeSystemsNumberItThatWay() {
        ProcessReading reading = new ProcessReading(
                new ProcessIdentity(4, START),
                "name",
                OptionalLong.empty(),
                Optional.of(ProcessState.RUNNING),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.of(-20),
                OptionalLong.empty(),
                OptionalLong.empty());

        assertThat(reading.priority()).hasValue(-20);
    }

    @Test
    void aSnapshotCpuValueIsAPercentageOfTheWholeMachine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(OptionalDouble.of(100.1), OptionalDouble.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(OptionalDouble.of(-0.1), OptionalDouble.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> snapshot(OptionalDouble.of(Double.NaN), OptionalDouble.empty()));
        assertThat(snapshot(OptionalDouble.of(100), OptionalDouble.empty()).cpuPercent())
                .hasValue(100);
        assertThat(snapshot(OptionalDouble.empty(), OptionalDouble.empty()).cpuPercent())
                .isEmpty();
    }

    @Test
    void aSnapshotRateIsFiniteAndNotNegative() {
        OptionalDouble fine = OptionalDouble.of(1_000);
        for (double bad : new double[] {-1, Double.POSITIVE_INFINITY, Double.NaN}) {
            assertThatIllegalArgumentException()
                    .as("read rate %s", bad)
                    .isThrownBy(() -> snapshot(OptionalDouble.empty(), OptionalDouble.of(bad), fine));
            assertThatIllegalArgumentException()
                    .as("write rate %s", bad)
                    .isThrownBy(() -> snapshot(OptionalDouble.empty(), fine, OptionalDouble.of(bad)));
        }
        assertThat(snapshot(OptionalDouble.empty(), fine, OptionalDouble.of(0)).ioWriteBytesPerSecond())
                .hasValue(0);
    }

    @Test
    void aResultOfReadingDetailsNamesTheProcessAskedForWhateverTheOutcome() {
        ProcessIdentity identity = new ProcessIdentity(4_242, START);
        ProcessDetails details = new ProcessDetails(identity, Optional.of("C:\\bin\\tool.exe"), Optional.empty());

        ProcessDetailsResult read = ProcessDetailsResult.read(details);
        ProcessDetailsResult gone = ProcessDetailsResult.gone(identity);
        ProcessDetailsResult failed = ProcessDetailsResult.failed(identity);

        assertThat(read.identity()).isEqualTo(identity);
        assertThat(read.outcome()).isEqualTo(ProcessDetailsResult.Outcome.READ);
        assertThat(read.details()).contains(details);
        assertThat(gone.identity()).isEqualTo(identity);
        assertThat(gone.outcome()).isEqualTo(ProcessDetailsResult.Outcome.PROCESS_GONE);
        assertThat(gone.details()).isEmpty();
        assertThat(failed.identity()).isEqualTo(identity);
        assertThat(failed.outcome()).isEqualTo(ProcessDetailsResult.Outcome.FAILED);
        assertThat(failed.details()).isEmpty();
    }

    @Test
    void aResultHasDetailsExactlyWhenTheProcessWasRead() {
        ProcessIdentity identity = new ProcessIdentity(4_242, START);
        ProcessDetails details = new ProcessDetails(identity, Optional.empty(), Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ProcessDetailsResult(identity, ProcessDetailsResult.Outcome.READ, Optional.empty()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessDetailsResult(
                        identity, ProcessDetailsResult.Outcome.PROCESS_GONE, Optional.of(details)));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new ProcessDetailsResult(identity, ProcessDetailsResult.Outcome.FAILED, Optional.of(details)));
    }

    /** A result that named one process and carried the details of another could be shown for the wrong one. */
    @Test
    void aResultCannotCarryTheDetailsOfAnotherProcessThanTheOneItNames() {
        ProcessIdentity asked = new ProcessIdentity(4_242, START);
        ProcessDetails ofAReusedPid = new ProcessDetails(
                new ProcessIdentity(4_242, START.plusSeconds(30)), Optional.empty(), Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new ProcessDetailsResult(asked, ProcessDetailsResult.Outcome.READ, Optional.of(ofAReusedPid)));
    }

    private static ProcessReading reading(OptionalLong cpuTimeMillis, OptionalLong bytesRead) {
        return new ProcessReading(
                new ProcessIdentity(4, START),
                "name",
                OptionalLong.empty(),
                Optional.of(ProcessState.RUNNING),
                cpuTimeMillis,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                bytesRead,
                OptionalLong.empty());
    }

    private static ProcessSnapshot snapshot(OptionalDouble cpuPercent, OptionalDouble ioRate) {
        return snapshot(cpuPercent, ioRate, ioRate);
    }

    private static ProcessSnapshot snapshot(
            OptionalDouble cpuPercent, OptionalDouble readRate, OptionalDouble writeRate) {
        return new ProcessSnapshot(
                new ProcessIdentity(4, START),
                "name",
                OptionalLong.empty(),
                Optional.of(ProcessState.RUNNING),
                cpuPercent,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                readRate,
                writeRate);
    }
}
