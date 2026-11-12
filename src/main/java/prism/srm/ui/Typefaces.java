package prism.srm.ui;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javafx.scene.text.Font;

/**
 * The typeface bundled with the application, IBM Plex Sans in its Regular, Medium and SemiBold
 * faces, loaded into the JavaFX font registry once at start-up.
 *
 * <p>JavaFX registers a loaded face under the family name its file carries, which for the Medium
 * and the SemiBold face is not the name of the typeface but an abbreviation of its own ("IBM Plex
 * Sans Medm", "IBM Plex Sans SmBld"; measured with JavaFX 25 on Windows). The stylesheets name
 * those families directly, so a face is used only if it was registered under the name the
 * stylesheets expect. If any face is missing, fails to load, or is registered under another
 * name, nothing of the typeface is relied on: {@link #bundled()} is false and the caller adds the
 * fallback stylesheet, which puts every role on the system font. A failure is reported, never
 * hidden behind a face that happens to render.
 *
 * <p>Load once, in {@code App.init()} on the launcher thread, after the toolkit is started and
 * before the first scene is built; the files are read off the JavaFX Application Thread. The
 * result is a value: it holds no static state of its own.
 */
public final class Typefaces {

    /** The family of the Regular face, as the stylesheets name it. */
    public static final String FAMILY = "IBM Plex Sans";

    /** The family JavaFX registers the Medium face under. */
    public static final String MEDIUM_FAMILY = "IBM Plex Sans Medm";

    /** The family JavaFX registers the SemiBold face under. */
    public static final String SEMI_BOLD_FAMILY = "IBM Plex Sans SmBld";

    private static final List<Face> FACES = List.of(
            new Face("fonts/IBMPlexSans-Regular.ttf", FAMILY),
            new Face("fonts/IBMPlexSans-Medium.ttf", MEDIUM_FAMILY),
            new Face("fonts/IBMPlexSans-SemiBold.ttf", SEMI_BOLD_FAMILY));

    /** The size the faces are registered at; any size can be made from a registered face. */
    private static final double REGISTRATION_SIZE = 13;

    private final boolean bundled;
    private final String problem;

    private Typefaces(boolean bundled, String problem) {
        this.bundled = bundled;
        this.problem = problem;
    }

    /**
     * Loads the three faces and checks the family names they were registered under.
     *
     * @return what was loaded; {@link #bundled()} says whether the typeface can be relied on
     */
    public static Typefaces load() {
        return load(Typefaces.class::getResource, url -> Font.loadFont(url.toExternalForm(), REGISTRATION_SIZE));
    }

    /**
     * Loads the faces through the given functions, for tests of what happens when a file is
     * missing, cannot be loaded, or is registered under another name.
     *
     * @param resources finds the file of a face by its resource name; {@code null} if it is missing
     * @param loader registers a file with the toolkit; {@code null} if that fails
     */
    static Typefaces load(Function<String, URL> resources, Function<URL, Font> loader) {
        for (Face face : FACES) {
            URL url = resources.apply(face.resource());
            if (url == null) {
                return new Typefaces(false, "missing font resource " + face.resource());
            }
            Font loaded = loader.apply(url);
            if (loaded == null) {
                return new Typefaces(false, "font could not be loaded: " + face.resource());
            }
            if (!face.family().equals(loaded.getFamily())) {
                return new Typefaces(
                        false,
                        "font " + face.resource() + " registered as \"" + loaded.getFamily() + "\", expected \""
                                + face.family() + "\"");
            }
        }
        return new Typefaces(true, "");
    }

    /** Whether all three faces were loaded under the names the stylesheets use. */
    public boolean bundled() {
        return bundled;
    }

    /** Why the typeface cannot be relied on; empty when it can. */
    public String problem() {
        return problem;
    }

    /** The resources of the faces, relative to this class, for a test that checks the build. */
    public static List<String> resources() {
        return FACES.stream().map(Face::resource).toList();
    }

    private record Face(String resource, String family) {
        Face {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(family, "family");
        }
    }
}
