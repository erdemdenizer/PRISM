package prism.srm.ui.snapshot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javax.imageio.ImageIO;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Renders a dialog the way its window shows it: the pane in the dialog's own scene, at the size the
 * window takes when it is sized to its scene (the preferred size of the pane), not in a scene of a
 * size chosen by the test (UI audit LO-17). Writes {@code <name>-<theme>-<width>x<height>.png} to
 * the snapshot directory, the size being the size of the dialog's content in logical pixels.
 *
 * <p>The dialog is never shown, so the window frame and the title bar are not in the image.
 */
public final class DialogSnapshots {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("srm.snapshot.dir", "target/ui-snapshots"));

    private DialogSnapshots() {}

    /** Renders the dialog {@code build} makes; {@code prepare} may set its controls first. */
    public static <T> Path render(String name, Theme theme, Supplier<Dialog<T>> build, Consumer<Dialog<T>> prepare) {
        WritableImage image = SnapshotHarness.inspect(theme, 100, 100, Pane::new, root -> {
            Dialog<T> dialog = build.get();
            prepare.accept(dialog);
            DialogPane pane = dialog.getDialogPane();
            pane.getScene().getPreferences().setColorScheme(SnapshotHarness.colorScheme(theme));
            pane.getScene().getPreferences().setReducedMotion(true);
            for (int pass = 0; pass < 3; pass++) {
                pane.applyCss();
                double width = Math.ceil(pane.prefWidth(-1));
                pane.resize(width, Math.ceil(pane.prefHeight(width)));
                pane.layout();
            }
            return pane.snapshot(null, null);
        });
        return write(name, theme, image);
    }

    /** Renders the dialog {@code build} makes as it opens. */
    public static <T> Path render(String name, Theme theme, Supplier<Dialog<T>> build) {
        return render(name, theme, build, dialog -> {});
    }

    private static Path write(String name, Theme theme, WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        Path file = OUTPUT_DIR.resolve(
                "%s-%s-%dx%d.png".formatted(name, theme.name().toLowerCase(Locale.ROOT), width, height));
        try {
            Files.createDirectories(file.getParent());
            if (!ImageIO.write(buffered, "png", file.toFile())) {
                throw new IOException("No PNG writer available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write snapshot " + file, e);
        }
        return file;
    }
}
