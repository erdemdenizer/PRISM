package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.ColorScheme;
import org.junit.jupiter.api.Test;
import prism.srm.app.TitleBarTint.Look;
import prism.srm.domain.ThemeMode;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome.Result;

/** UI lab v2: which colors the title bar gets and how often a window is looked for. No window is shown. */
class TitleBarTintTest {

    /** A high-contrast theme is read from the JavaFX platform preference; absent means off. */
    @Test
    void highContrastIsOnlyOnWhenThePlatformSaysTrue() {
        Map<String, Object> preferences = new HashMap<>();
        assertThat(TitleBarTint.highContrast(preferences)).isFalse();
        preferences.put("Windows.SPI.HighContrast", Boolean.FALSE);
        assertThat(TitleBarTint.highContrast(preferences)).isFalse();
        preferences.put("Windows.SPI.HighContrast", Boolean.TRUE);
        assertThat(TitleBarTint.highContrast(preferences)).isTrue();
    }

    @Test
    void aForcedModeIgnoresTheSystem() {
        assertThat(TitleBarTint.colors(ThemeMode.DARK, ColorScheme.LIGHT, true)).isEqualTo(TitleBarTint.DARK);
        assertThat(TitleBarTint.colors(ThemeMode.LIGHT, ColorScheme.DARK, true)).isEqualTo(TitleBarTint.LIGHT);
    }

    @Test
    void theSystemModeFollowsTheSystem() {
        assertThat(TitleBarTint.colors(ThemeMode.SYSTEM, ColorScheme.DARK, true))
                .isEqualTo(TitleBarTint.DARK);
        assertThat(TitleBarTint.colors(ThemeMode.SYSTEM, ColorScheme.LIGHT, true))
                .isEqualTo(TitleBarTint.LIGHT);
    }

    /** An inactive window keeps its theme: only the caption text changes, in every mode. */
    @Test
    void anInactiveWindowGetsTheInactiveColorsOfItsTheme() {
        assertThat(TitleBarTint.colors(ThemeMode.DARK, ColorScheme.LIGHT, false))
                .isEqualTo(TitleBarTint.DARK_INACTIVE);
        assertThat(TitleBarTint.colors(ThemeMode.LIGHT, ColorScheme.DARK, false))
                .isEqualTo(TitleBarTint.LIGHT_INACTIVE);
        assertThat(TitleBarTint.colors(ThemeMode.SYSTEM, ColorScheme.DARK, false))
                .isEqualTo(TitleBarTint.DARK_INACTIVE);
        assertThat(TitleBarTint.colors(ThemeMode.SYSTEM, ColorScheme.LIGHT, false))
                .isEqualTo(TitleBarTint.LIGHT_INACTIVE);
    }

    /**
     * Inactive differs from active in the text alone: the caption stays the chrome (so the join with the
     * rail and page header holds in both states), the border the hairline, the buttons those of the theme.
     */
    @Test
    void activeAndInactiveDifferInTheCaptionTextOnly() {
        for (ChromeColors[] pair : pairs()) {
            ChromeColors active = pair[0];
            ChromeColors inactive = pair[1];
            assertThat(inactive.dark()).isEqualTo(active.dark());
            assertThat(inactive.caption()).isEqualTo(active.caption());
            assertThat(inactive.border()).isEqualTo(active.border());
            assertThat(inactive.text()).isNotEqualTo(active.text());
        }
    }

    /**
     * The inactive text stays readable, at least 4.5:1 on the caption, and is clearly dimmer than the active
     * text: at most half its contrast.
     */
    @Test
    void theInactiveTextIsReadableAndClearlyDimmer() {
        for (ChromeColors[] pair : pairs()) {
            double active = contrast(pair[0].text(), pair[0].caption());
            double inactive = contrast(pair[1].text(), pair[1].caption());
            assertThat(active).as("active %s", pair[0]).isGreaterThanOrEqualTo(7.0);
            assertThat(inactive).as("inactive %s", pair[1]).isGreaterThanOrEqualTo(4.5);
            assertThat(inactive).as("inactive %s", pair[1]).isLessThanOrEqualTo(active / 2);
        }
    }

    /** A high-contrast theme gives the system's own colors back, active or not, in every mode. */
    @Test
    void underHighContrastTheLookIsTheSystemsOwn() {
        for (ThemeMode mode : ThemeMode.values()) {
            for (boolean active : new boolean[] {true, false}) {
                assertThat(TitleBarTint.look(mode, ColorScheme.DARK, true, active))
                        .isEqualTo(Look.SYSTEM);
            }
        }
        assertThat(TitleBarTint.look(ThemeMode.DARK, ColorScheme.LIGHT, false, false))
                .isEqualTo(new Look(TitleBarTint.DARK_INACTIVE));
    }

    /**
     * A frame gets an attempt only when it does not show the wanted look, no lookup for it runs and its
     * last lookup was not lost: a focus change that changes nothing makes no native call.
     */
    @Test
    void aFrameIsTintedOnlyWhenItsLookChangesAndNoLookupRunsOrWasLost() {
        Look active = new Look(TitleBarTint.DARK);
        Look inactive = new Look(TitleBarTint.DARK_INACTIVE);

        assertThat(TitleBarTint.due(null, active, false, false))
                .as("never tinted")
                .isTrue();
        assertThat(TitleBarTint.due(active, active, false, false))
                .as("shows it already")
                .isFalse();
        assertThat(TitleBarTint.due(active, inactive, false, false))
                .as("focus lost")
                .isTrue();
        assertThat(TitleBarTint.due(inactive, Look.SYSTEM, false, false))
                .as("high contrast on")
                .isTrue();
        assertThat(TitleBarTint.due(Look.SYSTEM, Look.SYSTEM, false, false))
                .as("still high contrast")
                .isFalse();
        assertThat(TitleBarTint.due(active, inactive, true, false))
                .as("a lookup runs")
                .isFalse();
        assertThat(TitleBarTint.due(null, inactive, false, true)).as("lost").isFalse();
    }

