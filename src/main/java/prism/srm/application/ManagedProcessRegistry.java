package prism.srm.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.ProcessIdentity;

/**
 * The processes the user chose to manage in this session (MGD-1, MGD-2), in the order they were
 * chosen, with their settings. A managed process is one that already runs: Prism starts none. It
 * is managed by identity, so a PID that is reused is another process and not managed. Nothing here
 * outlives the session; saving settings comes in a later milestone.
 *
 * <p>Used by the limit service on the limiter thread only; no lock.
 */
final class ManagedProcessRegistry {

    private final Map<ProcessIdentity, ManagedProcess> managed = new LinkedHashMap<>();

    /** Adds the process with no settings; false when it is managed already. */
    boolean add(ProcessIdentity identity, String name) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        return managed.putIfAbsent(identity, new ManagedProcess(identity, name, ManagedSettings.NONE)) == null;
    }

    Optional<ManagedProcess> remove(ProcessIdentity identity) {
        return Optional.ofNullable(managed.remove(identity));
    }

    Optional<ManagedProcess> find(ProcessIdentity identity) {
        return Optional.ofNullable(managed.get(identity));
    }

    /** Changes the settings of a managed process; empty when it is not managed. */
    Optional<ManagedProcess> update(ProcessIdentity identity, ManagedSettings settings) {
        ManagedProcess current = managed.get(identity);
        if (current == null) {
            return Optional.empty();
        }
        ManagedProcess changed = current.with(settings);
        managed.put(identity, changed);
        return Optional.of(changed);
    }

    List<ManagedProcess> all() {
        return List.copyOf(managed.values());
    }

    /** The managed processes a listing does not show: those to check for an exit. */
    List<ProcessIdentity> missingFrom(Set<ProcessIdentity> listing) {
        return managed.keySet().stream()
                .filter(identity -> !listing.contains(identity))
                .toList();
    }

    int size() {
        return managed.size();
    }
}
