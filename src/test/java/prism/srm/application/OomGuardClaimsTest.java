package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomState;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryState;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;

/**
 * Plan A15.9 M2: a suspension held for memory pressure and for the process's own memory limit is one
 * physical hold of the coordinator with two logical claims. Every assertion about what is held is made
 * against the coordinator and the journal file, not against the guard's own bookkeeping.
 */
class OomGuardClaimsTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final long LIMIT = 100 * OomRig.MIB;
    private static final long ABOVE = LIMIT + OomRig.MIB;
    private static final long CLEAR = OomGuard.clearBelow(LIMIT, OomGuard.Tuning.DEFAULTS);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @BeforeEach
    void setUp() {
        rig = new OomRig(folder.path());
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.watch(P, LIMIT);
        rig.listed(P, LIMIT / 2, 8);
        rig.run();
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void listed(long resident) {
        rig.listed(P, resident, 8);
        rig.run();
    }

    private boolean physicallyHeld() {
        return rig.coordinator.heldFor(P).contains(SuspensionReason.OOM_GUARD)
                && rig.controller.suspensions.stream().anyMatch(s -> s.outstanding > 0);
    }

    /**
     * A process the user suspended (ALREADY_HELD, plan A15.2, test review): OOM Guard's claim adds its
     * reason without a second native suspend, and at Normal it lets go of its reason only; the user's
     * suspension stays, in the coordinator and in the journal (LIFE-2).
     */
    @Test
    void aProcessTheUserSuspendedStaysSuspendedAfterOomGuardLetsGo() throws Exception {
        assertThat(rig.coordinator
                        .suspend(P, "p100", SuspensionReason.USER)
                        .outcome()
                        .succeeded())
                .isTrue();
        long suspendsBefore = rig.count("suspend 100");

        rig.samples(4, 4);
        assertThat(rig.count("suspend 100")).as("no second native suspend").isEqualTo(suspendsBefore);
        assertThat(rig.coordinator.heldFor(P)).contains(SuspensionReason.USER, SuspensionReason.OOM_GUARD);

        normal();
        rig.samples(50, 50);

        assertThat(rig.count("resume 100")).isZero();
        assertThat(rig.coordinator.heldFor(P)).containsExactly(SuspensionReason.USER);
        assertThat(rig.journal.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.reasons()).containsExactly(SuspensionReason.USER));
    }

    private void normal() {
        for (int i = 0; i < 60 && rig.machine.state() != OomState.NORMAL; i++) {
            rig.sample(50);
        }
        assertThat(rig.machine.state()).isEqualTo(OomState.NORMAL);
    }

    /** First the limit, then pressure: one suspension, two claims. */
    private void bothResidentFirst() {
        rig.sample(50);
        listed(ABOVE);
        rig.samples(4, 4);
    }

    @Test
    void pressureRecoversWhileResidentPolicyStillHolds() throws Exception {
        bothResidentFirst();
        assertThat(rig.hold(P).suspendClaims()).containsExactlyInAnyOrder(Claim.PRESSURE, Claim.RESIDENT_POLICY);

        normal();
        rig.samples(50, 50);

        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.hold(P).suspendClaims()).containsExactly(Claim.RESIDENT_POLICY);
        assertThat(physicallyHeld()).isTrue();
        assertThat(rig.journaled()).hasSize(1);
    }

    @Test
    void residentClearsWhilePressureStillHolds() {
        rig.samples(4, 4);
        listed(ABOVE);
        assertThat(rig.hold(P).suspendClaims()).containsExactlyInAnyOrder(Claim.PRESSURE, Claim.RESIDENT_POLICY);

        listed(CLEAR);
        listed(CLEAR);
        rig.sample(4);

        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.hold(P).suspendClaims()).containsExactly(Claim.PRESSURE);
        assertThat(physicallyHeld()).isTrue();
    }

    @Test
    void bothActiveInEitherOrderMakeOneNativeSuspendAndOneDeclaration() throws Exception {
        int before = rig.files.writes;
        bothResidentFirst();
        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.files.writes - before).isEqualTo(1);
        assertThat(rig.journaled())
                .singleElement()
                .satisfies(entry -> assertThat(entry.cause())
                        .hasValueSatisfying(cause -> assertThat(cause).contains("memory limit")));
    }

    @Test
    void bothActiveTheOtherWayRoundAlsoMakeOneSuspendAndOneDeclaration() throws Exception {
        int before = rig.files.writes;
        rig.samples(4, 4);
        listed(ABOVE);

        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.files.writes - before).isEqualTo(1);
        assertThat(rig.journaled())
                .singleElement()
                .satisfies(entry -> assertThat(entry.cause())
                        .hasValueSatisfying(cause -> assertThat(cause).contains("emergency")));
    }

    @Test
    void releasingTheLastClaimReleasesPhysically() throws Exception {
        bothResidentFirst();
        normal();
        listed(CLEAR);
        listed(CLEAR);
        rig.sample(50);

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
        assertThat(rig.journaled()).isEmpty();
    }

    @Test
    void disablingOneJustificationLeavesTheOther() {
        bothResidentFirst();
        rig.unwatch(P);
        rig.sample(4);

        assertThat(rig.hold(P).suspendClaims()).containsExactly(Claim.PRESSURE);
        assertThat(physicallyHeld()).isTrue();
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void turningOomManagementOffReleasesBothClaims() {
        bothResidentFirst();
        rig.manage(P, new OomManagement(false, OomPriority.NORMAL, EmergencyAction.SUSPEND));
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.hold(P).suspendClaims()).isEmpty();
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
    }

    @Test
    void suspendSwitchOffReleasesBothClaims() {
        bothResidentFirst();
        rig.switches(false, false, false);
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
    }

    @Test
    void aNewClaimDuringIncompleteReleaseIsNotReSuspendedAndThePolicyFlagSurvives() {
        rig.unwatch(P);
        rig.samples(4, 4);
        AtomicInteger resumes = new AtomicInteger();
        rig.controller.resume = suspension -> {
            if (resumes.incrementAndGet() <= 2) {
                suspension.outstanding = 1;
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        normal();
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.RELEASE_INCOMPLETE);

        rig.watch(P, LIMIT);
        listed(ABOVE);
        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.notNow(P)).contains("not now: release incomplete");
        assertThat(rig.hold(P).exceededPending()).isTrue();

        rig.guard.settingsChanged();
        rig.run();
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.count("suspend 100")).as("not on a settings change").isEqualTo(1);

        rig.sample(50);
        listed(ABOVE);
        assertThat(rig.count("suspend 100")).isEqualTo(2);
    }

    @Test
    void incompleteReleaseIsRetriedEveryTickRegardlessOfClaims() throws Exception {
        rig.unwatch(P);
        rig.samples(4, 4);
        AtomicInteger resumes = new AtomicInteger();
        rig.controller.resume = suspension -> {
            if (resumes.incrementAndGet() <= 3) {
                suspension.outstanding = 1;
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        for (int i = 0; i < 60 && rig.count("resume 100") == 0; i++) {
            rig.sample(50);
        }
        assertThat(rig.count("resume 100")).isEqualTo(1);
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.RELEASE_INCOMPLETE);
        assertThat(rig.journaled())
                .as("the declaration stays while anything is held")
                .hasSize(1);

        rig.guard.settingsChanged();
        rig.run();
        assertThat(rig.count("resume 100")).isEqualTo(2);
        rig.guard.settingsChanged();
        rig.run();
        assertThat(rig.count("resume 100")).isEqualTo(3);
        rig.guard.settingsChanged();
        rig.run();

        assertThat(rig.count("resume 100")).isEqualTo(4);
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.journaled()).isEmpty();
    }

    @Test
    void anotherOwnersIncompleteRemainderIsNeitherSuspendedOverNorRetriedByOomGuard() {
        rig.unwatch(P);
        rig.coordinator.suspend(P, "p100", SuspensionReason.USER);
        rig.controller.resume = suspension -> {
            suspension.outstanding = 1;
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
        };
        rig.coordinator.release(P, SuspensionReason.USER);
        assertThat(rig.coordinator.incomplete(P)).isTrue();
        List<String> before = rig.actions();

        rig.samples(4, 4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).isEqualTo(before);
        assertThat(rig.notNow(P).orElseThrow()).contains("incomplete suspension");
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
    }

    @Test
    void shutdownReleasesThePhysicalReasonWhateverTheClaims() throws Exception {
        bothResidentFirst();
        rig.guard.stop();
        rig.run();
        assertThat(rig.hold(P).suspendClaims()).isEmpty();

        rig.coordinator.refuseFurtherSuspensions();
        rig.coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));

        assertThat(rig.count("resume 100")).isEqualTo(1);
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.journaled()).isEmpty();
    }

    @Test
    void crashRecoveryListsTheEntryOnceAndOnWindowsItCanBeResumed() throws Exception {
        rig.controller.suspend = target -> {
            FakeSuspension suspension = new FakeSuspension(target, 3);
            suspension.memento = Optional.of("threads 1,2,3");
            rig.controller.suspensions.add(suspension);
            return SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
        };
        bothResidentFirst();
        List<JournalEntry> left = rig.journaled();
        assertThat(left).singleElement().satisfies(entry -> {
            assertThat(entry.reasons()).containsExactly(SuspensionReason.OOM_GUARD);
            assertThat(entry.memento()).contains("threads 1,2,3");
        });

        FakeProcessController next = new FakeProcessController();
        next.stopState = target -> StopState.UNKNOWN;
        SuspensionCoordinator restarted =
                new SuspensionCoordinator(next, new ActivityLog(10, () -> OomRig.START), new QueuedExecutor());
        restarted.recover(left);

        List<RecoveredSuspension> listed = restarted.recovered();
        assertThat(listed).singleElement().satisfies(entry -> {
            assertThat(entry.state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED);
            assertThat(entry.resumable()).isTrue();
        });
        assertThat(next.calls).noneMatch(call -> call.startsWith("resume") || call.startsWith("adopt"));
    }

    @Test
    void journalFailureReleasesBothClaims() throws Exception {
        bothResidentFirst();
        rig.files.diskFull = true;
        rig.coordinator.declare(OomRig.process(999), "other", SuspensionReason.CPU_LIMITER, "CPU limit 10 %");
        assertThat(rig.coordinator.journalHealthy()).isFalse();
        rig.guard.journalFailed("disk full");
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.hold(P).suspendClaims()).isEmpty();
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
        assertThat(rig.journaled())
                .as("the file keeps what was written, as evidence")
                .hasSize(1);

        listed(LIMIT / 2);
        listed(ABOVE);
        rig.samples(4, 4);
        assertThat(rig.count("suspend 100"))
                .as("no new suspension without the journal")
                .isEqualTo(1);
    }

    // ----- Seeded sequences -----

    /** One of a fixed set of steps, chosen by the seed. */
    private void step(Random random, ProcessIdentity[] processes) {
        ProcessIdentity target = processes[random.nextInt(processes.length)];
        switch (random.nextInt(12)) {
            case 0, 1 -> rig.sample(4);
            case 2 -> rig.sample(7);
            case 3 -> rig.sample(50);
            case 4 -> {
                rig.listed(
                        processes[0],
                        random.nextBoolean() ? ABOVE : CLEAR,
                        8,
                        processes[1],
                        random.nextBoolean() ? ABOVE : CLEAR,
                        8);
                rig.run();
            }
            case 5 -> rig.switches(false, random.nextBoolean(), false);
            case 6 ->
                rig.manage(
                        target,
                        random.nextBoolean()
                                ? OomRig.oom(EmergencyAction.SUSPEND)
                                : new OomManagement(false, OomPriority.NORMAL, EmergencyAction.SUSPEND));
            case 7 -> rig.watch(target, LIMIT);
            case 8 -> rig.unwatch(target);
            case 9 -> {
                rig.unmanage(target);
                rig.manage(target, OomRig.oom(EmergencyAction.SUSPEND));
            }
            case 10 -> {
                int choice = random.nextInt(4);
                rig.controller.suspend = identity -> {
                    FakeSuspension suspension = new FakeSuspension(identity, 3);
                    rig.controller.suspensions.add(suspension);
                    return switch (choice) {
                        case 0, 1 -> SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
                        case 2 -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "denied"));
                        default -> {
                            suspension.outstanding = 1;
                            yield new SuspendResult(
                                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "one thread left"),
                                    Optional.of(suspension));
                        }
                    };
                };
            }
            default -> {
                AtomicInteger once = new AtomicInteger();
                rig.controller.resume = suspension -> {
                    if (once.getAndIncrement() == 0) {
                        suspension.outstanding = 1;
                        return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
                    }
                    suspension.outstanding = 0;
                    return ControlOutcome.success("released");
                };
            }
        }
        rig.scheduler.elapse(Duration.ofMillis(10));
    }

    /** I11 against the coordinator: no claim without a hold, and no hold without a claim but an incomplete release. */
    private void assertNoPrematureResume(ProcessIdentity[] processes, long seed, List<String> trail) {
        for (ProcessIdentity identity : processes) {
            Optional<OomHold> hold = rig.view().candidates().stream()
                    .filter(candidate -> candidate.identity().equals(identity))
                    .map(candidate -> candidate.hold())
                    .findFirst();
            boolean held = rig.coordinator.heldFor(identity).contains(SuspensionReason.OOM_GUARD);
            boolean outstanding = rig.controller.suspensions.stream()
                    .anyMatch(s -> s.identity().equals(identity) && s.outstanding > 0);
            if (hold.isPresent() && !hold.get().suspendClaims().isEmpty()) {
                assertThat(held && outstanding)
                        .as("seed %d: a claim without a hold after %s", seed, trail)
                        .isTrue();
            }
            if (held) {
                assertThat(hold.isPresent()
                                && (!hold.get().suspendClaims().isEmpty()
                                        || hold.get().physical() == OomHold.Physical.RELEASE_INCOMPLETE))
                        .as("seed %d: a hold without a claim after %s", seed, trail)
                        .isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 42, 1_001, 65_537})
    void noStrandedSuspensionAndNoPrematureResume(long seed) throws Exception {
        ProcessIdentity[] processes = {P, OomRig.process(200)};
        rig.manage(processes[1], OomRig.oom(EmergencyAction.SUSPEND));
        Random random = new Random(seed);
        List<String> trail = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int mark = rig.actions().size();
            step(random, processes);
            trail.add(String.valueOf(i));
            rig.sample(random.nextBoolean() ? 4 : 50);
            assertNoPrematureResume(
                    processes,
                    seed,
                    rig.actions().subList(Math.max(0, mark - 3), rig.actions().size()));
        }
        // Wind down: normal pressure, nothing watched, no management, every release finished.
        rig.controller.resume = suspension -> {
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        for (ProcessIdentity identity : processes) {
            rig.unwatch(identity);
            rig.manage(identity, new OomManagement(false, OomPriority.NORMAL, EmergencyAction.SUSPEND));
        }
        normal();
        for (int i = 0; i < 10; i++) {
            rig.sample(50);
        }
        // A remainder that OOM Guard could not release when the process was unmanaged is handed to the
        // coordinator (plan A7.4): the user's Resume finishes it, as the shutdown would.
        for (ProcessIdentity identity : processes) {
            if (rig.coordinator.incomplete(identity)) {
                rig.coordinator.release(identity, SuspensionReason.USER);
            }
        }

        assertThat(rig.coordinator.held())
                .as(
                        "seed %d: reasons %s, incomplete %s, actions %s",
                        seed, rig.coordinator.heldFor(P), rig.coordinator.incomplete(P), rig.actions())
                .isEmpty();
        for (ProcessIdentity identity : processes) {
            assertThat(rig.coordinator.heldFor(identity)).as("seed %d", seed).isEmpty();
        }
        assertThat(rig.journaled()).as("seed %d", seed).isEmpty();
    }

    /**
     * Plan A15.9 M2 (mutation N10 of the second run): the pressure path, as the policy path above: a
     * new EMERGENCY while the release of an earlier hold is incomplete does not suspend the process
     * again; the release is tried again instead.
     */
    @Test
    void aNewEmergencyDuringAnIncompleteReleaseIsNotSuspendedAgain() {
        rig.unwatch(P);
        rig.samples(4, 4);
        assertThat(rig.count("suspend 100")).isEqualTo(1);
        AtomicBoolean refuse = new AtomicBoolean(true);
        rig.controller.resume = suspension -> {
            if (refuse.get()) {
                suspension.outstanding = 1;
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        normal();
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.RELEASE_INCOMPLETE);

        rig.samples(4, 4, 4, 4);
        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);

        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.hold(P).suspendClaims()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: release incomplete");
        refuse.set(false);
        rig.sample(4);
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.SUSPENDED);
        assertThat(rig.count("suspend 100"))
                .as("only the incomplete release held it back: once released, the new incident suspends it")
                .isEqualTo(2);
    }
}
