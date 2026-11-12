package prism.srm.ui.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The crossfade of a metric panel, read from a real scene: it starts when the state changes
 * ("Reading…" to the values, the values to "Unavailable") and never when a value changes, and
 * with reduced motion there is none. A started fade applies its first frame at once, which is
 * what is read here; the harness asks for reduced motion, the test switches it on the scene.
 */
@Tag("ui-snapshot")
class MetricPanelMotionSceneTest {

    @Test
    void theReadoutFadesWhenTheStateChangesAndNotWhenAValueChanges() {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                400,
                200,
                () -> {
                    MetricReadout readout = new MetricReadout();
                    MetricPanel panel = MetricPanel.single("CPU", "Utilization, last 60 s", readout, 60);
                    panel.setUserData(readout);
                    return new StackPane(panel);
                },
                root -> {
                    MetricPanel panel =
                            (MetricPanel) ((StackPane) root).getChildren().getFirst();
                    MetricReadout readout = (MetricReadout) panel.getUserData();
                    Node fading = panel.lookup(".metric-readout");
                    List<Object> facts = new ArrayList<>();
                    root.getScene().getPreferences().setReducedMotion(true);
                    readout.showValue(new Quantity("12.4", "%"), "");
                    facts.add(fading.getOpacity());
                    root.getScene().getPreferences().setReducedMotion(false);
                    readout.showValue(new Quantity("13.0", "%"), "");
                    facts.add(fading.getOpacity());
                    readout.showNoValues(MetricState.UNAVAILABLE);
                    facts.add(fading.getOpacity() < 1);
                    fading.setOpacity(1);
                    readout.showValue(new Quantity("14.0", "%"), "");
                    facts.add(fading.getOpacity() < 1);
                    return facts;
                });

        // Reduced: no fade. A value: no fade. A state: fade. A state again: fade.
        assertThat(seen).containsExactly(1.0, 1.0, true, true);
    }
}
