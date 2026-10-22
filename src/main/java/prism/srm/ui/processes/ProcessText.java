package prism.srm.ui.processes;

import java.time.ZoneId;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.format.ValueFormatter;

/**
 * The text of every value the Processes page shows of a process. A value the snapshot does not
 * have is an en dash, never a zero and never an invented word: a process that used no processor
 * time shows "0.0 %", one whose processor time could not be compared shows the dash.
 *
 * <p>The texts are made when a cell or a label shows them, so only for what is on screen.
 */
final class ProcessText {

    /** Stands in for a value the snapshot does not have. */
    static final String MISSING = Quantity.MISSING.number();

    private final ValueFormatter formatter;
    private final ZoneId zone;

    /**
     * @param formatter formats numbers in the notation of the user's locale
     * @param zone the time zone start times are shown in
     */
    ProcessText(ValueFormatter formatter, ZoneId zone) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    String name(ProcessSnapshot process) {
        return process.name().isEmpty() ? MISSING : process.name();
    }

    /** The share of the whole machine's processor capacity, with one decimal. */
    Quantity cpu(ProcessSnapshot process) {
        return process.cpuPercent().isPresent()
                ? formatter.percent(process.cpuPercent().getAsDouble())
                : Quantity.MISSING;
    }

    /** The resident memory. */
    Quantity memory(ProcessSnapshot process) {
        return size(process.residentBytes());
    }

    /** The I/O rate, reads and writes together; missing unless both rates are known. */
    Quantity io(ProcessSnapshot process) {
        return rate(ProcessColumn.ioBytesPerSecond(process));
    }

    String user(ProcessSnapshot process) {
        return process.user().orElse(MISSING);
    }

    String threads(ProcessSnapshot process) {
        return process.threadCount().isPresent()
                ? formatter.count(process.threadCount().getAsInt())
                : MISSING;
    }

    /** The priority as the system numbers it: a number of its own scale, shown as it is. */
    String priority(ProcessSnapshot process) {
        return process.priority().isPresent()
                ? Integer.toString(process.priority().getAsInt())
                : MISSING;
    }

    /** The state as a word, or the dash where the system has no state for a process as a whole. */
    String state(ProcessSnapshot process) {
        return process.state().map(ProcessColumn::stateName).orElse(MISSING);
    }

    /** When the process started, in the time zone of the user. */
    String started(ProcessSnapshot process) {
        return formatter.dateTime(process.identity().startTime(), zone);
    }

    /** The PID of the creating process: an identifier, so without grouping. */
    String parentPid(ProcessSnapshot process) {
        return process.parentPid().isPresent()
                ? Long.toString(process.parentPid().getAsLong())
                : MISSING;
    }

    String privateMemory(ProcessSnapshot process) {
        return size(process.privateResidentBytes()).text();
    }

    String virtualSize(ProcessSnapshot process) {
        return size(process.virtualBytes()).text();
    }

    String ioRead(ProcessSnapshot process) {
        return rate(process.ioReadBytesPerSecond()).text();
    }

    String ioWrite(ProcessSnapshot process) {
        return rate(process.ioWriteBytesPerSecond()).text();
    }

    private Quantity size(OptionalLong bytes) {
        return bytes.isPresent() ? formatter.bytes(bytes.getAsLong()) : Quantity.MISSING;
    }

    private Quantity rate(OptionalDouble bytesPerSecond) {
        return bytesPerSecond.isPresent() ? formatter.bytesPerSecond(bytesPerSecond.getAsDouble()) : Quantity.MISSING;
    }
}
