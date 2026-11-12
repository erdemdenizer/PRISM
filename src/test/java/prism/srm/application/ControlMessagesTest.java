package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.application.ControlMessages.Wording;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * CTL-2: plain sentences that say what was done and never what was not seen; a sentence the user
 * reads first, and where it helps, what it does not say.
 */
class ControlMessagesTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.EPOCH);

    private static ControlRequest request(ControlAction action) {
        return action == ControlAction.CHANGE_PRIORITY
                ? ControlRequest.priority(DEMO, "java", PriorityLevel.ABOVE_NORMAL)
                : ControlRequest.of(action, DEMO, "java");
    }

    @Test
    void successSaysWhatWasDoneAndWhatItDoesNotMean() {
        assertThat(ControlMessages.of(request(ControlAction.END_TASK), ControlOutcome.success()))
                .isEqualTo(new Wording("Close request sent to java (PID 4242).", "The application may ignore it."));
        assertThat(ControlMessages.of(request(ControlAction.FORCE_KILL), ControlOutcome.success()))
                .isEqualTo(new Wording(
                        "Force termination requested for java (PID 4242).", "The table shows when it has ended."));
        assertThat(ControlMessages.of(request(ControlAction.CHANGE_PRIORITY), ControlOutcome.success()))
                .isEqualTo(new Wording(
                        "Priority of java (PID 4242) set to Above normal.",
                        "The table shows it with the next listing."));
        assertThat(ControlMessages.of(request(ControlAction.SUSPEND), ControlOutcome.success()))
                .isEqualTo(new Wording("java (PID 4242) was suspended.", "Its threads were stopped one by one."));
    }

    @ParameterizedTest
    @EnumSource(ControlAction.class)
    void noWordingClaimsAnExitItDidNotObserveOrShowsACode(ControlAction action) {
        for (OperationResult result : OperationResult.values()) {
            Wording wording = ControlMessages.of(request(action), ControlOutcome.of(result, 31, "TECHNICAL-DETAIL-7"));
            for (String part : new String[] {wording.headline(), wording.explanation()}) {
                assertThat(part)
                        .as("%s %s", action, result)
                        .doesNotContainIgnoringCase("terminated")
                        .doesNotContainIgnoringCase("was closed")
                        .doesNotContainIgnoringCase("has exited")
                        .doesNotContain("TECHNICAL-DETAIL-7")
                        .doesNotContain("31");
            }
            assertThat(wording.headline()).as("%s %s", action, result).isNotBlank();
        }
    }

    @Test
    void failuresAreNamedPlainlyWithoutCodes() {
        ControlRequest suspend = request(ControlAction.SUSPEND);
        assertThat(ControlMessages.of(suspend, ControlOutcome.of(OperationResult.ACCESS_DENIED, 5, "OpenProcess")))
                .isEqualTo(new Wording(
                        "Access denied: java (PID 4242).", "The system does not let Prism act on this process."));
        assertThat(ControlMessages.of(suspend, ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone")))
                .isEqualTo(new Wording("java (PID 4242) is no longer running.", ""));
        assertThat(ControlMessages.of(suspend, ControlOutcome.of(OperationResult.NATIVE_ERROR, 31, "SuspendThread")))
                .isEqualTo(
                        new Wording("The system did not suspend java (PID 4242).", "The Logs page has the details."));
        assertThat(ControlMessages.of(
                        request(ControlAction.END_TASK), ControlOutcome.of(OperationResult.UNSUPPORTED, "")))
                .isEqualTo(new Wording("java (PID 4242) has no window to ask to close.", "Use Force kill instead."));
    }

    @Test
    void refusalsSayWhyPrismDoesNotAct() {
        ControlRequest kill = request(ControlAction.FORCE_KILL);
        assertThat(ControlMessages.refused(kill, ProtectionRules.Refusal.OWN_PROCESS))
                .isEqualTo(new Wording("Prism does not act on java (PID 4242).", "It is Prism's own process."));
        assertThat(ControlMessages.refused(kill, ProtectionRules.Refusal.CRITICAL_PROCESS))
                .isEqualTo(new Wording("Prism does not act on java (PID 4242).", "The system marks it as critical."));
        assertThat(ControlMessages.refused(kill, ProtectionRules.Refusal.SYSTEM_PROCESS))
                .isEqualTo(new Wording("Prism does not act on java (PID 4242).", "It is a system process."));
    }

    @Test
    void requestsNotCarriedOutSayNothingWasDoneAndWhy() {
        ControlRequest kill = request(ControlAction.FORCE_KILL);
        assertThat(ControlMessages.notMonitoring(kill))
                .isEqualTo(new Wording("Nothing was done to java (PID 4242).", "Prism is not monitoring processes."));
        assertThat(ControlMessages.busy(kill).explanation()).isEqualTo("Another action on it is in progress.");
        assertThat(ControlMessages.shuttingDown(kill).explanation()).isEqualTo("Prism is closing.");
        assertThat(ControlMessages.tooManyPending(kill).explanation()).isEqualTo("Too many actions are waiting.");
    }

    @Test
    void suspensionsSayWhatBecameOfTheReason() {
        ControlRequest resume = request(ControlAction.RESUME);
        assertThat(ControlMessages.suspension(
                        resume,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.success(),
                                SuspensionCoordinator.Change.STILL_HELD,
                                Set.of(SuspensionReason.CPU_LIMITER),
                                0)))
                .isEqualTo(new Wording(
                        "Your suspension of java (PID 4242) is released.",
                        "The CPU limiter still stops it a moment at a time, to hold it under its limit."));
        assertThat(ControlMessages.suspension(
                        resume,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.success(),
                                SuspensionCoordinator.Change.STILL_HELD,
                                Set.of(SuspensionReason.OOM_GUARD),
                                0)))
                .isEqualTo(new Wording("java (PID 4242) stays suspended.", "Prism still holds it for OOM Guard."));
        assertThat(ControlMessages.suspension(
                        resume,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.of(OperationResult.NATIVE_ERROR, "x"),
                                SuspensionCoordinator.Change.INCOMPLETE,
                                Set.of(),
                                2)))
                .isEqualTo(new Wording(
                        "Resuming java (PID 4242) was not completed.",
                        "2 thread(s) Prism suspended could not be released; Resume tries again."));
        assertThat(ControlMessages.suspension(
                        resume,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "x"),
                                SuspensionCoordinator.Change.EXITED,
                                Set.of(),
                                0)))
                .isEqualTo(new Wording("java (PID 4242) has exited.", "Prism no longer holds it suspended."));
    }

    @Test
    void theLogGetsBothSentencesAsOneText() {
        ProcessControlResult result = ControlMessages.result(
                request(ControlAction.END_TASK),
                ControlOutcome.success(),
                ControlMessages.of(request(ControlAction.END_TASK), ControlOutcome.success()));

        assertThat(result.message()).isEqualTo("Close request sent to java (PID 4242).");
        assertThat(result.explanation()).isEqualTo("The application may ignore it.");
        assertThat(result.text()).isEqualTo("Close request sent to java (PID 4242). The application may ignore it.");
    }

    @Test
    void whatIsOnItsWayNamesTheActionAndTheProcess() {
        assertThat(ControlMessages.inProgress(request(ControlAction.SUSPEND))).isEqualTo("Suspending java (PID 4242)…");
        assertThat(ControlMessages.inProgress(request(ControlAction.END_TASK)))
                .isEqualTo("Asking java (PID 4242) to close…");
        assertThat(ControlMessages.inProgress(request(ControlAction.FORCE_KILL)))
                .isEqualTo("Requesting termination of java (PID 4242)…");
        assertThat(ControlMessages.inProgress(request(ControlAction.CHANGE_PRIORITY)))
                .isEqualTo("Setting the priority of java (PID 4242) to Above normal…");
    }

    @Test
    void aProcessWithoutANameIsStillNamedByItsPid() {
        assertThat(ControlMessages.of(ControlRequest.of(ControlAction.FORCE_KILL, DEMO, ""), ControlOutcome.success())
                        .headline())
                .isEqualTo("Force termination requested for the process (PID 4242).");
    }

    @Test
    void theFiveLevelsHaveTheirNamesAndThereIsNoRealtime() {
        assertThat(java.util.Arrays.stream(PriorityLevel.values()).map(ControlMessages::levelName))
                .containsExactly("Low", "Below normal", "Normal", "Above normal", "High");
    }

    @Test
    void aSuspensionReadsTheSameFromTheCoordinatorAndFromThePlatform() {
        ControlRequest suspend = request(ControlAction.SUSPEND);
        assertThat(ControlMessages.suspension(
                        suspend,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.success(), SuspensionCoordinator.Change.SUSPENDED, Set.of(), 0)))
                .isEqualTo(ControlMessages.of(suspend, ControlOutcome.success()));
    }

    @Test
    void theRestOfWhatIsOnItsWay() {
        assertThat(ControlMessages.inProgress(request(ControlAction.RESUME))).isEqualTo("Resuming java (PID 4242)…");
        assertThat(ControlMessages.inProgress(ControlRequest.priority(DEMO, "java", PriorityLevel.BELOW_NORMAL)))
                .isEqualTo("Setting the priority of java (PID 4242) to Below normal…");
    }

    @Test
    void noRefusalOrSuspensionWordingShowsACode() {
        for (ProtectionRules.Refusal refusal : ProtectionRules.Refusal.values()) {
            Wording wording = ControlMessages.refused(request(ControlAction.FORCE_KILL), refusal);
            assertThat(wording.headline() + wording.explanation()).doesNotContainPattern("code|\\[");
        }
        for (SuspensionCoordinator.Change change : SuspensionCoordinator.Change.values()) {
            Wording wording = ControlMessages.suspension(
                    request(ControlAction.RESUME),
                    new SuspensionCoordinator.Outcome(
                            ControlOutcome.of(OperationResult.NATIVE_ERROR, 31, "TECHNICAL-DETAIL-7"),
                            change,
                            Set.of(SuspensionReason.USER),
                            1));
            assertThat(wording.headline() + " " + wording.explanation())
                    .as(change.name())
                    .doesNotContain("TECHNICAL-DETAIL-7")
                    .doesNotContain("31");
        }
    }

    private static final ControlMessages.Means SIGNALS =
            new ControlMessages.Means(Optional.of(ControlMechanism.TERMINATION_SIGNAL), true);
    private static final ControlMessages.Means STOPS =
            new ControlMessages.Means(Optional.of(ControlMechanism.STOP_SIGNAL), true);
    private static final ControlMessages.Means NICE_UNLISTED =
            new ControlMessages.Means(Optional.of(ControlMechanism.NICE_VALUE), false);

    /** CAP-5: a termination signal is a request to terminate, not to close a window. */
    @Test
    void aTerminationSignalIsWordedAsARequestToTerminate() {
        ControlRequest endTask = request(ControlAction.END_TASK);

        assertThat(ControlMessages.of(endTask, ControlOutcome.success(), SIGNALS))
                .isEqualTo(new Wording("Asked java (PID 4242) to terminate.", "It may ignore the request."));
        assertThat(ControlMessages.inProgress(endTask, Optional.of(ControlMechanism.TERMINATION_SIGNAL)))
                .isEqualTo("Asking java (PID 4242) to terminate…");
        assertThat(ControlMessages.of(endTask, ControlOutcome.of(OperationResult.NATIVE_ERROR, 5, "x"), SIGNALS)
                        .headline())
                .isEqualTo("The system did not pass on the request to terminate java (PID 4242).");
        assertThat(ControlMessages.of(endTask, ControlOutcome.of(OperationResult.UNSUPPORTED, "x"), SIGNALS)
                        .headline())
                .as("no window is needed for a signal")
                .isEqualTo("End task is not available on this system.");
    }

    @Test
    void aStopSignalStopsTheWholeProcess() {
        assertThat(ControlMessages.of(request(ControlAction.SUSPEND), ControlOutcome.success(), STOPS))
                .isEqualTo(new Wording("java (PID 4242) was suspended.", "The system stopped the whole process."));
        assertThat(ControlMessages.suspension(
                                request(ControlAction.RESUME),
                                new SuspensionCoordinator.Outcome(
                                        ControlOutcome.of(OperationResult.ACCESS_DENIED, 1, "x"),
                                        SuspensionCoordinator.Change.INCOMPLETE,
                                        Set.of(),
                                        1),
                                STOPS)
                        .explanation())
                .isEqualTo("Prism could not let it run again; Resume tries again.");
    }

    /** K5: a process another program stopped is left to that program, and the words say so. */
    @Test
    void aProcessStoppedAlreadyIsSaidToBeLeftToWhoStoppedIt() {
        assertThat(ControlMessages.of(
                        request(ControlAction.SUSPEND),
                        ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "already stopped"),
                        STOPS))
                .isEqualTo(new Wording(
                        "java (PID 4242) is already stopped.",
                        "Another program stopped it; Prism does not take over that stop."));
        assertThat(ControlMessages.of(
                                request(ControlAction.SUSPEND),
                                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "x"),
                                ControlMessages.Means.UNKNOWN)
                        .headline())
                .isEqualTo("Prism could not do that to java (PID 4242).");
    }

    /** CTL-6: the refusal of a nice value says that raising needs privileges. */
    @Test
    void aRefusedNiceValueSaysThatRaisingNeedsPrivileges() {
        ControlRequest high = ControlRequest.priority(DEMO, "java", PriorityLevel.HIGH);

        assertThat(ControlMessages.of(high, ControlOutcome.of(OperationResult.ACCESS_DENIED, 13, "x"), NICE_UNLISTED))
                .isEqualTo(
                        new Wording(
                                "Access denied: java (PID 4242).",
                                "Raising a priority, or changing another user's process, needs privileges Prism does not have."));
        assertThat(ControlMessages.of(
                                request(ControlAction.FORCE_KILL),
                                ControlOutcome.of(OperationResult.ACCESS_DENIED, 1, "x"),
                                NICE_UNLISTED)
                        .explanation())
                .as("only a change of priority")
                .isEqualTo("The system does not let Prism act on this process.");
    }

    /** Where the table shows no level (macOS), the set level is confirmed by the system, not by the table. */
    @Test
    void aLevelTheTableDoesNotShowIsConfirmedByTheSystem() {
        ControlRequest low = ControlRequest.priority(DEMO, "java", PriorityLevel.LOW);

        assertThat(ControlMessages.of(low, ControlOutcome.success(), NICE_UNLISTED)
                        .explanation())
                .isEqualTo("The system confirmed it.");
        assertThat(ControlMessages.of(low, ControlOutcome.success(), ControlMessages.Means.UNKNOWN)
                        .explanation())
                .isEqualTo("The table shows it with the next listing.");
    }

    /** Without a mechanism the words are those of milestone 7, unchanged. */
    @Test
    void theWordsOfTheWindowsMechanismsAreThoseOfMilestoneSeven() {
        ControlMessages.Means windows = new ControlMessages.Means(Optional.of(ControlMechanism.CLOSE_REQUEST), true);

        assertThat(ControlMessages.of(request(ControlAction.END_TASK), ControlOutcome.success(), windows))
                .isEqualTo(ControlMessages.of(request(ControlAction.END_TASK), ControlOutcome.success()));
        assertThat(ControlMessages.inProgress(
                        request(ControlAction.END_TASK), Optional.of(ControlMechanism.CLOSE_REQUEST)))
                .isEqualTo("Asking java (PID 4242) to close…");
        assertThat(ControlMessages.of(
                        request(ControlAction.SUSPEND),
                        ControlOutcome.success(),
                        new ControlMessages.Means(Optional.of(ControlMechanism.PER_THREAD_SUSPENSION), true)))
                .isEqualTo(new Wording("java (PID 4242) was suspended.", "Its threads were stopped one by one."));
    }

    /** Milestone 9: a stop made by another program, the user and the limiter together, and a suspension the journal missed. */
    @Test
    void theWordsOfTheLimitersMilestoneSayWhatIsHeldAndWhatWasNotRecorded() {
        ControlRequest suspend = request(ControlAction.SUSPEND);
        assertThat(ControlMessages.suspension(
                        suspend,
                        new SuspensionCoordinator.Outcome(
                                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "already stopped"),
                                SuspensionCoordinator.Change.STOPPED_ELSEWHERE,
                                Set.of(),
                                0)))
                .isEqualTo(new Wording(
                        "java (PID 4242) is already stopped.",
                        "Another program stopped it; Prism does not take over that stop."));
        assertThat(ControlMessages.suspension(
                                suspend,
                                new SuspensionCoordinator.Outcome(
                                        ControlOutcome.success(),
                                        SuspensionCoordinator.Change.ALREADY_HELD,
                                        Set.of(SuspensionReason.USER, SuspensionReason.CPU_LIMITER),
                                        0))
                        .headline())
                .isEqualTo("java (PID 4242) stays suspended, now for you as well.");
        Wording unrecorded = ControlMessages.suspension(
                suspend,
                new SuspensionCoordinator.Outcome(
                        ControlOutcome.success(),
                        SuspensionCoordinator.Change.SUSPENDED,
                        Set.of(SuspensionReason.USER),
                        0,
                        false));
        assertThat(unrecorded.headline()).isEqualTo("java (PID 4242) was suspended.");
        assertThat(unrecorded.explanation()).endsWith("the suspension journal cannot be written.");
    }
}
