package prism.srm.ui.format;

import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;

/** The words for a capability level and for the mechanism behind it (CAP-3, CAP-5). */
public final class CapabilityWords {

    private CapabilityWords() {}

    /** The name of a level: an emulated mechanism is never called native. */
    public static String level(CapabilityLevel level) {
        return switch (level) {
            case NATIVE_HARD -> "Native hard";
            case NATIVE_SOFT -> "Native soft";
            case EMULATED -> "Emulated";
            case UNAVAILABLE -> "Unavailable";
        };
    }

    /** The name of a mechanism, with the limit of what it does where a reader could assume more. */
    public static String mechanism(ControlMechanism mechanism) {
        return switch (mechanism) {
            case CLOSE_REQUEST -> "A close request to its windows; the process may ignore it";
            case PROCESS_HANDLE_TERMINATE -> "Forced termination through the process handle";
            case PER_THREAD_SUSPENSION -> "Every thread suspended one by one; not atomic";
            case PRIORITY_CLASS -> "The priority class of the process";
            case TERMINATION_SIGNAL -> "The termination signal; the process may handle or ignore it";
            case KILL_SIGNAL -> "The kill signal; the process cannot ignore it";
            case STOP_SIGNAL -> "The stop and continue signals; other programs can continue the process";
            case NICE_VALUE -> "The nice value; without privileges it can only be raised";
            case DUTY_CYCLE -> "A duty cycle Prism runs; approximate, and only while Prism runs";
            case JOB_OBJECT -> "A Job Object, enforced by the system";
            case SYSTEMD_SCOPE -> "A systemd scope, enforced by the system";
            case RESIDENT_POLICY -> "Resident memory watched by Prism; not a cap, and only while Prism runs";
        };
    }
}
