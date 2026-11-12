package prism.srm.app;

import java.net.URL;
import java.util.List;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import prism.srm.ui.Stylesheets;

/**
 * The application window: its title, the size it opens with and the smallest size it can be given
 * (docs/UI_GUIDE.md, Window and layout behavior). All sizes are JavaFX units, which are pixels on
 * a monitor at 100 % and more pixels on a scaled one.
 */
final class MainWindow {

    static final String TITLE = "PRISM \u2014 Process & Resource Interactive System Manager";

    /** The size of the content the window opens with. */
    static final double DEFAULT_WIDTH = 1100;

    static final double DEFAULT_HEIGHT = 700;

    /** The smallest size of the window, its frame included. Every view is usable at this size. */
    static final double MIN_WIDTH = 720;

    static final double MIN_HEIGHT = 480;

    /** The sizes of the window icon; the system picks the one for the title bar, the taskbar and Alt+Tab. */
    static final List<Integer> ICON_SIZES = List.of(16, 24, 32, 48, 64, 128, 256);

    private MainWindow() {}

    /**
     * Gives the stage its title, its icon, its content at the default size and its smallest size, which it
     * keeps on every monitor. The stage is not shown. Call on the JavaFX Application Thread.
     *
     * @param typefaceBundled whether the bundled typeface was loaded ({@code Typefaces}); if not,
     *     the scene gets the stylesheet that puts every type role on the system font
     */
    static void prepare(Stage stage, Parent content, boolean typefaceBundled) {
        Scene scene = new Scene(content, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Stylesheets.app());
        if (!typefaceBundled) {
            scene.getStylesheets().add(Stylesheets.fontsFallback());
        }
        stage.setTitle(TITLE);
        for (int size : ICON_SIZES) {
            URL icon = MainWindow.class.getResource("icon/prism-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        new MinimumWindowSize(stage.minWidthProperty(), stage.minHeightProperty(), MIN_WIDTH, MIN_HEIGHT)
                .keep(stage.outputScaleXProperty(), stage.outputScaleYProperty());
        stage.setScene(scene);
    }
}
