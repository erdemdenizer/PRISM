package prism.srm.domain;

/**
 * Why OOM Guard holds an intervention on a process (ADR-0015 §4). One process may be held for both at
 * once; the suspension or the lowered priority ends only when no claim is left.
 */
public enum Claim {
    /** Memory pressure on the whole system: CRITICAL or EMERGENCY. */
    PRESSURE,
    /** The process's own memory limit, watched as resident memory (LIM-9). */
    RESIDENT_POLICY
}
