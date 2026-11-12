package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit.Verdict;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;

/**
 * The Job Object limiter against the fake system (plan revision 4.1, §6.2 to §8.6): every path of
 * the first assignment, of a change of a live job, of a process that exited and of recovery, with
 * the handles it keeps. The invariant checked after each case: the open job handles are exactly the
 * jobs the limiter holds; no handle is ever closed twice.
 */
class WindowsJobLimiterTest {

    private static final int PID = 4100;
    private static final Instant START = Instant.parse("2026-10-10T08:00:00.123Z");
    private static final ProcessIdentity DEMO = new ProcessIdentity(PID, START);
    private static final String NAME = "Local\\Prism.Job.4100." + START.toEpochMilli();
    private static final long MIB = 1L << 20;

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsJobLimiter limiter = new WindowsJobLimiter(win32);

    @BeforeEach
    void demo() {
        win32.process(PID, START);
    }

    @AfterEach
    void handlesAreTheHolds() {
        assertThat(win32.doubleCloses()).isEmpty();
        long held = limiterHolds();
        assertThat(win32.openJobHandles()).hasSize((int) held);
        assertThat(win32.openHandles().stream().filter(what -> what.startsWith("process ")))
                .as("no process handle outlives a call")
                .isEmpty();
    }

    private long limiterHolds() {
        return limiter.hold(DEMO).isPresent() ? 1 : 0;
    }

    private static LimitSpec cpu(int percent) {
        return new LimitSpec(OptionalInt.of(percent), OptionalLong.empty());
    }

    private static LimitSpec both(int percent, long mib) {
        return new LimitSpec(OptionalInt.of(percent), OptionalLong.of(mib * MIB));
    }

    private FakeWin32.Job job() {
        return win32.jobs.stream().filter(j -> NAME.equals(j.name)).findFirst().orElseThrow();
    }

    private void enforced(LimitSpec spec) {
        assertThat(limiter.apply(DEMO, spec).phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        win32.calls.clear();
    }

    // ----- Capabilities -----

    @Test
    void theProbeSetsAndReadsBothLimitsOnAJobWithoutAProcessAndLetsItGo() {
        assertThat(limiter.capabilities().get(LimitKind.CPU).level()).isEqualTo(CapabilityLevel.NATIVE_HARD);
        assertThat(limiter.capabilities().get(LimitKind.MEMORY).level()).isEqualTo(CapabilityLevel.NATIVE_HARD);
        assertThat(win32.calls)
                .contains("CreateJobObject null", "SetCpuRate 0x5 2000", "SetExtendedLimits 0x100 1073741824");
        assertThat(win32.jobs.get(0).members).isEmpty();
    }

    @Test
    void dynamicFairShareSchedulingMakesTheCpuLimitUnavailableWithItsReasonButNotTheMemoryLimit() {
        win32.cpuRateErrorForAll = 50;
        assertThat(limiter.capabilities().get(LimitKind.CPU).reason())
                .hasValueSatisfying(reason -> assertThat(reason).contains("Dynamic Fair Share Scheduling"));
        assertThat(limiter.capabilities().get(LimitKind.MEMORY).available()).isTrue();
        assertThat(limiter.apply(DEMO, cpu(20)).outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
    }

    @Test
    void anotherFailureOfTheProbeIsReportedAsWhatItIsNotAsSchedulingPolicy() {
        win32.cpuRateErrorForAll = 87;
        assertThat(limiter.capabilities().get(LimitKind.CPU).reason())
                .hasValueSatisfying(
                        reason -> assertThat(reason).contains("probe failed").doesNotContain("Fair Share"));
    }

    @Test
    void aProcessInAJobPrismDoesNotHoldIsUnavailableForANativeLimitAndNothingIsCreated() {
        FakeWin32.Job other = new FakeWin32.Job("someone else");
        win32.processes.get(PID).jobs.add(other);

        assertThat(limiter.capabilitiesFor(DEMO).get(LimitKind.CPU).reason()).contains(WindowsJobLimiter.NOT_HELD_JOB);
        win32.calls.clear();
        ApplyResult result = limiter.apply(DEMO, cpu(20));

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.fallbackEligible()).isTrue();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("CreateJobObject") || call.startsWith("Assign"));
    }

