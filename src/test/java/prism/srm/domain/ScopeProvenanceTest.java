package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The scope provenance a Linux record may carry (S1), and the Linux recovery verdicts: only values a record could have
 * been written with are accepted; a provenance missing its invocation id or cgroup path is incomplete; ownership is
 * proven by exactly two verdicts, and only a verified clearing says that nothing may be left in force.
 */
class ScopeProvenanceTest {

    private static final String UNIT = "prism-srm-limit-2071-1791970282990.scope";
    private static final String ID = "86f1083782564429bf3a1a34ede0c182";
    private static final String CGROUP = "/user.slice/user-1001.slice/user@1001.service/app.slice/" + UNIT;

    @Test
    void unitNamesFollowSystemdsRulesAndEndInScope() {
        for (String good : List.of(
                UNIT,
                "a.scope",
                "app-gnome-org.gnome.Terminal-1234.scope",
                "x\\x2dy.scope",
                "a:b_c.d-e.scope",
                "a".repeat(249) + ".scope")) {
            assertThat(ScopeProvenance.validUnit(good)).as(good).isTrue();
        }
        for (String bad : List.of(
                "",
                ".scope",
                "a.service",
                "a.scope.bak",
                "a b.scope",
                "a/b.scope",
                "a\n.scope",
                "ä.scope",
                "a".repeat(250) + ".scope")) {
            assertThat(ScopeProvenance.validUnit(bad)).as(bad).isFalse();
        }
    }

    @Test
    void invocationIdsAreThirtyTwoLowercaseHexDigitsNotAllZero() {
        assertThat(ScopeProvenance.validInvocationId(ID)).isTrue();
        for (String bad : List.of(
                "",
                ID.substring(1),
                ID + "0",
                ID.toUpperCase(java.util.Locale.ROOT),
                "0".repeat(32),
                "g" + ID.substring(1),
                "86f10837-8256-4429-bf3a-1a34ede0c182")) {
            assertThat(ScopeProvenance.validInvocationId(bad)).as(bad).isFalse();
        }
    }

    @Test
    void cgroupPathsAreAbsoluteWithoutDotSegmentsOrWhitespace() {
        for (String good : List.of("/", CGROUP, "/user.slice", "/a\\x2db")) {
            assertThat(ScopeProvenance.validCgroupPath(good)).as(good).isTrue();
        }
        for (String bad : List.of(
                "",
                "user.slice",
                "/user.slice/",
                "//x",
                "/a/../b",
                "/a/./b",
                "/..",
                "/a b",
                "/a\tb",
                "/a\nb",
                "/a\u007fb",
                "/" + "a".repeat(4096))) {
            assertThat(ScopeProvenance.validCgroupPath(bad))
                    .as(bad.replace("\n", "\\n"))
                    .isFalse();
        }
    }

    @Test
    void anInvalidValueIsNeverAProvenance() {
        assertThatThrownBy(() -> ScopeProvenance.unitOnly("not-a-scope.service"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopeProvenance(UNIT, Optional.of("0".repeat(32)), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopeProvenance(UNIT, Optional.empty(), Optional.of("relative/path")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAProvenanceWithItsInvocationIdAndCgroupIsComplete() {
        assertThat(new ScopeProvenance(UNIT, Optional.of(ID), Optional.of(CGROUP)).complete())
                .isTrue();
        ScopeProvenance noId = new ScopeProvenance(UNIT, Optional.empty(), Optional.of(CGROUP));
        ScopeProvenance noPath = new ScopeProvenance(UNIT, Optional.of(ID), Optional.empty());
        assertThat(noId.complete()).isFalse();
        assertThat(noPath.complete()).isFalse();
        assertThat(ScopeProvenance.unitOnly(UNIT).complete()).isFalse();
        assertThat(noId.describe()).isEqualTo(UNIT + " (invocation id not recorded)");
        assertThat(ScopeProvenance.unitOnly(UNIT).describe())
                .isEqualTo(UNIT + " (invocation id and cgroup path not recorded)");
    }

    @Test
    void ownershipIsProvenByExactlyTwoVerdictsAndNothingClearsByItself() {
        LimitRecord record = LimitRecord.intended(
                new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L)),
                "demo",
                EffectiveLimits.NONE,
                "1-1");
        for (RecoveredLimit.Verdict verdict : RecoveredLimit.Verdict.values()) {
            RecoveredLimit recovered = new RecoveredLimit(record, verdict, "");
            boolean proven = verdict == RecoveredLimit.Verdict.OWNED_LIMITED
                    || verdict == RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY;
            assertThat(recovered.ownershipProven()).as(verdict.name()).isEqualTo(proven);
        }
        assertThat(new RecoveredLimit(record, RecoveredLimit.Verdict.CLEARED, "").maybeStillLimited())
                .isFalse();
        for (RecoveredLimit.Verdict blocking : List.of(
                RecoveredLimit.Verdict.OWNED_LIMITED,
                RecoveredLimit.Verdict.CLEAR_FAILED,
                RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                RecoveredLimit.Verdict.FOREIGN_MEMBER)) {
            assertThat(new RecoveredLimit(record, blocking, "").blocksAnotherLimiter())
                    .as(blocking.name())
                    .isTrue();
        }
        assertThat(new RecoveredLimit(record, RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY, "").blocksAnotherLimiter())
                .as("its process is gone: no identity to block")
                .isFalse();
        assertThat(new RecoveredLimit(record, RecoveredLimit.Verdict.CLEARED, "").blocksAnotherLimiter())
                .isFalse();
    }
}
