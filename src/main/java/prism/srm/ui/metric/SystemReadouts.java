package prism.srm.ui.metric;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.DiskRates;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.AutoScale;
import prism.srm.ui.graph.GraphData;

/**
 * Fills the readouts of the four system metrics from a snapshot: total CPU, physical memory, and
 * the disk and network totals, each with its 60-second history. Every page that shows one of them
 * shows it through this class, so a value reads the same wherever it stands.
 *
 * <p>One instance per view model; used on the JavaFX Application Thread only.
 */
public final class SystemReadouts {

    private static final double PERCENT_SCALE = 100;

    private final ValueFormatter formatter;

    /** The scale of the memory graph; kept for the cycles in which memory cannot be read. */
    private long memoryTotalBytes;

    public SystemReadouts(ValueFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    /**
     * Returns the number of monitoring cycles so far, as far as the snapshot shows it: every
     * history gets one entry per cycle, so any of them counts the cycles.
     */
    public static int cycles(SystemSnapshot snapshot) {
        return snapshot.history().memoryUsedBytes().size();
    }

    /** Takes the values and the history off a readout, because monitoring is not running. */
    public static void showNothing(MetricReadout readout) {
        readout.showNoValues(MetricState.UNAVAILABLE);
        readout.showGraph(GraphData.EMPTY, "");
    }

    /** The value at the top of a scale of percent, as text. */
    public String percentScale() {
        return formatter.count(Math.round(PERCENT_SCALE)) + " %";
    }

    /** Utilization of the whole machine, against a scale of 100 %. */
    public void showCpu(MetricReadout readout, SystemSnapshot snapshot) {
        MetricHistory history = snapshot.history().cpuTotalPercent();
        if (snapshot.cpu().isPresent()) {
            readout.showValue(formatter.percent(snapshot.cpu().get().totalPercent()), "");
        } else {
            readout.showNoValues(MetricState.of(false, cycles(snapshot)));
        }
        // A scale is stated only over a graph that has something drawn against it.
        String scale = AutoScale.hasSample(history) ? percentScale() : "";
        readout.showGraph(GraphData.single(history, PERCENT_SCALE), scale);
    }

    /** Physical memory in use, with its share of the total, against the total. */
    public void showMemory(MetricReadout readout, SystemSnapshot snapshot) {
        Optional<MemorySample> sample = snapshot.memory();
        if (sample.isPresent()) {
            MemorySample now = sample.get();
            memoryTotalBytes = now.totalBytes();
            readout.showValue(
                    formatter.bytes(now.usedBytes()),
                    formatter.percent(now.usedPercent()).text());
        } else {
            readout.showNoValues(MetricState.of(false, cycles(snapshot)));
        }
        // Until the total is known there is no scale, and the graph shows its grid only.
        String scale = memoryTotalBytes > 0 ? formatter.bytes(memoryTotalBytes).text() : "";
        readout.showGraph(GraphData.single(snapshot.history().memoryUsedBytes(), memoryTotalBytes), scale);
    }

    /** Read and write rate summed over the physical disks. */
    public void showDisk(MetricReadout readout, SystemSnapshot snapshot) {
        Optional<DiskRates> rates = snapshot.diskRates();
        if (rates.isPresent()) {
            readout.showValues(
                    formatter.bytesPerSecond(rates.get().readBytesPerSecond()),
                    formatter.bytesPerSecond(rates.get().writeBytesPerSecond()));
        } else {
            readout.showNoValues(MetricState.of(false, cycles(snapshot)));
        }
        SystemHistory history = snapshot.history();
        showRateGraph(readout, history.diskReadBytesPerSecond(), history.diskWriteBytesPerSecond());
    }

    /** Download and upload rate summed over the physical network adapters. */
    public void showNetwork(MetricReadout readout, SystemSnapshot snapshot) {
        Optional<NetworkRates> rates = snapshot.network().flatMap(NetworkUsage::total);
        if (rates.isPresent()) {
            readout.showValues(
                    formatter.bytesPerSecond(rates.get().receiveBytesPerSecond()),
                    formatter.bytesPerSecond(rates.get().sendBytesPerSecond()));
        } else {
            readout.showNoValues(MetricState.of(false, cycles(snapshot)));
        }
        SystemHistory history = snapshot.history();
        showRateGraph(readout, history.networkDownloadBytesPerSecond(), history.networkUploadBytesPerSecond());
    }

    private void showRateGraph(MetricReadout readout, MetricHistory first, MetricHistory second) {
        double maximum = AutoScale.maximumFor(AutoScale.largestSample(first, second));
        // A scale is stated only over a graph that has something drawn against it.
        String scale = AutoScale.hasSample(first, second)
                ? formatter.bytesPerSecond(maximum).text()
                : "";
        readout.showGraph(new GraphData(first, second, maximum), scale);
    }
}
