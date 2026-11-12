# Milestone 11: mutation evidence (plan A15.9 M1 to M11, and the action start)

**Date and machine.** First run (historical): 2026-10-12, Windows 11 Pro build 26200, JDK 25, on the then uncommitted
working tree of `feat/m11-oom-guard`.

**Method.** Hand-written mutations, not a mutation tool. A script (`mutate.py`, kept with the
review bundle) did the following for each mutation:
- replaced one exact text, which had to occur exactly once;
- ran the named test classes offline (`./mvnw -o -q test -Dtest=…`);
- recorded every failing test and whether it failed by an assertion or by an exception;
- restored the original bytes.

The three mutated files were checked against their SHA-256 afterwards, and all matched. The raw
result is `mutation-results.json` in the review bundle.

R2 was run again by hand after the correction below. Its row shows that later result, not the
scripted run, in which it survived. A first run was discarded for one row. Its M3 mutation dereferenced a field that is null on the
first sample, so its kill was an exception, not evidence. M3 was rewritten null-safe and the
whole set was run again. The table is that second run. Every kill is an assertion failure.

## Results

| Id | Plan row | Mutation | Verdict | Killed by (assertions) |
|---|---|---|---|---|
| M1a | M1 | an Emergency action counts a lowering at Critical as acted | **killed** | `criticalLoweringDoesNotConsumeTheEmergencyAction`, `criticalLoweringThenTerminate`, the parametric `everyConfigurationProducesItsLiteralCallSequence` rows 5, 6, 13, 14, and 6 more |
| M1b | M1 | one emergency action per incident removed | **killed** | `secondSuspendInSameIncidentNeverHappens`, `actionChangeMidIncidentDoesNotEscalate` |
| M1c | M1 | Terminate once per identity per session removed | **killed** | `terminateIsSentOnceForAnIdentityAcrossIncidentsAndBothAttemptsAreRecorded`, `aNewIncidentResetsTheLedgerButNotTerminateSent`, `aNewIncidentIsNotHeldBackByTheSettleOfTheLast` |
| M2a | M2 | a hold that is still partly justified is released physically | **killed** | `pressureRecoversWhileResidentPolicyStillHolds`, `residentClearsWhilePressureStillHolds`, `disablingOneJustificationLeavesTheOther` |
| M2b | M2 / OOM-11 | more than one resume per evaluation | **killed** | `twoHoldsUnwindOnePerEvaluationResumesFirst` |
| M3 | M3 | a threshold change starts no new epoch (only a monitoring change does) | **killed** | `raisingAThresholdInvalidatesThePreviousSample`, `loweringAThresholdKeepsTheStateAndNeedsAFullDwell`, `dwellDuringRecoveryRestartsOnAnyThresholdChange` |
| M4a | M4 | restore over a level someone set meanwhile | **killed** | `restoreSkippedWhenSomeoneChangedThePriority` |
| M4b | M4 / O16 | lowering allowed where a priority cannot be restored | **killed** | `notReversibleSystemShowsLowerPriorityAsNotInEffectAndNeverLowers`, `whereAPriorityCannotBeRestoredLoweringIsNotAvailableAndSuspendStillIs` |
| M5 | M5 / O14 | a journal failure does not release the claims | **killed** | `aJournalFailureReleasesEveryClaimAndResumes`, `aFailedNativeReleaseOnJournalFailureKeepsOwnershipAndReportsIncomplete`, `journalFailedTwiceIsIdempotent`, `journalFailureAfterMonitorEndedStillReleases` |
| M6 | M6 | `floorFrac` multiplies before dividing | **killed** | `floorFracIsExactWhereTheProductWouldOverflow`, three `Long.MAX_VALUE` edge tests, `clearanceIsReachableForEveryValidLimit[10]` |
| M7 | M7 / O17 | the signal gate ignored for suspension | **killed** | `onASystemWhoseSignalIsNotValidatedEveryActionFallsBackToNotify`, `policyInterventionIsOffOnAnUnvalidatedSystem[2]`, the Linux pre-validation tests |
| M8a | M8 item 3 | a stale reading accepted | **killed** | `item3AStaleReadingActsNothing` |
| M8b | M8 item 7 | protection facts not re-read before acting | **killed** | `item7EveryActionReChecksProtectionFirstAndAProtectedProcessIsNotTriedAgain` and 12 call-sequence tests |
| M8c | M8 item 5 | settle ignored | **killed** | `item5TheSettleIntervalHoldsTheNextCandidate`, `policyRunsInTheSameEvaluateWhenPressureDidNotAct` |
| M8d | M8 item 2 | monitoring off ignored | **killed** | `item2MonitoringOffActsNothing` |
| M9 | M9 | the policy margin computed as `limit / 100 * 5` | **killed** | `clearanceIsReachableForEveryValidLimit[9]` (256 MiB) |
| M10 | M10 | an observation under other settings accepted (item 0b removed) | **killed** | `aQueuedEvaluationCannotUseAPreChangeObservation`, `aChangedThresholdNeedsAReadingThatMeetsItUnderTheNewVersion` |
| M11 | M11 | the trigger–intent check removed | **killed** | `aListingTriggerNeverAuthorizesAPressureIntervention`, `aSampleTriggerNeverAuthorizesAPolicyIntervention`, `aSecondaryTriggerStartsNoIntervention` ×3, the journal-trigger tests, `postGapHealthyReadingActsNothing` |
| L1 | ADR-0015 §6 | stop not checked at the start of an action | **killed** | `anActionAuthorizedBeforeStopButNotBegunIsNeverDispatched` ×3 |
| R1 | ADR-0015 §2, P0 audit | the settings version not checked at the start of an action | **killed** | all 8 revocation tests of `OomGuardRevocationTest` |
| R2 | ADR-0015 §2, P0 audit | settings published without the action lock | **killed** (after correction, see below) | `aPublicationCannotCompleteBetweenTheVersionCheckAndTheInFlightCount` |

