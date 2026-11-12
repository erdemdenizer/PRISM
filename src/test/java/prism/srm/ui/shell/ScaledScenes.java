package prism.srm.ui.shell;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * A scene laid out as a window of a display scale lays it out (fix round H): in a stage whose render
 * scale is set, so that JavaFX snaps the padding, the spacing and the sizes of every region to the
 * device pixels of that scale (a 10 px padding is 10.4 px at 125 %), and at the fractional scene size a
 * real window of that scale has (owner decision K8: a 720 x 480 window at 125 % has a scene of 705.6 x
 * 442.4). The stage is never shown: its render scale is what the layout reads.
 */
final class ScaledScenes {

    private static final long TIMEOUT_SECONDS = 30;

    /** The scene of a window at a display scale, with the outer size of the window. */
    record Size(double width, double height, String window) {

        /** The size rounded to whole pixels, for a snapshot file, which the harness renders at 100 %. */
        int[] whole() {
            return new int[] {(int) Math.round(width), (int) Math.round(height)};
        }
    }

    /**
     * The scenes of real windows at 125 % on Windows 11, measured with GetClientRect (fix brief K8): 14.4
     * px narrower and 37.6 px lower than the window.
     */
    static final List<Size> AT_125 = List.of(
            new Size(705.6, 442.4, "720x480"),
            new Size(745.6, 522.4, "760x560"),
            new Size(785.6, 562.4, "800x600"),
            new Size(885.6, 1162.4, "900x1200"),
            new Size(1085.6, 662.4, "1100x700"),
            new Size(1162.4, 746.4, "1177x784"),
            new Size(1352, 730.4, "1366x768"),
            new Size(1425.6, 862.4, "1440x900"),
            new Size(1585.6, 462.4, "1600x500"),
            new Size(1905.6, 1042.4, "1920x1080"));

    /** The scale of the windows of {@link #AT_125}. */
    static final double SCALE_125 = 1.25;

    private ScaledScenes() {}

    /**
     * Builds the node tree in a scene of this size, in a stage of this render scale, lays it out with
     * the stylesheets of the application and returns what {@code inspection} reads from it.
     */
    static <T> T inspect(
            Theme theme,
            double width,
            double height,
            double scale,
            Supplier<Parent> content,
            Function<Parent, T> inspection) {
        // Starts the toolkit, as the harness does it.
        SnapshotHarness.inspect(theme, 1, 1, Pane::new, root -> null);
        CompletableFuture<T> inspected = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Parent root = content.get();
                Scene scene = new Scene(root, width, height);
                scene.getPreferences().setColorScheme(SnapshotHarness.colorScheme(theme));
                scene.getPreferences().setReducedMotion(true);
                scene.getStylesheets().add(Stylesheets.app());
                Stage stage = new Stage();
                stage.setRenderScaleX(scale);
                stage.setRenderScaleY(scale);
                stage.setScene(scene);
                for (int pass = 0; pass < 4; pass++) {
                    root.applyCss();
                    root.layout();
                }
                inspected.complete(inspection.apply(root));
            } catch (RuntimeException | Error e) {
                inspected.completeExceptionally(e);
            }
        });
        try {
            return inspected.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while laying out a scaled scene", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Laying out a scaled scene failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Laying out a scaled scene timed out", e);
        }
    }
}
