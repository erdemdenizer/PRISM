package prism.srm.platform.oshi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import oshi.software.os.OSProcess;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProcessState;
import prism.srm.platform.HostSystem;

/**
 * Covers how OSHI processes become readings, with hand-written OSHI processes. The readings of a
 * real system are checked on Windows with {@code HostProcessProbe}; what these tests say about
 * Linux and macOS follows OSHI's code and has not been run there.
 */
class OshiProcessSourceTest {

    private static final long START = 1_700_000_000_000L;
    private static final HostSystem[] NOT_WINDOWS = {HostSystem.LINUX, HostSystem.MACOS, HostSystem.OTHER};

    /** What the fake system lists. A lookup by PID finds the same objects. */
    private final List<OSProcess> listed = new ArrayList<>();

    /** What the system's own source of virtual sizes holds. Only a Windows source asks it. */
    private final Map<ProcessIdentity, Long> virtualSizes = new HashMap<>();

    private boolean listingFails;
    private int lookups;
    private RuntimeException virtualSizeFailure;
    private boolean virtualSizeSourceReturnsNull;
    private Error virtualSizeError;
    private int virtualSizeReads;

    private final OshiProcessSource windows = newSource(HostSystem.WINDOWS);
    private final OshiProcessSource linux = newSource(HostSystem.LINUX);

    @Test
    void everyValueOshiHoldsBecomesPartOfTheReading() {
        listed.add(new FakeOsProcess(100, START));

        assertThat(linux.processes())
                .containsExactly(new ProcessReading(
                        new ProcessIdentity(100, Instant.ofEpochMilli(START)),
                        "fake",
                        OptionalLong.of(1),
                        Optional.of(ProcessState.RUNNING),
                        OptionalLong.of(1_000),
                        OptionalLong.of(2_000_000),
                        OptionalLong.of(1_500_000),
                        OptionalLong.of(4_000_000),
                        Optional.of("alice"),
                        OptionalInt.of(4),
                        OptionalInt.of(8),
                        OptionalLong.of(10_000),
                        OptionalLong.of(5_000)));
    }

    @Test
    void theNormalReadingNeverAsksAProcessForAnythingExpensive() {
        FakeOsProcess first = new FakeOsProcess(100, START);
        FakeOsProcess second = new FakeOsProcess(200, START);
        listed.add(first);
        listed.add(second);

        for (HostSystem system : HostSystem.values()) {
            OshiProcessSource source = newSource(system);
            source.processes();
            source.processes();
        }

        assertThat(first.expensiveCalls).isEmpty();
        assertThat(second.expensiveCalls).isEmpty();
    }

    @Test
    void whatOshiCouldNotDetermineIsAbsentAndNotZero() {
        FakeOsProcess partial = new FakeOsProcess(100, START);
        partial.parentPid = -1;
        partial.threadCount = -1;
        partial.virtualSize = -1;
        partial.residentMemory = -1;
        partial.privateResidentMemory = -1;
        partial.kernelTime = -1;
        partial.bytesRead = -1;
        partial.bytesWritten = -1;
        partial.user = "unknown";
        partial.name = null;
        listed.add(partial);

        // On Linux no value is absent for another reason, so each assertion can fail.
        ProcessReading reading = linux.processes().get(0);

        assertThat(reading.parentPid()).isEmpty();
        assertThat(reading.threadCount()).isEmpty();
        assertThat(reading.virtualBytes()).isEmpty();
        assertThat(reading.residentBytes()).isEmpty();
        assertThat(reading.privateResidentBytes()).isEmpty();
        assertThat(reading.cpuTimeMillis()).isEmpty();
        assertThat(reading.bytesRead()).isEmpty();
        assertThat(reading.bytesWritten()).isEmpty();
        assertThat(reading.user()).isEmpty();
        assertThat(reading.name()).isEmpty();
        assertThat(reading.priority()).hasValue(8);
    }

    @Test
    void theCpuTimeIsAbsentWhenEitherOfItsTwoPartsIs() {
        FakeOsProcess noKernelTime = new FakeOsProcess(100, START);
        noKernelTime.kernelTime = -1;
        FakeOsProcess noUserTime = new FakeOsProcess(200, START);
        noUserTime.userTime = -1;
        listed.add(noKernelTime);
        listed.add(noUserTime);

        assertThat(windows.processes())
                .extracting(ProcessReading::cpuTimeMillis)
                .containsOnly(OptionalLong.empty());
    }

