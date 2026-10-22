package prism.srm.ui.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ValueFormatterTest {

    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;
    private static final long GIB = 1024 * MIB;

    private final ValueFormatter english = new ValueFormatter(Locale.US);
    private final ValueFormatter turkish = new ValueFormatter(Locale.forLanguageTag("tr-TR"));

    @Test
    void aPercentageHasOneDecimalInTheNotationOfTheLocale() {
        assertThat(english.percent(12.44)).isEqualTo(new Quantity("12.4", "%"));
        assertThat(turkish.percent(12.44)).isEqualTo(new Quantity("12,4", "%"));
        assertThat(english.percent(0)).isEqualTo(new Quantity("0.0", "%"));
        assertThat(english.percent(100)).isEqualTo(new Quantity("100.0", "%"));
    }

    /** For where room is scarce: no decimal, rounded to the nearest whole percent. */
    @Test
    void aWholePercentageHasNoDecimalAndIsRounded() {
        assertThat(english.wholePercent(12.44)).isEqualTo(new Quantity("12", "%"));
        assertThat(english.wholePercent(12.5)).isEqualTo(new Quantity("13", "%"));
        assertThat(english.wholePercent(0.4)).isEqualTo(new Quantity("0", "%"));
        assertThat(english.wholePercent(99.5)).isEqualTo(new Quantity("100", "%"));
        assertThat(english.wholePercent(100).text()).isEqualTo("100 %");
        assertThat(turkish.wholePercent(12.44)).isEqualTo(new Quantity("12", "%"));
    }

    @Test
    void bytesBelowOneKibibyteAreShownWhole() {
        assertThat(english.bytes(0).text()).isEqualTo("0 B");
        assertThat(english.bytes(512).text()).isEqualTo("512 B");
        assertThat(english.bytes(1023).text()).isEqualTo("1023 B");
    }

    @Test
    void sizesUseBinaryUnitsWithOneDecimal() {
        assertThat(english.bytes(KIB).text()).isEqualTo("1.0 KiB");
        assertThat(english.bytes(1536).text()).isEqualTo("1.5 KiB");
        assertThat(english.bytes(12_025_908_428L).text()).isEqualTo("11.2 GiB");
        assertThat(turkish.bytes(12_025_908_428L).text()).isEqualTo("11,2 GiB");
        assertThat(english.bytes(1024 * GIB).text()).isEqualTo("1.0 TiB");
    }

    /** 1023.96 KiB would round to "1024.0 KiB"; the next unit is chosen instead. */
    @Test
    void theUnitIsChosenAfterRounding() {
        long justBelowOneMebibyte = MIB - 41;

        assertThat(english.bytes(justBelowOneMebibyte).text()).isEqualTo("1.0 MiB");
        assertThat(english.bytes(MIB - 60).text()).isEqualTo("1023.9 KiB");
        assertThat(english.bytesPerSecond(1023.6).text()).isEqualTo("1.0 KiB/s");
        assertThat(english.bytesPerSecond(1023.4).text()).isEqualTo("1023 B/s");
    }

    @Test
    void theLargestSizeStillHasAUnit() {
        assertThat(english.bytes(Long.MAX_VALUE).text()).isEqualTo("8.0 EiB");
    }

    @Test
    void aRateCarriesThePerSecondUnit() {
        assertThat(english.bytesPerSecond(0)).isEqualTo(new Quantity("0", "B/s"));
        assertThat(english.bytesPerSecond(1.5 * MIB)).isEqualTo(new Quantity("1.5", "MiB/s"));
        assertThat(turkish.bytesPerSecond(1.5 * MIB)).isEqualTo(new Quantity("1,5", "MiB/s"));
    }

    @Test
    void aCountIsGroupedAsTheLocaleDoes() {
        assertThat(english.count(392)).isEqualTo("392");
        assertThat(english.count(1_234_567)).isEqualTo("1,234,567");
        assertThat(turkish.count(1_234_567)).isEqualTo("1.234.567");
    }

    @Test
    void uptimeIsAClockWithDaysOnceThereAreAny() {
        assertThat(english.uptime(Duration.ZERO)).isEqualTo("00:00:00");
        assertThat(english.uptime(Duration.ofHours(23).plusMinutes(59).plusSeconds(59)))
                .isEqualTo("23:59:59");
        assertThat(english.uptime(Duration.ofDays(1))).isEqualTo("1 d 00:00:00");
        assertThat(english.uptime(
                        Duration.ofDays(400).plusHours(1).plusMinutes(2).plusSeconds(3)))
                .isEqualTo("400 d 01:02:03");
        assertThat(turkish.uptime(
                        Duration.ofDays(3).plusHours(4).plusMinutes(12).plusSeconds(55)))
                .isEqualTo("3 d 04:12:55");
    }

    @Test
    void aMomentIsDateAndTimeOfDayInTheGivenZoneAndTheSameInEveryLocale() {
        Instant moment = Instant.parse("2026-10-06T21:03:09.987Z");

        assertThat(english.dateTime(moment, ZoneOffset.UTC)).isEqualTo("2026-10-06 21:03:09");
        // Past midnight in Istanbul: the date changes with the zone.
        assertThat(english.dateTime(moment, ZoneId.of("Europe/Istanbul"))).isEqualTo("2026-10-07 00:03:09");
        assertThat(turkish.dateTime(moment, ZoneOffset.UTC)).isEqualTo("2026-10-06 21:03:09");
    }

    @Test
    void aQuantityWithoutAUnitIsItsNumberAlone() {
        assertThat(new Quantity("12", "").text()).isEqualTo("12");
        assertThat(new Quantity("12", "%").text()).isEqualTo("12 %");
    }

    @Test
    void theDefaultLocaleDoesNotChangeTheResult() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(english.percent(12.44).number()).isEqualTo("12.4");
            assertThat(english.bytes(1536).text()).isEqualTo("1.5 KiB");
            assertThat(english.count(1_234_567)).isEqualTo("1,234,567");
            assertThat(english.uptime(Duration.ofDays(1))).isEqualTo("1 d 00:00:00");
        } finally {
            Locale.setDefault(original);
        }
    }
}