    @Test
    void aProcessPrismMayNotOpenWithTheAssignRightsIsRefusedAndTheFallbackMayBeOffered() {
        win32.processes.get(PID).deniedRights = WindowsJobLimiter.ASSIGN_RIGHTS;
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.fallbackEligible()).isTrue();
        assertThat(limiter.capabilitiesFor(DEMO).get(LimitKind.MEMORY).reason())
                .hasValueSatisfying(reason -> assertThat(reason).contains("refused"));
    }

    @Test
    void aProcessThatExitedOrIsAnotherWithTheSamePidIsNotFoundAndNoFallbackIsOffered() {
        ApplyResult reused = limiter.apply(new ProcessIdentity(PID, START.plusSeconds(1)), cpu(20));
        assertThat(reused.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(reused.fallbackEligible()).isFalse();
        assertThat(win32.jobs.stream().filter(j -> j.name != null)).isEmpty();
    }

    // ----- The first assignment -----

    @Test
    void aFirstAssignmentSetsTheLimitsOnTheEmptyJobBeforeTheProcessJoinsAndReadsThemBack() {
        limiter.capabilities();
        win32.calls.clear();

        ApplyResult result = limiter.apply(DEMO, both(20, 512));

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(result.observed()).contains(both(20, 512).effective());
        assertThat(win32.calls)
                .containsSubsequence(
                        "OpenProcess 4100 0x1101",
                        "IsProcessInJob 4100 any",
                        "CreateJobObject " + NAME,
                        "SetCpuRate 0x5 2000",
                        "SetExtendedLimits 0x100 " + 512 * MIB,
                        "AssignProcessToJobObject 4100",
                        "IsProcessInJob 4100 job");
        assertThat(job().members).containsExactly(PID);
        assertThat(limiter.hold(DEMO))
                .contains(new NativeHold(
                        NativeHold.State.ASSIGNED, Optional.of(both(20, 512).effective())));
        assertThat(win32.calls).noneMatch(call -> call.contains("0x2000") || call.contains("Terminate"));
    }

    @Test
    void aJobOfTheSameNameIsNeverUsedAndOnlyPrismsOwnHandleToItIsClosed() {
        Win32.Created foreign = win32.createJob(NAME);

        ApplyResult result = limiter.apply(DEMO, cpu(20));

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.outcome().detail())
                .hasValueSatisfying(d -> assertThat(d).contains("exists already"));
        assertThat(job().members).isEmpty();
        assertThat(win32.openJobHandles()).hasSize(1);
        win32.close(foreign.handle());
    }

    @Test
    void aNameTakenByAnotherKindOfObjectIsSaid() {
        limiter.capabilities();
        win32.createJobError = 6;
        assertThat(limiter.apply(DEMO, cpu(20)).outcome().detail())
                .hasValueSatisfying(d -> assertThat(d).contains("another kind of object"));
    }

    @Test
    void aLimitThatCannotBeSetOnTheEmptyJobEndsItBeforeAnyAssignment() {
        limiter.capabilities();
        win32.afterSet = () -> job().setMemoryError = 1450;
        ApplyResult result = limiter.apply(DEMO, both(20, 512));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Assign"));
        assertThat(job().handles).isZero();
    }

    @Test
    void aRefusedAssignmentThatTheSystemConfirmsDidNotHappenEndsTheJob() {
        limiter.capabilities();
        win32.afterSet = () -> job().assignError = 5;
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.fallbackEligible()).isTrue();
        assertThat(limiter.hold(DEMO)).isEmpty();
    }

    @Test
    void aFailedAssignmentAfterWhichTheProcessIsInTheJobAnywayIsUncertainAndKeepsTheJob() {
        limiter.capabilities();
        win32.afterSet = () -> {
            job().assignError = 31;
            job().assignTakesEffectAnyway = true;
        };
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(result.fallbackEligible()).isFalse();
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.UNCERTAIN);
        assertThat(limiter.hold(DEMO).orElseThrow().mayCapCpu()).isTrue();
        assertThat(win32.named).containsKey(NAME);
    }

    @Test
    void anAssignmentWhoseMembershipCannotBeReadIsUncertainAndKeepsTheJob() {
        limiter.capabilities();
        win32.afterSet = () -> job().membershipUnreadable = true;
        assertThat(limiter.apply(DEMO, cpu(20)).phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(job().handles).isEqualTo(1);
    }

    @Test
    void anAssignmentThatThrowsAfterItWasAttemptedIsUncertainAndNoFinallyClosesTheJob() {
        limiter.capabilities();
        win32.assignThrows = new IllegalStateException("the JNA call failed");
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.UNCERTAIN);
        assertThat(result.outcome().detail())
                .hasValueSatisfying(d -> assertThat(d).contains("JNA call failed"));
        assertThat(job().handles).isEqualTo(1);
    }

    @Test
    void aProcessThatExitsAsItIsAssignedIsClearedAndLetGo() {
        limiter.capabilities();
        win32.afterAssign = () -> win32.exit(PID);
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_RELEASED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(job().cpuFlags).isZero();
        assertThat(job().handles).isZero();
    }

    @Test
    void aProcessThatExitsAsItIsAssignedWithLimitsThatCannotBeClearedLeavesTheJobHeld() {
        limiter.capabilities();
        win32.afterAssign = () -> {
            win32.exit(PID);
            job().setCpuError = 5;
        };
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);
        assertThat(job().handles).isEqualTo(1);
    }

    @Test
    void aJobThatReadsBackOtherwiseAfterTheAssignmentIsAMismatchNotASuccess() {
        limiter.capabilities();
        win32.afterAssign = () -> job().cpuRate = 3000;
        ApplyResult result = limiter.apply(DEMO, cpu(20));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ASSIGNED_MISMATCH);
        assertThat(result.outcome().succeeded()).isFalse();
        assertThat(result.observed()).contains(cpu(30).effective());
        assertThat(limiter.hold(DEMO).orElseThrow().inForce()).contains(cpu(30).effective());
    }

    @Test
    void aSecondAssignmentOfAHeldProcessIsRefusedWithoutAnyCall() {
        enforced(cpu(20));
        ApplyResult result = limiter.apply(DEMO, cpu(30));
        assertThat(result.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(win32.calls).isEmpty();
    }

    // ----- Changes of a live job -----

    @Test
    void aChangeSetsOnlyTheKindThatChangesAndNeverAssigns() {
        enforced(both(20, 512));
        ChangeResult result = limiter.change(DEMO, both(30, 512));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(win32.calls)
                .contains("SetCpuRate 0x5 3000")
                .noneMatch(call -> call.startsWith("SetExtended") || call.startsWith("Assign"));
        assertThat(job().cpuRate).isEqualTo(3000);
    }

    @Test
    void aChangeWhoseMemoryCallFailsAfterTheCpuCallIsPartialAndSaysWhatIsInForce() {
        enforced(both(20, 512));
        job().setMemoryError = 5;
        ChangeResult result = limiter.change(DEMO, both(30, 256));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.PARTIAL);
        assertThat(result.observed()).contains(both(30, 512).effective());
        assertThat(limiter.hold(DEMO).orElseThrow().inForce())
                .contains(both(30, 512).effective());
    }

    @Test
    void aChangeThatCannotBeReadBackIsUnverifiedAndTheJobIsKnownToHoldNothingCertain() {
        enforced(both(20, 512));
        job().readsBeforeFailure = 2;
        ChangeResult result = limiter.change(DEMO, both(30, 256));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(limiter.hold(DEMO).orElseThrow().settled()).isFalse();
        assertThat(limiter.hold(DEMO).orElseThrow().mayCapCpu()).isTrue();
    }

    @Test
    void aChangeThatCannotReadTheJobFirstSetsNothing() {
        enforced(cpu(20));
        job().readsBeforeFailure = 0;
        ChangeResult result = limiter.change(DEMO, cpu(30));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Set"));
        assertThat(limiter.hold(DEMO).orElseThrow().inForce()).contains(cpu(20).effective());
    }

    @Test
    void clearingTakesBothKindsOffAndKeepsTheJobWhileTheProcessLives() {
        enforced(both(20, 512));
        ChangeResult result = limiter.change(DEMO, LimitSpec.NONE);
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(win32.calls).contains("SetCpuRate 0x0 0", "SetExtendedLimits 0x0 0");
        assertThat(limiter.hold(DEMO))
                .contains(new NativeHold(NativeHold.State.ASSIGNED, Optional.of(EffectiveLimits.NONE)));
        assertThat(win32.named).containsKey(NAME);
        assertThat(limiter.change(DEMO, cpu(15)).phase())
                .as("a later limit reuses the job")
                .isEqualTo(ChangeResult.Phase.APPLIED);
    }

    @Test
    void aClearThatFailsForOneKindIsPartial() {
        enforced(both(20, 512));
        job().setMemoryError = 5;
        ChangeResult result = limiter.change(DEMO, LimitSpec.NONE);
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.PARTIAL);
        assertThat(result.observed().orElseThrow().memoryBytes()).hasValue(512 * MIB);
    }

    @Test
    void aChangeOfAProcessThatHasNoJobOrHasExitedIsNotAChange() {
        assertThat(limiter.change(DEMO, cpu(20)).phase()).isEqualTo(ChangeResult.Phase.NO_HOLD);
        enforced(cpu(20));
        win32.exit(PID);
        ChangeResult result = limiter.change(DEMO, cpu(30));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.NOT_DONE);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(limiter.forget(DEMO).released()).isTrue();
    }

    @Test
    void anUncertainAssignmentIsAskedAgainAtTheNextChange() {
        limiter.capabilities();
        win32.afterSet = () -> job().membershipUnreadable = true;
        limiter.apply(DEMO, cpu(20));
        win32.afterSet = () -> {};
        job().membershipUnreadable = false;

        ChangeResult result = limiter.change(DEMO, cpu(30));

        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.APPLIED);
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.ASSIGNED);
    }

    @Test
    void anUncertainAssignmentProvenNotToHaveHappenedLetsTheJobGo() {
        limiter.capabilities();
        win32.afterSet = () -> {
            job().assignError = 31;
            job().membershipUnreadable = true;
        };
        limiter.apply(DEMO, cpu(20));
        win32.afterSet = () -> {};
        job().membershipUnreadable = false;

        ChangeResult result = limiter.change(DEMO, cpu(30));

        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.NO_HOLD);
        assertThat(limiter.hold(DEMO)).isEmpty();
    }

    // ----- A process that exited -----

    @Test
    void aProcessThatStillRunsIsNotForgotten() {
        enforced(cpu(20));
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.STILL_RUNS);
        assertThat(limiter.hold(DEMO)).isPresent();
    }

    /** Review of stage 2, item 4: only a process shown to be gone lets its job go. */
    @Test
    void aProcessThatCannotBeOpenedIsNotTakenForGone() {
        enforced(cpu(20));
        win32.processes.get(PID).openError = 5;
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.STILL_RUNS);
        assertThat(job().cpuRate).isEqualTo(2000);
        win32.processes.get(PID).openError = 0;
    }

    /** Review of stage 2, item 1: an Error after the attempt cannot lose the job, and the shutdown still reaches it. */
    @Test
    void anErrorThrownAfterTheAssignmentWasAttemptedLeavesTheJobHeld() {
        limiter.capabilities();
        win32.assignThrows = new StackOverflowError("thrown by the call");
        try {
            limiter.apply(DEMO, cpu(20));
        } catch (StackOverflowError expected) {
            // An Error is never caught by the limiter.
        }
        assertThat(limiter.hold(DEMO).orElseThrow().state()).isEqualTo(NativeHold.State.UNCERTAIN);
        assertThat(job().handles).isEqualTo(1);
        assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsKey(DEMO);
        assertThat(job().handles).isZero();
    }

    /** Review of stage 2, item 7: membership cannot be undone, so nothing is assigned for no limit. */
    @Test
    void noProcessIsPutInAJobForNoLimit() {
        assertThat(limiter.apply(DEMO, LimitSpec.NONE).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("CreateJobObject " + NAME));
    }

    /** Review of stage 2, item 6: the code of the failed call travels; 50 is unsupported, not a plain error. */
    @Test
    void aChangeRefusedByDynamicFairShareSchedulingSaysSo() {
        enforced(both(20, 512));
        job().setCpuError = 50;
        ChangeResult result = limiter.change(DEMO, both(30, 512));
        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.PARTIAL);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.outcome().nativeCode()).hasValue(50);
    }

    @Test
    void anExitedProcessWhoseJobIsEmptyIsLetGo() {
        enforced(cpu(20));
        win32.exit(PID);
        ForgetResult result = limiter.forget(DEMO);
        assertThat(result.result()).isEqualTo(ForgetResult.Outcome.NOTHING_TO_RELEASE);
        assertThat(win32.named).doesNotContainKey(NAME);
    }

    @Test
    void theChildrenOfAnExitedProcessAreFreedOfItsLimitsBeforeTheJobIsLetGo() {
        enforced(both(20, 512));
        job().extraActive = 2;
        win32.exit(PID);

        ForgetResult result = limiter.forget(DEMO);

        assertThat(result.result()).isEqualTo(ForgetResult.Outcome.RELEASED);
        assertThat(result.survivors()).isEqualTo(2);
        assertThat(job().cpuFlags).isZero();
        assertThat(job().limitFlags).isZero();
        assertThat(job().handles).isZero();
    }

    @Test
    void limitsThatCannotBeClearedFromTheChildrenStayAResidueAndAreRetried() {
        enforced(cpu(20));
        job().extraActive = 1;
        job().setCpuError = 5;
        win32.exit(PID);

        ForgetResult first = limiter.forget(DEMO);
        assertThat(first.result()).isEqualTo(ForgetResult.Outcome.RESIDUE);
        assertThat(first.inForce()).contains(cpu(20).effective());
        assertThat(job().handles).as("the job is not let go").isEqualTo(1);

        job().setCpuError = 0;
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.RELEASED);
    }

    /** At shutdown a residue gets its last try, and is let go only once its limits read back cleared. */
    @Test
    void releaseAllClearsAResidueAndLetsItsJobGo() {
        enforced(cpu(20));
        job().extraActive = 1;
        job().setCpuError = 5;
        win32.exit(PID);
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.RESIDUE);
        job().setCpuError = 0;

        assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsEntry(DEMO, true);

        assertThat(job().cpuFlags).isZero();
        assertThat(job().handles).isZero();
    }

    /** A change that throws after its first call is unverified: the job is kept, and nothing is believed. */
    @Test
    void aChangeThatThrowsAfterItsFirstCallIsUnverifiedAndKeepsTheJob() {
        enforced(both(20, 512));
        win32.afterSet = () -> {
            throw new IllegalStateException("the call failed half way");
        };

        ChangeResult result = limiter.change(DEMO, both(30, 256));

        assertThat(result.phase()).isEqualTo(ChangeResult.Phase.UNVERIFIED);
        assertThat(limiter.hold(DEMO).orElseThrow().settled()).isFalse();
        assertThat(job().handles).as("the job is kept").isEqualTo(1);
    }

    @Test
    void anUncertainJobOfAnExitedProcessIsLetGoOnlyWhenItsAccountingShowsItEmpty() {
        limiter.capabilities();
        win32.afterSet = () -> job().membershipUnreadable = true;
        limiter.apply(DEMO, cpu(20));
        win32.exit(PID);
        job().accountingUnreadable = true;
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.UNRESOLVED);
        job().accountingUnreadable = false;
        assertThat(limiter.forget(DEMO).result()).isEqualTo(ForgetResult.Outcome.NOTHING_TO_RELEASE);
    }

    // ----- Recovery -----

    private LimitRecord record(EffectiveLimits values) {
        return LimitRecord.intended(DEMO, "demo", values, "9-9").applied(values);
    }

    @Test
    void afterACrashTheNameIsGoneAndARunningProcessInAJobIsReportedAsPossiblyStillLimited() {
        win32.processes.get(PID).jobs.add(new FakeWin32.Job(null));
        assertThat(limiter.recover(record(cpu(20).effective())).verdict()).isEqualTo(Verdict.UNREACHABLE);
        assertThat(win32.calls).contains("OpenJobObject 0x4 " + NAME).noneMatch(call -> call.startsWith("Set"));
    }

    @Test
    void aGoneProcessIsReportedAsPossiblyLeavingLimitedChildrenUnlessTheRecordWasCleared() {
        LimitRecord applied = record(cpu(20).effective());
        win32.processes.remove(PID);
        assertThat(limiter.recover(applied).verdict()).isEqualTo(Verdict.GONE_CHILDREN_UNKNOWN);
        assertThat(limiter.recover(LimitRecord.intended(DEMO, "demo", cpu(20).effective(), "9-9"))
                        .verdict())
                .as("INTENDED does not prove the assignment did not happen")
                .isEqualTo(Verdict.GONE_CHILDREN_UNKNOWN);
        assertThat(limiter.recover(applied.cleared()).verdict()).isEqualTo(Verdict.GONE);
    }

    @Test
    void aRunningProcessInNoJobProvesTheLimitNeverTookEffect() {
        assertThat(limiter.recover(LimitRecord.intended(DEMO, "demo", cpu(20).effective(), "9-9"))
                        .verdict())
                .isEqualTo(Verdict.NOT_IN_JOB);
    }

    /**
     * Audit of the recovery trust boundary, C: a job that can still be opened by its name has a live
     * holder, because a name lives only while a handle is open (Life Cycle of an Object; E2). Here the
     * holder is another limiter that still runs. Recovery reads, reports, and changes nothing.
     */
    @Test
    void aJobStillReachableByNameHasALiveHolderAndIsNeverChangedByRecovery() {
        enforced(both(20, 512));
        LimitRecord changing =
                record(both(20, 512).effective()).changing(both(30, 256).effective());
        job().cpuRate = 3000;
        WindowsJobLimiter later = new WindowsJobLimiter(win32);
        win32.calls.clear();

        prism.srm.domain.RecoveredLimit found = later.recover(changing);

        assertThat(found.verdict()).isEqualTo(Verdict.HELD_ELSEWHERE);
        assertThat(found.blocksAnotherLimiter()).isTrue();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Set"));
        assertThat(job().cpuRate).isEqualTo(3000);
        assertThat(job().limitFlags).isNotZero();
        assertThat(job().handles).as("only the live holder's handle is left").isEqualTo(1);
    }

    /** A held job with no limit now still blocks: its running holder may set one at any moment. */
    @Test
    void aReachableJobWithNoLimitNowIsStillHeldElsewhere() {
        enforced(cpu(20));
        limiter.change(DEMO, LimitSpec.NONE);
        WindowsJobLimiter later = new WindowsJobLimiter(win32);
        win32.calls.clear();

        prism.srm.domain.RecoveredLimit found = later.recover(record(cpu(20).effective()));

        assertThat(found.verdict()).isEqualTo(Verdict.HELD_ELSEWHERE);
        assertThat(found.blocksAnotherLimiter()).isTrue();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Set"));
    }

    /** The process has exited while a running program holds its job: the ones it started may be limited. */
    @Test
    void aReachableJobOfAnExitedProcessSaysItsChildrenMayBeLimitedAndBlocksNoIdentity() {
        enforced(cpu(20));
        win32.exit(PID);
        WindowsJobLimiter later = new WindowsJobLimiter(win32);
        win32.calls.clear();

        prism.srm.domain.RecoveredLimit found = later.recover(record(cpu(20).effective()));

        assertThat(found.verdict()).isEqualTo(Verdict.GONE_CHILDREN_UNKNOWN);
        assertThat(found.blocksAnotherLimiter()).isFalse();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Set"));
        assertThat(job().cpuRate).isEqualTo(2000);
    }

    /** The handle recovery opens cannot change the job, even by mistake: the fake refuses as the system does. */
    @Test
    void aHandleOpenedForRecoveryCannotSetLimits() {
        enforced(cpu(20));
        Win32.Opened read = win32.openJob(WindowsJobLimiter.REOPEN_RIGHTS, NAME);
        try {
            assertThat(win32.setCpuRate(read.handle(), 0, 0))
                    .as("ERROR_ACCESS_DENIED")
                    .isEqualTo(5);
            assertThat(job().cpuRate).isEqualTo(2000);
        } finally {
            win32.close(read.handle());
        }
    }

    @Test
    void aJobOfPrismsNameHoldingValuesPrismNeverSetIsLeftAlone() {
        enforced(cpu(20));
        job().cpuRate = 4000;
        WindowsJobLimiter later = new WindowsJobLimiter(win32);
        win32.calls.clear();

        assertThat(later.recover(record(cpu(20).effective())).verdict()).isEqualTo(Verdict.VALUES_MISMATCH);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Set"));
        assertThat(job().cpuRate).isEqualTo(4000);
    }

    /** Review of stage 2, item 2: a later look at an earlier record never touches the job this session holds. */
    @Test
    void recoveryLeavesTheJobThisSessionHoldsAlone() {
        enforced(cpu(20));
        assertThat(limiter.recover(record(cpu(20).effective())).verdict()).isEqualTo(Verdict.NOT_IN_JOB);
        assertThat(job().cpuRate).isEqualTo(2000);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("OpenJobObject") || call.startsWith("Set"));
    }

    /** Review of stage 2, item 3: a membership that cannot be read proves nothing. */
    @Test
    void aMembershipThatCannotBeReadIsAFailureNotProofThatTheLimitNeverTookEffect() {
        enforced(cpu(20));
        WindowsJobLimiter later = new WindowsJobLimiter(win32);
        job().membershipUnreadable = true;
        assertThat(later.recover(record(cpu(20).effective())).verdict()).isEqualTo(Verdict.FAILED);
        job().membershipUnreadable = false;
    }

    @Test
    void aClearedRecordOfARunningProcessInItsOldJobIsNoWarning() {
        win32.processes.get(PID).jobs.add(new FakeWin32.Job(null));
        assertThat(limiter.recover(record(cpu(20).effective()).cleared()).verdict())
                .isEqualTo(Verdict.NOTHING_TO_CLEAR);
        assertThat(LedgerState.CLEARED)
                .isEqualTo(record(cpu(20).effective()).cleared().state());
    }

    // ----- Shutdown -----

    @Test
    void releaseAllClearsEveryJobLetsItGoAndRefusesWhatComesAfter() {
        enforced(both(20, 512));
        assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsEntry(DEMO, true);
        assertThat(job().cpuFlags).isZero();
        assertThat(job().handles).isZero();
        assertThat(limiter.apply(DEMO, cpu(20)).outcome().detail()).contains("Prism is closing");
        assertThat(win32.jobs.stream().filter(j -> j.name != null && j.handles > 0))
                .isEmpty();
        assertThat(job().members).as("membership stays; only the limits go").containsExactly(PID);
    }

    /** A shutdown thread that was interrupted still clears: a cap left now lasts until its process exits. */
    @Test
    void releaseAllClearsAlsoOnAnInterruptedThreadAndKeepsTheInterrupt() {
        enforced(both(20, 512));
        Thread.currentThread().interrupt();
        try {
            assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsEntry(DEMO, true);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is kept")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(job().cpuFlags).isZero();
        assertThat(job().handles).isZero();
    }

    /**
     * Audit item 3: an assignment in flight holds the limiter while the shutdown runs out of time.
     * The answer is never "nothing left": it says the clearing could not begin and names the job held,
     * whose handle stays; nothing is assigned after the shutdown began.
     */
    @Test
    void aShutdownThatCannotTakeTheLimiterInTimeSaysSoAndNamesTheHoldsItLeaves() throws Exception {
        limiter.capabilities();
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        win32.afterAssign = () -> {
            inside.countDown();
            try {
                go.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.CompletableFuture<ApplyResult> inFlight =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> limiter.apply(DEMO, cpu(20)));
        assertThat(inside.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        ReleaseResult result = limiter.releaseAll(Duration.ofMillis(100));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.cleared()).isEmpty();
        assertThat(result.notReached())
                .as("the job the assignment in flight holds")
                .containsExactly(DEMO);
        assertThat(result.notCleared()).isEqualTo(1);
        go.countDown();
        ApplyResult applied = inFlight.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(applied.held())
                .as("the assignment in flight completed and holds its job")
                .isTrue();
        assertThat(job().handles).as("the handle is kept, not lost").isEqualTo(1);
        assertThat(limiter.apply(new ProcessIdentity(PID + 1, START), cpu(20))
                        .outcome()
                        .detail())
                .as("nothing is assigned after the shutdown began")
                .contains("Prism is closing");
        // The exit hook after a timed-out shutdown: the hold left behind can still be cleared.
        assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsEntry(DEMO, true);
        assertThat(job().handles).isZero();
    }

    @Test
    void releaseAllSaysWhichLimitsItCouldNotVerifyAsCleared() {
        enforced(cpu(20));
        job().setCpuError = 5;
        assertThat(limiter.releaseAll(Duration.ofSeconds(1)).cleared()).containsEntry(DEMO, false);
    }
}
