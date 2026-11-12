package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ForgetResult;

/**
 * A target that exits while a process it started after the limit lives on in its job (plan §8.6,
 * §11.3 E7): Prism counts the survivor, clears its own limits on the job, verifies, and only then lets
 * the job go; the survivor runs freely again and stays a member, which limits nothing. Only processes
 * this test starts are touched, and each is ended before the test returns (SAFE-6).
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ParentExitsChildSurvivesTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration START = Duration.ofSeconds(60);

    private final WindowsJobLimiter limiter = new WindowsJobLimiter();
    private final JnaWin32 win32 = new JnaWin32();
    private final List<AutoCloseable> owned = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        limiter.releaseAll(Duration.ofSeconds(5));
        for (AutoCloseable each : owned.reversed()) {
            each.close();
        }
    }

    @Test
    void whenTheTargetExitsPrismClearsItsLimitsForTheChildAndLetsTheJobGo() throws Exception {
        TestJvm parent = TestJvm.start(SpawningTarget.class);
        owned.add(parent);
        parent.await("ready", START);
        NativeValidation.requireOutsideAnyJob(parent.identity().pid());
        ProcessIdentity target = parent.identity();

        ApplyResult applied = limiter.apply(target, new LimitSpec(OptionalInt.of(20), OptionalLong.empty()));
        assertThat(applied.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        parent.say("spawn --cpu 60 --duration 150s");
        long childPid = Long.parseLong(parent.await("child=", START).substring("child=".length()));
        // The handle, taken at once, ends only that process: the JDK checks its start time, so a
        // reused PID is never ended (test review).
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        owned.add(child::destroyForcibly);
        assertThat(inAnyJob(childPid))
                .as("the child started after the limit is in the job")
                .contains(true);
        double limited = TestJvm.use(child, WINDOW);

        parent.say("exit");
        assertThat(parent.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
        ForgetResult forgotten = limiter.forget(target);
        double free = TestJvm.use(child, WINDOW);

        String childChildren = child.children()
                .map(each -> each.pid() + ":"
                        + each.info()
                                .command()
                                .map(c -> Path.of(c).getFileName().toString())
                                .orElse("?"))
                .toList()
                .toString();
        System.out.println(
                "parent-exits: limited=" + TestJvm.percent(limited) + " " + forgotten.result() + " survivors="
                        + forgotten.survivors() + " childChildren=" + childChildren + " free=" + TestJvm.percent(free));
        assertThat(limited).as("the child shares the cap").isBetween(15.0, 25.0);
        assertThat(forgotten.result()).isEqualTo(ForgetResult.Outcome.RELEASED);
        // The child, and the console host Windows starts for a console child without a window.
        assertThat(forgotten.survivors()).isPositive();
        assertThat(limiter.hold(target)).as("the job is let go").isEmpty();
        assertThat(free).as("the child runs freely again").isGreaterThan(30.0);
        assertThat(inAnyJob(childPid)).as("membership stays; it limits nothing").contains(true);
    }

    private Optional<Boolean> inAnyJob(long pid) {
        Win32.Opened opened = win32.openProcess(WindowsProcessAccess.QUERY, (int) pid);
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
