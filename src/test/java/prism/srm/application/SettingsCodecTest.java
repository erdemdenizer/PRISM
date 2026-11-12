package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.application.SettingsCodec.Decoded;
import prism.srm.application.SettingsCodec.Status;
import prism.srm.domain.AppSettings;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.ThemeMode;

/** The form of the settings file (ADR-0014): what is written reads back, and what is not right is not taken. */
class SettingsCodecTest {

    private final Locale before = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    /** A file as Prism writes one: the lines given, with the end line Prism would write after them. */
    static byte[] file(String... settingLines) {
        StringBuilder body = new StringBuilder("prism-settings 1\n");
        for (String line : settingLines) {
            body.append(line).append('\n');
        }
        CRC32 crc = new CRC32();
        crc.update(body.toString().getBytes(StandardCharsets.UTF_8));
        body.append("end ")
                .append(settingLines.length)
                .append(' ')
                .append(Long.toHexString(crc.getValue()))
                .append('\n');
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void whatIsWrittenReadsBackTheSameUnderATurkishLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        AppSettings settings = new AppSettings(
                new OomSettings(new OomThresholds(20, 10, 3, 1L << 30), new OomSwitches(false, true, true, true)),
                ThemeMode.DARK);

        Decoded decoded = SettingsCodec.decode(SettingsCodec.encode(settings));

        assertThat(decoded.status()).isEqualTo(Status.READ);
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.settings()).isEqualTo(settings);
        assertThat(new String(SettingsCodec.encode(AppSettings.DEFAULTS), StandardCharsets.UTF_8))
                .startsWith("prism-settings 1\noom.monitoring=true\noom.warning.percent=15\n")
                .contains("oom.auto.terminate=false\nappearance=SYSTEM\nend 9 ");
    }

    @Test
    void theDefaultsReadBackAsTheDefaults() {
        assertThat(SettingsCodec.decode(SettingsCodec.encode(AppSettings.DEFAULTS))
                        .settings())
                .isEqualTo(AppSettings.DEFAULTS);
    }

    @Test
    void aChangedByteIsCorruptNotPartial() {
        byte[] bytes = SettingsCodec.encode(new AppSettings(
                OomSettings.DEFAULTS.withSwitches(OomSwitches.DEFAULTS.withAutoSuspend(true)), ThemeMode.LIGHT));
        String text =
                new String(bytes, StandardCharsets.UTF_8).replace("oom.auto.suspend=true", "oom.auto.suspend=fals");

        Decoded decoded = SettingsCodec.decode(text.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.status()).isEqualTo(Status.CORRUPT);
        assertThat(decoded.settings()).isEqualTo(AppSettings.DEFAULTS);
    }

    @Test
    void aTruncatedFileAndAWrongHeaderAreCorrupt() {
        byte[] whole = SettingsCodec.encode(AppSettings.DEFAULTS);
        String text = new String(whole, StandardCharsets.UTF_8);
        assertThat(SettingsCodec.decode(text.substring(0, text.length() / 2).getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo(Status.CORRUPT);
        assertThat(SettingsCodec.decode("hello\n".getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo(Status.CORRUPT);
        assertThat(SettingsCodec.decode(new byte[0]).status()).isEqualTo(Status.CORRUPT);
        assertThat(SettingsCodec.decode("prism-settings x\n".getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo(Status.CORRUPT);
        assertThat(SettingsCodec.decode(file("no equals sign")).status()).isEqualTo(Status.CORRUPT);
    }

    @Test
    void aLaterVersionIsNewerAndGivesTheDefaults() {
        Decoded decoded =
                SettingsCodec.decode("prism-settings 2\nsomething=else\nend 1 0\n".getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.status()).isEqualTo(Status.NEWER);
        assertThat(decoded.settings()).isEqualTo(AppSettings.DEFAULTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"True", "TRUE", "1", "yes", "on", "true ", ""})
    void terminationIsOnlyForTheLiteralTrue(String value) {
        Decoded decoded = SettingsCodec.decode(file("oom.auto.terminate=" + value));

        assertThat(decoded.status()).isEqualTo(Status.READ);
        assertThat(decoded.settings().oom().switches().termination()).isFalse();
    }

    @Test
    void terminationIsOnWithTheLiteralTrueAndOffWhenTheKeyIsMissing() {
        assertThat(SettingsCodec.decode(file("oom.auto.terminate=true"))
                        .settings()
                        .oom()
                        .switches()
                        .termination())
                .isTrue();
        assertThat(SettingsCodec.decode(file("oom.monitoring=true"))
                        .settings()
                        .oom()
                        .switches()
                        .termination())
                .isFalse();
    }

    @Test
    void invalidThresholdsFallBackToTheDefaultsTogetherWhileTheSwitchesAreKept() {
        Decoded decoded = SettingsCodec.decode(file(
                "oom.warning.percent=5",
                "oom.critical.percent=8",
                "oom.emergency.percent=15",
                "oom.auto.suspend=true"));

        assertThat(decoded.settings().oom().thresholds()).isEqualTo(OomThresholds.DEFAULTS);
        assertThat(decoded.settings().oom().switches().autoSuspend()).isTrue();
        assertThat(decoded.problems()).anySatisfy(problem -> assertThat(problem).contains("all three defaults"));

        Decoded notNumbers = SettingsCodec.decode(file("oom.warning.percent=fifteen"));
        assertThat(notNumbers.settings().oom().thresholds()).isEqualTo(OomThresholds.DEFAULTS);
        assertThat(notNumbers.problems()).isNotEmpty();
    }

    @Test
    void oneValidThresholdChangedAloneIsTakenWithTheOthersAtTheirDefaults() {
        Decoded decoded = SettingsCodec.decode(file("oom.warning.percent=25", "oom.emergency.floor.bytes=0"));

        assertThat(decoded.settings().oom().thresholds()).isEqualTo(new OomThresholds(25, 8, 5, 0));
        assertThat(decoded.problems()).isEmpty();
    }

    @Test
    void aNegativeOrUnreadableFloorTakesTheDefaultFloor() {
        assertThat(SettingsCodec.decode(file("oom.emergency.floor.bytes=-1"))
                        .settings()
                        .oom()
                        .thresholds()
                        .emergencyFloorBytes())
                .isEqualTo(OomThresholds.DEFAULTS.emergencyFloorBytes());
        assertThat(SettingsCodec.decode(file("oom.emergency.floor.bytes=lots")).problems())
                .isNotEmpty();
    }

    @Test
    void anUnknownKeyIsIgnoredAndSaidAndAMissingKeyTakesItsDefault() {
        Decoded decoded = SettingsCodec.decode(file("oom.monitoring=false", "colour=red"));

        assertThat(decoded.status()).isEqualTo(Status.READ);
        assertThat(decoded.settings().oom().switches()).isEqualTo(OomSwitches.DEFAULTS.withMonitoring(false));
        assertThat(decoded.problems()).containsExactly("the unknown setting colour is ignored");
    }

    @Test
    void aSwitchThatIsNotABooleanKeepsItsDefault() {
        Decoded decoded = SettingsCodec.decode(file("oom.monitoring=maybe", "oom.auto.priority=1"));

        assertThat(decoded.settings().oom().switches()).isEqualTo(OomSwitches.DEFAULTS);
        assertThat(decoded.problems()).hasSize(2);
    }

    @Test
    void anUnknownAppearanceIsTheSystemAppearanceAndCaseDoesNotMatterUnderTurkish() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertThat(SettingsCodec.decode(file("appearance=sepia")).settings().appearance())
                .isEqualTo(ThemeMode.SYSTEM);
        assertThat(SettingsCodec.decode(file("appearance=light")).settings().appearance())
                .isEqualTo(ThemeMode.LIGHT);
    }
}
