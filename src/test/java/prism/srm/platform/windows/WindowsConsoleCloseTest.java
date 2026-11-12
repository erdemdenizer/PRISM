package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** LIFE-5: what the console close handler does with each signal. No native call is made. */
class WindowsConsoleCloseTest {

    @Test
    void aCloseRunsTheReleaseAndLetsTheNextHandlerRun() {
        AtomicInteger released = new AtomicInteger();

        // The value of the HandlerRoutine page, not the constant: a wrong constant must fail here.
        assertThat(WindowsConsoleClose.CTRL_CLOSE_EVENT).isEqualTo(2);
        boolean handled = WindowsConsoleClose.handle(2, released::incrementAndGet);

        assertThat(released).hasValue(1);
        assertThat(handled).as("FALSE: the JVM's handler is called next").isFalse();
    }

    /** Ctrl+C, Ctrl+Break, logoff and shutdown (0, 1, 5, 6 on the HandlerRoutine page) are not this handler's. */
    @Test
    void everyOtherSignalIsLeftToTheOtherHandlers() {
        AtomicInteger released = new AtomicInteger();

        for (int type : new int[] {0, 1, 5, 6}) {
            assertThat(WindowsConsoleClose.handle(type, released::incrementAndGet))
                    .as("signal %d", type)
                    .isFalse();
        }

        assertThat(released).hasValue(0);
    }

    @Test
    void aReleaseThatFailsDoesNotStopTheJvmFromShuttingDown() {
        boolean handled = WindowsConsoleClose.handle(WindowsConsoleClose.CTRL_CLOSE_EVENT, () -> {
            throw new IllegalStateException("release failed");
        });

        assertThat(handled).isFalse();
    }
}
