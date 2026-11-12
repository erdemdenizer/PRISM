package prism.srm.domain;

/**
 * What a control operation on a process came to (PRODUCT_SPEC section 2). Exactly one of these is
 * the answer to every operation; the user sees a plain message derived from it, and native error
 * codes travel separately, for the log (CTL-2).
 */
public enum OperationResult {

    /** The operation was carried out, in the sense its action defines: a close request posted, a termination started, a suspension held, a priority set and read back. */
    SUCCESS,

    /** The system refused, or Prism refused before asking it because the target is protected. */
    ACCESS_DENIED,

    /** The process no longer exists, or its PID now belongs to another process. */
    PROCESS_NOT_FOUND,

    /** The action is not possible for this target or on this system. */
    UNSUPPORTED,

    /** The request cannot be served as asked: for instance, an action on that process is already in progress. */
    INVALID_ARGUMENT,

    /** A native call failed for another reason; the code is in the log. */
    NATIVE_ERROR
}
