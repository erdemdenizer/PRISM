package prism.srm.ui.snapshot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import prism.srm.ui.Stylesheets;

/**
 * Development-only harness that renders a JavaFX node tree to a PNG file so a screen can be
 * inspected without launching the application. Lives in test sources and is never shipped.
 *
 * <p>Limits: needs a display (no headless rendering), renders at scale 1.0, and captures a single
 * scene only, so popups, tooltips, hover states and window decorations are not in the image.
 */
public final class SnapshotHarness {

    private static final Path OUTPUT_DIR = Path.of(System.getProperty("srm.snapshot.dir", "target/ui-snapshots"));
    private static final long TIMEOUT_SECONDS = 30;
    private static final Object TOOLKIT_LOCK = new Object();
    private static boolean toolkitStarted;

    private SnapshotHarness() {}

    /** Visual theme to render with. */
    public enum Theme {
        LIGHT,
        DARK
    }

    /**
     * Renders the node tree produced by {@code content} at the given scene size and writes it to
     * {@code <name>-<theme>-<width>x<height>.png} in the snapshot directory.
     *
     * @param content builds the root node; called on the JavaFX Application Thread
     * @return the path of the written PNG
     */
    public static Path render(String name, Theme theme, int width, int height, Supplier<Parent> content) {
        return render(name, theme, width, height, content, root -> {});
    }

    /**
     * Renders as {@link #render(String, Theme, int, int, Supplier)} does, after {@code afterLayout}
     * has acted on the node tree once it was laid out. For a state that a control can take only
     * when it has its skin and its size, such as a table scrolled to a column.
     *
     * @param afterLayout acts on the root node; called on the JavaFX Application Thread
     */
    public static Path render(
            String name, Theme theme, int width, int height, Supplier<Parent> content, Consumer<Parent> afterLayout) {
        startToolkit();
        CompletableFuture<WritableImage> rendered = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                rendered.complete(snapshot(theme, width, height, content, afterLayout));
            } catch (RuntimeException | Error e) {
                rendered.completeExceptionally(e);
            }
        });

        WritableImage image = await(rendered, name);
        String fileName = "%s-%s-%dx%d.png".formatted(name, theme.name().toLowerCase(Locale.ROOT), width, height);
        return write(image, OUTPUT_DIR.resolve(fileName));
    }

    /**
     * Builds the node tree as {@link #render} does, with CSS applied and laid out at the given
     * scene size, and returns what {@code inspection} reads from it. For checks an image cannot
     * make, such as a color the stylesheet resolved or the number of nodes.
     *
     * @param content builds the root node; called on the JavaFX Application Thread
     * @param inspection reads from the root node; called on the JavaFX Application Thread
     */
    public static <T> T inspect(
            Theme theme, int width, int height, Supplier<Parent> content, Function<Parent, T> inspection) {
        startToolkit();
        CompletableFuture<T> inspected = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                inspected.complete(
                        inspection.apply(scene(theme, width, height, content).getRoot()));
            } catch (RuntimeException | Error e) {
                inspected.completeExceptionally(e);
            }
        });
        return await(inspected, "inspection");
    }

    private static WritableImage snapshot(
            Theme theme, int width, int height, Supplier<Parent> content, Consumer<Parent> afterLayout) {
        Scene scene = scene(theme, width, height, content);
        afterLayout.accept(scene.getRoot());
        layOut(scene.getRoot());
        return scene.snapshot(null);
    }

    private static Scene scene(Theme theme, int width, int height, Supplier<Parent> content) {
        Parent root = content.get();
        if (theme == Theme.DARK) {
            root.getStyleClass().add("theme-dark");
        }
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(Stylesheets.app());
        layOut(root);
        return scene;
    }

    /**
     * Several passes: virtualized controls create their cells during the first layout, and a
     * scroll pane decides about its scroll bars only after its content was laid out once.
     */
    private static void layOut(Parent root) {
        for (int pass = 0; pass < 3; pass++) {
            root.applyCss();
            root.layout();
        }
    }

    private static <T> T await(CompletableFuture<T> rendered, String name) {
        try {
            return rendered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rendering snapshot " + name, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Rendering snapshot " + name + " failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "Rendering snapshot " + name + " timed out after " + TIMEOUT_SECONDS + " s", e);
        }
    }

    private static Path write(WritableImage image, Path file) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);

        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
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

    private static void startToolkit() {
        synchronized (TOOLKIT_LOCK) {
            if (toolkitStarted) {
                return;
            }
            CountDownLatch started = new CountDownLatch(1);
            try {
                Platform.startup(started::countDown);
            } catch (IllegalStateException alreadyRunning) {
                started.countDown();
            }
            try {
                if (!started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("JavaFX toolkit did not start; is a display available?");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting the JavaFX toolkit", e);
            }
            Platform.setImplicitExit(false);
            toolkitStarted = true;
        }
    }
}
