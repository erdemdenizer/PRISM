package prism.srm.platform.windows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.locks.LockSupport;
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;

/**
 * A Prism stand-in for {@link NativeLimitRecoveryIntegrationTest} (plan §11.3 E2): in a JVM of its
 * own it limits the process with the PID it is given natively, in the order the application keeps
 * (the durable INTENDED record first, then the assignment, then APPLIED), says "limiting" once the
 * ledger holds it on disk, and waits to be killed; nothing ends it gracefully, so the job's name goes
 * with its handle as after a crash.
 *
 * <p>With {@code partial} it then starts a change to CPU 30 % and half the memory as the application does
 * (the durable PENDING_CHANGE record first) and carries out only its CPU half, as a crash between the
 * two calls would leave it, and says "changed-cpu" (variant e).
 *
 * <p>Arguments: the ledger folder, the instance name ({@code @self} for one named after this process), the PID, the CPU limit in percent, the memory
 * limit in MiB (0 for none of either), and optionally {@code partial}.
 */
public final class NativeLimitingChild {

    private NativeLimitingChild() {}

    public static void main(String[] args) {
        Path folder = Path.of(args[0]);
        // "@self": named as a Prism session is, after this process's own PID and start time.
        String instance = args[1].equals("@self")
                ? SuspensionJournal.instanceName(
                        ProcessHandle.current().pid(),
                        ProcessHandle.current().info().startInstant())
                : args[1];
        long pid = Long.parseLong(args[2]);
        int percent = Integer.parseInt(args[3]);
        long bytes = Long.parseLong(args[4]) << 20;
        boolean partial = args.length > 5 && args[5].equals("partial");
        ProcessIdentity target = new ProcessIdentity(
                pid, ProcessHandle.of(pid).orElseThrow().info().startInstant().orElseThrow());

        WindowsJobLimiter limiter = new WindowsJobLimiter();
        NativeLimitLedger ledger =
                new NativeLimitLedger(Optional.of(folder), instance, SuspensionJournal.onDisk(), Runnable::run);
        LimitSpec spec = new LimitSpec(
                percent > 0 ? OptionalInt.of(percent) : OptionalInt.empty(),
                bytes > 0 ? OptionalLong.of(bytes) : OptionalLong.empty());
        LimitRecord intended = LimitRecord.intended(target, "demo", spec.effective(), instance);
        if (!ledger.putDurably(intended)) {
            say("not-limiting ledger " + ledger.problem().orElse(""));
            return;
        }
        ApplyResult applied = limiter.apply(target, spec);
        if (applied.phase() != ApplyResult.Phase.ENFORCED) {
            say("not-limiting " + applied.phase() + " " + applied.outcome());
            return;
        }
        LimitRecord record = intended.applied(applied.observed().orElseThrow());
        ledger.put(record);
        if (!ledger.awaitLatest(Duration.ofSeconds(5))) {
            say("not-limiting the ledger did not land");
            return;
        }
        say("limiting " + applied.observed().orElseThrow().describe());
        if (partial) {
            EffectiveLimits to = new LimitSpec(OptionalInt.of(30), OptionalLong.of(bytes / 2)).effective();
            if (!ledger.putDurably(record.changing(to))) {
                say("not-changing ledger");
                return;
            }
            // Only the CPU half of the change: the state a crash between the two calls leaves.
            ChangeResult cpu = limiter.change(target, new LimitSpec(OptionalInt.of(30), OptionalLong.of(bytes)));
            say("changed-cpu " + cpu.phase() + " "
                    + cpu.observed().map(EffectiveLimits::describe).orElse("?"));
        }
        while (true) {
            LockSupport.park();
        }
    }

    private static void say(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
