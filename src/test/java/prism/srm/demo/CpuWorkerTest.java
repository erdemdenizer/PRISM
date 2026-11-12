package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CpuWorkerTest {

    /** A worker that is told to stop before it starts does nothing and returns. */
    @Test
    void returnsAtOnceWhenAlreadyStopped() {
        AtomicBoolean stop = new AtomicBoolean(true);
        new CpuWorker(new DutyCycle(1.0, CpuWorker.PERIOD_NANOS), stop).run();
        assertThat(stop).isTrue();
    }

    /** An interrupt during the sleep ends the worker and leaves the interrupt flag for its owner. */
    @Test
    void anInterruptEndsTheWorkerAndKeepsTheFlag() {
        AtomicBoolean stop = new AtomicBoolean(false);
        // A duty so small that the busy part rounds to nothing: the worker goes straight to its sleep.
        CpuWorker worker = new CpuWorker(new DutyCycle(0.000001, CpuWorker.PERIOD_NANOS), stop);
        Thread.currentThread().interrupt();
        try {
            worker.run();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the flag is set again for the owner")
                    .isTrue();
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
        assertThat(stop).as("the worker did not change the shared flag").isFalse();
    }
}
