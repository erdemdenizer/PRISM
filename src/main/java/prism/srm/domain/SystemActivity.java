package prism.srm.domain;

import java.time.Duration;
import java.util.Objects;

/** How long the system has been running and how many processes it has. */
public record SystemActivity(Duration uptime, int processCount) {

    public SystemActivity {
        Objects.requireNonNull(uptime, "uptime");
        if (uptime.isNegative() || processCount < 0) {
            throw new IllegalArgumentException("Expected a non-negative uptime and process count: uptime=" + uptime
                    + ", processCount=" + processCount);
        }
    }
}
