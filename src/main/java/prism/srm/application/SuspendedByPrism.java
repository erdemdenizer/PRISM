package prism.srm.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;

/**
 * Shows in a listing which processes Prism holds suspended (PROC-6). The state comes from Prism's
 * own record, not from scanning threads: a scan would be too expensive on every listing, and a
 * process suspended by another program is therefore not detected on Windows (a documented
 * limitation). Matched by identity, so a process that took over a suspended process's PID is not
 * marked.
 */
public final class SuspendedByPrism {

    private SuspendedByPrism() {}

    /** The listing with the state of each held process replaced; the same list when none is held. */
    public static List<ProcessSnapshot> overlay(List<ProcessSnapshot> listing, Set<ProcessIdentity> held) {
        if (held.isEmpty()) {
            return listing;
        }
        List<ProcessSnapshot> marked = new ArrayList<>(listing.size());
        for (ProcessSnapshot snapshot : listing) {
            marked.add(held.contains(snapshot.identity()) ? suspendedByPrism(snapshot) : snapshot);
        }
        return List.copyOf(marked);
    }

    private static ProcessSnapshot suspendedByPrism(ProcessSnapshot p) {
        return new ProcessSnapshot(
                p.identity(),
                p.name(),
                p.parentPid(),
                Optional.of(ProcessState.SUSPENDED_BY_PRISM),
                p.cpuPercent(),
                p.residentBytes(),
                p.privateResidentBytes(),
                p.virtualBytes(),
                p.user(),
                p.threadCount(),
                p.priority(),
                p.ioReadBytesPerSecond(),
                p.ioWriteBytesPerSecond());
    }
}
