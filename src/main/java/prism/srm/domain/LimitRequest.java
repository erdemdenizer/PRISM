package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A request about managing one process.
 *
 * @param identity the process as it was selected: PID and start time
 * @param name the name it was listed under, for the messages
 * @param percent the CPU limit to set; present exactly for {@link LimitAction#SET_CPU_LIMIT}. Its
 *     range is checked where the request is carried out, so that a wrong number gets an answer
 * @param bytes the memory limit to set; present exactly for {@link LimitAction#SET_MEMORY_LIMIT},
 *     checked the same way
 * @param oom the OOM settings to set; present exactly for {@link LimitAction#SET_OOM}
 */
public record LimitRequest(
        LimitAction action,
        ProcessIdentity identity,
        String name,
        OptionalInt percent,
        OptionalLong bytes,
        Optional<OomManagement> oom) {

    public LimitRequest {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(oom, "oom");
        if (percent.isPresent() != (action == LimitAction.SET_CPU_LIMIT)) {
            throw new IllegalArgumentException("A percentage is given exactly for setting a CPU limit: " + action);
        }
        if (bytes.isPresent() != (action == LimitAction.SET_MEMORY_LIMIT)) {
            throw new IllegalArgumentException(
                    "A number of bytes is given exactly for setting a memory limit: " + action);
        }
        if (oom.isPresent() != (action == LimitAction.SET_OOM)) {
            throw new IllegalArgumentException("OOM settings are given exactly for setting them: " + action);
        }
    }

    public LimitRequest(
            LimitAction action, ProcessIdentity identity, String name, OptionalInt percent, OptionalLong bytes) {
        this(action, identity, name, percent, bytes, Optional.empty());
    }

    public LimitRequest(LimitAction action, ProcessIdentity identity, String name, OptionalInt percent) {
        this(action, identity, name, percent, OptionalLong.empty());
    }

    public static LimitRequest of(LimitAction action, ProcessIdentity identity, String name) {
        return new LimitRequest(action, identity, name, OptionalInt.empty(), OptionalLong.empty());
    }

    public static LimitRequest cpuLimit(ProcessIdentity identity, String name, int percent) {
        return new LimitRequest(
                LimitAction.SET_CPU_LIMIT, identity, name, OptionalInt.of(percent), OptionalLong.empty());
    }

    public static LimitRequest memoryLimit(ProcessIdentity identity, String name, long bytes) {
        return new LimitRequest(
                LimitAction.SET_MEMORY_LIMIT, identity, name, OptionalInt.empty(), OptionalLong.of(bytes));
    }

    public static LimitRequest oom(ProcessIdentity identity, String name, OomManagement oom) {
        return new LimitRequest(
                LimitAction.SET_OOM, identity, name, OptionalInt.empty(), OptionalLong.empty(), Optional.of(oom));
    }
}