**Before and after the P0 fix.** Separately from the mutations, the 11 revocation tests of that
time were run against the code as it was before the fix:
- `OomGuard.java` restored from its pre-fix copy, and the test rig's publication path restored
  too: 8 of the 11 tests fail;
- the fixed code: 11 of 11 pass.

The 3 tests that pass on both are the positive controls and the call that began before the
publication.

## R2: the earlier "equivalent mutant" claim was wrong (corrected 2026-10-12)

The first version of this file said R2 survived as an equivalent mutant. The argument was that
the start's volatile read and the publication's atomic write are ordered even without the lock.
The external review showed why that does not hold, and the claim is withdrawn.

The start of an action does two things under the action lock:
1. It reads the version.
2. It counts itself in flight.

Without the lock on publication, the interleaving goes like this:
1. The start reads the old version.
2. The publication completes and returns.
3. Only then does the start count itself, and the action begins under the settings the user has
   just replaced.

When the publication returned, that action was neither in flight nor refused.

**The test.** `OomGuardRevocationTest.aPublicationCannotCompleteBetweenTheVersionCheckAndTheInFlightCount`
forces exactly this interleaving:
- The test rig's settings supplier pauses the start right after its version read
  (`OomRig.afterSettingsRead`), while the start still holds the action lock and before the count.
- Another thread publishes the revocation.
- The moment the publication returns is observed without the lock.

A first draft of the test asked OOM Guard what was in flight. That takes the lock, so the
observation itself waited for the start, and the draft passed on the mutant too. It was
corrected before any result was recorded as evidence.

**Results**, each run separately, from `./mvnw -o test -Dtest=OomGuardRevocationTest`:

| Code | Runs | Result |
|---|---|---|
| Current, `publishSettings` under the action lock | 3 | 12 of 12 pass each time |
| R2 mutant, `publishSettings` without the lock | 3 | 11 pass, this test fails each time: "the publication returned while the start had read the old version but was not yet in flight, and that action then began under the replaced settings" |

The production code was restored afterwards and checked against its SHA-256. No production code
was changed: the current implementation already holds the property, and the test now proves it.

## Plan rows without a mutation of their own after the first run (historical; see the final run below)

These rows of A15.9 have tests, but no mutation was made for them in this run:
- **M1:** `oscillationEceIsOneIncident` (the incident ends at Critical).
- **M2:** `bothActiveInEitherOrderMakeOneNativeSuspendAndOneDeclaration` (one declaration for two
  claims), and the randomized I11 walk. Its coverage of interesting states is not counted
  (test review, consider).
- **M4:** the shutdown bound of restoring and the exit hook not restoring (tests exist:
  `shutdownRestoresWithinItsBound`, `exitHookReleasesButDoesNotRestore`).
- **M5:** the incomplete-release retry once per evaluation (`releasedNow`).
- **M6:** `OshiSystemMetricsSourceTest.aZeroAvailableReadingIsUnavailableNotEmergency` (the OSHI
  zero guard).
- **M7:** the page side (`OomGuardViewModelTest`).
- **M8:** item 0 by the settings, managed and end-of-monitoring triggers individually; items 1, 4
  and 8 other than through L1 and M1b.
- **M9:** each boundary row except 256 MiB. The `Long.MAX_VALUE` row is covered by M6.
- **M10/M11:** the coalescing and trigger-draining order (`pendingTriggersAreClearedAtTaskStartNotEnd`
  failed under M8b, but its own mutation was not made).

No mutation tool (PIT or similar) was run. These results are evidence for the specific
mutations listed, not a mutation score.

## Final run: every A15.9 row against the mutations (acceptance gate 12, 2026-10-13)

**Why.** Plan A16 gate 12 asks that *every* row of the A15.9 matrix be shown to fail once when the code is broken on purpose, with test-reviewer approval. The first run (above) ran each mutation only against its own test classes, and several rows had no mutation of their own.

**How it went.**
1. **A second run** tested every mutation against all matrix classes. It found five test gaps (N01, N05, N10, N30, N43), and a focused test was added for each.
2. **The `test-reviewer` agent** reviewed that run. It found two more gaps:
   - N40 had been called equivalent wrongly: item 6 at the gate had no test;
   - `exitHookReleasesButDoesNotRestore` could not fail on a restore.

   It also asked for more targeted mutations (H6, H7) and stronger assertions (N36, H4), and it objected to rows credited only through broad mutants. All of this was done. The report is `second-run/test-reviewer-report.md` in the handoff archive.
3. **The final run below** repeated everything on the final test code, after an unmutated baseline of the same classes (318 tests, all passing).

**Method.**
- Hand-written mutations, as in the first run; no mutation tool.
- Each mutant runs against all matrix test classes: `OomGuardLedgerTest`, `OomGuardClaimsTest`, `OomGuardRestoreTest`, `OomGuardJournalTest`, `OomGuardGateTest`, `OomGuardPolicyTest`, `ClearanceTest`, `OomStateMachineTest`, `EffectiveActionTest`, `OomGuardTest`, `OomGuardRevocationTest`, `OomGuardLifecycleTest`, `MonitoringRuntimeOomTest`, `LimitServiceOomTest`, `OomGuardViewModelTest`, `OshiSystemMetricsSourceTest`.
- The production files are restored after each mutant, and their SHA-256 is checked at the end: **MATCH**.
- The script and the raw JSON are under `03_TEST_EVIDENCE/mutation/second-run/`.

**Result.** 71 distinct mutants: the 21 of the first run, N01–N44 and H1–H7. **69 killed, 2 not killed.** Every kill includes an assertion failure.

**Tests added or strengthened. Tests only: no production code changed, because the code already held each property.**

