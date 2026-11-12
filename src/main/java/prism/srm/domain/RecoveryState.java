package prism.srm.domain;

/** What Prism can offer for a process an earlier session may have left suspended. */
public enum RecoveryState {

    /**
     * The process is there with the recorded identity and, where the system lets Prism see it
     * without changing anything, stopped. Prism offers to resume it on the user's request, after
     * checking again and saying that this does not prove the stop is Prism's.
     */
    POSSIBLY_SUSPENDED,

    /**
     * Prism cannot tell which suspensions are its own and changes nothing: on Windows when the
     * journal does not record which threads Prism held, and on any system when the process is
     * under a debugger. The entry is shown until the user dismisses it; resuming the process, if it
     * is stuck, is up to the user, outside Prism.
     */
    MANUAL_INTERVENTION
}
