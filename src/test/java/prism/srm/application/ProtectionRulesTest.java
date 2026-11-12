package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;

/** SAFE-2 and SAFE-3: what Prism refuses, decided from facts, a name only adding to them. */
class ProtectionRulesTest {

    private static final long OWN = 7_777;
    private final ProtectionRules rules = new ProtectionRules(OWN);

    @Test
    void prismItselfIsRefusedByItsPidWhateverTheStartTime() {
        assertThat(rules.refusal(new ProcessIdentity(OWN, Instant.EPOCH)))
                .contains(ProtectionRules.Refusal.OWN_PROCESS);
        assertThat(rules.refusal(new ProcessIdentity(OWN + 4, Instant.EPOCH))).isEmpty();
    }

    @Test
    void aSystemProcessIsRefused() {
        assertThat(rules.refusal(new ProtectionFacts(true, Optional.empty(), false)))
                .contains(ProtectionRules.Refusal.SYSTEM_PROCESS);
    }

    @Test
    void aProcessTheSystemMarksCriticalIsRefused() {
        assertThat(rules.refusal(new ProtectionFacts(false, Optional.of(true), false)))
                .contains(ProtectionRules.Refusal.CRITICAL_PROCESS);
    }

    @Test
    void theNameOfASystemExecutableIsAnAdditionalRefusal() {
        assertThat(rules.refusal(new ProtectionFacts(false, Optional.of(false), true)))
                .contains(ProtectionRules.Refusal.SYSTEM_EXECUTABLE);
    }

    /** Criticality that could not be read does not protect by itself; the system's own checks still apply. */
    @Test
    void anUndeterminedCriticalityDoesNotRefuse() {
        assertThat(rules.refusal(ProtectionFacts.NONE)).isEmpty();
        assertThat(rules.refusal(new ProtectionFacts(false, Optional.of(false), false)))
                .isEmpty();
    }
}