    @Test
    void aValueOfZeroThatTheSystemReportsStaysZero() {
        FakeOsProcess idle = new FakeOsProcess(0, START);
        idle.parentPid = 0;
        idle.threadCount = 0;
        idle.residentMemory = 0;
        idle.kernelTime = 0;
        idle.userTime = 0;
        listed.add(idle);

        ProcessReading reading = windows.processes().get(0);

        assertThat(reading.parentPid()).hasValue(0);
        assertThat(reading.threadCount()).hasValue(0);
        assertThat(reading.residentBytes()).hasValue(0);
        assertThat(reading.cpuTimeMillis()).hasValue(0);
    }

    @Test
    void onWindowsIoCountersOfZeroAreTheSystemsOwnValue() {
        FakeOsProcess noIo = new FakeOsProcess(100, START);
        noIo.bytesRead = 0;
        noIo.bytesWritten = 0;
        listed.add(noIo);

        ProcessReading reading = windows.processes().get(0);

        assertThat(reading.bytesRead()).hasValue(0);
        assertThat(reading.bytesWritten()).hasValue(0);
    }

    @Test
    void elsewhereIoCountersThatAreBothZeroAreAbsentBecauseZeroAlsoMeansCouldNotBeRead() {
        FakeOsProcess unreadable = new FakeOsProcess(100, START);
        unreadable.bytesRead = 0;
        unreadable.bytesWritten = 0;
        FakeOsProcess onlyReads = new FakeOsProcess(200, START);
        onlyReads.bytesRead = 4_096;
        onlyReads.bytesWritten = 0;
        listed.add(unreadable);
        listed.add(onlyReads);

        for (HostSystem system : NOT_WINDOWS) {
            List<ProcessReading> readings = newSource(system).processes();

            assertThat(readings.get(0).bytesRead()).as("%s", system).isEmpty();
            assertThat(readings.get(0).bytesWritten()).as("%s", system).isEmpty();
            // One counter above zero shows that the process could be read, so the other zero is real.
            assertThat(readings.get(1).bytesRead()).as("%s", system).hasValue(4_096);
            assertThat(readings.get(1).bytesWritten()).as("%s", system).hasValue(0);
        }
    }

    @Test
    void onWindowsTheStateIsAbsentBecauseOshiSetsRunningWithoutAskingTheSystem() {
        listed.add(new FakeOsProcess(100, START));

        assertThat(windows.processes().get(0).state()).isEmpty();
    }

    @Test
    void aStateOshiDidDetermineOnWindowsIsKept() {
        assertThat(OshiProcessSource.stateOf(HostSystem.WINDOWS, OSProcess.State.SUSPENDED))
                .contains(ProcessState.SUSPENDED);
    }

