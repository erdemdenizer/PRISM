package prism.srm.ui.metric;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * What a page that shows monitoring has to show as a whole: whether snapshots arrive, and if they
 * do not, the sentence that says why. Every such page says the same failure in the same words.
 * Changed on the JavaFX Application Thread only, by a view model.
 */
public final class PageStatus {

    /** What the page as a whole has to show. */
    public enum PageState {
        /** No snapshot has arrived yet. */
        LOADING,
        /** Snapshots arrive. Single parts may still be waiting or unavailable. */
        LIVE,
        /** Monitoring could not start or has ended, so no snapshot will arrive. */
        ERROR
    }

    private final ReadOnlyObjectWrapper<PageState> state = new ReadOnlyObjectWrapper<>(PageState.LOADING);
    private final ReadOnlyStringWrapper error = new ReadOnlyStringWrapper("");

    public ReadOnlyObjectProperty<PageState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** What failed and what the user can do; empty unless the state is {@link PageState#ERROR}. */
    public ReadOnlyStringProperty errorProperty() {
        return error.getReadOnlyProperty();
    }

    /** A snapshot has arrived. */
    public void live() {
        state.set(PageState.LIVE);
        error.set("");
    }

    /**
     * Monitoring could not start.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void startupFailed(String logFolder) {
        failed("Monitoring could not start, so no values are shown. Restart Prism; if that does not help, "
                + "the log file in " + logFolder + " has the details.");
    }

    /**
     * System monitoring has ended while the application ran.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void monitoringStopped(String logFolder) {
        failed("System monitoring stopped unexpectedly, so no values are shown. Restart Prism; the log file in "
                + logFolder + " has the details.");
    }

    /**
     * Process monitoring has ended while the application ran.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void processMonitoringStopped(String logFolder) {
        failed("Process monitoring stopped unexpectedly, so no processes are shown. Restart Prism; the log file in "
                + logFolder + " has the details.");
    }

    /**
     * System monitoring has ended before any process was listed. Process monitoring starts with
     * a system snapshot, so it may never start now; a listing that was already on its way still
     * arrives.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void processesNotListed(String logFolder) {
        failed("System monitoring stopped unexpectedly before the processes were listed. If none appear, restart"
                + " Prism; the log file in " + logFolder + " has the details.");
    }

    private void failed(String why) {
        state.set(PageState.ERROR);
        error.set(why);
    }
}
