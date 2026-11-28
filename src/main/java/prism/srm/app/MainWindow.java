package prism.srm.app;

import java.net.URL;
import java.util.ArrayList;
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

    private MainWindow() {}

    /** The sizes of the window icon in the resources (icon/prism-<size>.png); JavaFX picks the closest one. */
    static final List<Integer> ICON_SIZES = List.of(16, 24, 32, 48, 64, 128, 256);

    /**
     * The window icons, the cut prism on PRISM's indigo (packaging/icons/README.md). An icon that cannot be read is
     * left out: a missing icon must never keep the window from opening.
     */
    static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            URL url = MainWindow.class.getResource("icon/prism-" + size + ".png");
            if (url != null) {
                Image image = new Image(url.toExternalForm());
                if (!image.isError()) {
                    icons.add(image);
                }
            }
        }
        return icons;
    }

    /**
     * Gives the stage its title, its icons, its content at the default size and its smallest size, which it
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
        // The application icon: the taskbar, the window switcher and the title bar take it from here.
        stage.getIcons().setAll(windowIcons());
        new MinimumWindowSize(stage.minWidthProperty(), stage.minHeightProperty(), MIN_WIDTH, MIN_HEIGHT)
                .keep(stage.outputScaleXProperty(), stage.outputScaleYProperty());
        stage.setScene(scene);
    }
}