| Mutant | Gap | Test |
|---|---|---|
| N01 | The incident ledger could end at CRITICAL unnoticed | `OomGuardLedgerTest.passingThroughCriticalDoesNotGiveTheEmergencyActionBack` (new) |
| N05 | The re-managed process was never listed again, so "not listed" masked the ledger | `OomGuardLedgerTest.forgetThenReManageAndListAgainDoesNotReArm` (new) |
| N10 | The pressure path's "release incomplete" check was untested | `OomGuardClaimsTest.aNewEmergencyDuringAnIncompleteReleaseIsNotSuspendedAgain` (new, with a positive control) |
| N30 | No test for "a resident equal to the limit is not above it" | `OomGuardPolicyTest.aResidentEqualToTheLimitIsNotAbove` (new) |
| N43 | No test for the settle after a gap while the readings still meet EMERGENCY | `OomGuardGateTest.afterAGapAReadingStillAtEmergencyWaitsForTheSettle` (new) |
| N40 | Item 6 at the gate: a switch turned off between the choice of the candidate and the gate | `OomGuardGateTest.item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing` (new, Suspend and Terminate) and `item6WithoutTheSwitchChangeTheSameSequenceActs` (positive control) |
| N36 | A stop that arrives during the gate still allowed a read of the protection facts | `OomGuardGateTest.aStopDuringTheGateMakesNoNativeCallNotEvenAFactsRead` (new) |
| N24 | The exit-hook test listed the process at Normal, so a restore was skipped anyway | `OomGuardRestoreTest.exitHookReleasesButDoesNotRestore` (now lists it at Low) |
| H4 | The guard's own failure text was not asserted | `OomGuardJournalTest.aDeclarationThatCannotBeWrittenSuspendsNothing` (now asserts it) |

**Not killed:**
- **N44**: Not killed (residual). While the journal is unhealthy, the coordinator's withdraw writes nothing, just as dropDeclaration writes nothing. The two differ only if OOM Guard's pushed failure and the coordinator's health disagree, and no test makes them disagree.
- **H4**: Not killed (redundant). The coordinator itself refuses to suspend without a durable declaration, and the guard's own failure record is the same either way. H5, which breaks the check that actually serves the row, is killed.

**A flaky test.** `MonitoringRuntimeOomTest.aJournalFailureReleasesWhatOomGuardHolds` is a real-thread test. It failed under concurrent load with mutants that have nothing to do with it: N41 and N30 in the interim runs. In the final run it failed under M4a, M5, N17, H1 and H3. M5 and H1 touch the journal-failure and stop paths that the test drives, so those two failures may be real kills. The others are not related in any obvious way. On unmutated code it has never failed: the baseline of the final run, every gate, every CI job, and 5 runs out of 5 alone. It is not an A15.9 row, so no row is credited through it. It is recorded as a flaky test for a follow-up.

**Every mutant of the final run.**