    /** The lookup is bounded: twenty attempts in all, and only a window not found yet is looked for again. */
    @Test
    void onlyAWindowNotFoundYetIsLookedForAgainAndAtMostTwentyTimes() {
        assertThat(TitleBarTint.retries(Result.NOT_FOUND, 1)).isTrue();
        assertThat(TitleBarTint.retries(Result.NOT_FOUND, 19)).isTrue();
        assertThat(TitleBarTint.retries(Result.NOT_FOUND, 20)).isFalse();
        for (Result settled : new Result[] {Result.APPLIED, Result.AMBIGUOUS, Result.UNAVAILABLE, Result.FAILED}) {
            assertThat(TitleBarTint.retries(settled, 1)).as("%s", settled).isFalse();
        }
    }

    /** A high-contrast theme of the system wins: only its own colors are given back, nothing is applied. */
    @Test
    void underHighContrastOnlyResetIsCalled() {
        FakeWindowChrome chrome = new FakeWindowChrome(call -> Result.APPLIED);

        for (ThemeMode mode : ThemeMode.values()) {
            assertThat(TitleBarTint.tint(chrome, "PRISM", TitleBarTint.look(mode, ColorScheme.DARK, true, true)))
                    .isEqualTo(Result.APPLIED);
        }

        assertThat(chrome.calls()).containsExactly("reset PRISM", "reset PRISM", "reset PRISM");
    }

    @Test
    void otherwiseApplyGetsTheColorsOfTheModeAndOfTheSystemForSystem() {
        FakeWindowChrome chrome = new FakeWindowChrome(call -> Result.NOT_FOUND);

        Result result =
                TitleBarTint.tint(chrome, "PRISM", TitleBarTint.look(ThemeMode.DARK, ColorScheme.LIGHT, false, true));
        TitleBarTint.tint(chrome, "PRISM", TitleBarTint.look(ThemeMode.LIGHT, ColorScheme.DARK, false, true));
        TitleBarTint.tint(chrome, "PRISM", TitleBarTint.look(ThemeMode.SYSTEM, ColorScheme.DARK, false, true));
        TitleBarTint.tint(chrome, "PRISM", TitleBarTint.look(ThemeMode.SYSTEM, ColorScheme.LIGHT, false, false));

        // The answer of the frame is passed on as it is.
        assertThat(result).isEqualTo(Result.NOT_FOUND);
        assertThat(chrome.calls())
                .containsExactly(
                        "apply PRISM dark 16181d eceef2",
                        "apply PRISM light eef0f3 15181e",
                        "apply PRISM dark 16181d eceef2",
                        "apply PRISM light eef0f3 5c6474");
    }

    /**
     * The frame must be the chrome, text and hairline tokens of the stylesheet, in both themes, and an
     * inactive frame the chrome, subtle text and hairline tokens.
     */
    @Test
    void theColorsAreTheTokensOfTheStylesheet() throws IOException {
        String css;
        try (InputStream in = TitleBarTintTest.class.getResourceAsStream("/prism/srm/ui/app.css")) {
            assertThat(in).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int dark = css.indexOf("@media (prefers-color-scheme: dark)");
        assertThat(dark).isPositive();
        String light = css.substring(0, dark);
        String darkBlock = css.substring(dark);

        assertThat(fromCss(light, TitleBarTint.LIGHT.dark(), "text")).isEqualTo(TitleBarTint.LIGHT);
        assertThat(fromCss(darkBlock, TitleBarTint.DARK.dark(), "text")).isEqualTo(TitleBarTint.DARK);
        assertThat(fromCss(light, false, "text-subtle")).isEqualTo(TitleBarTint.LIGHT_INACTIVE);
        assertThat(fromCss(darkBlock, true, "text-subtle")).isEqualTo(TitleBarTint.DARK_INACTIVE);
    }

    private static ChromeColors[][] pairs() {
        return new ChromeColors[][] {
            {TitleBarTint.DARK, TitleBarTint.DARK_INACTIVE}, {TitleBarTint.LIGHT, TitleBarTint.LIGHT_INACTIVE}
        };
    }

    private static ChromeColors fromCss(String block, boolean dark, String text) {
        return new ChromeColors(dark, token(block, "chrome"), token(block, text), token(block, "hairline"));
    }

    private static int token(String block, String name) {
        Matcher matcher =
                Pattern.compile("-srm-" + name + ":\\s*#([0-9a-fA-F]{6});").matcher(block);
        assertThat(matcher.find()).as("-srm-%s", name).isTrue();
        return Integer.parseInt(matcher.group(1), 16);
    }

    /** The WCAG 2 contrast ratio of two 0xRRGGBB colors. */
    private static double contrast(int one, int other) {
        double a = luminance(one);
        double b = luminance(other);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int value) {
        double c = (value & 0xFF) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
