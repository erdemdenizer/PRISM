package prism.srm.domain;

/** How a mechanism is provided on this system (PRODUCT_SPEC section 2). */
public enum CapabilityLevel {

    /** Enforced by the operating system; the target cannot ignore it. */
    NATIVE_HARD,

    /**
     * Provided by a native mechanism without a strict, atomic, mandatory guarantee over the whole
     * target: a request the target may decline, a preference the scheduler weighs, a mechanism that
     * is not atomic.
     */
    NATIVE_SOFT,

    /** Enforced by Prism itself, approximately, and only while Prism runs. */
    EMULATED,

    /** Not possible here. */
    UNAVAILABLE
}
