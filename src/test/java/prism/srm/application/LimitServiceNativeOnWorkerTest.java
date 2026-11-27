package prism.srm.application;

import java.util.concurrent.Executor;

/**
 * Every test of {@link LimitServiceNativeTest} again, with the native part of each operation handed to a worker (risk
 * D6) and its answer taken back later: the worker is a task of its own on the virtual limiter thread, so the native
 * call runs after the request's own task, and the request is answered only by the task after it. Every result, record
 * and row must be what it is when the native part runs inline.
 */
class LimitServiceNativeOnWorkerTest extends LimitServiceNativeTest {

    @Override
    Executor nativeWork(VirtualScheduler limiterThread) {
        return limiterThread::execute;
    }
}
