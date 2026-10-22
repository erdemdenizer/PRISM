package prism.srm.ui.performance;

import java.util.Optional;
import prism.srm.domain.MemorySample;
import prism.srm.domain.SwapUsage;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.SystemReadouts;

/**
 * The Memory section: physical memory in use with its share and its 60-second history, the
 * available and the total memory, and the swap space or pagefile.
 *
 * <p>"In use" is the total minus what the system reports as available, so memory the system
 * could give back at once, such as its file cache, counts as available and not as in use.
 */
final class MemoryModel implements SectionModel {

    /** Shown as the swap fact on a system that has neither swap space nor a pagefile. */
    static final String NO_SWAP = "None";

    private final ValueFormatter formatter;
    private final SystemReadouts readouts;
    private final MetricReadout inUse = new MetricReadout();
    private final FactReadout available = new FactReadout();
    private final FactReadout total = new FactReadout();
    private final FactReadout swap = new FactReadout();

    MemoryModel(ValueFormatter formatter, SystemReadouts readouts) {
        this.formatter = formatter;
        this.readouts = readouts;
    }

    /** Physical memory in use, with its share of the total. */
    MetricReadout inUse() {
        return inUse;
    }

    FactReadout available() {
        return available;
    }

    FactReadout total() {
        return total;
    }

    /** Swap space or pagefile: how much of it is in use, and its size. */
    FactReadout swap() {
        return swap;
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        int cycles = SystemReadouts.cycles(snapshot);
        Optional<MemorySample> memory = snapshot.memory();
        readouts.showMemory(inUse, snapshot);
        available.show(memory.map(now -> formatter.bytes(now.availableBytes()).text()), cycles);
        total.show(memory.map(now -> formatter.bytes(now.totalBytes()).text()), cycles);
        // A reading without swap means that the system has none, which is a fact and not a gap.
        swap.show(memory.map(now -> now.swap().map(this::describe).orElse(NO_SWAP)), cycles);
    }

    @Override
    public void showNothing() {
        SystemReadouts.showNothing(inUse);
        available.showUnavailable();
        total.showUnavailable();
        swap.showUnavailable();
    }

    private String describe(SwapUsage swapUsage) {
        return formatter.bytes(swapUsage.usedBytes()).text() + " of "
                + formatter.bytes(swapUsage.totalBytes()).text();
    }
}
