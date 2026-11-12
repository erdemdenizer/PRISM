package prism.srm.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import prism.srm.application.JournalEntry;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * A first Prism for {@link InstanceLockTest}, in a JVM of its own: it asks for the ownership of the
 * profile whose state folder it is given, and, when it gets it, opens its suspension journal there
 * with one entry, as a running Prism would. It says "held" or "refused" and then waits to be killed.
 * It limits and suspends nothing.
 */
public final class InstanceLockHolder {

    private InstanceLockHolder() {}

    public static void main(String[] args) {
        Path state = Path.of(args[0]);
        InstanceLock lock = InstanceLock.acquire(state);
        if (!lock.owned()) {
            System.out.println("refused " + lock.outcome());
            System.out.flush();
            return;
        }
        String instance = SuspensionJournal.instanceName(
                ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
        SuspensionJournal journal =
                SuspensionJournal.open(state.resolve("journal"), instance, SuspensionJournal.onDisk());
        Instant now = Instant.parse("2026-10-10T10:00:00Z");
        journal.write(List.of(new JournalEntry(
                new ProcessIdentity(4_242, now),
                "demo",
                EnumSet.of(SuspensionReason.USER),
                Optional.empty(),
                Optional.empty(),
                instance,
                now,
                now,
                false,
                false)));
        System.out.println("held " + instance);
        System.out.flush();
        while (true) {
            LockSupport.park();
        }
    }
}
