package prism.srm.platform.linux;

import java.util.Objects;

/**
 * The systemd user manager as the Linux scope limiter uses it (milestone 12, S3B, plan Rev 2.2 §2.5, §5.2): one
 * transient scope per process, created by pidfd only, its unit read back, and its runtime limits set one property per
 * call. Nothing here stops, kills, abandons or attaches to a unit, and there is no way to name a process by PID. A seam:
 * tests give a fake systemd.
 *
 * <p>Every state-changing answer says whether the manager refused (a D-Bus error reply: nothing was done) or whether
 * the outcome is unknown (no reply in time, a broken connection): an unknown outcome is never taken for a failure
 * without side effects.
 */
interface ScopeManager {

    /** What became of a request to create a scope. */
    enum StartStatus {
        /** The manager created the unit and queued its start job. */
        STARTED,
        /** A unit of that name exists: nothing was created or moved. */
        UNIT_EXISTS,
        /** The manager does not accept {@code PIDFDs}: nothing was created; there is no fallback to PIDs. */
        PIDFDS_REFUSED,
        /** The manager refused the caller: nothing was created. */
        ACCESS_DENIED,
        /** The manager refused the request for another reason, or it was never sent: nothing was created. */
        REFUSED,
        /** No answer: the unit may or may not exist, and the process may or may not have moved. */
        UNKNOWN
    }

    /** The answer to {@link #startScope}. */
    record Start(StartStatus status, String detail) {

        public Start {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** What became of setting one runtime property. */
    enum SetStatus {
        /** The manager accepted the value; its effect is still to be read back. */
        SET,
        /** The unit is gone: nothing was set. */
        NO_SUCH_UNIT,
        /** The manager refused: nothing was set. */
        REFUSED,
        /** No answer: the value may or may not be set. */
        UNKNOWN
    }

    /** The answer to {@link #setLimit}. */
    record LimitSet(SetStatus status, String detail) {

        public LimitSet {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * Asks the manager to create and start a transient scope in {@code app.slice} that contains exactly the process the
     * descriptor refers to, without limits, with {@code OOMPolicy=continue}, {@code ManagedOOMPreference=omit} and
     * {@code CollectMode=inactive-or-failed}. The descriptor stays the caller's.
     */
    Start startScope(String unit, int pidfd, String description);

    /** The unit's current properties; read-only. */
    SystemdUserBus.Reading<SystemdUserBus.UnitFacts> unit(String unit);

    /** One string property of a scope unit ({@code org.freedesktop.systemd1.Scope}); read-only. */
    SystemdUserBus.Reading<String> scopeProperty(String unit, String property);

    /**
     * Sets one runtime property of a unit Prism created: {@code CPUQuotaPerSecUSec}, {@code MemoryHigh} or {@code
     * MemoryMax}, with {@link #INFINITY} for none. The unit is addressed by its invocation id, so the call reaches that
     * invocation of the unit or none: a unit that later took the same name is never changed ({@link
     * SetStatus#NO_SUCH_UNIT}). The name is checked only for its form: the caller must have proven that the invocation id
     * is the one of Prism's unit of that name (the scope limiter reads both from the live unit before it sends).
     */
    LimitSet setLimit(String unit, String invocationId, String property, long value);

    /** systemd's "infinity" for a {@code t} property: UINT64_MAX, -1 as a signed long. */
    long INFINITY = -1L;
}
