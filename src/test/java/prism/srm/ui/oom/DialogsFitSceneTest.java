package prism.srm.ui.oom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.FallbackOffer;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomThresholds;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.control.AlreadyRunningDialog;
import prism.srm.ui.control.CpuLimitDialog;
import prism.srm.ui.control.FallbackOfferDialog;
import prism.srm.ui.control.MemoryLimitDialog;
import prism.srm.ui.control.ResidentOfferDialog;
import prism.srm.ui.snapshot.DialogFit;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Every dialog of OOM Guard and of the limits, shown in its window with long names and in the state
 * that adds a line, keeps its buttons inside the window with their words whole (UI audit LO-17), at
 * the render scales of 100 % and 125 %. The confirmations of End task and Force kill are checked in
 * the same way by ConfirmationFitSceneTest.
 */
@Tag("ui-snapshot")
class DialogsFitSceneTest {

    private static final String LONG = "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut-somewhere.exe";

    private static final Map<EmergencyAction, String> NOTES = Map.of(
            EmergencyAction.NOTIFY,
            "A warning and a log entry; nothing is done to the process.",
            EmergencyAction.LOWER_PRIORITY,
            "Lowers its priority to Low at Critical and in an Emergency; restored at Normal.",
            EmergencyAction.SUSPEND,
            "Suspends it, thread by thread, in an Emergency; resumed at Normal. Not in effect: automatic"
                    + " suspend is off.",
            EmergencyAction.TERMINATE,
            "Force-kills it in an Emergency, at most once in this session. Not in effect: emergency"
                    + " termination is off.");

    @ParameterizedTest(name = "render scale {0}")
    @ValueSource(doubles = {1.0, 1.25})
    void everyDialogFitsItsWindow(double scale) {
        Map<String, Supplier<DialogFit.Fit>> dialogs = new LinkedHashMap<>();
        dialogs.put(
                "oom management",
                () -> DialogFit.measure(
                        OomManagementDialog.build(
                                LONG, 9120, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND), NOTES),
                        dialog -> {},
                        scale));
        dialogs.put(
                "oom management, Terminate chosen",
                () -> DialogFit.measure(
                        OomManagementDialog.build(
                                LONG, 9120, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND), NOTES),
                        dialog -> ((RadioButton) dialog.getDialogPane().lookup("#oom-action-TERMINATE"))
                                .setSelected(true),
                        scale));
        dialogs.put(
                "oom management, off",
                () -> DialogFit.measure(
                        OomManagementDialog.build(LONG, 9120, OomManagement.OFF, NOTES), dialog -> {}, scale));
        dialogs.put(
                "thresholds, invalid",
                () -> DialogFit.measure(
                        ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30)),
                        dialog -> ((TextField) dialog.getDialogPane().lookup("#thresholds-critical")).setText("20"),
                        scale));
        dialogs.put("termination switch", () -> DialogFit.measure(TerminationConsent.build(), dialog -> {}, scale));
        dialogs.put(
                "cpu limit, invalid",
                () -> DialogFit.measure(
                        CpuLimitDialog.build(
                                LONG, 9120, OptionalInt.of(30), CpuLimitDialog.means(LimitsFixtures.EMULATED)),
                        dialog -> ((TextField) dialog.getDialogPane().lookup(".cpu-limit-field")).setText("0"),
                        scale));
        dialogs.put(
                "memory limit",
                () -> DialogFit.measure(
                        MemoryLimitDialog.build(LONG, 9120, OptionalLong.of(2L << 30), "1.4 GiB"),
                        dialog -> {},
                        scale));
        dialogs.put(
                "fallback offer",
                () -> DialogFit.measure(
                        FallbackOfferDialog.build(
                                LONG,
                                18812,
                                new FallbackOffer("it is in a Job Object Prism does not hold", 4, 16, true)),
                        dialog -> {},
                        scale));
        dialogs.put(
                "resident offer",
                () -> DialogFit.measure(
                        ResidentOfferDialog.build(LONG, 18812, "it is in a Job Object Prism does not hold"),
                        dialog -> {},
                        scale));
        dialogs.put(
                "already running",
                () -> DialogFit.measure(
                        AlreadyRunningDialog.build(
                                "Prism is already running for this user. Only one Prism may run at a time,"
                                        + " so that two never limit the same process; switch to the window that is open."),
                        dialog -> {},
                        scale));

        Map<String, DialogFit.Fit> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Map<String, DialogFit.Fit> fits = new LinkedHashMap<>();
            dialogs.forEach((name, fit) -> fits.put(name, fit.get()));
            return fits;
        });

        System.out.println(
                "Dialog fit at render scale " + seen.values().iterator().next().renderScale() + ": " + seen);
        assertThat(seen)
                .allSatisfy((name, fit) ->
                        assertThat(fit.fits()).as(name + " " + fit).isTrue());
    }
}
