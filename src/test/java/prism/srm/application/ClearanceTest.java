package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.ProcessIdentity;

/**
 * Plan A15.9 M9: below what resident memory the memory policy lets go, for every valid limit. Each
 * row says which earlier rule it tells apart; the numbers are written out, not computed.
 */
class ClearanceTest {

    @ParameterizedTest(name = "limit {0} -> clear below {1} ({2})")
    @CsvSource(delimiter = '|', textBlock = """
            1048576             | 524288              | Rev 2 gave -3145728
            2097152             | 1048576             | Rev 2 gave -2097152
            4194304             | 2097152             | Rev 2 gave 0
            6291456             | 3145728             | Rev 2 gave 2097152, inside the faulty range
            8388607             | 4194304             | Rev 2 gave 4194303, at the cap
            8388608             | 4194304             | the point where both rules agree
            83886080            | 79691776            | 5 % equals 4 MiB
            83886100            | 79691795            | 5 % just above 4 MiB
            268435456           | 255013684           | limit / 100 * 5 would give 255013686
            9223372036854775807 | 8762203435012037017 | limit * 5 / 100 would overflow
            """)
    void clearanceIsReachableForEveryValidLimit(long limit, long clearBelow, String tellsApart) {
        long computed = OomGuard.clearBelow(limit, OomGuard.Tuning.DEFAULTS);

        assertThat(computed).as(tellsApart).isEqualTo(clearBelow);
        assertThat(computed).isGreaterThanOrEqualTo(limit / 2).isLessThan(limit);
    }

    @Test
    void watchingRejectsAnInvalidLimit() {
        ManagedProcess process = new ManagedProcess(new ProcessIdentity(100, Instant.EPOCH), "p", ManagedSettings.NONE);
        for (long limit : new long[] {0, -1, ManagedSettings.LEAST_MEMORY_BYTES - 1}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new OomGuard.Managed(process, OptionalLong.of(limit), 1));
        }
        assertThat(new OomGuard.Managed(process, OptionalLong.of(ManagedSettings.LEAST_MEMORY_BYTES), 1).watchedLimit())
                .hasValue(ManagedSettings.LEAST_MEMORY_BYTES);
    }
}
