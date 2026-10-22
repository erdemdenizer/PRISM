package prism.srm.domain;

import java.time.Instant;
import java.util.Objects;


// record keywordd is used for transferring immutable objects
//in our case im using it to get PID
//this module gets pid's of processes.

public record ProcessIdentity(long pid, Instant startTime) {

    public ProcessIdentity {
        Objects.requireNonNull(startTime, "startTime");
        if (pid < 0) {
            throw new IllegalArgumentException("pid must not be negative: " + pid); // to not crash
        }
    }
}
