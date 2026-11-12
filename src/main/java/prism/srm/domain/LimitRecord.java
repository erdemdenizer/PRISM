package prism.srm.domain;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the native-limit ledger records for one process (milestone 10, ADR-0012): written durably
 * before Prism assigns the process to a job or tightens its limits, so that a later session knows
 * which limits an earlier one may have left on it.
 *
 * @param values the limits Prism set, or means to set (INTENDED), on the process's job
 * @param pendingTo while a change is under way (PENDING_CHANGE), the limits it changes to; the
 *     system then holds, per kind, the value of {@code values} or of {@code pendingTo}
 * @param instance the Prism session that wrote it
 * @param provenance on Linux (milestone 12), the systemd scope the record's limits live in and the evidence that
 *     ties the record to one run of it; empty for Job Objects, and for a Linux record written before the scope was
 *     known. Evidence for a later, separately authorized decision, never a permission by itself: recovery classifies
 *     and changes nothing (ResourceLimiter#recover).
 */
public record LimitRecord(
        ProcessIdentity identity,
        String name,
        EffectiveLimits values,
        Optional<EffectiveLimits> pendingTo,
        Set<Flag> flags,
        LedgerState state,
        String instance,
        Optional<ScopeProvenance> provenance) {

    /** What a record says beyond its state. */
    public enum Flag {
        /** The assignment may or may not have taken effect (apply UNCERTAIN). */
        UNCERTAIN,
        /** A change was made but its result could not be read back. */
        UNVERIFIED,
        /** The process exited while its job still carried limits Prism had not cleared. */
        TARGET_EXITED
    }

    public LimitRecord {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(pendingTo, "pendingTo");
        flags = flags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flags));
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(provenance, "provenance");
        if (pendingTo.isPresent() != (state == LedgerState.PENDING_CHANGE)) {
            throw new IllegalArgumentException("A change under way names its target, and only then: " + state);
        }
        if (values.foreign().isPresent()
                || pendingTo.flatMap(EffectiveLimits::foreign).isPresent()) {
            throw new IllegalArgumentException("A record holds only limits Prism sets");
        }
    }

    /** A record without scope provenance: every Job Object record, as milestone 10 wrote them. */
    public LimitRecord(
            ProcessIdentity identity,
            String name,
            EffectiveLimits values,
            Optional<EffectiveLimits> pendingTo,
            Set<Flag> flags,
            LedgerState state,
            String instance) {
        this(identity, name, values, pendingTo, flags, state, instance, Optional.empty());
    }

    public static LimitRecord intended(ProcessIdentity identity, String name, EffectiveLimits values, String instance) {
        return new LimitRecord(identity, name, values, Optional.empty(), Set.of(), LedgerState.INTENDED, instance);
    }

    public LimitRecord applied(EffectiveLimits observed) {
        return new LimitRecord(
                identity,
                name,
                observed,
                Optional.empty(),
                without(Flag.UNCERTAIN, Flag.UNVERIFIED),
                LedgerState.APPLIED,
                instance,
                provenance);
    }

    public LimitRecord changing(EffectiveLimits to) {
        return new LimitRecord(
                identity, name, values, Optional.of(to), flags, LedgerState.PENDING_CHANGE, instance, provenance);
    }

    /** The limits were cleared; the provenance stays, since the process may stay in its scope. */
    public LimitRecord cleared() {
        return new LimitRecord(
                identity,
                name,
                EffectiveLimits.NONE,
                Optional.empty(),
                Set.of(),
                LedgerState.CLEARED,
                instance,
                provenance);
    }

    public LimitRecord flagged(Flag flag) {
        Set<Flag> more = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        more.add(flag);
        return new LimitRecord(identity, name, values, pendingTo, more, state, instance, provenance);
    }

    public LimitRecord carriedInto(String session) {
        return new LimitRecord(identity, name, values, pendingTo, flags, state, session, provenance);
    }

    /** The same record with the scope evidence a Linux limiter learnt. */
    public LimitRecord withProvenance(ScopeProvenance scope) {
        return new LimitRecord(identity, name, values, pendingTo, flags, state, instance, Optional.of(scope));
    }

    /**
     * Per kind, every value the system may hold for this record (plan revision 4.1): the recorded
     * value, and during a change also the one it changes to. Each kind is set by a call of its own
     * that takes effect as a whole or not at all, so any combination of these per-kind values is
     * possible after a crash, a failed call or a failed read-back, and nothing else is.
     */
    public Map<LimitKind, Set<Object>> admissible() {
        return Map.of(LimitKind.CPU, admissible(LimitKind.CPU), LimitKind.MEMORY, admissible(LimitKind.MEMORY));
    }

    public Set<Object> admissible(LimitKind kind) {
        Set<Object> values = new LinkedHashSet<>();
        values.add(this.values.value(kind));
        pendingTo.ifPresent(to -> values.add(to.value(kind)));
        return Set.copyOf(values);
    }

    /** Whether what the system holds is, kind by kind, a value this record allows. */
    public boolean admits(EffectiveLimits observed) {
        return observed.foreign().isEmpty()
                && admissible(LimitKind.CPU).contains(observed.value(LimitKind.CPU))
                && admissible(LimitKind.MEMORY).contains(observed.value(LimitKind.MEMORY));
    }

    /** The limits in words, kind by kind, with both values while a change is under way. */
    public String describe() {
        if (pendingTo.isEmpty()) {
            return values.describe();
        }
        EffectiveLimits to = pendingTo.get();
        return part(
                        "CPU",
                        values.withMemoryBytes(java.util.OptionalLong.empty()),
                        to.withMemoryBytes(java.util.OptionalLong.empty()))
                + "; "
                + part(
                        "memory",
                        values.withCpuRate(java.util.OptionalInt.empty()),
                        to.withCpuRate(java.util.OptionalInt.empty()));
    }

    private static String part(String kind, EffectiveLimits from, EffectiveLimits to) {
        String a = from.none() ? "no " + kind + " limit" : from.describe();
        String b = to.none() ? "no " + kind + " limit" : to.describe();
        return a.equals(b) ? a : a + " or " + b;
    }

    private Set<Flag> without(Flag... removed) {
        Set<Flag> left = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
        for (Flag flag : removed) {
            left.remove(flag);
        }
        return left;
    }
}
