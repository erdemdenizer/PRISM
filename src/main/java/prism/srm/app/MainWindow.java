package prism.srm.app;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import prism.srm.ui.Stylesheets;

/**
 * The application window: its title, the size it opens with and the smallest size it can be given
 * (docs/UI_GUIDE.md, Window and layout behavior). All sizes are JavaFX units, which are pixels on
 * a monitor at 100 % and more pixels on a scaled one.
 */
final class MainWindow {

    static final String TITLE = "Prism System Resource Manager";

    /** The size of the content the window opens with. */
    static final double DEFAULT_WIDTH = 1100;

    static final double DEFAULT_HEIGHT = 700;

    /** The smallest size of the window, its frame included. Every view is usable at this size. */
    static final double MIN_WIDTH = 720;

    static final double MIN_HEIGHT = 480;

    private MainWindow() {}

    /**
     * Gives the stage its title, its content at the default size and its smallest size, which it
     * keeps on every monitor. The stage is not shown. Call on the JavaFX Application Thread.
     */
    static void prepare(Stage stage, Parent content) {
        Scene scene = new Scene(content, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Stylesheets.app());
        stage.setTitle(TITLE);
        new MinimumWindowSize(stage.minWidthProperty(), stage.minHeightProperty(), MIN_WIDTH, MIN_HEIGHT)
                .keep(stage.outputScaleXProperty(), stage.outputScaleYProperty());
        stage.setScene(scene);
    }
}
