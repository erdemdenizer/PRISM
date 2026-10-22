package prism.srm.ui.processes;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.metric.MetricState;

/**
 * What the details pane shows: the selected process with the values no column has, and its
 * executable path and command line.
 *
 * <p><b>Path and command line are read on demand</b> (PROC-5). When a process is selected, this
 * model asks for them once, through the function it was given, and says "Reading…" until the
 * answer arrives. It reads nothing itself, and nothing is asked for while the selection stays.
 *
 * <p><b>An answer is shown only for the process it was asked for.</b> The answer arrives later,
 * on its own way to the JavaFX Application Thread, and the selection may have changed by then. A
 * result names its process identity; one that names another identity than the selected one is
 * dropped. A PID that passed to another process is another identity, so the details of the old
 * process can never appear under the new one.
 *
 * <p>The other values come from the row of the process and follow it from listing to listing.
 * Only the details of the selected process are held, and they go with the selection.
 */
final class ProcessDetailsModel {

    static final String NOTHING_SELECTED = "No process selected";

    /** A snapshot cannot tell the two reasons apart, so the text names both. */
    static final String NOT_AVAILABLE = "Not available: the system does not report it, or access was denied.";

    static final String PROCESS_EXITED = "The process has exited.";

    private final ProcessText text;
    private final String logFolder;
    private final Consumer<ProcessIdentity> request;

    private final ReadOnlyStringWrapper title = new ReadOnlyStringWrapper(NOTHING_SELECTED);
    private final ReadOnlyStringWrapper pid = new ReadOnlyStringWrapper("");
    private final ReadOnlyBooleanWrapper processShown = new ReadOnlyBooleanWrapper(false);
    private final DetailValue path = new DetailValue();
    private final DetailValue commandLine = new DetailValue();
    private final DetailValue started = new DetailValue();
    private final DetailValue parentPid = new DetailValue();
    private final DetailValue privateMemory = new DetailValue();
    private final DetailValue virtualSize = new DetailValue();
    private final DetailValue ioRead = new DetailValue();
    private final DetailValue ioWrite = new DetailValue();

    private final ChangeListener<ProcessSnapshot> followRow = (observable, before, after) -> showValues(after);

    /** The row of the selected process; {@code null} while none is shown. */
    private ProcessRow subject;

    /**
     * @param logFolder where the log file is, as text for the user; named when a read fails
     * @param request asks for the path and the command line of a process and returns at once;
     *     the answer comes back through {@link #accept}
     */
    ProcessDetailsModel(ProcessText text, String logFolder, Consumer<ProcessIdentity> request) {
        this.text = Objects.requireNonNull(text, "text");
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder");
        this.request = Objects.requireNonNull(request, "request");
    }

    /** The name of the process, or the sentence that says why none is shown. */
    ReadOnlyStringProperty titleProperty() {
        return title.getReadOnlyProperty();
    }

    /**
     * "PID 1234" next to the name of the process shown, or "PID 1234 has exited" next to the
     * name of the one that was; empty while nothing is selected.
     */
    ReadOnlyStringProperty pidProperty() {
        return pid.getReadOnlyProperty();
    }

    /** Whether the title is the name of a process. */
    ReadOnlyBooleanProperty processShownProperty() {
        return processShown.getReadOnlyProperty();
    }

    DetailValue path() {
        return path;
    }

    DetailValue commandLine() {
        return commandLine;
    }

    /** When the process started; with the PID it is what makes it this process. */
    DetailValue started() {
        return started;
    }

    DetailValue parentPid() {
        return parentPid;
    }

    DetailValue privateMemory() {
        return privateMemory;
    }

    DetailValue virtualSize() {
        return virtualSize;
    }

    DetailValue ioRead() {
        return ioRead;
    }

    DetailValue ioWrite() {
        return ioWrite;
    }

    /** Shows a process and asks for its path and command line. Showing it again asks nothing. */
    void show(ProcessRow row) {
        Objects.requireNonNull(row, "row");
        if (row == subject) {
            return;
        }
        release();
        subject = row;
        row.snapshotProperty().addListener(followRow);
        processShown.set(true);
        pid.set("PID " + row.pidText());
        showValues(row.snapshot());
        path.showInstead(MetricState.WAITING.placeholder());
        commandLine.showInstead(MetricState.WAITING.placeholder());
        request.accept(row.identity());
    }

    /** Shows that nothing is selected. */
    void showNothing() {
        release();
        title.set(NOTHING_SELECTED);
    }

    /**
     * Shows that the process that was selected has exited; its values are taken away. The name
     * and the sentence are two texts: a long name is cut, and what happened to it is not.
     */
    void showExited(ProcessRow row) {
        release();
        String exited = "PID " + row.pidText() + " has exited";
        String name = row.snapshot().name();
        if (name.isEmpty()) {
            title.set(exited);
        } else {
            title.set(name);
            pid.set(exited);
        }
    }

    /**
     * Takes the result of a read. It is shown if it is for the process shown now, and dropped
     * otherwise: it was then asked for a selection that is no longer there.
     */
    void accept(ProcessDetailsResult result) {
        if (subject == null || !result.identity().equals(subject.identity())) {
            return;
        }
        switch (result.outcome()) {
            case READ -> {
                ProcessDetails details = result.details().orElseThrow();
                showOnDemand(path, details.executablePath());
                showOnDemand(commandLine, details.commandLine());
            }
            case PROCESS_GONE -> {
                // The next listing takes the row away; until then this says why there is no value.
                path.showInstead(PROCESS_EXITED);
                commandLine.showInstead(PROCESS_EXITED);
            }
            case FAILED -> {
                String failed = "Could not be read. The log file in " + logFolder + " has the details.";
                path.showInstead(failed);
                commandLine.showInstead(failed);
            }
        }
    }

    private static void showOnDemand(DetailValue line, Optional<String> value) {
        if (value.isPresent()) {
            line.showValue(onOneLine(value.get()));
        } else {
            line.showInstead(NOT_AVAILABLE);
        }
    }

    /**
     * A command line may hold line breaks and tabs, as that of a script given on the command line
     * does. Shown as they are, they would turn the one line of the pane into several and push the
     * table up. Every such character is shown as a blank; nothing else is changed.
     */
    static String onOneLine(String text) {
        StringBuilder oneLine = null;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) {
                if (oneLine == null) {
                    oneLine = new StringBuilder(text);
                }
                oneLine.setCharAt(index, ' ');
            }
        }
        return oneLine == null ? text : oneLine.toString();
    }

    private void showValues(ProcessSnapshot process) {
        title.set(text.name(process));
        started.showValue(text.started(process));
        parentPid.show(text.parentPid(process));
        privateMemory.show(text.privateMemory(process));
        virtualSize.show(text.virtualSize(process));
        ioRead.show(text.ioRead(process));
        ioWrite.show(text.ioWrite(process));
    }

    /** Lets go of the process shown and takes every value off the pane. */
    private void release() {
        if (subject != null) {
            subject.snapshotProperty().removeListener(followRow);
            subject = null;
        }
        processShown.set(false);
        pid.set("");
        for (DetailValue line :
                new DetailValue[] {path, commandLine, started, parentPid, privateMemory, virtualSize, ioRead, ioWrite
                }) {
            line.showInstead(ProcessText.MISSING);
        }
    }
}
