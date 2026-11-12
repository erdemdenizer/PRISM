package prism.srm.domain;

/** Where a native limit stands in the ledger (milestone 10, ADR-0012). */
public enum LedgerState {
    /**
     * Written before the assignment. It does not prove that the assignment did not happen: a crash
     * after it leaves the same record.
     */
    INTENDED,
    /** A change of the limits is under way; per kind the system holds the old or the new value. */
    PENDING_CHANGE,
    /** The limits were read back as recorded. */
    APPLIED,
    /** The limits were cleared and read back as none; the process may stay in the job. */
    CLEARED
}
