package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import prism.srm.application.OomStateMachine.Transition;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.MemorySample;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;

/**
 * The memory pressure states (OOM-1 to OOM-4, OOM-13) on an injected clock: every transition, the
 * hysteresis, the floor, a missing reading, a gap, a change of the thresholds, and the integer
 * arithmetic at the edges of a {@link MemorySample}.
 */
class OomStateMachineTest {

    /** 100 GB: one hundredth of a percent is 10 MB exactly, and the floor never counts here. */
    private static final long BIG = 100_000_000_000L;

    private static final long MIB = 1L << 20;
    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    private final OomStateMachine machine = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
    private long now = 1_000 * SECOND;
    private OomSettings settings = OomSettings.DEFAULTS;

    /** One sample a second later, with this share available in hundredths of a percent of {@link #BIG}. */
    private Transition at(long hundredths) {
        return bytes(BIG, BIG / 10_000 * hundredths);
    }

    private Transition percent(long whole) {
        return at(whole * 100);
    }

    private Transition bytes(long total, long available) {
        now += SECOND;
        return machine.observe(Optional.of(new MemorySample(total, available, Optional.empty())), now, settings);
    }

    private Transition absent() {
        now += SECOND;
        return machine.observe(Optional.empty(), now, settings);
    }

    /** Samples of the same share for this many seconds; the last transition. */
    private Transition hold(long whole, int seconds) {
        Transition last = null;
        for (int i = 0; i < seconds; i++) {
            last = percent(whole);
        }
        return last;
    }

    private void enter(OomState state, long whole) {
        percent(whole);
        assertThat(percent(whole).to()).isEqualTo(state);
    }

    // ----- Up -----

