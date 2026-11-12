package prism.srm.domain;

/**
 * The memory pressure states of OOM Guard (OOM-1), from the least to the most severe. Whether
 * OOM Guard monitors at all is not a state: it is said beside the state ({@link OomView#monitoring()}).
 */
public enum OomState {
    NORMAL,
    WARNING,
    CRITICAL,
    EMERGENCY;

    /** Whether this state is more severe than the other. */
    public boolean above(OomState other) {
        return compareTo(other) > 0;
    }

    /** The state one level less severe; NORMAL stays NORMAL. */
    public OomState below() {
        return this == NORMAL ? NORMAL : values()[ordinal() - 1];
    }
}
