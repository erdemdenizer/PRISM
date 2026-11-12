package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuantitiesTest {

    @Test
    void parsesMemorySizesInBinaryUnitsWhateverTheCase() {
        assertThat(Quantities.parseMemorySize("256MiB")).isEqualTo(256 * Quantities.MIB);
        assertThat(Quantities.parseMemorySize("1GiB")).isEqualTo(Quantities.GIB);
        assertThat(Quantities.parseMemorySize("4gib")).isEqualTo(4 * Quantities.GIB);
        assertThat(Quantities.parseMemorySize(" 8MIB ")).isEqualTo(8 * Quantities.MIB);
        assertThat(Quantities.parseMemorySize("0")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"256", "256MB", "256 MiB", "-1MiB", "1.5GiB", "abc", "", "MiB", "1KiB", "1TiB"})
    void rejectsMalformedMemorySizes(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseMemorySize(text));
    }

    @Test
    void rejectsMemorySizesThatOverflow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Quantities.parseMemorySize("999999999999999GiB"))
                .withMessageContaining("too large");
        // More digits than a long can hold never reach the multiplication.
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseMemorySize("99999999999999999999MiB"));
    }

    @Test
    void parsesDurationsInSecondsAndMinutes() {
        assertThat(Quantities.parseDuration("300s")).isEqualTo(Duration.ofSeconds(300));
        assertThat(Quantities.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Quantities.parseDuration("30M")).isEqualTo(Duration.ofMinutes(30));
        assertThat(Quantities.parseDuration("0s")).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "5h", "1m30s", "-5s", "1.5m", "", "s", "PT5M"})
    void rejectsMalformedDurations(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseDuration(text));
    }

    @Test
    void parsesPercentagesWithAPeriod() {
        assertThat(Quantities.parsePercent("25")).isEqualTo(25.0);
        assertThat(Quantities.parsePercent("6.25")).isEqualTo(6.25);
        assertThat(Quantities.parsePercent("100")).isEqualTo(100.0);
        assertThat(Quantities.parsePercent("0")).isZero();
        assertThat(Quantities.parsePercent("0.5")).isEqualTo(0.5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1e2", "-1", ".5", "NaN", "Infinity", "25%", "6,25", "", "1000"})
    void rejectsMalformedPercentages(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parsePercent(text));
    }

    @Test
    void parsesCounts() {
        assertThat(Quantities.parseCount("4")).isEqualTo(4);
        assertThat(Quantities.parseCount("0")).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseCount("-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseCount("4.0"));
        assertThatIllegalArgumentException().isThrownBy(() -> Quantities.parseCount("9999999999"));
    }

    @Test
    void formatsBytesInTheUnitThatFits() {
        assertThat(Quantities.formatBytes(0)).isEqualTo("0MiB");
        assertThat(Quantities.formatBytes(256 * Quantities.MIB)).isEqualTo("256MiB");
        assertThat(Quantities.formatBytes(Quantities.MIB + Quantities.MIB / 2)).isEqualTo("1.5MiB");
        assertThat(Quantities.formatBytes(Quantities.GIB)).isEqualTo("1.00GiB");
        assertThat(Quantities.formatBytes(32_057_126_912L)).isEqualTo("29.86GiB");
        assertThat(Quantities.formatSeconds(Duration.ofMinutes(5))).isEqualTo("300s");
        assertThat(Quantities.formatPercent(6.25)).isEqualTo("6.3");
    }

    /** The command line is read and written the same way on a Turkish machine. */
    @Test
    void parsesAndFormatsIndependentlyOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(Quantities.parsePercent("6.25")).isEqualTo(6.25);
            assertThat(Quantities.parseMemorySize("1GIB")).isEqualTo(Quantities.GIB);
            assertThat(Quantities.parseDuration("5M")).isEqualTo(Duration.ofMinutes(5));
            assertThat(Quantities.formatPercent(6.25)).isEqualTo("6.3");
            assertThat(Quantities.formatBytes(Quantities.GIB)).isEqualTo("1.00GiB");
        } finally {
            Locale.setDefault(original);
        }
    }
}