| Id | Mutation | Verdict | Failing tests |
|---|---|---|---|
| M1a | M1 CRITICAL lowering consumes EMERGENCY eligibility | KILLED | 17 |
| M1b | M1 one emergency action per incident removed | KILLED | 8 |
| M1c | M1 Terminate once per session removed | KILLED | 3 |
| M2a | M2 a partly unjustified hold is released physically (claims not independent) | KILLED | 3 |
| M2b | M2 more than one resume per evaluation | KILLED | 1 |
| M3 | M3 no new epoch when the thresholds change | KILLED | 3 |
| M4a | M4 restore over a level someone set meanwhile | KILLED | 2 |
| M4b | M4 lowering allowed where it cannot be restored | KILLED | 2 |
| M5 | M5 (O14) journal failure does not release the claims | KILLED | 6 |
| M6 | M6 floorFrac multiplies before dividing (overflow) | KILLED | 5 |
| M7 | M7 the signal gate ignored for suspension | KILLED | 7 |
| M8a | M8 item 3 stale reading accepted | KILLED | 1 |
| M8b | M8 item 7 protection facts not re-read | KILLED | 28 |
| M8c | M8 item 5 settle ignored | KILLED | 5 |
| M8d | M8 item 2 monitoring off ignored | KILLED | 2 |
| M9 | M9 clearance margin computed as limit/100*5 | KILLED | 1 |
| M10 | M10 observation under other settings accepted (0b removed) | KILLED | 2 |
| M11 | M11 trigger-intent check removed | KILLED | 10 |
| L1 | Lifecycle: stop not checked at the start of an action | KILLED | 3 |
| R1 | Revocation: settings version not checked at the start of an action | KILLED | 10 |
| R2 | Revocation: publication not under the action lock | KILLED | 1 |
| N01 | M1 oscillationEceIsOneIncident: the emergency ledger ends at CRITICAL | KILLED | 1 |
| N02 | M1 memoryRelievedWithinSettle: state-based gate (the reading is not re-checked, F32) | KILLED | 4 |
| N03 | M1 directEmergencyLowersWhenCriticalWasSkipped: no lowering at Emergency | KILLED | 3 |
| N04 | M1 suspendWithPrioritySwitchOff: the priority switch ignored for lowering (OOM-6) | KILLED | 25 |
| N05 | M1 forgetThenReManageDoesNotReArm: forget clears the ledger | KILLED | 1 |
| N06 | M2 bothActiveInEitherOrder: a second claim suspends and declares again | KILLED | 2 |
| N07 | M2 releasingTheLastClaim: claims cleared without the physical resume | KILLED | 25 |
| N08 | M2 suspendSwitchOffReleasesBothClaims: the switch ignored in the justification | KILLED | 3 |
| N09 | M2 turningOomManagementOffReleasesBothClaims: management ignored in the justification | KILLED | 1 |
| N10 | M2 aNewClaimDuringIncompleteRelease: suspended again while the release is incomplete | KILLED | 1 |
| N11a | M2/M5 incompleteReleaseIsRetriedEveryTick: an incomplete release is not retried | KILLED | 5 |
| N11b | M5 releasedNow: an incomplete release retried twice in one evaluation | KILLED | 1 |
| N12 | M2 anotherOwnersIncompleteRemainder: another owner's remainder is suspended over | KILLED | 1 |
| N13 | M2 shutdownReleasesThePhysicalReason: stop keeps the claims | KILLED | 1 |
| N15 | M3 changingAnActionSwitchDoesNotChangeTheEpoch: every switch starts an epoch | KILLED | 1 |
| N16 | M3 togglingMonitoringStartsANewEpoch: monitoring left out of the epoch | KILLED | 1 |
| N17 | M3 unchangedSettingsDoNotResetAnything: identity instead of equality | KILLED | 125 |
| N18 | M3 aMissingSample: a missing sample keeps the previous one | KILLED | 2 |
| N19 | M4 restorableSystemRestoresAtNormal: restored to Normal, not to the level read | KILLED | 1 |
| N20 | M4 restoreFailureIsAnErrorNotSilence: a failed restore logged as INFO | KILLED | 1 |
| N21 | M4 unvalidatedSignalDisablesLoweringToo: the signal gate ignored for lowering | KILLED | 4 |
| N22 | M4 shutdownRestoresWithinItsBound: the bound ignored | KILLED | 2 |
| N23 | M4 switchOffMidIncidentRestoresImmediately: the priority switch ignored in the justification | KILLED | 1 |
| N24 | M4 exitHookReleasesButDoesNotRestore: stop restores the priorities | KILLED | 5 |
| N25 | M6 aZeroAvailableReadingIsUnavailable: a zero reading accepted | KILLED | 1 |
| N26 | M7 the signal gate ignored for termination | KILLED | 5 |
| N27 | M7 page side: the page does not say the signal is not validated | KILLED | 1 |
| N28 | M7 aMemoryLimitIsUnsupportedOnAnUnvalidatedSystem: the policy offered unvalidated | KILLED | 1 |
| N29 | M9 clearance: at the clearance value it does not clear (strict) | KILLED | 5 |
| N30 | M9 crossing: the limit itself counts as above | KILLED | 1 |
| N31 | M9 watchingRejectsAnInvalidLimit: an invalid limit accepted | KILLED | 1 |
| N32 | M9 belowTheLimitWithTheFlagSetIsNotActedOn: the reading not re-checked at the gate | KILLED | 1 |
| N33 | M10 theGateReadsTheSettingsOncePerAuthorization: read twice | KILLED | 1 |
| N34 | M10/M11 pendingTriggersAreClearedAtTaskStartNotEnd: cleared at the end | KILLED | 3 |
| N35 | M11 coalescedTriggers: a later trigger replaces the earlier ones | KILLED | 4 |
| N36 | M8 item 1: the gate stop check removed (the start check L1 stays) | KILLED | 1 |
| N37 | M8 item 4: the end of monitoring ignored | KILLED | 1 |
| N38 | M8 item 4: a GAP accepted | KILLED | 1 |
| N39 | M8 item 4: a HOLD accepted | KILLED | 2 |
| N40 | M8 item 6: the switch not re-checked at the gate | KILLED | 2 |
| N41 | M8 item 7: the not-listed check removed | KILLED | 11 |
| N43 | M8 postGapHealthyReadingActsNothing: no settle after a gap | KILLED | 1 |
| N44 | M5 a released declaration is withdrawn (written) while the journal is down | SURVIVED | 0 |
| H1 | M8 item 1 and the stop rows: every stop check on the way to a native call removed together (evaluate, gate items 1 and 8, action start) | KILLED | 8 |
| H2 | M8 aSampleAfterStopQueuesNothing: samples and triggers after stop accepted | KILLED | 1 |
| H3 | M8 theEarlierObservationIsNotActedOnWhenTheLatestIsNormal: the first observation kept, not the latest | KILLED | 97 |
| H4 | M5 noSuspendWithoutADurableDeclaration: suspended although the declaration was not written | SURVIVED | 0 |
| H5 | M5 noSuspendWithoutADurableDeclaration: an unhealthy journal does not take suspension out of effect | KILLED | 3 |
| H6 | M1 aSettingsChangeTriggersNoInterventionByItself: items 0 and 0b removed together | KILLED | 13 |
| H7 | M3 aMissingSampleAfterAnEpochChange: neither the epoch change nor a missing sample drops the previous sample | KILLED | 4 |

**Row reconciliation.** The table has 101 rows:
- every test that A15.9 names;
- every test of `OomGuardGateTest` (M8/M10/M11) and of `OomGuardJournalTest` (M5), which the matrix covers as families.

One row is struck in the plan (O16 b rejected).
- **97 of 100 applicable rows fail under at least one mutation.**
  - 82 of them fail under a targeted mutant.
  - 15 fail only under a broad one, meaning a mutant that fails 20 or more tests (H3, M8b, N04, N07, N17).
- 3 rows fail under none.

Rows shown only by a broad mutant (a weaker form of evidence):
- M1 `suspendWithPrioritySwitchOffSkipsLoweringButStillSuspends`: H3, N04, N17
- M1 `forgetThenReManageDoesNotReArm`: H3, N17
- M2 `noStrandedSuspension`: N07
- M2 `noPrematureResume`: N07
- M3 `unchangedSettingsDoNotResetAnything`: N17
- M9 `aOneMiBLimitCanBeCleared`: N07
- M8/M10/M11 `whenEveryConditionHoldsTheActionIsTaken`: M8b
- M8/M10/M11 `item7EveryActionReChecksProtectionFirstAndAProtectedProcessIsNotTriedAgain`: M8b
- M8/M10/M11 `theLatestObservationIsEvaluatedWhenTriggersCoalesce`: H3, M8b
- M8/M10/M11 `theEarlierObservationIsNotActedOnWhenTheLatestIsNormal`: H3
- M8/M10/M11 `item6WithoutTheSwitchChangeTheSameSequenceActs`: M8b
- M5 `aSuspensionIsDeclaredDurablyBeforeTheProcessIsTouched`: H3, N17
- M5 `aDeclarationThatCannotBeWrittenSuspendsNothing`: H3, N17
- M5 `aProcessStoppedElsewhereLeavesNoEntry`: H3, N17
- M5 `anIncompleteSuspendIsReleasedAtOnceAndRecordedAsFailed`: H3, N17