    @Test
    void elsewhereEveryOshiStateHasACounterpart() {
        for (HostSystem system : NOT_WINDOWS) {
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.NEW)).contains(ProcessState.NEW);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.RUNNING))
                    .contains(ProcessState.RUNNING);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.SLEEPING))
                    .contains(ProcessState.SLEEPING);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.WAITING))
                    .contains(ProcessState.WAITING);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.STOPPED))
                    .contains(ProcessState.STOPPED);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.SUSPENDED))
                    .contains(ProcessState.SUSPENDED);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.ZOMBIE))
                    .contains(ProcessState.ZOMBIE);
            assertThat(OshiProcessSource.stateOf(system, OSProcess.State.OTHER)).contains(ProcessState.OTHER);
        }
    }

    @Test
    void privateMemoryIsPresentOnlyWhereOshiReturnsThatQuantity() {
        listed.add(new FakeOsProcess(100, START));

        assertThat(windows.processes().get(0).privateResidentBytes()).hasValue(1_500_000);
        assertThat(linux.processes().get(0).privateResidentBytes()).hasValue(1_500_000);
        assertThat(newSource(HostSystem.MACOS).processes().get(0).privateResidentBytes())
                .as("macOS: OSHI returns the physical footprint")
                .isEmpty();
        assertThat(newSource(HostSystem.OTHER).processes().get(0).privateResidentBytes())
                .as("elsewhere: OSHI falls back to the whole resident memory")
                .isEmpty();
    }

    @Test
    void onWindowsTheVirtualSizeIsTheSystemsOwnValueAndNeverTheDifferentQuantityOshiReturns() {
        // The fake OSHI process answers 4 000 000, which on Windows would be the page file usage.
        listed.add(new FakeOsProcess(100, START));
        virtualSizes.put(identity(100, START), 14_302_126_080L);

        assertThat(windows.processes().get(0).virtualBytes()).hasValue(14_302_126_080L);
    }

    @Test
    void elsewhereTheVirtualSizeIsOshisAndTheWindowsSourceIsNotAsked() {
        listed.add(new FakeOsProcess(100, START));
        virtualSizes.put(identity(100, START), 14_302_126_080L);

        for (HostSystem system : NOT_WINDOWS) {
            assertThat(newSource(system).processes().get(0).virtualBytes())
                    .as("%s", system)
                    .hasValue(4_000_000);
        }
        assertThat(virtualSizeReads).isZero();
    }

    @Test
    void onWindowsEachProcessGetsTheVirtualSizeOfItsOwnIdentity() {
        listed.add(new FakeOsProcess(100, START));
        listed.add(new FakeOsProcess(200, START + 7));
        virtualSizes.put(identity(200, START + 7), 2_000_000_000L);
        virtualSizes.put(identity(100, START), 1_000_000_000L);

        assertThat(windows.processes())
                .extracting(reading -> reading.identity().pid(), ProcessReading::virtualBytes)
                .containsExactly(
                        tuple(100L, OptionalLong.of(1_000_000_000L)), tuple(200L, OptionalLong.of(2_000_000_000L)));
    }

    @Test
    void onWindowsAProcessTheSourceDoesNotListHasNoVirtualSizeAndDoesNotFallBackToOshis() {
        listed.add(new FakeOsProcess(100, START));
        listed.add(new FakeOsProcess(200, START));
        virtualSizes.put(identity(200, START), 2_000_000_000L);

        List<ProcessReading> readings = windows.processes();

        assertThat(readings.get(0).virtualBytes()).isEmpty();
        assertThat(readings.get(1).virtualBytes()).hasValue(2_000_000_000L);
    }

    @Test
    void onWindowsAVirtualSizeReadForAnotherProcessUnderTheSamePidIsNotUsed() {
        // Between the listing and the read of the sizes a PID can change hands in either
        // direction: the source may hold the process that had the PID before, or the next one.
        listed.add(new FakeOsProcess(100, START));
        virtualSizes.put(identity(100, START - 60_000), 1_000_000_000L);
        virtualSizes.put(identity(100, START + 1), 2_000_000_000L);

        assertThat(windows.processes().get(0).virtualBytes()).isEmpty();
    }

    @Test
    void onWindowsAFailedReadOfTheVirtualSizesCostsOnlyTheVirtualSizesAndOnlyForThatListing() {
        listed.add(new FakeOsProcess(100, START));
        listed.add(new FakeOsProcess(200, START));
        virtualSizes.put(identity(100, START), 1_000_000_000L);
        virtualSizes.put(identity(200, START), 2_000_000_000L);
        virtualSizeFailure = new IllegalStateException("performance data unavailable");

        // Twice, so that the failure that is no longer logged is handled like the first one.
        windows.processes();
        List<ProcessReading> withoutSizes = windows.processes();

        assertThat(withoutSizes).extracting(reading -> reading.identity().pid()).containsExactly(100L, 200L);
        assertThat(withoutSizes).extracting(ProcessReading::virtualBytes).containsOnly(OptionalLong.empty());
        assertThat(withoutSizes).extracting(ProcessReading::residentBytes).containsOnly(OptionalLong.of(2_000_000));
        assertThat(withoutSizes).extracting(ProcessReading::cpuTimeMillis).containsOnly(OptionalLong.of(1_000));

        virtualSizeFailure = null;

        assertThat(windows.processes())
                .extracting(ProcessReading::virtualBytes)
                .containsExactly(OptionalLong.of(1_000_000_000L), OptionalLong.of(2_000_000_000L));
        // The source was asked again on every listing, also after it had failed.
        assertThat(virtualSizeReads).isEqualTo(3);
    }

    @Test
    void onWindowsASourceThatAnswersWithNoMapAtAllIsAFailedReadAndNotTheLossOfEveryProcess() {
        listed.add(new FakeOsProcess(100, START));
        listed.add(new FakeOsProcess(200, START));
        virtualSizeSourceReturnsNull = true;

        List<ProcessReading> readings = windows.processes();

        assertThat(readings).extracting(reading -> reading.identity().pid()).containsExactly(100L, 200L);
        assertThat(readings).extracting(ProcessReading::virtualBytes).containsOnly(OptionalLong.empty());
    }

    @Test
    void onWindowsAnErrorFromTheVirtualSizeSourceIsNotSwallowed() {
        listed.add(new FakeOsProcess(100, START));
        virtualSizeError = new LinkageError("test");

        assertThatThrownBy(windows::processes).isSameAs(virtualSizeError);
    }

    @Test
    void onWindowsTheVirtualSizesAreReadOncePerListingAndNotOncePerProcess() {
        for (int pid = 100; pid < 150; pid++) {
            listed.add(new FakeOsProcess(pid, START));
        }

        windows.processes();
        assertThat(virtualSizeReads).isEqualTo(1);

        windows.processes();
        assertThat(virtualSizeReads).isEqualTo(2);
    }

    @Test
    void onWindowsAVirtualSizeFarBeyondFourGibibytesIsKeptToTheByte() {
        // 3 971 686 010 880 bytes, 3698 GiB, is the largest value seen on the development machine.
        listed.add(new FakeOsProcess(100, START));
        listed.add(new FakeOsProcess(200, START));
        virtualSizes.put(identity(100, START), 3_971_686_010_880L);
        virtualSizes.put(identity(200, START), Long.MAX_VALUE);

        assertThat(windows.processes())
                .extracting(ProcessReading::virtualBytes)
                .containsExactly(OptionalLong.of(3_971_686_010_880L), OptionalLong.of(Long.MAX_VALUE));
    }

    @Test
    void onWindowsANegativeNumberFromTheSourceIsNotAVirtualSize() {
        listed.add(new FakeOsProcess(100, START));
        virtualSizes.put(identity(100, START), -1L);

        assertThat(windows.processes().get(0).virtualBytes()).isEmpty();
    }

    @Test
    void detailsDoNotReadTheVirtualSizes() {
        listed.add(new FakeOsProcess(100, START));

        assertThat(windows.details(identity(100, START))).isPresent();

        assertThat(virtualSizeReads).isZero();
    }

    @Test
    void aProcessThatVanishedWhileItWasReadIsLeftOut() {
        FakeOsProcess vanished = new FakeOsProcess(100, START);
        vanished.state = OSProcess.State.INVALID;
        listed.add(vanished);
        listed.add(new FakeOsProcess(200, START));

        assertThat(windows.processes())
                .extracting(reading -> reading.identity().pid())
                .containsExactly(200L);
    }

    @Test
    void aProcessWithoutAStartTimeIsLeftOutBecauseAPidAloneIsNotAnIdentity() {
        listed.add(new FakeOsProcess(100, 0));
        listed.add(new FakeOsProcess(200, START));

        assertThat(windows.processes())
                .extracting(reading -> reading.identity().pid())
                .containsExactly(200L);
    }

    @Test
    void oneProcessThatCannotBeReadDoesNotCostTheOthersTheirReading() {
        FakeOsProcess denied = new FakeOsProcess(100, START);
        denied.failure = new IllegalStateException("access denied");
        listed.add(new FakeOsProcess(50, START));
        listed.add(denied);
        listed.add(new FakeOsProcess(200, START));

        assertThat(windows.processes())
                .extracting(reading -> reading.identity().pid())
                .containsExactly(50L, 200L);
    }

    @Test
    void aFailedListingIsNotHidden() {
        listingFails = true;

        assertThatIllegalStateException().isThrownBy(windows::processes);
    }

    @Test
    void onWindowsTheUserIsAskedOncePerProcessIdentityAndNotOnEveryPoll() {
        FakeOsProcess process = new FakeOsProcess(100, START);
        listed.add(process);

        windows.processes();
        windows.processes();
        ProcessReading third = windows.processes().get(0);

        assertThat(process.userQueries).isEqualTo(1);
        assertThat(third.user()).contains("alice");
    }

    @Test
    void onWindowsAUserThatMayNotBeReadIsNotAskedForAgainEither() {
        FakeOsProcess protectedProcess = new FakeOsProcess(4, START);
        protectedProcess.user = "unknown";
        listed.add(protectedProcess);

        windows.processes();
        ProcessReading second = windows.processes().get(0);

        assertThat(protectedProcess.userQueries).isEqualTo(1);
        assertThat(second.user()).isEmpty();
    }

    @Test
    void onWindowsANewProcessUnderAReusedPidGetsItsOwnUser() {
        listed.add(new FakeOsProcess(100, START));
        windows.processes();
        listed.clear();
        FakeOsProcess reused = new FakeOsProcess(100, START + 5_000);
        reused.user = "bob";
        listed.add(reused);

        assertThat(windows.processes().get(0).user()).contains("bob");
        assertThat(reused.userQueries).isEqualTo(1);
    }

    @Test
    void onWindowsTheUserOfAProcessThatStaysIsNotAskedForAgainWhenAnotherProcessExits() {
        FakeOsProcess stays = new FakeOsProcess(100, START);
        FakeOsProcess exits = new FakeOsProcess(200, START);
        listed.add(stays);
        listed.add(exits);
        windows.processes();
        listed.remove(exits);

        windows.processes();
        windows.processes();

        assertThat(stays.userQueries).isEqualTo(1);
    }

    @Test
    void onWindowsNothingIsKeptForAProcessThatIsGone() {
        FakeOsProcess process = new FakeOsProcess(100, START);
        listed.add(process);
        windows.processes();
        listed.clear();
        windows.processes();
        listed.add(process);

        windows.processes();

        // Asked again: the user remembered for it was dropped when it left the listing.
        assertThat(process.userQueries).isEqualTo(2);
    }

    @Test
    void elsewhereAProcessThatChangesItsUserShowsTheNewUserOnTheNextReading() {
        FakeOsProcess daemon = new FakeOsProcess(100, START);
        daemon.userId = "0";
        daemon.user = "root";
        listed.add(daemon);

        assertThat(linux.processes().get(0).user()).contains("root");

        // It dropped its privileges: same PID, same start time, another user.
        daemon.userId = "33";
        daemon.user = "www-data";

        assertThat(linux.processes().get(0).user()).contains("www-data");
    }

    @Test
    void elsewhereAUserNameIsLookedUpOncePerUserIdAndNotOncePerProcess() {
        FakeOsProcess first = new FakeOsProcess(100, START);
        FakeOsProcess second = new FakeOsProcess(200, START);
        FakeOsProcess other = new FakeOsProcess(300, START);
        other.userId = "0";
        other.user = "root";
        listed.add(first);
        listed.add(second);
        listed.add(other);

        linux.processes();
        List<ProcessReading> readings = linux.processes();

        assertThat(readings)
                .extracting(ProcessReading::user)
                .containsExactly(Optional.of("alice"), Optional.of("alice"), Optional.of("root"));
        assertThat(first.userQueries + second.userQueries)
                .as("lookups for user ID 1000")
                .isEqualTo(1);
        assertThat(other.userQueries).as("lookups for user ID 0").isEqualTo(1);
    }

    @Test
    void elsewhereAUserNameIsForgottenWhenNoProcessOfThatUserIsLeft() {
        FakeOsProcess process = new FakeOsProcess(100, START);
        listed.add(process);
        linux.processes();
        listed.clear();
        linux.processes();
        listed.add(process);

        linux.processes();

        assertThat(process.userQueries).isEqualTo(2);
    }

    @Test
    void elsewhereAProcessWithoutAUserIdHasNoUser() {
        FakeOsProcess process = new FakeOsProcess(100, START);
        process.userId = "";
        listed.add(process);

        assertThat(linux.processes().get(0).user()).isEmpty();
        assertThat(process.userQueries).isZero();
    }

    @Test
    void detailsAreReadOnDemandForTheProcessWithThatIdentity() {
        FakeOsProcess process = new FakeOsProcess(100, START);
        listed.add(process);
        ProcessIdentity identity = new ProcessIdentity(100, Instant.ofEpochMilli(START));

        assertThat(windows.details(identity))
                .contains(new ProcessDetails(identity, Optional.of("/opt/fake/bin/fake"), Optional.of("fake --serve")));
        assertThat(process.expensiveCalls).containsExactlyInAnyOrder("getPath", "getCommandLine");
    }

    @Test
    void detailsThatTheSystemDoesNotGiveThisUserAreAbsent() {
        FakeOsProcess protectedProcess = new FakeOsProcess(4, START);
        protectedProcess.path = "";
        protectedProcess.commandLine = "";
        listed.add(protectedProcess);
        ProcessIdentity identity = new ProcessIdentity(4, Instant.ofEpochMilli(START));

        assertThat(windows.details(identity))
                .contains(new ProcessDetails(identity, Optional.empty(), Optional.empty()));
    }

    @Test
    void thereAreNoDetailsForAProcessThatIsGone() {
        assertThat(windows.details(new ProcessIdentity(100, Instant.ofEpochMilli(START))))
                .isEmpty();
    }

    @Test
    void thereAreNoDetailsWhenThePidNowBelongsToAnotherProcess() {
        FakeOsProcess reused = new FakeOsProcess(100, START + 5_000);
        listed.add(reused);

        assertThat(windows.details(new ProcessIdentity(100, Instant.ofEpochMilli(START))))
                .isEmpty();
        // The other process must not even be asked for its command line.
        assertThat(reused.expensiveCalls).isEmpty();
    }

    @Test
    void thereAreNoDetailsWhenThePidChangesHandsWhileTheCommandLineIsBeingRead() {
        FakeOsProcess original = new FakeOsProcess(100, START);
        FakeOsProcess reused = new FakeOsProcess(100, START + 5_000);
        int[] lookupsOfPid = {0};
        // The first lookup finds the original; by the second, its PID belongs to another process.
        OshiProcessSource source = new OshiProcessSource(
                List::of, pid -> lookupsOfPid[0]++ == 0 ? original : reused, HostSystem.WINDOWS, Map::of);

        assertThat(source.details(new ProcessIdentity(100, Instant.ofEpochMilli(START))))
                .isEmpty();
        assertThat(lookupsOfPid[0]).isEqualTo(2);
    }

    @Test
    void thereAreNoDetailsForAProcessThatVanishedWhileItWasLookedUp() {
        FakeOsProcess vanished = new FakeOsProcess(100, START);
        vanished.state = OSProcess.State.INVALID;
        listed.add(vanished);

        assertThat(windows.details(new ProcessIdentity(100, Instant.ofEpochMilli(START))))
                .isEmpty();
        assertThat(vanished.expensiveCalls).isEmpty();
    }

    @Test
    void aPidOshiCannotAddressIsNotLookedUpUnderATruncatedNumber() {
        // 2^32 + 100 truncated to 32 bits is 100, the PID of a process that does exist.
        listed.add(new FakeOsProcess(100, START));

        assertThat(windows.details(new ProcessIdentity((1L << 32) + 100, Instant.ofEpochMilli(START))))
                .isEmpty();
        assertThat(lookups).isZero();
    }

    private OshiProcessSource newSource(HostSystem system) {
        return new OshiProcessSource(
                () -> {
                    if (listingFails) {
                        throw new IllegalStateException("listing failed");
                    }
                    return List.copyOf(listed);
                },
                pid -> {
                    lookups++;
                    return listed.stream()
                            .filter(process -> process.getProcessID() == pid)
                            .findFirst()
                            .orElse(null);
                },
                system,
                () -> {
                    virtualSizeReads++;
                    if (virtualSizeError != null) {
                        throw virtualSizeError;
                    }
                    if (virtualSizeFailure != null) {
                        throw virtualSizeFailure;
                    }
                    return virtualSizeSourceReturnsNull ? null : Map.copyOf(virtualSizes);
                });
    }

    private static ProcessIdentity identity(long pid, long startMillis) {
        return new ProcessIdentity(pid, Instant.ofEpochMilli(startMillis));
    }
}
