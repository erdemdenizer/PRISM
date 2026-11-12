package prism.srm.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

/**
 * The token mapping of a Windows high-contrast theme, with a fake map of platform preferences. A
 * real high-contrast session was not available to test against.
 */
class HighContrastTest {

    private static final Pattern TOKEN = Pattern.compile("(-srm-[a-z0-9-]+):");

    /** A high-contrast theme like Windows' "Night sky": every color distinct. */
    private static Map<String, Object> nightSky() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(HighContrast.PREFERENCE, Boolean.TRUE);
        preferences.put("Windows.SysColor.COLOR_WINDOW", Color.rgb(0, 0, 0));
        preferences.put("Windows.SysColor.COLOR_WINDOWTEXT", Color.rgb(255, 255, 255));
        preferences.put("Windows.SysColor.COLOR_HIGHLIGHT", Color.rgb(209, 147, 255));
        preferences.put("Windows.SysColor.COLOR_HIGHLIGHTTEXT", Color.rgb(36, 0, 66));
        preferences.put("Windows.SysColor.COLOR_GRAYTEXT", Color.rgb(166, 166, 166));
        preferences.put("Windows.SysColor.COLOR_BTNFACE", Color.rgb(16, 16, 16));
        preferences.put("Windows.SysColor.COLOR_HOTLIGHT", Color.rgb(128, 128, 255));
        return preferences;
    }

    @Test
    void whileAThemeIsOnEverySystemColorIsGivenAsALookedUpColor() {
        assertThat(HighContrast.style(nightSky()))
                .contains("-hc-window: #000000; -hc-windowtext: #ffffff; -hc-highlight: #d193ff;"
                        + " -hc-highlighttext: #240042; -hc-graytext: #a6a6a6; -hc-btnface: #101010;"
                        + " -hc-hotlight: #8080ff;");
    }

    @Test
    void withoutAThemeNothingIsApplied() {
        Map<String, Object> preferences = nightSky();
        preferences.put(HighContrast.PREFERENCE, Boolean.FALSE);
        assertThat(HighContrast.style(preferences)).isEmpty();

        preferences.remove(HighContrast.PREFERENCE);
        assertThat(HighContrast.style(preferences)).isEmpty();
        assertThat(HighContrast.style(Map.of())).isEmpty();
    }

    @Test
    void withoutTheWindowOrItsTextColorNothingIsApplied() {
        Map<String, Object> preferences = nightSky();
        preferences.remove("Windows.SysColor.COLOR_WINDOWTEXT");
        assertThat(HighContrast.style(preferences)).isEmpty();

        preferences = nightSky();
        preferences.put("Windows.SysColor.COLOR_WINDOW", "not a color");
        assertThat(HighContrast.style(preferences)).isEmpty();
    }

    @Test
    void aMissingColorIsDerivedFromTheWindowAndItsText() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(HighContrast.PREFERENCE, Boolean.TRUE);
        preferences.put("Windows.SysColor.COLOR_WINDOW", Color.WHITE);
        preferences.put("Windows.SysColor.COLOR_WINDOWTEXT", Color.BLACK);

        assertThat(HighContrast.style(preferences))
                .contains("-hc-window: #ffffff; -hc-windowtext: #000000; -hc-highlight: #000000;"
                        + " -hc-highlighttext: #ffffff; -hc-graytext: #000000; -hc-btnface: #ffffff;"
                        + " -hc-hotlight: #000000;");
    }

    @Test
    void theColorsAreWrittenTheSameInEveryLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String turkish = HighContrast.style(nightSky()).orElseThrow();
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            String arabic = HighContrast.style(nightSky()).orElseThrow();
            assertThat(turkish).isEqualTo(arabic).contains("#d193ff");
        } finally {
            Locale.setDefault(before);
        }
    }

    /** Every token app.css defines is mapped, so no lab color is left in a high-contrast theme. */
    @Test
    void hcCssMapsEveryTokenOfTheApplicationToASystemColor() throws IOException {
        Set<String> defined = tokens(read(Stylesheets.app()));
        String hc = read(Stylesheets.highContrast());
        Set<String> mapped = tokens(hc);

        assertThat(defined).isNotEmpty();
        assertThat(mapped)
                .containsAll(defined.stream()
                        .filter(token -> !token.equals("-srm-icon"))
                        .toList());
        assertThat(hc).contains(".root.high-contrast");
        for (String line : hc.split("\n")) {
            if (line.trim().startsWith("-srm-")) {
                assertThat(line).as(line).containsAnyOf("-hc-", "transparent");
            }
        }
    }

    private static Set<String> tokens(String css) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(css);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    private static String read(String location) throws IOException {
        try (InputStream in = URI.create(location).toURL().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
