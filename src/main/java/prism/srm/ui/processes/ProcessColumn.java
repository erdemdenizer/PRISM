package prism.srm.ui.processes;

import java.util.OptionalDouble;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;

/**
 * The columns of the process table, in the order of the product specification (UI-2), each with
 * what it means to sort by it. A column compares the values themselves, never their text: 9 comes
 * before 10, and 900 KiB before 1 MiB.
 *
 * <p>A process may have no value in a column. {@link #hasValue} says so; where such a process
 * goes in the order is decided in {@link ProcessOrder}, the same way for every column.
 */
enum ProcessColumn {
    /** The short name of the process. Sorted without regard to case, the same in every locale. */
    NAME("Name") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return !process.name().isEmpty();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
        }
    },

    /** The PID. Every process has one. */
    PID("PID") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return true;
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Long.compare(a.identity().pid(), b.identity().pid());
        }
    },

    /** The share of the whole machine's processor capacity; 100 means every logical processor. */
    CPU("CPU") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.cpuPercent().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Double.compare(a.cpuPercent().getAsDouble(), b.cpuPercent().getAsDouble());
        }
    },

    /** The resident memory: what the process has in physical memory. */
    MEMORY("Memory") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.residentBytes().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Long.compare(a.residentBytes().getAsLong(), b.residentBytes().getAsLong());
        }
    },

    /** The I/O rate of the process, reads and writes together. Process I/O, not disk activity. */
    IO("I/O") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return ioBytesPerSecond(process).isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Double.compare(
                    ioBytesPerSecond(a).getAsDouble(), ioBytesPerSecond(b).getAsDouble());
        }
    },

    /** The user the process runs as. Sorted like the name. */
    USER("User") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.user().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return String.CASE_INSENSITIVE_ORDER.compare(
                    a.user().get(), b.user().get());
        }
    },

    THREADS("Threads") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.threadCount().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Integer.compare(a.threadCount().getAsInt(), b.threadCount().getAsInt());
        }
    },

    /** The scheduling priority as the system numbers it. */
    PRIORITY("Priority") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.priority().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return Integer.compare(a.priority().getAsInt(), b.priority().getAsInt());
        }
    },

    /** The state the system reports for the process as a whole. Sorted by its name. */
    STATE("State") {
        @Override
        boolean hasValue(ProcessSnapshot process) {
            return process.state().isPresent();
        }

        @Override
        int compareValues(ProcessSnapshot a, ProcessSnapshot b) {
            return stateName(a.state().get()).compareTo(stateName(b.state().get()));
        }
    };

    private final String title;

    ProcessColumn(String title) {
        this.title = title;
    }

    /** The text of the column header. */
    String title() {
        return title;
    }

    /** Whether the process has a value in this column. */
    abstract boolean hasValue(ProcessSnapshot process);

    /** Compares two processes that both have a value in this column, the smaller value first. */
    abstract int compareValues(ProcessSnapshot a, ProcessSnapshot b);

    /**
     * The rate the I/O column shows: read rate and write rate added up. It exists only when both
     * exist; a sum of one known and one unknown rate would pass for the whole.
     */
    static OptionalDouble ioBytesPerSecond(ProcessSnapshot process) {
        if (process.ioReadBytesPerSecond().isEmpty()
                || process.ioWriteBytesPerSecond().isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(process.ioReadBytesPerSecond().getAsDouble()
                + process.ioWriteBytesPerSecond().getAsDouble());
    }

    /** The word the State column shows for a state. */
    static String stateName(ProcessState state) {
        return switch (state) {
            case NEW -> "New";
            case RUNNING -> "Running";
            case SLEEPING -> "Sleeping";
            case WAITING -> "Waiting";
            case STOPPED -> "Stopped";
            case SUSPENDED -> "Suspended";
            case ZOMBIE -> "Zombie";
            case OTHER -> "Other";
        };
    }
}