Rows not shown. These are the remaining acceptance exception of gate 12, for the owner:
- **M2 `crashRecoveryListsTheEntryOnce`**: The row tests the recovery listing of the suspension coordinator (ADR-0011, milestone 9 code that M11 extends with a reason). No OOM Guard mutation reaches it, and no coordinator mutation was made.
- **M10 `theListenerReadsSettingsOncePerSample`**: **A deviation from the plan.** Plan M10 names this pin test (one read of the settings per sample in the runtime listener, the producer side of item 0b), but it was never written. The property holds by the structure of `MonitoringRuntime`. No test proves it.
- **M8/M10/M11 `item6TheSwitchOffActsNothingAndSaysSo`**: This test turns the switch off before the sample, so the candidate choice refuses it and the gate is never reached. Item 6 at the gate itself is now tested by `item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing`.

This is not exhaustive mutation coverage. For every row except those listed, it shows one deliberate defect that the row's tests catch.

<details><summary>Row-by-row table (broad mutants in italics)</summary>

| Family | Plan row | Test in the code | Killed by (assertion; broad mutants in italics) |
|---|---|---|---|
| M1 | `everyConfigurationProducesItsLiteralCallSequence` | `OomGuardLedgerTest.everyConfigurationProducesItsLiteralCallSequence` | *H3*, M1a, *N04*, *N17* |
| M1 | `criticalLoweringDoesNotConsumeTheEmergencyAction` | `OomGuardLedgerTest.criticalLoweringDoesNotConsumeTheEmergencyAction` | *H3*, M1a, *N17* |
| M1 | `criticalLoweringThenTerminate` | `OomGuardLedgerTest.criticalLoweringThenTerminate` | *H3*, M1a, *N17* |
| M1 | `memoryRelievedWithinSettleStopsTheSecondAction` | `OomGuardLedgerTest.memoryRelievedWithinSettleStopsTheSecondAction` | *H3*, N02, *N07*, *N17* |
| M1 | `lowerPriorityConfiguredIsNotLoweredTwice` | `OomGuardLedgerTest.lowerPriorityConfiguredIsNotLoweredTwice` | *H3*, M1a, *N17* |
| M1 | `directEmergencyLowersWhenCriticalWasSkipped` | `OomGuardLedgerTest.directEmergencyLowersWhenCriticalWasSkipped` | *H3*, N03, *N17* |
| M1 | `suspendWithPrioritySwitchOffSkipsLoweringButStillSuspends` | `OomGuardLedgerTest.suspendWithPrioritySwitchOffSkipsLoweringButStillSuspends` | *H3*, *N04*, *N17* |
| M1 | `suspendSwitchOffAtCriticalThenOnAtEmergencySuspendsOnce` | `OomGuardLedgerTest.suspendSwitchOffAtCriticalThenOnAtEmergencySuspendsOnce` | *H3*, M1a, *N17* |
| M1 | `unwindOrderIsResumeThenRestore` | `OomGuardLedgerTest.unwindOrderIsResumeThenRestore` | *H3*, M1a, *N07*, *N17* |
| M1 | `actionChangeMidIncidentDoesNotEscalate` | `OomGuardLedgerTest.actionChangeMidIncidentDoesNotEscalate` | *H3*, M1b, *N07*, *N17* |
| M1 | `aSettingsChangeTriggersNoInterventionByItself` | `OomGuardLedgerTest.aSettingsChangeTriggersNoInterventionByItself` | *H3*, H6, M1a, *N17* |
| M1 | `secondSuspendInSameIncidentNeverHappens` | `OomGuardLedgerTest.secondSuspendInSameIncidentNeverHappens` | *H3*, M1b, *N17* |
| M1 | `forgetThenReManageDoesNotReArm` | `OomGuardLedgerTest.forgetThenReManageDoesNotReArm` | *H3*, *N17* |
| M1 | `aNewIncidentResetsTheLedgerButNotTerminateSent` | `OomGuardLedgerTest.aNewIncidentResetsTheLedgerButNotTerminateSent` | *H3*, M1c, *N17* |
| M1 | `oscillationEceIsOneIncident` | `OomGuardLedgerTest.oscillationEceIsOneIncident` | *H3*, N03, *N17* |
| M2 | `pressureRecoversWhileResidentPolicyStillHolds` | `OomGuardClaimsTest.pressureRecoversWhileResidentPolicyStillHolds` | *H3*, M2a, *N17* |
| M2 | `residentClearsWhilePressureStillHolds` | `OomGuardClaimsTest.residentClearsWhilePressureStillHolds` | *H3*, M2a, *N17*, N29 |
| M2 | `bothActiveInEitherOrderMakeOneNativeSuspendAndOneDeclaration` | `OomGuardClaimsTest.bothActiveInEitherOrderMakeOneNativeSuspendAndOneDeclaration` | N06 |
| M2 | `releasingTheLastClaimReleasesPhysically` | `OomGuardClaimsTest.releasingTheLastClaimReleasesPhysically` | *N07*, N29 |
| M2 | `disablingOneJustificationLeavesTheOther` | `OomGuardClaimsTest.disablingOneJustificationLeavesTheOther` | *H3*, M2a, *N17* |
| M2 | `turningOomManagementOffReleasesBothClaims` | `OomGuardClaimsTest.turningOomManagementOffReleasesBothClaims` | *N07*, N09 |
| M2 | `suspendSwitchOffReleasesBothClaims` | `OomGuardClaimsTest.suspendSwitchOffReleasesBothClaims` | *N07*, N08 |
| M2 | `aNewClaimDuringIncompleteReleaseIsNotReSuspendedAndThePolicyFlagSurvives` | `OomGuardClaimsTest.aNewClaimDuringIncompleteReleaseIsNotReSuspendedAndThePolicyFlagSurvives` | *H3*, H6, M11, *N07*, N11a, *N17* |
| M2 | `incompleteReleaseIsRetriedEveryTickRegardlessOfClaims` | `OomGuardClaimsTest.incompleteReleaseIsRetriedEveryTickRegardlessOfClaims` | *H3*, *N07*, N11a, *N17* |
| M2 | `incompleteSuspendOnAnotherOwnersRemainderStopsRetrying` | `OomGuardClaimsTest.anotherOwnersIncompleteRemainderIsNeitherSuspendedOverNorRetriedByOomGuard (renamed)` | N12 |
| M2 | `shutdownReleasesThePhysicalReasonWhateverTheClaims` | `OomGuardClaimsTest.shutdownReleasesThePhysicalReasonWhateverTheClaims` | N13 |
| M2 | `crashRecoveryListsTheEntryOnce` | `OomGuardClaimsTest.crashRecoveryListsTheEntryOnceAndOnWindowsItCanBeResumed` | **none** |
| M2 | `journalFailureReleasesBothClaims` | `OomGuardClaimsTest.journalFailureReleasesBothClaims` | M5 |
| M2 | `noStrandedSuspension` | `OomGuardClaimsTest.noStrandedSuspensionAndNoPrematureResume (renamed)` | *N07* |
| M2 | `noPrematureResume` | `OomGuardClaimsTest.noStrandedSuspensionAndNoPrematureResume (renamed)` | *N07* |
| M3 | `raisingAThresholdInvalidatesThePreviousSample` | `OomStateMachineTest.raisingAThresholdInvalidatesThePreviousSample` | H7, M3, *N17* |
| M3 | `loweringAThresholdKeepsTheStateAndNeedsAFullDwell` | `OomStateMachineTest.loweringAThresholdKeepsTheStateAndNeedsAFullDwell` | M3, *N17* |
| M3 | `changingAnActionSwitchDoesNotChangeTheEpoch` | `OomStateMachineTest.changingAnActionSwitchDoesNotChangeTheEpoch` | N15, *N17* |
| M3 | `togglingMonitoringStartsANewEpoch` | `OomStateMachineTest.togglingMonitoringStartsANewEpoch` | N16, *N17* |
| M3 | `unchangedSettingsDoNotResetAnything` | `OomStateMachineTest.unchangedSettingsDoNotResetAnything` | *N17* |
| M3 | `aMissingSampleAfterAnEpochChangeStillNeedsTwoFreshSamples` | `OomStateMachineTest.aMissingSampleAfterAnEpochChangeStillNeedsTwoFreshSamples` | H7, *N17* |
| M3 | `dwellDuringRecoveryRestartsOnAnyThresholdChange` | `OomStateMachineTest.dwellDuringRecoveryRestartsOnAnyThresholdChange` | M3, *N17* |
| M4 | `restorableSystemRestoresAtNormal` | `OomGuardRestoreTest.restorableSystemRestoresAtNormal` | *H3*, *N17*, N19 |
| M4 | `restoreFailureIsAnErrorNotSilence` | `OomGuardRestoreTest.restoreFailureIsAnErrorNotSilence` | *H3*, *N17*, N20 |
| M4 | `restoreSkippedWhenSomeoneChangedThePriority` | `OomGuardRestoreTest.restoreSkippedWhenSomeoneChangedThePriority` | *H3*, M4a, *N17* |
| M4 | `notReversibleSystemWithOptionAOffersNoLowering` | — (struck in the plan, O16 b rejected) | n/a |
| M4 | `notReversibleSystemShowsLowerPriorityAsNotInEffect` | `OomGuardRestoreTest.notReversibleSystemShowsLowerPriorityAsNotInEffectAndNeverLowers (renamed)` | M4b |
| M4 | `unvalidatedSignalDisablesLoweringToo` | `OomGuardRestoreTest.unvalidatedSignalDisablesLoweringToo` | N21 |
| M4 | `shutdownRestoresWithinItsBound` | `OomGuardRestoreTest.shutdownRestoresWithinItsBound` | *H3*, *N17*, N22, N24 |
| M4 | `switchOffMidIncidentRestoresImmediately` | `OomGuardRestoreTest.switchOffMidIncidentRestoresImmediately` | *H3*, N03, *N17*, N23 |
| M4 | `exitHookReleasesButDoesNotRestore` | `OomGuardRestoreTest.exitHookReleasesButDoesNotRestore` | *H3*, M1a, *N17*, N24 |
| M5 | `aFailedNativeReleaseOnJournalFailureKeepsOwnershipAndReportsIncomplete` | `OomGuardJournalTest.aFailedNativeReleaseOnJournalFailureKeepsOwnershipAndReportsIncomplete` | *H3*, M5, N11a, N11b, *N17* |
| M5 | `journalFailureAfterMonitorEndedStillReleases` | `OomGuardJournalTest.journalFailureAfterMonitorEndedStillReleases` | *H3*, M5, *N17* |
| M7 | `allSwitchesTrueOnAnUnvalidatedSystemMakeNoNativeCall` | `MonitoringRuntimeOomTest.allSwitchesOnAnUnvalidatedSystemMakeNoNativeCall (renamed)` | *H3*, M7, *N17*, N26 |
| M7 | `aMemoryLimitIsUnsupportedOnAnUnvalidatedSystem` | `LimitServiceOomTest.aMemoryLimitIsUnsupportedOnAnUnvalidatedSystem` | N28 |
| M9 | `clearanceIsReachableForEveryValidLimit` | `ClearanceTest.clearanceIsReachableForEveryValidLimit` | M6, M9 |
| M9 | `aOneMiBLimitCanBeCleared` | `OomGuardPolicyTest.aOneMiBLimitCanBeCleared` | *N07* |
| M9 | `watchResidentRejectsAnInvalidLimit` | `ClearanceTest.watchingRejectsAnInvalidLimit (renamed)` | N31 |
| M9 | `belowTheLimitWithTheFlagSetIsNotActedOn` | `OomGuardPolicyTest.belowTheLimitWithTheFlagSetIsNotActedOn` | *H3*, N32 |
| M10 | `aQueuedEvaluationCannotUseAPreChangeObservation` | `OomGuardGateTest.aQueuedEvaluationCannotUseAPreChangeObservation` | *H3*, H6, M10, *M8b*, N35 |
| M10 | `theListenerReadsSettingsOncePerSample` | **no test of this name** | — |
| M10 | `theGateReadsSettingsOncePerAuthorization` | `OomGuardGateTest.theGateReadsTheSettingsOncePerAuthorization (renamed)` | *M8b*, N33, R1 |
| M11 | `aListingTriggerNeverAuthorizesAPressureIntervention` | `OomGuardGateTest.aListingTriggerNeverAuthorizesAPressureIntervention` | H6, M11, *M8b*, N41 |
| M11 | `aSampleTriggerNeverAuthorizesAPolicyIntervention` | `OomGuardGateTest.aSampleTriggerNeverAuthorizesAPolicyIntervention` | H6, M11, *M8b* |
| M11 | `coalescedTriggersAuthorizeEachIntentOnceWithOneActionPerEvaluate` | `OomGuardGateTest.coalescedTriggersAuthorizeEachIntentOnceWithOneActionPerEvaluate` | *M8b*, N35 |
| M11 | `policyRunsInTheSameEvaluateWhenPressureDidNotAct` | `OomGuardGateTest.policyRunsInTheSameEvaluateWhenPressureDidNotAct` | *M8b*, M8c, N35 |
| M11 | `pendingTriggersAreClearedAtTaskStartNotEnd` | `OomGuardGateTest.pendingTriggersAreClearedAtTaskStartNotEnd` | *M8b*, N34 |
| M8 | `postGapHealthyReadingActsNothing` | `OomGuardGateTest.postGapHealthyReadingActsNothing` | H6, M11, N02, *N17*, N41 |
| M8/M10/M11 | `whenEveryConditionHoldsTheActionIsTaken` | `OomGuardGateTest.whenEveryConditionHoldsTheActionIsTaken` | *M8b* |
| M8/M10/M11 | `aSecondaryTriggerStartsNoIntervention` | `OomGuardGateTest.aSecondaryTriggerStartsNoIntervention` | H6, M11, N41 |
| M8/M10/M11 | `aJournalTriggerStartsNoIntervention` | `OomGuardGateTest.aJournalTriggerStartsNoIntervention` | H6, M11, N41 |
| M8/M10/M11 | `aJournalTriggerStartsNoTermination` | `OomGuardGateTest.aJournalTriggerStartsNoTermination` | H6, M11, N41 |
| M8/M10/M11 | `stoppedWhileTheFactsAreReadActsNothing` | `OomGuardGateTest.stoppedWhileTheFactsAreReadActsNothing` | H1, *M8b* |
| M8/M10/M11 | `item1StoppedActsNothing` | `OomGuardGateTest.item1StoppedActsNothing` | H1 |
| M8/M10/M11 | `aSampleAfterStopQueuesNothing` | `OomGuardGateTest.aSampleAfterStopQueuesNothing` | H2 |
| M8/M10/M11 | `item2MonitoringOffActsNothing` | `OomGuardGateTest.item2MonitoringOffActsNothing` | M8d |
| M8/M10/M11 | `item3AStaleReadingActsNothing` | `OomGuardGateTest.item3AStaleReadingActsNothing` | M8a |
| M8/M10/M11 | `item4NoReadingAGapOrNoMonitoringActsNothing` | `OomGuardGateTest.item4NoReadingAGapOrNoMonitoringActsNothing` | *H3*, N37, N38, N39 |
| M8/M10/M11 | `item5AReadingThatNoLongerMeetsTheLevelActsNothing` | `OomGuardGateTest.item5AReadingThatNoLongerMeetsTheLevelActsNothing` | N02 |
| M8/M10/M11 | `item5TheSettleIntervalHoldsTheNextCandidate` | `OomGuardGateTest.item5TheSettleIntervalHoldsTheNextCandidate` | *M8b*, M8c |
| M8/M10/M11 | `item6TheSwitchOffActsNothingAndSaysSo` | `OomGuardGateTest.item6TheSwitchOffActsNothingAndSaysSo` | **none** |
| M8/M10/M11 | `item7AProcessNotListedIsNotActedOn` | `OomGuardGateTest.item7AProcessNotListedIsNotActedOn` | N41 |
| M8/M10/M11 | `item7EveryActionReChecksProtectionFirstAndAProtectedProcessIsNotTriedAgain` | `OomGuardGateTest.item7EveryActionReChecksProtectionFirstAndAProtectedProcessIsNotTriedAgain` | *M8b* |
| M8/M10/M11 | `item8TheLedgerAllowsOneEmergencyActionPerIncident` | `OomGuardGateTest.item8TheLedgerAllowsOneEmergencyActionPerIncident` | M1b |
| M8/M10/M11 | `aQueuedEvaluateAfterStopMakesNoNativeCall` | `OomGuardGateTest.aQueuedEvaluateAfterStopMakesNoNativeCall` | H1 |
| M8/M10/M11 | `noNewActionOnAMissingReading` | `OomGuardGateTest.noNewActionOnAMissingReading` | *H3*, *N17*, N39 |
| M8/M10/M11 | `aChangedThresholdNeedsAReadingThatMeetsItUnderTheNewVersion` | `OomGuardGateTest.aChangedThresholdNeedsAReadingThatMeetsItUnderTheNewVersion` | *H3*, H6, M10, *M8b*, N02, N35 |
| M8/M10/M11 | `theGateReadsTheSettingsOncePerAuthorization` | `OomGuardGateTest.theGateReadsTheSettingsOncePerAuthorization` | *M8b*, N33, R1 |
| M8/M10/M11 | `theLatestObservationIsEvaluatedWhenTriggersCoalesce` | `OomGuardGateTest.theLatestObservationIsEvaluatedWhenTriggersCoalesce` | *H3*, *M8b* |
| M8/M10/M11 | `theEarlierObservationIsNotActedOnWhenTheLatestIsNormal` | `OomGuardGateTest.theEarlierObservationIsNotActedOnWhenTheLatestIsNormal` | *H3* |
| M8/M10/M11 | `anObservationDuringAnEvaluationIsNotLost` | `OomGuardGateTest.anObservationDuringAnEvaluationIsNotLost` | *H3*, *N07*, N34 |
| M8/M10/M11 | `afterAGapAReadingStillAtEmergencyWaitsForTheSettle` | `OomGuardGateTest.afterAGapAReadingStillAtEmergencyWaitsForTheSettle` | *H3*, H6, M11, M8c, *N17*, N41, N43 |
| M8/M10/M11 | `item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing` | `OomGuardGateTest.item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing` | N40 |
| M8/M10/M11 | `item6WithoutTheSwitchChangeTheSameSequenceActs` | `OomGuardGateTest.item6WithoutTheSwitchChangeTheSameSequenceActs` | *M8b* |
| M8/M10/M11 | `aStopDuringTheGateMakesNoNativeCallNotEvenAFactsRead` | `OomGuardGateTest.aStopDuringTheGateMakesNoNativeCallNotEvenAFactsRead` | H1, N36 |
| M5 | `aSuspensionIsDeclaredDurablyBeforeTheProcessIsTouched` | `OomGuardJournalTest.aSuspensionIsDeclaredDurablyBeforeTheProcessIsTouched` | *H3*, *N17* |
| M5 | `noSuspendWithoutADurableDeclaration` | `OomGuardJournalTest.noSuspendWithoutADurableDeclaration` | H5 |
| M5 | `aDeclarationThatCannotBeWrittenSuspendsNothing` | `OomGuardJournalTest.aDeclarationThatCannotBeWrittenSuspendsNothing` | *H3*, *N17* |
| M5 | `aSuspendThatFailsTakesTheDeclarationOutOfTheJournal` | `OomGuardJournalTest.aSuspendThatFailsTakesTheDeclarationOutOfTheJournal` | *H3*, M1b, *N17* |
| M5 | `aTransientSuspendFailureIsNotRetriedByOomGuard` | `OomGuardJournalTest.aTransientSuspendFailureIsNotRetriedByOomGuard` | *H3*, M1b, *N17* |
| M5 | `aProcessStoppedElsewhereLeavesNoEntry` | `OomGuardJournalTest.aProcessStoppedElsewhereLeavesNoEntry` | *H3*, *N17* |
| M5 | `anIncompleteSuspendIsReleasedAtOnceAndRecordedAsFailed` | `OomGuardJournalTest.anIncompleteSuspendIsReleasedAtOnceAndRecordedAsFailed` | *H3*, *N17* |
| M5 | `anIncompleteReleaseKeepsTheJournalEntryAndIsTriedAgainOnTheNextTick` | `OomGuardJournalTest.anIncompleteReleaseKeepsTheJournalEntryAndIsTriedAgainOnTheNextTick` | *H3*, *N07*, N08, N11a, *N17* |
| M5 | `aJournalFailureReleasesEveryClaimAndResumes` | `OomGuardJournalTest.aJournalFailureReleasesEveryClaimAndResumes` | *H3*, M5, *N17* |
| M5 | `journalFailedTwiceIsIdempotent` | `OomGuardJournalTest.journalFailedTwiceIsIdempotent` | *H3*, M5, *N17* |
| M5 | `lowerPriorityAndTerminateAreNotAffectedByAFailedJournal` | `OomGuardJournalTest.lowerPriorityAndTerminateAreNotAffectedByAFailedJournal` | *H3*, M1a, *N17* |

