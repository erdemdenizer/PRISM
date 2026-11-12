package prism.srm.platform.linux;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ScopeProvenance;
import prism.srm.platform.ApplyResult;

/**
 * A disposable Prism "session" for the real-crash test (T-L7, test review S7): a separate JVM that composes the harness
 * limiter, adopts exactly the one disposable process named on its command line (its PID and start time), prints the
 * ownership evidence it recorded, and then waits to be killed with SIGKILL by the test. It never releases anything: the
 * scope and its limit are left as a crash leaves them.
 *
 * <p>Arguments: the PID, the start time in epoch milliseconds, the CPU percentage of the whole machine.
 */
public final class CrashingAdopter {

    private CrashingAdopter() {}

    public static void main(String[] arguments) throws Exception {
        int pid = Integer.parseInt(arguments[0]);
        ProcessIdentity named = new ProcessIdentity(pid, Instant.ofEpochMilli(Long.parseLong(arguments[1])));
        int percent = Integer.parseInt(arguments[2]);
        LinuxHarness harness = LinuxHarness.open();
        ProcessIdentity seen = harness.identityOf(pid);
        if (!seen.equals(named)) {
            System.out.println("event=refused identity " + seen + " is not " + named);
            System.out.flush();
            return;
        }
        LinuxScopeLimiter limiter = harness.limiter(Set.of(named), Set.of());
        ScopeProvenance[] recorded = new ScopeProvenance[1];
        ApplyResult applied =
                limiter.apply(named, new LimitSpec(OptionalInt.of(percent), OptionalLong.empty()), provenance -> {
                    recorded[0] = provenance;
                    return true;
                });
        Optional<ScopeProvenance> evidence = Optional.ofNullable(recorded[0]);
        System.out.println("event=adopted phase=" + applied.phase()
                + " unit=" + evidence.map(ScopeProvenance::unit).orElse("-")
                + " invocation="
                + evidence.flatMap(ScopeProvenance::invocationId).orElse("-")
                + " group=" + evidence.flatMap(ScopeProvenance::cgroupPath).orElse("-"));
        // Why, when it is not ENFORCED (diagnosis of an UNCERTAIN adoption seen once on a VM, 2026-10-16).
        System.out.println("event=outcome " + applied.outcome().result() + " "
                + applied.outcome().detail().orElse(""));
        System.out.flush();
        Thread.sleep(600_000);
    }
}
