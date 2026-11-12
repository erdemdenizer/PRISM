package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.fail;

import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Assumptions;

/**
 * The precondition of every native-limit integration test (plan §11.6): a child of the test JVM
 * inherits the test JVM's job, and a process in a job Prism does not hold is refused by design
 * (plan D1). Where that is so the tests cannot validate anything; with {@code
 * -Dprism.native.required=true} that fails the run, otherwise the test is skipped with the reason,
 * and the separate native-validation check counts every skip as "not validated". Each call prints
 * one {@code native-validation:} line for the record.
 */
final class NativeValidation {

    private NativeValidation() {}

    static void requireOutsideAnyJob(long pid) {
        JnaWin32 win32 = new JnaWin32();
        Optional<Boolean> self = win32.selfInJob();
        Win32.Opened opened = win32.openProcess(WindowsProcessAccess.QUERY, (int) pid);
        Optional<Boolean> child = Optional.empty();
        if (opened.ok()) {
            try {
                child = win32.inJob(opened.handle(), OptionalLong.empty());
            } finally {
                win32.close(opened.handle());
            }
        }
        System.out.println("native-validation: selfInJob=" + self + " childInJob=" + child + " pid=" + pid);
        String why = "native validation cannot run here: the test's child process is in a Job Object (" + child
                + "); the test JVM runs inside one (selfInJob=" + self + ")";
        switch (decide(child, Boolean.getBoolean("prism.native.required"))) {
            case RUN -> {}
            case FAIL -> fail(why);
            case SKIP -> Assumptions.abort(why);
        }
    }

    /** What a native test does. */
    enum Decision {
        RUN,
        FAIL,
        SKIP
    }

    /**
     * Runs only where the child is known to be in no job; otherwise fails where validation is
     * required and is skipped elsewhere. An unknown membership is not taken for "in no job".
     */
    static Decision decide(Optional<Boolean> childInJob, boolean required) {
        if (childInJob.equals(Optional.of(false))) {
            return Decision.RUN;
        }
        return required ? Decision.FAIL : Decision.SKIP;
    }
}
