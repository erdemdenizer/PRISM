package prism.srm.ui.shell;

import java.util.Optional;
import java.util.Set;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import prism.srm.domain.MonitoringFailure;
import prism.srm.ui.format.OomWords;

/**
 * The line at the bottom of the window. It says whether monitoring runs, and if a part of it does
 * not, which part; and at its right end, in words and only from WARNING up, the memory pressure OOM
 * Guard sees (plan O11). No indicator stands there for nothing.
 */
final class StatusBar extends HBox {

    /** What monitoring is doing, as far as the user needs to tell the cases apart. */
    enum Monitoring {
        STARTING("Starting monitoring…", false),
        RUNNING("Monitoring", false),
        COULD_NOT_START("Monitoring could not start", true),
        /** The Overview has no live values any more; whether processes are listed is not said. */
        SYSTEM_STOPPED("System monitoring stopped unexpectedly", true),
        /** The Overview is live; only the process listing has ended. */
        DEGRADED("Monitoring degraded: process monitoring stopped unexpectedly. Restart Prism to restore it.", true),
        STOPPED("Monitoring stopped unexpectedly", true);

        private final String text;
        private final boolean failure;

        Monitoring(String text, boolean failure) {
            this.text = text;
            this.failure = failure;
        }

        String text() {
            return text;
        }

        /**
         * Whether system snapshots may be shown. Once monitoring could not start or system
         * monitoring has ended, a snapshot can only be one that was sent before and arrives
         * late; showing it would put values back on a page that says there are none.
         */
        static boolean systemValuesAreLive(Set<MonitoringFailure> failures) {
            return !failures.contains(MonitoringFailure.STARTUP)
                    && !failures.contains(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        }

        /**
         * Whether listings of the processes may be shown. Once monitoring could not start or
         * process monitoring has ended, a listing can only be one that was sent before and
         * arrives late.
         */
        static boolean processValuesAreLive(Set<MonitoringFailure> failures) {
            return !failures.contains(MonitoringFailure.STARTUP)
                    && !failures.contains(MonitoringFailure.PROCESS_MONITORING_STOPPED);
        }

        /**
         * Whether the Processes page has to say that it may never get a listing. Process
         * monitoring starts with a system snapshot, so once system monitoring has ended it
         * cannot start any more; a listing that was already on its way may still arrive. After
         * the first listing this is not a question: the process monitor runs on its own.
         *
         * @param processesSeen whether a listing of the processes has arrived
         */
        static boolean processesMayNeverBeListed(Set<MonitoringFailure> failures, boolean processesSeen) {
            return !processesSeen
                    && processValuesAreLive(failures)
                    && failures.contains(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
        }

        /**
         * @param failures what has failed so far
         * @param snapshotSeen whether a system snapshot has arrived, which is what shows that
         *     monitoring runs
         */
        static Monitoring of(Set<MonitoringFailure> failures, boolean snapshotSeen) {
            boolean system = failures.contains(MonitoringFailure.SYSTEM_MONITORING_STOPPED);
            boolean processes = failures.contains(MonitoringFailure.PROCESS_MONITORING_STOPPED);
            if (failures.contains(MonitoringFailure.STARTUP)) {
                return COULD_NOT_START;
            }
            if (system) {
                return processes ? STOPPED : SYSTEM_STOPPED;
            }
            if (processes) {
                return DEGRADED;
            }
            return snapshotSeen ? RUNNING : STARTING;
        }
    }

    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");
    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");

    private final Label monitoring = new Label();
    private final Tooltip whereToLook = new Tooltip();
    private final Label pressure = new Label();

    StatusBar() {
        getStyleClass().add("status-bar");
        monitoring.getStyleClass().addAll("status-text", "text-small");
        // The monitoring sentence gives way, cut with its tooltip; the pressure sentence never does.
        monitoring.setMinWidth(0);
        monitoring.setTextOverrun(OverrunStyle.ELLIPSIS);
        pressure.getStyleClass().addAll("status-text", "status-pressure", "text-small");
        pressure.setMinWidth(Region.USE_PREF_SIZE);
        pressure.setVisible(false);
        pressure.managedProperty().bind(pressure.visibleProperty());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        getChildren().addAll(monitoring, gap, pressure);
        show(Monitoring.STARTING, "");
    }

    /** The sentence about memory pressure, in its tone; empty takes it away. */
    void showPressure(Optional<OomWords.Line> line) {
        pressure.setVisible(line.isPresent());
        pressure.setText(line.map(OomWords.Line::text).orElse(""));
        pressure.setAccessibleText(pressure.getText());
        pressure.pseudoClassStateChanged(
                WARNING,
                line.map(found -> found.tone() == OomWords.Tone.WARNING).orElse(false));
        pressure.pseudoClassStateChanged(
                FAILED, line.map(found -> found.tone() == OomWords.Tone.ERROR).orElse(false));
    }

    /** Returns the pressure sentence shown, empty when none is; for tests. */
    String pressureText() {
        return pressure.isVisible() ? pressure.getText() : "";
    }

    /**
     * @param logFolder where the log file with the cause of a failure is, as text for the user;
     *     shown as the tooltip of a failure
     */
    void show(Monitoring state, String logFolder) {
        monitoring.setText(state.text);
        monitoring.pseudoClassStateChanged(FAILED, state.failure);
        if (state.failure) {
            whereToLook.setText(state.text + ". The log file in " + logFolder + " has the details.");
            monitoring.setTooltip(whereToLook);
            // A tooltip needs a mouse; a screen reader gets the same sentence as help text.
            monitoring.setAccessibleHelp(whereToLook.getText());
        } else {
            monitoring.setTooltip(null);
            monitoring.setAccessibleHelp(null);
        }
    }

    /** Returns the text shown, for tests. */
    String text() {
        return monitoring.getText();
    }
}
