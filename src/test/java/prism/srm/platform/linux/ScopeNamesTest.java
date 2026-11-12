package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;

/**
 * The scope name of a process identity (S2): one identity, one name, and back; every representation that is not
 * exactly that name, or an identity no Linux process can have, gives nothing. A name is a key, never proof.
 */
class ScopeNamesTest {

    private static final ProcessIdentity CI2 = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));

    @Test
    void anIdentityHasExactlyOneNameAndBack() {
        assertThat(ScopeNames.of(CI2)).contains("prism-srm-limit-2071-1791970282990.scope");
        assertThat(ScopeNames.parse("prism-srm-limit-2071-1791970282990.scope")).contains(CI2);
        for (ProcessIdentity identity : List.of(
                new ProcessIdentity(1, Instant.EPOCH),
                new ProcessIdentity(CgroupV2.MOST_PID, Instant.ofEpochMilli(999_999_999_999_999_999L)),
                new ProcessIdentity(4_100, Instant.parse("2026-10-10T08:00:00.123Z")))) {
            String name = ScopeNames.of(identity).orElseThrow();
            assertThat(ScopeNames.parse(name)).as(name).contains(identity);
        }
    }

    @Test
    void anIdentityNoLinuxProcessCanHaveHasNoName() {
        assertThat(ScopeNames.of(new ProcessIdentity(0, Instant.ofEpochMilli(5))))
                .isEmpty();
        assertThat(ScopeNames.of(new ProcessIdentity(CgroupV2.MOST_PID + 1L, Instant.ofEpochMilli(5))))
                .isEmpty();
        assertThat(ScopeNames.of(new ProcessIdentity(5, Instant.ofEpochMilli(-1))))
                .isEmpty();
        assertThat(ScopeNames.of(new ProcessIdentity(5, Instant.ofEpochSecond(10, 500))))
                .as("not a whole number of milliseconds")
                .isEmpty();
        assertThat(ScopeNames.of(new ProcessIdentity(5, Instant.MAX))).isEmpty();
    }

    @Test
    void everyOtherRepresentationIsNoName() {
        for (String bad : List.of(
                "prism-srm-limit-02071-1791970282990.scope",
                "prism-srm-limit-2071-01791970282990.scope",
                "prism-srm-limit-0-5.scope",
                "prism-srm-limit-+2071-5.scope",
                "prism-srm-limit--2071-5.scope",
                "prism-srm-limit-2071--5.scope",
                "Prism-srm-limit-2071-5.scope",
                "prism-srm-limit-2071-5.SCOPE",
                "prism-srm-limit-2071-5.scope.bak",
                "prism-srm-limit-2071-5",
                "prism-srm-limit-2071-5.service",
                " prism-srm-limit-2071-5.scope",
                "prism-srm-limit-2071 -5.scope",
                "prism-srm-limit-4194305-5.scope",
                "prism-srm-limit-2071-1234567890123456789.scope",
                "prism-srm-limit-2071-5\n.scope",
                "x-prism-srm-limit-2071-5.scope",
                "")) {
            assertThat(ScopeNames.parse(bad)).as(bad.replace("\n", "\\n")).isEmpty();
        }
    }

    @Test
    void aCgroupPathIsInsideAPrismScopeOrNot() {
        String app = "/user.slice/user-1001.slice/user@1001.service/app.slice/";
        String scope = "prism-srm-limit-2071-1791970282990.scope";
        assertThat(ScopeNames.enclosingPrismScope(app + scope)).contains(scope);
        assertThat(ScopeNames.enclosingPrismScope(app + scope + "/child/deeper"))
                .contains(scope);
        assertThat(ScopeNames.enclosingPrismScope(app + "app-gnome-org.gnome.Terminal-2000.scope"))
                .isEmpty();
        assertThat(ScopeNames.enclosingPrismScope(app + "prism-srm-limit-not-a-scope"))
                .isEmpty();
        assertThat(ScopeNames.enclosingPrismScope("/")).isEmpty();
        assertThat(ScopeNames.enclosingPrismScope("prism-srm-limit-2071-5.scope"))
                .contains("prism-srm-limit-2071-5.scope");
    }
}
