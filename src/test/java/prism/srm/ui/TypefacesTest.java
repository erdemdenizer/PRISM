package prism.srm.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javafx.scene.text.Font;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The bundled typeface: its files are in the build, and once loaded its three faces are
 * registered under the family names the stylesheets use. Nothing here depends on how the faces
 * rasterize, which differs per operating system.
 */
class TypefacesTest {

    @Test
    void theThreeFacesAreBundledWithTheirLicense() {
        assertThat(Typefaces.resources())
                .containsExactly(
                        "fonts/IBMPlexSans-Regular.ttf",
                        "fonts/IBMPlexSans-Medium.ttf",
                        "fonts/IBMPlexSans-SemiBold.ttf");
        for (String resource : Typefaces.resources()) {
            assertThat(Typefaces.class.getResource(resource)).as(resource).isNotNull();
        }
        assertThat(Typefaces.class.getResource("fonts/LICENSE.txt")).isNotNull();
    }

    /** A missing file, a file the toolkit rejects, or a face under another name: nothing is relied on. */
    @Test
    @Tag("ui-snapshot")
    void aMissingOrMisregisteredFaceMeansTheTypefaceIsNotBundledAndSaysWhich() {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 16, 16, javafx.scene.layout.StackPane::new, ignored -> {
                    java.util.function.Function<String, java.net.URL> real = Typefaces.class::getResource;
                    java.util.function.Function<java.net.URL, Font> load =
                            url -> Font.loadFont(url.toExternalForm(), 13);
                    Typefaces missing = Typefaces.load(
                            resource -> resource.contains("SemiBold") ? null : real.apply(resource), load);
                    Typefaces rejected =
                            Typefaces.load(real, url -> url.toString().contains("Medium") ? null : load.apply(url));
                    Typefaces otherName = Typefaces.load(
                            real,
                            url -> url.toString().contains("Regular") ? load.apply(url) : Font.font("System", 13));
                    return List.of(
                            missing.bundled(),
                            missing.problem(),
                            rejected.bundled(),
                            rejected.problem(),
                            otherName.bundled(),
                            otherName.problem());
                });

        assertThat(seen.get(0)).isEqualTo(false);
        assertThat((String) seen.get(1)).contains("missing").contains("SemiBold");
        assertThat(seen.get(2)).isEqualTo(false);
        assertThat((String) seen.get(3)).contains("could not be loaded").contains("Medium");
        assertThat(seen.get(4)).isEqualTo(false);
        assertThat((String) seen.get(5)).contains("registered as").contains(Typefaces.MEDIUM_FAMILY);
    }

    /** Needs the toolkit: a font is registered with it. The harness loads the faces once. */
    @Test
    @Tag("ui-snapshot")
    void onceLoadedTheFacesAreRegisteredUnderTheFamiliesTheStylesheetsName() {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 16, 16, javafx.scene.layout.StackPane::new, ignored -> {
                    Typefaces again = Typefaces.load();
                    return List.of(
                            again.bundled(),
                            again.problem(),
                            Font.getFamilies().contains(Typefaces.FAMILY),
                            Font.getFamilies().contains(Typefaces.MEDIUM_FAMILY),
                            Font.getFamilies().contains(Typefaces.SEMI_BOLD_FAMILY),
                            Font.font(Typefaces.SEMI_BOLD_FAMILY, 13).getFamily());
                });

        assertThat(seen).containsExactly(true, "", true, true, true, Typefaces.SEMI_BOLD_FAMILY);
    }
}
