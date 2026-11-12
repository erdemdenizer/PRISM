package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ResourceLimiter;
import prism.srm.platform.linux.ScopeSystemFixture;

/**
 * T-D26 across the layers (test review S8): the real Linux scope limiter with its own R table and clearing rules, over a
 * fake systemd, with the real ledger, native-limit controller and recovery, over three sessions. The target exits and a
 * process it started survives in its scope; the first session crashes. Invariant: while the scope holds processes and
 * no clearing was verified, its record with provenance is in the newest ledger; the survivor is kept from a second
 * limiter by the scope-membership rule; the exited identity and a process that reuses its PID are never blocked.
 */
class SurvivingDescendantScopeLimiterTest {

    private static final int TARGET_PID = 2_071;
    private static final int CHILD_PID = 2_080;

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    @TempDir
    Path temp;

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private ScopeSystemFixture system;

    @BeforeEach
    void setUp() {
        system = new ScopeSystemFixture(temp);
    }

    private NativeLimitLedger ledger(String instance) {
        return new NativeLimitLedger(Optional.of(folder.path()), instance, files, Runnable::run);
    }

    private NativeLimitLedger newest;

    /** The next session's recovery, with its own ledger ({@link #newest}), into which it carries what it keeps. */
    private List<RecoveredLimit> recover(ResourceLimiter limiter, String instance) {
        newest = ledger(instance);
        NativeRecovery recovery = new NativeRecovery(
                limiter,
                newest,
                Optional.of(folder.path()),
                files,
                new ActivityLog(100, () -> Instant.EPOCH),
                running -> false);
        return recovery.lookAtOrphans();
    }

    @Test
    void aSurvivorsScopeKeepsItsEvidenceUntilAVerifiedClearingAndBlocksOnlyByMembership() {
        // Session 1: the target is limited, starts a child in its scope, exits; the session crashes.
        ProcessIdentity target = system.start(TARGET_PID, 0);
        ResourceLimiter first = system.session(Set.of(target));
        NativeLimitLedger firstLedger = ledger("1-1");
        NativeLimitController controller = new NativeLimitController(first, firstLedger);
        ApplyResult applied = controller
                .apply(target, "demo", new LimitSpec(OptionalInt.of(20), OptionalLong.empty()))
                .orElseThrow();
        assertThat(applied.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        assertThat(firstLedger.find(target).orElseThrow().provenance()).isPresent();
        ProcessIdentity child = system.child(TARGET_PID, CHILD_PID, 50);
        system.exit(TARGET_PID);
        assertThat(first.scopeBarrier(child))
                .as("the survivor, in a scope this session holds for another")
                .isPresent();
        system.crash(first);

        // Session 2: the clearing is refused; the record is kept with its evidence, the survivor stays blocked.
        system.refuseLimits(true);
        ResourceLimiter second = system.session(Set.of());
        List<RecoveredLimit> found = recover(second, "2-2");
        assertThat(found).singleElement().satisfies(r -> {
            assertThat(r.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEAR_FAILED);
            assertThat(r.blocksAnotherLimiter())
                    .as("the identity rule names only the record's identity")
                    .isTrue();
        });
        assertThat(newest.find(target))
                .as("carried as ownership evidence")
                .hasValueSatisfying(r -> assertThat(r.provenance()).isPresent());
        assertThat(system.cpuMax(CHILD_PID)).isEqualTo("80000 100000");
        assertThat(second.scopeBarrier(child)).as("the survivor, by membership").isPresent();
        ProcessIdentity reused = system.start(TARGET_PID, 900);
        assertThat(second.scopeBarrier(reused))
                .as("a new process with the PID is outside the scope")
                .isEmpty();

        // Session 3: the clearing goes through and is read back; only now does the record go.
        system.refuseLimits(false);
        ResourceLimiter third = system.session(Set.of());
        List<RecoveredLimit> settled = recover(third, "3-3");
        assertThat(settled)
                .singleElement()
                .satisfies(r -> assertThat(r.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEARED));
        assertThat(newest.find(target)).as("verified cleared: the record goes").isEmpty();
        assertThat(system.cpuMax(CHILD_PID)).isEqualTo("max 100000");
        assertThat(system.inPrismScope(CHILD_PID))
                .as("it stays in the scope until it exits")
                .isTrue();
        assertThat(third.scopeBarrier(child))
                .as("still no second limiter in a Prism scope")
                .isPresent();
        assertThat(system.unitCount()).as("no scope was stopped").isEqualTo(1);
    }
}
