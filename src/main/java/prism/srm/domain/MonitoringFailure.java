package prism.srm.domain;

/**
 * A part of monitoring that is not running although the application was started to run it. None
 * of them heals during a session: a monitor that has ended is not started again.
 */
public enum MonitoringFailure {
    /** Monitoring could not start at all. */
    STARTUP,
    /** System monitoring ended on its own while the application ran. */
    SYSTEM_MONITORING_STOPPED,
    /** Process monitoring ended on its own while the application ran. */
    PROCESS_MONITORING_STOPPED
}
