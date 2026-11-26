package prism.srm.domain;

/** What PRISM can offer for a process an earlier session may have left suspended. */
public enum RecoveryState {

    /**
     * The process is there with the recorded identity and, where the system lets PRISM see it
     * without changing anything, stopped. PRISM offers to resume it on the user's request, after
     * checking again and saying that this does not prove the stop is PRISM's.
     */
    POSSIBLY_SUSPENDED,

    /**
     * PRISM cannot tell which suspensions are its own and changes nothing: on Windows when the
     * journal does not record which threads PRISM held, and on any system when the process is
     * under a debugger. The entry is shown until the user dismisses it; resuming the process, if it
     * is stuck, is up to the user, outside PRISM.
     */
    MANUAL_INTERVENTION
}
