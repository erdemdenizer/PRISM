package prism.srm.ui.format;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.OomFixtures.reading;
import static prism.srm.ui.OomFixtures.settings;
import static prism.srm.ui.OomFixtures.view;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomView;
import prism.srm.ui.OomFixtures;

/** The words of OOM Guard's state on the Overview and in the status bar (UI-1, plan O11, the pressure line). */
class OomWordsTest {

    private final OomWords words = new OomWords(new ValueFormatter(Locale.US));

    @Test
    void theStatusBarSaysNothingAtNormalWhileOffOrWhenMonitoringHasEnded() {
        assertThat(words.pressure(view(
                        settings(false, false, false),
                        OomState.NORMAL,
                        Optional.of(reading(50)),
                        List.of(),
                        List.of())))
                .isEmpty();
        OomSettings off = OomSettings.DEFAULTS.withSwitches(
                OomSettings.DEFAULTS.switches().withMonitoring(false));
        assertThat(words.pressure(view(off, OomState.NORMAL, Optional.empty(), List.of(), List.of())))
                .isEmpty();
        assertThat(words.pressure(OomFixtures.view(
                        settings(false, false, false),
                        OomState.EMERGENCY,
                        Optional.of(reading(3)),
                        List.of(),
                        List.of(),
                        Optional.empty(),
                        true,
                        OomSignalStatus.VALIDATED,
                        OomView.Availability.ALL)))
                .isEmpty();
    }

    @Test
    void fromWarningOnItSaysThePressureInWordsAndItsTone() {
        assertThat(words.pressure(view(
                        settings(false, false, false),
                        OomState.WARNING,
                        Optional.of(reading(13.4)),
                        List.of(),
                        List.of())))
                .contains(new OomWords.Line("Memory pressure warning: 13 % available", OomWords.Tone.WARNING));
        assertThat(words.pressure(view(
                        settings(false, false, false),
                        OomState.CRITICAL,
                        Optional.of(reading(7)),
                        List.of(),
                        List.of())))
                .contains(new OomWords.Line("Memory pressure critical: 7 % available", OomWords.Tone.WARNING));
        assertThat(words.pressure(view(
                        settings(false, false, false), OomState.EMERGENCY, Optional.empty(), List.of(), List.of())))
                .contains(new OomWords.Line("Memory pressure emergency, reading unavailable", OomWords.Tone.ERROR));
    }

    @Test
    void theOverviewSaysTheStateAndTheShareWhenItMatters() {
        assertThat(words.overview(view(
                        settings(false, false, false),
                        OomState.NORMAL,
                        Optional.of(reading(50)),
                        List.of(),
                        List.of())))
                .isEqualTo("Normal");
        assertThat(words.overview(view(
                        settings(false, false, false),
                        OomState.WARNING,
                        Optional.of(reading(13)),
                        List.of(),
                        List.of())))
                .isEqualTo("Warning, 13 % available");
        assertThat(words.overview(
                        view(settings(false, false, false), OomState.WARNING, Optional.empty(), List.of(), List.of())))
                .isEqualTo("Warning, reading unavailable");
        OomSettings off = OomSettings.DEFAULTS.withSwitches(
                OomSettings.DEFAULTS.switches().withMonitoring(false));
        assertThat(words.overview(view(off, OomState.NORMAL, Optional.empty(), List.of(), List.of())))
                .isEqualTo("Off");
    }

    @Test
    void theShareIsWrittenInTheNotationOfTheLocale() {
        OomWords german = new OomWords(new ValueFormatter(Locale.GERMANY));
        assertThat(german.available(reading(13.4))).isEqualTo("4,3 GiB, 13 %");
    }
}