</details>

## Closure addendum: the listener pin test (2026-10-13)

The plan's M10 pin test `theListenerReadsSettingsOncePerSample` was one of the three rows not
shown above. It is now written, as
`MonitoringRuntimeOomTest.theListenerReadsSettingsOncePerSample`. The test is behavioral; it does
not count reads.
- **The race it sets up.** Another thread publishes new settings right after the sample took its
  settings snapshot and before the sample is judged. The publication is made through the clock that
  the sample reads next.
- **What it asserts.**
  - The observation still carries the version it was judged under, so OOM Guard refuses it with
    "settings changed; waiting for a sample".
  - Only the next sample, judged under the new settings, acts.
- **Result on the real code.** It passed 5 times out of 5 alone, and its class (13 tests) passed.
  No production code was changed.
- **One targeted mutant.** The listener was changed to read the settings a second time for the
  version (`OomObservation.of(transition, oomSettings.get(), now)`). Under that mutant the test
  failed by assertion: the refusal was never shown. `MonitoringRuntime.java` was then restored, and
  its SHA-256 matched.

No new mutation campaign was run. With this test, 98 of the 100 applicable rows are shown, and 2
remain exceptions of gate 12:
- `crashRecoveryListsTheEntryOnce`: the coordinator's recovery listing; no coordinator mutation
  was made.
- `item6TheSwitchOffActsNothingAndSaysSo`: the switch is refused at the choice of the candidate
  before the gate. Item 6 at the gate is shown by
  `item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing` (N40).

15 rows are still shown only by a broad mutant, as listed in the final run above. Both counts
include the single targeted mutant of this addendum: 72 mutants in all, 70 killed.

