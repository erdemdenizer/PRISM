package prism.srm.platform;

import java.util.Optional;
import prism.srm.domain.ProcessIdentity;

/**
 * A suspension Prism holds on one process: what it did, kept so that exactly that can be undone.
 * Opaque above the platform. It holds operating-system resources, released by {@link
 * ProcessController#resume} when that undoes all of it, or by {@link #close()}.
 *
 * <p>What it counts are the units the system stops: threads where they are suspended one by one
 * (Windows), the whole process as one unit where a signal stops it (Linux, macOS).
 */
public interface Suspension extends AutoCloseable {

    ProcessIdentity identity();

    /** How many units (threads, or the one process) the suspension stopped when it was made. */
    int threads();

    /** How many of them Prism still holds stopped; zero once all of it is undone. */
    int outstanding();

    /**
     * What the platform needs to know, after Prism has ended and its handles with it, about which
     * units this suspension holds: written to the suspension journal, and handed to {@link
     * ProcessController#adopt} if the user later asks to resume the process. Opaque above the
     * platform. Describes the units held at the moment of the call, so it is asked for again when
     * they change. Empty where the identity is the unit (Linux, macOS).
     */
    default Optional<String> memento() {
        return Optional.empty();
    }

    /**
     * Releases the resources the suspension holds without undoing it: what is still suspended
     * stays suspended. For a process that has exited, and for a suspension the user asked for and
     * that is left in place when Prism closes. Safe to call more than once.
     */
    @Override
    void close();
}
