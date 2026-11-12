package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import prism.srm.application.ActivityLog;
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.NativeRecovery;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;

/**
 * Crash recovery of native limits on the real system (MILESTONES section 10, plan §11.3 E2): a Prism
 * stand-in limits the demo workload natively and is killed as Task Manager would kill it; the limit
 * is measured to stay in force, the job's name is gone with its last handle, and the next start reads
 * the ledger and says what it found, without changing anything it cannot prove is its own.
 *
 * <p>Only processes this test starts are limited, and each is ended before the test returns (SAFE-6).
 * The job outlives the stand-in by design: no job is ever made to kill its processes on close.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 300, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class NativeLimitRecoveryIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration START = Duration.ofSeconds(60);

    /**
     * A commit limit the demo JVM never reaches. Measured on the development machine: a 512 MiB cap
     * together with a 20 % CPU cap makes the demo JVM fail within seconds, the documented effect of
     * reaching a commit cap, which would confound what these tests measure.
     */
    private static final String MEMORY = "4096";

    @TempDir
    Path folder;

    private final JnaWin32 win32 = new JnaWin32();
    private final List<AutoCloseable> owned = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable each : owned.reversed()) {
            each.close();
        }
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.add(resource);
        return resource;
    }

    /** E2 a and c: after a hard kill the limit stays, the name is gone, and the next start lists it. */
    @Test
    void afterAHardKillTheLimitStaysTheNameIsGoneAndTheNextStartListsIt() throws Exception {
        DemoTarget demo = own(DemoTarget.start("--cpu", "60", "--duration", "240s"));
        NativeValidation.requireOutsideAnyJob(demo.identity().pid());
        String name = JobNames.of(demo.identity());

        TestJvm prism = own(TestJvm.start(
                NativeLimitingChild.class,
                folder.toString(),
                "77-1",
                Long.toString(demo.identity().pid()),
                "20",
                MEMORY));
        String said = prism.await("limiting", START);
        System.out.println("recovery: stand-in says " + said);
        assertThat(said).isEqualTo("limiting CPU 20 %, memory 4 GiB");
        assertThat(nameExists(name))
                .as("the name lives while the stand-in holds the job")
                .isTrue();
        double limited = TestJvm.use(demo.process().toHandle(), WINDOW);

        prism.kill();
        assertThat(demo.process().isAlive())
                .as("the target outlives the holder of its job")
                .isTrue();
        double afterKill = TestJvm.use(demo.process().toHandle(), WINDOW);
        Win32.Opened reopened = win32.openJob(WindowsJobLimiter.REOPEN_RIGHTS, name);
        if (reopened.ok()) {
            win32.close(reopened.handle());
        }
        Optional<Boolean> inJob = inAnyJob(demo.identity());
        System.out.println("recovery: limited=" + TestJvm.percent(limited) + " afterKill=" + TestJvm.percent(afterKill)
                + " reopenError=" + reopened.error() + " inJob=" + inJob);
        assertThat(limited).as("capped at 20 %% of the machine").isBetween(15.0, 25.0);
        assertThat(afterKill).as("still capped after the hard kill").isBetween(15.0, 25.0);
        assertThat(reopened.error()).as("the name went with the last handle").isEqualTo(2);
        assertThat(inJob).contains(true);

        // The next start: the ledger of the killed session, which has no lock or journal, is read.
        NativeLimitLedger own =
                new NativeLimitLedger(Optional.of(folder), "77-2", SuspensionJournal.onDisk(), Runnable::run);
        NativeRecovery recovery = new NativeRecovery(
                new WindowsJobLimiter(),
                own,
                Optional.of(folder),
                SuspensionJournal.onDisk(),
                new ActivityLog(100, Instant::now));
        List<RecoveredLimit> found = recovery.lookAtOrphans();
        found.forEach(each -> System.out.println("recovery: " + each.verdict() + " " + each.detail()));
        assertThat(found).singleElement().satisfies(each -> {
            assertThat(each.record().identity()).isEqualTo(demo.identity());
            assertThat(each.verdict())
                    .as("the name is gone, so the job cannot be reached")
                    .isEqualTo(RecoveredLimit.Verdict.UNREACHABLE);
        });
        double afterRecovery = TestJvm.use(demo.process().toHandle(), WINDOW);
        System.out.println("recovery: afterRecovery=" + TestJvm.percent(afterRecovery));
        if (found.get(0).verdict() == RecoveredLimit.Verdict.UNREACHABLE) {
            assertThat(afterRecovery).as("unreachable: the limit lasts").isBetween(15.0, 25.0);
            assertThat(own.find(demo.identity()))
                    .as("carried into this session's ledger")
                    .isPresent();
        } else {
            assertThat(afterRecovery).as("cleared: the workload runs freely").isGreaterThan(30.0);
        }

        // E2 c: the target has exited; the record names a limit that may have been in force.
        demo.close();
        assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        RecoveredLimit gone =
                new WindowsJobLimiter().recover(own.find(demo.identity()).orElseThrow());
        System.out.println("recovery: after the target exited: " + gone.verdict() + " " + gone.detail());
        assertThat(gone.verdict()).isEqualTo(RecoveredLimit.Verdict.GONE_CHILDREN_UNKNOWN);
        RecoveredLimit cleared = new WindowsJobLimiter()
                .recover(own.find(demo.identity()).orElseThrow().cleared());
        assertThat(cleared.verdict()).as("a record already cleared").isEqualTo(RecoveredLimit.Verdict.GONE);
    }

    /**
     * E2 e: a crash between the CPU half and the memory half of a change leaves CPU 30 % with the old memory limit,
     * neither the old limits nor the new ones; the record admits it and says both values per kind.
     */
    @Test
    void aCrashInTheMiddleOfAChangeIsReadAsEitherValuePerKind() throws Exception {
        DemoTarget demo = own(DemoTarget.start("--cpu", "60", "--duration", "200s"));
        NativeValidation.requireOutsideAnyJob(demo.identity().pid());

        TestJvm prism = own(TestJvm.start(
                NativeLimitingChild.class,
                folder.toString(),
                "78-1",
                Long.toString(demo.identity().pid()),
                "20",
                MEMORY,
                "partial"));
        prism.await("limiting", START);
        String changed = prism.await("changed-cpu", START);
        System.out.println("recovery: stand-in says " + changed);
        // What the stand-in asked of the system (the CPU half) took effect; the memory half never came.
        assertThat(changed).isEqualTo("changed-cpu APPLIED CPU 30 %, memory 4 GiB");
        prism.kill();
        assertThat(demo.process().isAlive()).isTrue();
        double afterKill = TestJvm.use(demo.process().toHandle(), WINDOW);

        List<LimitRecord> left = NativeLimitLedger.readDead(folder, "78-1", SuspensionJournal.onDisk());
        assertThat(left).singleElement().satisfies(record -> {
            assertThat(record.state()).isEqualTo(LedgerState.PENDING_CHANGE);
            assertThat(record.describe()).isEqualTo("CPU 20 % or CPU 30 %; memory 4 GiB or memory 2 GiB");
        });
        RecoveredLimit found = new WindowsJobLimiter().recover(left.get(0));
        System.out.println("recovery: partial change: afterKill=" + TestJvm.percent(afterKill) + " " + found.verdict()
                + " " + found.detail());
        assertThat(afterKill).as("the CPU half took effect").isBetween(25.0, 35.0);
        assertThat(found.verdict()).isEqualTo(RecoveredLimit.Verdict.UNREACHABLE);
    }

    /**
     * E2 d: the target exited after the hard kill, and a process it started inside the job lives on,
     * still limited; the next start cannot reach the job and says that processes it started may
     * still be limited, which the measured share of that process confirms.
     */
    @Test
    void aProcessTheTargetStartedStaysLimitedAndTheNextStartSaysItMayBe() throws Exception {
        TestJvm parent = own(TestJvm.start(SpawningTarget.class));
        parent.await("ready", START);
        NativeValidation.requireOutsideAnyJob(parent.identity().pid());

        TestJvm prism = own(TestJvm.start(
                NativeLimitingChild.class,
                folder.toString(),
                "79-1",
                Long.toString(parent.identity().pid()),
                "20",
                MEMORY));
        prism.await("limiting", START);
        parent.say("spawn --cpu 60 --duration 200s");
        long childPid = Long.parseLong(parent.await("child=", START).substring("child=".length()));
        ProcessHandle childHandle = ProcessHandle.of(childPid).orElseThrow();
        own(childHandle::destroyForcibly);
        ProcessIdentity child = TestJvm.identityOf(childPid);
        assertThat(inAnyJob(child)).as("the child inherits the job").contains(true);

        prism.kill();
        assertThat(childHandle.isAlive())
                .as("the child outlives the holder of the job")
                .isTrue();
        parent.say("exit");
        assertThat(parent.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
        double childUse = TestJvm.use(childHandle, WINDOW);

        List<LimitRecord> left = NativeLimitLedger.readDead(folder, "79-1", SuspensionJournal.onDisk());
        assertThat(left).hasSize(1);
        RecoveredLimit found = new WindowsJobLimiter().recover(left.get(0));
        System.out.println("recovery: child of an exited target: childUse=" + TestJvm.percent(childUse) + " "
                + found.verdict() + " " + found.detail());
        assertThat(childUse).as("the child stays limited").isBetween(15.0, 25.0);
        assertThat(found.verdict()).isEqualTo(RecoveredLimit.Verdict.GONE_CHILDREN_UNKNOWN);
    }

    /**
     * Audit of the recovery trust boundary, C, on the real system: a Prism stand-in that still runs
     * holds a 20 % cap; its ledger lies in the folder, named as a Prism session is named, with no
     * journal beside it. Recovery leaves it alone because its Prism process runs; and even asked
     * directly to look at the record, the limiter reads the job and changes nothing. The cap is
     * measured to stay.
     */
    @Test
    void recoveryNeverChangesTheJobOfAPrismSessionThatStillRuns() throws Exception {
        DemoTarget demo = own(DemoTarget.start("--cpu", "60", "--duration", "200s"));
        NativeValidation.requireOutsideAnyJob(demo.identity().pid());
        // The stand-in's ledger is named after the stand-in itself, as a Prism session's is.
        TestJvm live = own(TestJvm.start(
                NativeLimitingChild.class,
                folder.toString(),
                "@self",
                Long.toString(demo.identity().pid()),
                "20",
                MEMORY));
        live.await("limiting", START);
        String instance = SuspensionJournal.instanceName(
                live.identity().pid(), Optional.of(live.identity().startTime()));
        assertThat(NativeLimitLedger.readDead(folder, instance, SuspensionJournal.onDisk()))
                .hasSize(1);

        NativeLimitLedger own =
                new NativeLimitLedger(Optional.of(folder), "77-9", SuspensionJournal.onDisk(), Runnable::run);
        NativeRecovery recovery = new NativeRecovery(
                new WindowsJobLimiter(),
                own,
                Optional.of(folder),
                SuspensionJournal.onDisk(),
                new ActivityLog(100, Instant::now));
        assertThat(recovery.lookAtOrphans())
                .as("its Prism process runs: not an orphan")
                .isEmpty();
        assertThat(NativeLimitLedger.readDead(folder, instance, SuspensionJournal.onDisk()))
                .as("its ledger is not touched")
                .hasSize(1);
        assertThat(recovery.mayStillCap(demo.identity())).isPresent();

        // Past the session proof, as if it had failed: the limiter still changes nothing.
        RecoveredLimit direct = new WindowsJobLimiter()
                .recover(NativeLimitLedger.readDead(folder, instance, SuspensionJournal.onDisk())
                        .get(0));
        double capped = TestJvm.use(demo.process().toHandle(), WINDOW);
        System.out.println("recovery: live session: " + direct.verdict() + " " + direct.detail() + " use="
                + TestJvm.percent(capped));
        assertThat(direct.verdict()).isEqualTo(RecoveredLimit.Verdict.HELD_ELSEWHERE);
        assertThat(capped).as("the live session's cap stays").isBetween(15.0, 25.0);
        assertThat(live.process().isAlive()).isTrue();
    }

    private boolean nameExists(String name) {
        Win32.Opened opened = win32.openJob(WindowsJobLimiter.REOPEN_RIGHTS, name);
        if (opened.ok()) {
            win32.close(opened.handle());
            return true;
        }
        return false;
    }

    private Optional<Boolean> inAnyJob(ProcessIdentity identity) {
        Win32.Opened opened = win32.openProcess(WindowsProcessAccess.QUERY, (int) identity.pid());
        if (!opened.ok()) {
            return Optional.empty();
        }
        try {
            return win32.inJob(opened.handle(), OptionalLong.empty());
        } finally {
            win32.close(opened.handle());
        }
    }
}
