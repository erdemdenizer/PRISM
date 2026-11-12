package prism.srm.application;

import java.time.Duration;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.CpuTimeReading;
import prism.srm.platform.SuspendResult;

/**
 * A process on the virtual clock that uses a fixed share of the machine while it runs and nothing
 * while it is suspended, wired into the fake controller and source. A suspend may take time, during
 * which the process still runs, as on Windows, where a suspend lists every thread of the system.
 */
final class SimulatedTarget {

    private final VirtualScheduler scheduler;
    private final int processors;
    /** The share of the machine the process uses while it runs, 0 to 1. */
    volatile double demand;
    /** How long a suspend takes. */
    volatile Duration suspendTakes = Duration.ZERO;

    private double cpuMillis;
    private long last;
    private boolean stopped;

    SimulatedTarget(VirtualScheduler scheduler, int processors, double demand) {
        this.scheduler = scheduler;
        this.processors = processors;
        this.demand = demand;
        this.last = scheduler.now();
    }

    /** Lets the fake controller suspend and resume this process and the fake source read its time. */
    SimulatedTarget wire(FakeProcessController controller, FakeProcessSource source) {
        controller.suspend = target -> {
            accrue();
            scheduler.elapse(suspendTakes);
            accrue();
            stopped = true;
            return SuspendResult.suspended(ControlOutcome.success(), controller.adopted(target));
        };
        controller.resume = suspension -> {
            accrue();
            stopped = false;
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        source.onCpuTime = identity -> new CpuTimeReading.Read(cpuMillis());
        return this;
    }

    long cpuMillis() {
        accrue();
        return (long) cpuMillis;
    }

    boolean stopped() {
        return stopped;
    }

    /** The share of the machine the process used over the duration, letting it pass. */
    double useOver(Duration duration) {
        double before = exactCpuMillis();
        scheduler.advance(duration);
        double used = exactCpuMillis() - before;
        return used / (duration.toMillis() * (double) processors);
    }

    private double exactCpuMillis() {
        accrue();
        return cpuMillis;
    }

    private void accrue() {
        long now = scheduler.now();
        if (!stopped) {
            cpuMillis += (now - last) / 1_000_000.0 * demand * processors;
        }
        last = now;
    }
}
