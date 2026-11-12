package prism.srm.platform.windows;

import com.sun.jna.platform.win32.COM.WbemcliUtil;
import java.util.concurrent.TimeoutException;
import oshi.util.platform.windows.WmiQueryHandler;

/**
 * For tests on Windows: how many threads of a process the system reports as suspended, read
 * without touching the process, from the documented WMI class {@code Win32_Thread}: a thread whose
 * {@code ThreadState} is 5 (Waiting) and whose {@code ThreadWaitReason} is 5 (Suspended). Both are
 * required: the wait reason keeps its last value while the thread runs, so a thread resumed into a
 * busy loop still shows reason 5. Independent of Prism's record of what it holds; no suspend count
 * is read or changed.
 *
 * <p>A reading that fails is a failure, never zero: a query the system refuses or does not answer,
 * a value that is missing, and no thread at all for a process that runs each throw.
 */
public final class SuspendedThreads {

    private static final long WAITING = 5;
    private static final long SUSPENDED = 5;
    private static final int QUERY_TIMEOUT_MILLIS = 10_000;

    /** The two properties read, named as WMI names them. */
    enum ThreadProperty {
        THREADSTATE,
        THREADWAITREASON
    }

    private SuspendedThreads() {}

    /**
     * The number of suspended threads of the process.
     *
     * @throws IllegalStateException when the system gives no complete answer
     */
    public static int of(long pid) {
        WmiQueryHandler handler = WmiQueryHandler.createInstance();
        boolean initialized = handler.initCOM();
        try {
            WbemcliUtil.WmiResult<ThreadProperty> result;
            try {
                // execute, not the handler's queryWMI: that one answers an error with an empty result.
                result = new WbemcliUtil.WmiQuery<>(
                                "Win32_Thread WHERE ProcessHandle=\"" + pid + "\"", ThreadProperty.class)
                        .execute(QUERY_TIMEOUT_MILLIS);
            } catch (TimeoutException e) {
                throw new IllegalStateException(
                        "Win32_Thread did not answer within " + QUERY_TIMEOUT_MILLIS + " ms", e);
            }
            if (result.getResultCount() == 0
                    && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                throw new IllegalStateException("Win32_Thread reported no thread of the running process " + pid);
            }
            int suspended = 0;
            for (int row = 0; row < result.getResultCount(); row++) {
                if (value(result, ThreadProperty.THREADSTATE, row) == WAITING
                        && value(result, ThreadProperty.THREADWAITREASON, row) == SUSPENDED) {
                    suspended++;
                }
            }
            return suspended;
        } finally {
            if (initialized) {
                handler.unInitCOM();
            }
        }
    }

    private static long value(WbemcliUtil.WmiResult<ThreadProperty> result, ThreadProperty property, int row) {
        Object value = result.getValue(property, row);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Win32_Thread gave no " + property + " for a thread: " + value);
        }
        return number.longValue();
    }
}
