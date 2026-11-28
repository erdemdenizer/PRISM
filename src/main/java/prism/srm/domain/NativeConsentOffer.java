package prism.srm.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a native limit needs the user to confirm before PRISM asks anything of the system (LIM-6, ADR-0016 decisions 4
 * and 6): the consents and the exact statements they stand for, in the order they are shown. The statements come from
 * the limiter that needs them, so that what the user confirms is exactly what the limiter's consent means. Nothing was
 * changed while it is offered; the request is carried out only when it is sent again with these consents.
 *
 * @param kinds the consents the request needs, never empty
 * @param statements what the user confirms, never empty, each a sentence of its own
 */
public record NativeConsentOffer(Set<NativeConsentKind> kinds, List<String> statements) {

    public NativeConsentOffer {
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(statements, "statements");
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("A consent offer names at least one consent");
        }
        if (statements.isEmpty() || statements.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("A consent offer states what is consented to");
        }
        kinds = Set.copyOf(EnumSet.copyOf(kinds));
        statements = List.copyOf(statements);
    }

    /** Whether the request moves the process into a scope, the one-way step. */
    public boolean adoption() {
        return kinds.contains(NativeConsentKind.ADOPTION);
    }

    /** Whether the request sets a hard memory limit, under which the kernel may terminate the process. */
    public boolean hardMemory() {
        return kinds.contains(NativeConsentKind.HARD_MEMORY);
    }
}
