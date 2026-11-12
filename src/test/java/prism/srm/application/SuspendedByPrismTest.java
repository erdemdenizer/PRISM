package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;

/** PROC-6: the mark comes from Prism's own record, by identity, and changes nothing else. */
class SuspendedByPrismTest {

    private static final ProcessIdentity HELD = new ProcessIdentity(4_242, Instant.EPOCH);

    private static ProcessSnapshot snapshot(ProcessIdentity identity, Optional<ProcessState> state) {
        return new ProcessSnapshot(
                identity,
                "java",
                OptionalLong.of(1),
                state,
                OptionalDouble.of(25),
                OptionalLong.of(100),
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.of("ED"),
                OptionalInt.of(24),
                OptionalInt.of(8),
                OptionalDouble.empty(),
                OptionalDouble.empty());
    }

    @Test
    void aHeldProcessIsMarkedAndKeepsEveryOtherValue() {
        ProcessSnapshot held = snapshot(HELD, Optional.empty());
        ProcessSnapshot other = snapshot(new ProcessIdentity(5_555, Instant.EPOCH), Optional.empty());

        List<ProcessSnapshot> marked = SuspendedByPrism.overlay(List.of(held, other), Set.of(HELD));

        assertThat(marked.get(0).state()).contains(ProcessState.SUSPENDED_BY_PRISM);
        assertThat(marked.get(0))
                .usingRecursiveComparison()
                .ignoringFields("state")
                .isEqualTo(held);
        assertThat(marked.get(1)).isSameAs(other);
    }

    /** PROC-3: the process that took over a held process's PID is another identity and is not marked. */
    @Test
    void aProcessThatReusedAHeldPidIsNotMarked() {
        ProcessSnapshot successor =
                snapshot(new ProcessIdentity(4_242, Instant.EPOCH.plusSeconds(60)), Optional.empty());

        assertThat(SuspendedByPrism.overlay(List.of(successor), Set.of(HELD))
                        .get(0)
                        .state())
                .isEmpty();
    }

    @Test
    void withNothingHeldTheListingIsHandedOnAsItIs() {
        List<ProcessSnapshot> listing = List.of(snapshot(HELD, Optional.empty()));

        assertThat(SuspendedByPrism.overlay(listing, Set.of())).isSameAs(listing);
    }
}
