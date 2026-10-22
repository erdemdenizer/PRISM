package prism.srm.ui.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.AutoScale;
import prism.srm.ui.graph.GridData;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.SystemReadouts;

/**
 * The CPU section: utilization of the whole machine and of each logical processor, with their
 * 60-second histories, and the facts about the processor.
 *
 * <p>Utilization is busy time: the share of time a processor was not idle (ADR-0005).
 */
final class CpuModel implements SectionModel {

    private static final double PERCENT_SCALE = 100;

    private final ValueFormatter formatter;
    private final SystemReadouts readouts;
    private final MetricReadout total = new MetricReadout();
    private final ReadOnlyObjectWrapper<GridData> processors = new ReadOnlyObjectWrapper<>(GridData.EMPTY);
    private final ReadOnlyStringWrapper processorsStatus = new ReadOnlyStringWrapper(MetricState.WAITING.placeholder());
    private final ReadOnlyStringWrapper processorsScale = new ReadOnlyStringWrapper("");
    private final FactReadout name = new FactReadout();
    private final FactReadout logicalProcessors = new FactReadout();
    private final FactReadout physicalCores = new FactReadout();
    private final FactReadout packages = new FactReadout();

    CpuModel(ValueFormatter formatter, SystemReadouts readouts) {
        this.formatter = formatter;
        this.readouts = readouts;
    }

    /** Utilization of the whole machine. */
    MetricReadout total() {
        return total;
    }

    /** One cell per logical processor, in the processor order of the system, numbered from 0. */
    ReadOnlyObjectProperty<GridData> processorsProperty() {
        return processors.getReadOnlyProperty();
    }

    /** What to show in place of the cells while there are none; empty when there are cells. */
    ReadOnlyStringProperty processorsStatusProperty() {
        return processorsStatus.getReadOnlyProperty();
    }

    /** The value at the top of the scale of every cell; empty while no cell has a sample. */
    ReadOnlyStringProperty processorsScaleProperty() {
        return processorsScale.getReadOnlyProperty();
    }

    FactReadout name() {
        return name;
    }

    FactReadout logicalProcessors() {
        return logicalProcessors;
    }

    FactReadout physicalCores() {
        return physicalCores;
    }

    FactReadout packages() {
        return packages;
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        int cycles = SystemReadouts.cycles(snapshot);
        readouts.showCpu(total, snapshot);
        updateProcessors(snapshot.cpu(), snapshot.history().cpuPerProcessorPercent(), cycles);
        updateFacts(snapshot.processor(), cycles);
    }

    @Override
    public void showNothing() {
        SystemReadouts.showNothing(total);
        showProcessors(GridData.EMPTY, MetricState.UNAVAILABLE.placeholder(), "");
        name.showUnavailable();
        logicalProcessors.showUnavailable();
        physicalCores.showUnavailable();
        packages.showUnavailable();
    }

    /**
     * The histories say how many logical processors there are; the current values come from the
     * reading of this cycle, which can be missing as a whole or for single processors.
     */
    private void updateProcessors(Optional<CpuUsage> cpu, List<MetricHistory> histories, int cycles) {
        if (histories.isEmpty()) {
            showProcessors(GridData.EMPTY, MetricState.of(false, cycles).placeholder(), "");
            return;
        }
        List<OptionalDouble> current = cpu.map(CpuUsage::perProcessorPercent).orElse(List.of());
        List<GridData.Cell> cells = new ArrayList<>(histories.size());
        boolean anySample = false;
        for (int index = 0; index < histories.size(); index++) {
            MetricHistory history = histories.get(index);
            Quantity value = index < current.size() && current.get(index).isPresent()
                    ? formatter.wholePercent(current.get(index).getAsDouble())
                    : Quantity.MISSING;
            cells.add(new GridData.Cell(formatter.count(index), value.text(), history));
            anySample = anySample || AutoScale.hasSample(history);
        }
        // A scale is stated only over cells that have something drawn against it.
        showProcessors(
                new GridData(
                        cells,
                        PERCENT_SCALE,
                        formatter.wholePercent(PERCENT_SCALE).text()),
                "",
                anySample ? readouts.percentScale() : "");
    }

    private void showProcessors(GridData cells, String status, String scale) {
        if (!cells.equals(processors.get())) {
            processors.set(cells);
        }
        processorsStatus.set(status);
        processorsScale.set(scale);
    }

    /**
     * The facts are read once and kept. While they are not there yet, every one of them is being
     * read; once they are, a fact the system left out will not come later and is unavailable.
     */
    private void updateFacts(Optional<ProcessorInfo> processor, int cycles) {
        if (processor.isEmpty()) {
            for (FactReadout fact : new FactReadout[] {name, logicalProcessors, physicalCores, packages}) {
                fact.show(Optional.empty(), cycles);
            }
            return;
        }
        ProcessorInfo info = processor.get();
        showKnown(name, info.name());
        showKnown(logicalProcessors, Optional.of(formatter.count(info.logicalProcessorCount())));
        showKnown(physicalCores, count(info.physicalCoreCount()));
        showKnown(packages, count(info.packageCount()));
    }

    private Optional<String> count(OptionalInt count) {
        return count.isPresent() ? Optional.of(formatter.count(count.getAsInt())) : Optional.empty();
    }

    private static void showKnown(FactReadout fact, Optional<String> text) {
        if (text.isPresent()) {
            fact.show(text, 0);
        } else {
            fact.showUnavailable();
        }
    }
}