    @Test
    void everyStateIsEnteredAfterTwoConsecutiveSamplesAndNotAfterOne() {
        assertThat(percent(14).kind()).isEqualTo(Kind.NONE);
        Transition warning = percent(14);
        assertThat(warning.kind()).isEqualTo(Kind.ENTERED);
        assertThat(warning.from()).isEqualTo(OomState.NORMAL);
        assertThat(warning.to()).isEqualTo(OomState.WARNING);

        assertThat(percent(7).to()).isEqualTo(OomState.WARNING);
        assertThat(percent(7).to()).isEqualTo(OomState.CRITICAL);

        assertThat(percent(4).to()).isEqualTo(OomState.CRITICAL);
        assertThat(percent(4).to()).isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void normalMayGoStraightToEmergencyAndWarningMayToo() {
        percent(3);
        assertThat(percent(3).to()).isEqualTo(OomState.EMERGENCY);

        OomStateMachine other = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
        long t = 0;
        for (int share : new int[] {14, 14, 4, 4}) {
            t += SECOND;
            other.observe(Optional.of(new MemorySample(BIG, BIG / 100 * share, Optional.empty())), t, settings);
        }
        assertThat(other.state()).isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void aRampEntersOneLevelPerSample() {
        assertThat(percent(10).to()).isEqualTo(OomState.NORMAL);
        assertThat(percent(7).to()).isEqualTo(OomState.WARNING);
        assertThat(percent(4).to()).isEqualTo(OomState.CRITICAL);
        assertThat(percent(4).to()).isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void twoSamplesWithAMissingOneBetweenThemAreNotConsecutive() {
        percent(14);
        assertThat(absent().kind()).isEqualTo(Kind.HOLD);
        assertThat(percent(14).to()).isEqualTo(OomState.NORMAL);
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
    }

    @Test
    void atOrBelowTheThresholdEntersAndAHundredthAboveDoesNot() {
        at(1500);
        assertThat(at(1500).to()).isEqualTo(OomState.WARNING);

        OomStateMachine other = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
        long t = 0;
        for (int i = 0; i < 5; i++) {
            t += SECOND;
            assertThat(other.observe(
                                    Optional.of(new MemorySample(BIG, BIG / 10_000 * 1501, Optional.empty())),
                                    t,
                                    settings)
                            .to())
                    .isEqualTo(OomState.NORMAL);
        }
    }

    @Test
    void belowTheFloorIsEmergencyOnASmallMachineAndAtTheFloorIsNot() {
        long total = 4L << 30; // 5 % is 204.8 MiB: the floor of 256 MiB is the higher bar here.
        bytes(total, 255 * MIB);
        assertThat(bytes(total, 255 * MIB).to()).isEqualTo(OomState.EMERGENCY);

        OomStateMachine other = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
        other.observe(Optional.of(new MemorySample(total, 256 * MIB, Optional.empty())), SECOND, settings);
        Transition atFloor =
                other.observe(Optional.of(new MemorySample(total, 256 * MIB, Optional.empty())), 2 * SECOND, settings);
        assertThat(atFloor.to())
                .as("256 MiB is not below the floor, but is at most 8 %")
                .isEqualTo(OomState.CRITICAL);
    }

    @Test
    void onABigMachineTheShareDecidesAndTheFloorDoesNot() {
        // 5.01 % of 100 GB is far above 256 MiB: neither the share nor the floor is met.
        at(501);
        assertThat(at(501).to()).isEqualTo(OomState.CRITICAL);
        assertThat(at(501).to()).isEqualTo(OomState.CRITICAL);
    }

    @Test
    void aFloorOfZeroNeverTriggers() {
        settings = settings.withThresholds(new OomThresholds(15, 8, 5, 0));
        long total = 4L << 30;
        for (int i = 0; i < 5; i++) {
            bytes(total, total / 100 * 6);
        }
        assertThat(machine.state()).isEqualTo(OomState.CRITICAL);
    }

    @Test
    void aFloorAtOrAboveHalfTheTotalIsIgnored() {
        long total = 16L << 30;
        settings = settings.withThresholds(new OomThresholds(15, 8, 5, 8L << 30));
        for (int i = 0; i < 5; i++) {
            bytes(total, total / 2);
        }
        assertThat(machine.state()).isEqualTo(OomState.NORMAL);
        assertThat(OomStateMachine.floorUsable(8L << 30, total)).isFalse();
        assertThat(OomStateMachine.floorUsable((8L << 30) - 1, total)).isTrue();
    }

    @Test
    void theDecisionIsTheAvailableShareOfTheTotalWhateverTheTotal() {
        // A virtual machine that gains memory: 2 GB available of 10 GB, then 5 GB of 40 GB.
        bytes(10_000_000_000L, 2_000_000_000L);
        bytes(10_000_000_000L, 2_000_000_000L);
        assertThat(machine.state()).isEqualTo(OomState.NORMAL);
        bytes(40_000_000_000L, 5_000_000_000L);
        assertThat(bytes(40_000_000_000L, 5_000_000_000L).to()).isEqualTo(OomState.WARNING);
    }

    // ----- Down -----

    @Test
    void aStateIsLeftAfterTheDwellAboveTheThresholdPlusTheMarginAndNotBefore() {
        enter(OomState.WARNING, 14);
        Transition first = at(1801);
        assertThat(first.kind()).as("the dwell starts here").isEqualTo(Kind.NONE);
        for (int i = 0; i < 9; i++) {
            assertThat(at(1801).to()).isEqualTo(OomState.WARNING);
        }
        Transition left = at(1801);
        assertThat(left.kind()).isEqualTo(Kind.LEFT);
        assertThat(left.to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void justShortOfTheDwellTheStateStays() {
        enter(OomState.WARNING, 14);
        long start = now + SECOND;
        hold(19, 9);
        now = start + Duration.ofMillis(9_990).toNanos() - SECOND;
        assertThat(bytes(BIG, BIG / 100 * 19).to()).isEqualTo(OomState.WARNING);
        now = start + Duration.ofSeconds(10).toNanos() - SECOND;
        assertThat(bytes(BIG, BIG / 100 * 19).to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void exactlyTheThresholdPlusTheMarginIsNotAbove() {
        enter(OomState.WARNING, 14);
        assertThat(hold(18, 15).to()).isEqualTo(OomState.WARNING);
    }

    @Test
    void aSampleBelowTheMarginStartsTheDwellAgain() {
        enter(OomState.WARNING, 14);
        hold(19, 6);
        percent(17);
        assertThat(hold(19, 10).to()).isEqualTo(OomState.WARNING);
        assertThat(percent(19).to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void emergencyGoesDownOneLevelAtATimeTakingAtLeastThirtySeconds() {
        enter(OomState.EMERGENCY, 3);
        long entered = now;
        OomState last = OomState.EMERGENCY;
        while (machine.state() != OomState.NORMAL) {
            Transition t = percent(30);
            if (t.kind() == Kind.LEFT) {
                assertThat(t.to()).isEqualTo(last.below());
                last = t.to();
            }
        }
        assertThat(now - entered).isGreaterThanOrEqualTo(30 * SECOND);
    }

    @Test
    void aReadingBetweenTwoExitsLeavesOneLevelAndStaysInTheNext() {
        enter(OomState.EMERGENCY, 3);
        assertThat(hold(10, 11).to()).isEqualTo(OomState.CRITICAL);
        assertThat(hold(10, 60).to()).as("10 % is not above 8 + 3").isEqualTo(OomState.CRITICAL);
    }

    @Test
    void anEmergencyBelowTheFloorIsLeftOnlyAboveTheFloorPlusTheMargin() {
        long total = 4L << 30;
        bytes(total, 200 * MIB);
        bytes(total, 200 * MIB);
        assertThat(machine.state()).isEqualTo(OomState.EMERGENCY);
        // Above 5 + 3 % (328 MiB of 4 GiB), but not above 256 MiB + 3 % (about 379 MiB).
        long underFloorMargin = 256 * MIB + OomStateMachine.floorFrac(total, 3);
        for (int i = 0; i < 15; i++) {
            bytes(total, underFloorMargin);
        }
        assertThat(machine.state()).isEqualTo(OomState.EMERGENCY);
        for (int i = 0; i < 11; i++) {
            bytes(total, underFloorMargin + 1);
        }
        assertThat(machine.state()).isEqualTo(OomState.CRITICAL);
    }

    // ----- Flapping, missing samples and gaps -----

    @Test
    void flappingAroundTheThresholdNeverEnters() {
        for (int i = 0; i < 20; i++) {
            percent(i % 2 == 0 ? 14 : 16);
        }
        assertThat(machine.state()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void twoInARowEnterAndOneAboveDoesNotLeave() {
        percent(14);
        percent(14);
        percent(16);
        percent(14);
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
        assertThat(machine.state()).isEqualTo(OomState.WARNING);
    }

    @Test
    void anUnavailableReadingHoldsTheStateAndResetsTheStreakAndTheDwell() {
        enter(OomState.WARNING, 14);
        hold(19, 9);
        Transition held = absent();
        assertThat(held.kind()).isEqualTo(Kind.HOLD);
        assertThat(held.to()).isEqualTo(OomState.WARNING);
        assertThat(held.reading()).isEmpty();
        assertThat(hold(19, 10).to())
                .as("the dwell began again after the gap in readings")
                .isEqualTo(OomState.WARNING);
        assertThat(percent(19).to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void anEmergencyReadingAMissingOneAndAnotherEmergencyReadingStayNormal() {
        percent(4);
        absent();
        assertThat(percent(4).to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void anUnavailableReadingNeverEntersALevel() {
        for (int i = 0; i < 10; i++) {
            assertThat(absent().to()).isEqualTo(OomState.NORMAL);
        }
    }

    @Test
    void aGapResetsTheStreakAndTheDwell() {
        enter(OomState.WARNING, 14);
        hold(19, 9);
        now += Duration.ofSeconds(6).toNanos();
        Transition gap = percent(19);
        assertThat(gap.kind()).isEqualTo(Kind.GAP);
        assertThat(gap.to()).isEqualTo(OomState.WARNING);
        assertThat(hold(19, 10).to()).isEqualTo(OomState.WARNING);
        assertThat(percent(19).to()).isEqualTo(OomState.NORMAL);

        percent(4);
        now += Duration.ofSeconds(6).toNanos();
        assertThat(percent(4).kind()).as("not consecutive after a gap").isEqualTo(Kind.GAP);
        assertThat(percent(4).to()).isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void aPauseOfExactlyTheGapIsStillConsecutive() {
        percent(14);
        now += Duration.ofSeconds(4).toNanos();
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
    }

    @Test
    void theClockMayWrap() {
        now = Long.MAX_VALUE - Duration.ofSeconds(5).toNanos();
        enter(OomState.WARNING, 14);
        Transition last = null;
        for (int i = 0; i < 11; i++) {
            last = percent(19);
        }
        assertThat(now).isNegative();
        assertThat(last.kind()).isEqualTo(Kind.LEFT);
    }

    // ----- Monitoring off, and changed settings -----

    @Test
    void monitoringOffIsNormalAndOff() {
        enter(OomState.CRITICAL, 7);
        settings = settings.withSwitches(OomSwitches.DEFAULTS.withMonitoring(false));
        Transition off = percent(1);
        assertThat(off.kind()).isEqualTo(Kind.OFF);
        assertThat(off.to()).isEqualTo(OomState.NORMAL);
        assertThat(off.reading()).isEmpty();
    }

    @Test
    void offThenOnStartsFromNormalAndNeedsTwoSamples() {
        enter(OomState.CRITICAL, 7);
        settings = settings.withSwitches(OomSwitches.DEFAULTS.withMonitoring(false));
        percent(7);
        settings = settings.withSwitches(OomSwitches.DEFAULTS);
        assertThat(percent(7).to()).isEqualTo(OomState.NORMAL);
        assertThat(percent(7).to()).isEqualTo(OomState.CRITICAL);
    }

    @Test
    void togglingMonitoringStartsANewEpoch() {
        percent(20);
        long before = machine.epoch();
        settings = settings.withSwitches(OomSwitches.DEFAULTS.withMonitoring(false));
        percent(20);
        settings = settings.withSwitches(OomSwitches.DEFAULTS);
        percent(14);
        assertThat(machine.epoch()).isEqualTo(before + 2);
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
    }

    @Test
    void raisingAThresholdInvalidatesThePreviousSample() {
        assertThat(percent(18).to()).isEqualTo(OomState.NORMAL);
        settings = settings.withThresholds(new OomThresholds(20, 8, 5, 256 * MIB));
        Transition changed = percent(18);
        assertThat(changed.kind()).as("not a gap: one second").isEqualTo(Kind.NONE);
        assertThat(changed.to()).isEqualTo(OomState.NORMAL);
        assertThat(percent(18).kind()).isEqualTo(Kind.ENTERED);
    }

    @Test
    void loweringAThresholdKeepsTheStateAndNeedsAFullDwell() {
        enter(OomState.WARNING, 14);
        hold(19, 6);
        settings = settings.withThresholds(new OomThresholds(10, 8, 5, 256 * MIB));
        assertThat(hold(19, 5).to()).as("the dwell began again with the change").isEqualTo(OomState.WARNING);
        assertThat(hold(19, 5).to()).isEqualTo(OomState.WARNING);
        assertThat(percent(19).to())
                .as("ten seconds after the first sample of the new epoch")
                .isEqualTo(OomState.NORMAL);
    }

    @Test
    void changingAnActionSwitchDoesNotChangeTheEpoch() {
        percent(14);
        settings = settings.withSwitches(OomSwitches.DEFAULTS.withAutoSuspend(true));
        assertThat(percent(14).to()).as("still consecutive").isEqualTo(OomState.WARNING);

        long epoch = machine.epoch();
        hold(19, 6);
        settings = settings.withSwitches(OomSwitches.DEFAULTS.withTermination(true));
        assertThat(hold(19, 4).to()).as("the dwell went on").isEqualTo(OomState.WARNING);
        assertThat(percent(19).to()).isEqualTo(OomState.NORMAL);
        assertThat(machine.epoch()).isEqualTo(epoch);
    }

    @Test
    void unchangedSettingsDoNotResetAnything() {
        percent(14);
        settings = new OomSettings(new OomThresholds(15, 8, 5, 256 * MIB), new OomSwitches(true, false, false, false));
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
        hold(19, 6);
        settings = new OomSettings(new OomThresholds(15, 8, 5, 256 * MIB), new OomSwitches(true, false, false, false));
        hold(19, 4);
        assertThat(percent(19).to()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void aMissingSampleAfterAnEpochChangeStillNeedsTwoFreshSamples() {
        percent(14);
        settings = settings.withThresholds(new OomThresholds(16, 8, 5, 256 * MIB));
        absent();
        assertThat(percent(14).to()).isEqualTo(OomState.NORMAL);
        assertThat(percent(14).to()).isEqualTo(OomState.WARNING);
    }

    @Test
    void dwellDuringRecoveryRestartsOnAnyThresholdChange() {
        enter(OomState.CRITICAL, 7);
        hold(12, 6);
        settings = settings.withThresholds(new OomThresholds(16, 8, 5, 256 * MIB));
        hold(12, 5);
        assertThat(machine.state()).isEqualTo(OomState.CRITICAL);
        hold(12, 5);
        assertThat(percent(12).to()).isEqualTo(OomState.WARNING);
    }

    // ----- Arithmetic at the edges -----

    @Test
    void floorFracIsExactWhereTheProductWouldOverflow() {
        long max = Long.MAX_VALUE;
        assertThat(OomStateMachine.floorFrac(max, 15)).isEqualTo(1_383_505_805_528_216_371L);
        assertThat(OomStateMachine.floorFrac(max, 18)).isEqualTo(1_660_206_966_633_859_645L);
        assertThat(OomStateMachine.floorFrac(max, 8)).isEqualTo(737_869_762_948_382_064L);
        assertThat(OomStateMachine.floorFrac(max, 11)).isEqualTo(1_014_570_924_054_025_338L);
        assertThat(OomStateMachine.floorFrac(max, 5)).isEqualTo(461_168_601_842_738_790L);
        for (long total : new long[] {1, 99, 100, 199, 1_001_000_003L, 1L << 50, max}) {
            for (int p = 0; p <= 100; p++) {
                assertThat(OomStateMachine.floorFrac(total, p))
                        .as("%d of %d", p, total)
                        .isEqualTo(BigInteger.valueOf(total)
                                .multiply(BigInteger.valueOf(p))
                                .divide(BigInteger.valueOf(100))
                                .longValueExact());
            }
        }
        assertThatIllegalArgumentException().isThrownBy(() -> OomStateMachine.floorFrac(1, 101));
    }

    @Test
    void theLargestTotalEntersAtExactlyItsShareAndNotOneByteAbove() {
        long max = Long.MAX_VALUE;
        bytes(max, 1_383_505_805_528_216_371L);
        assertThat(bytes(max, 1_383_505_805_528_216_371L).to()).isEqualTo(OomState.WARNING);

        OomStateMachine other = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
        for (int i = 1; i <= 4; i++) {
            other.observe(
                    Optional.of(new MemorySample(max, 1_383_505_805_528_216_372L, Optional.empty())),
                    i * SECOND,
                    settings);
        }
        assertThat(other.state()).isEqualTo(OomState.NORMAL);
    }

    @Test
    void theLargestTotalLeavesOnlyOneByteAboveTheMargin() {
        long max = Long.MAX_VALUE;
        bytes(max, 1_383_505_805_528_216_371L);
        bytes(max, 1_383_505_805_528_216_371L);
        for (int i = 0; i < 12; i++) {
            bytes(max, 1_660_206_966_633_859_645L);
        }
        assertThat(machine.state()).isEqualTo(OomState.WARNING);
        Transition last = null;
        for (int i = 0; i < 11; i++) {
            last = bytes(max, 1_660_206_966_633_859_646L);
        }
        assertThat(last.kind()).isEqualTo(Kind.LEFT);
    }

    @Test
    void allOfTheMemoryAvailableIsNormalAndNoneOnATinyTotalIsEmergency() {
        for (int i = 0; i < 4; i++) {
            bytes(Long.MAX_VALUE, Long.MAX_VALUE);
        }
        assertThat(machine.state()).isEqualTo(OomState.NORMAL);

        OomStateMachine tiny = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
        tiny.observe(Optional.of(new MemorySample(1, 0, Optional.empty())), SECOND, settings);
        assertThat(tiny.observe(Optional.of(new MemorySample(1, 0, Optional.empty())), 2 * SECOND, settings)
                        .to())
                .isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void theFloorAtHalfTheLargestTotalCountsAndOneByteMoreDoesNot() {
        long max = Long.MAX_VALUE;
        long half = 4_611_686_018_427_387_903L;
        assertThat(OomStateMachine.floorUsable(half, max)).isTrue();
        assertThat(OomStateMachine.floorUsable(half + 1, max)).isFalse();
        assertThat(OomStateMachine.floorUsable(max - 1, max)).isFalse();
        OomThresholds thresholds = new OomThresholds(15, 8, 5, half);
        OomReading justAbove = new OomReading(4_888_387_179_533_031_178L, max, 0);
        OomReading atTheMargin = new OomReading(4_888_387_179_533_031_177L, max, 0);
        assertThat(OomStateMachine.clear(OomState.EMERGENCY, justAbove, thresholds, 3))
                .isTrue();
        assertThat(OomStateMachine.clear(OomState.EMERGENCY, atTheMargin, thresholds, 3))
                .isFalse();
    }

    @Test
    void aTotalThatIsNotAMultipleOfAHundredMeetsByTheExactProduct() {
        long total = 1_001_000_003L;
        long share = BigInteger.valueOf(total)
                .multiply(BigInteger.valueOf(15))
                .divide(BigInteger.valueOf(100))
                .longValueExact();
        assertThat(OomStateMachine.meets(OomState.WARNING, new OomReading(share, total, 0), OomThresholds.DEFAULTS))
                .isTrue();
        assertThat(OomStateMachine.meets(OomState.WARNING, new OomReading(share + 1, total, 0), OomThresholds.DEFAULTS))
                .isFalse();
        for (long t = 100L * 7 + 99; t < 100L * 7 + 100; t++) {
            long fifteen = OomStateMachine.floorFrac(t, 15);
            assertThat(100 * fifteen).isLessThanOrEqualTo(15 * t);
            assertThat(100 * (fifteen + 1)).isGreaterThan(15 * t);
        }
    }

    @Test
    void aTotalOfTwoToTheFiftyDoesNotOverflow() {
        long total = 1L << 50;
        bytes(total, total / 100 * 4);
        assertThat(bytes(total, total / 100 * 4).to()).isEqualTo(OomState.EMERGENCY);
    }

    @Test
    void manyRandomValidSamplesNeverThrow() {
        Random random = new Random(11);
        assertThatCode(() -> {
                    for (int i = 0; i < 500; i++) {
                        long total = 1 + Math.floorMod(random.nextLong(), Long.MAX_VALUE);
                        long available = Math.floorMod(random.nextLong(), total) + (random.nextBoolean() ? 1 : 0);
                        now += random.nextInt(8) * SECOND;
                        Optional<MemorySample> sample = random.nextInt(10) == 0
                                ? Optional.empty()
                                : Optional.of(new MemorySample(total, Math.min(available, total), Optional.empty()));
                        machine.observe(sample, now, settings);
                    }
                })
                .doesNotThrowAnyException();
    }
}
