package prism.srm.application;

import java.util.Objects;
import java.util.Optional;
import prism.srm.application.OomStateMachine.Transition;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomState;
import prism.srm.domain.VersionedSettings;

/**
 * What the system monitor hands OOM Guard after each memory sample: the state, the reading, what the
 * sample changed, and under which settings it was judged (ADR-0015 §2). Immutable; made on the
 * monitor's thread, read on the limiter's.
 *
 * @param kind what the sample changed: a new action needs a sample that was neither missing (HOLD),
 *     after a gap (GAP) nor with monitoring off (OFF)
 * @param settingsVersion the version of the settings the sample was judged under; an action under
 *     other settings waits for the next sample
 * @param atNanos when the observation was made, on the monitor's monotonic clock; for HOLD and OFF too
 */
public record OomObservation(
        OomState state,
        Optional<OomReading> reading,
        Transition.Kind kind,
        boolean monitoring,
        long epoch,
        long settingsVersion,
        long atNanos) {

    public OomObservation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(kind, "kind");
    }

    /** The one mapping from a transition to an observation, for the runtime and the tests alike. */
    public static OomObservation of(Transition transition, VersionedSettings settings, long nowNanos) {
        return new OomObservation(
                transition.to(),
                transition.reading(),
                transition.kind(),
                settings.settings().switches().monitoring(),
                transition.epoch(),
                settings.version(),
                nowNanos);
    }
}
