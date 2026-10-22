package prism.srm.ui.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.GraphData;
import prism.srm.ui.graph.GridData;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.PageStatus.PageState;
import prism.srm.ui.overview.Snapshots;

/** Needs no JavaFX toolkit: the view model uses properties and observable lists only. */
class PerformanceViewModelTest {

    private static final String READING = "Reading\u2026";
    private static final String UNAVAILABLE = "Unavailable";
    private static final long GIB = PerformanceSnapshots.GIB;
    private static final long KIB = PerformanceSnapshots.KIB;

    private final PerformanceViewModel model = new PerformanceViewModel(new ValueFormatter(Locale.US));

    // The page as a whole

    @Test
    void beforeTheFirstSnapshotEveryPartIsBeingRead() {
        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LOADING);
        assertThat(model.status().errorProperty().get()).isEmpty();
        assertThat(model.selectedProperty().get()).isEqualTo(Section.CPU);
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(readout.statusProperty().get()).isEqualTo(READING);
            assertThat(readout.graphProperty().get()).isEqualTo(GraphData.EMPTY);
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(fact.textProperty().get()).isEqualTo(READING);
        }
        assertThat(model.cpu().processorsProperty().get()).isEqualTo(GridData.EMPTY);
        assertThat(model.cpu().processorsStatusProperty().get()).isEqualTo(READING);
        assertThat(model.disk().fileSystems()).isEmpty();
        assertThat(model.disk().fileSystemsStatusProperty().get()).isEqualTo(READING);
        assertThat(model.network().interfaces()).isEmpty();
        assertThat(model.network().interfacesStatusProperty().get()).isEqualTo(READING);
        assertThat(model.network().totalNoteProperty().get()).isEmpty();
    }

    // CPU

    @Test
    void theCpuSectionShowsTheTotalWithItsHistoryAgainstOneHundredPercent() {
        SystemSnapshot live = PerformanceSnapshots.live();

        model.update(live);

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LIVE);
        assertThat(model.cpu().total().valueProperty().get()).isEqualTo("12.4");
        assertThat(model.cpu().total().unitProperty().get()).isEqualTo("%");
        assertThat(model.cpu().total().scaleProperty().get()).isEqualTo("100 %");
        assertThat(model.cpu().total().graphProperty().get())
                .isEqualTo(GraphData.single(live.history().cpuTotalPercent(), 100));
    }

    @Test
    void everyLogicalProcessorHasACellWithItsNumberItsValueAndItsHistory() {
        SystemSnapshot live = PerformanceSnapshots.live(4);

        model.update(live);

        GridData grid = model.cpu().processorsProperty().get();
        assertThat(grid.maximum()).isEqualTo(100);
        assertThat(grid.cells()).extracting(GridData.Cell::label).containsExactly("0", "1", "2", "3");
        // 7.4, 20.4, skipped in this cycle, 46.4. What was skipped is shown as missing, not as zero.
        assertThat(grid.cells()).extracting(GridData.Cell::value).containsExactly("7 %", "20 %", "\u2013", "46 %");
        // Every cell keeps room for the widest value, so the cells do not move as values change.
        assertThat(grid.widestValue()).isEqualTo("100 %");
        assertThat(grid.cells())
                .extracting(GridData.Cell::history)
                .containsExactlyElementsOf(live.history().cpuPerProcessorPercent());
        assertThat(model.cpu().processorsStatusProperty().get()).isEmpty();
        assertThat(model.cpu().processorsScaleProperty().get()).isEqualTo("100 %");
    }

    @Test
    void sixtyFourLogicalProcessorsAreSixtyFourCells() {
        model.update(PerformanceSnapshots.extreme());

        GridData grid = model.cpu().processorsProperty().get();
        assertThat(grid.cells()).hasSize(64);
        assertThat(grid.cells().getFirst().label()).isEqualTo("0");
        assertThat(grid.cells().getLast().label()).isEqualTo("63");
        assertThat(grid.cells()).allSatisfy(cell -> assertThat(cell.value()).isEqualTo("100 %"));
    }

    /** The histories are there from the first cycle on; the values need a second reading. */
    @Test
    void inTheFirstCycleTheCellsAreThereWithoutValuesAndWithoutAScale() {
        model.update(PerformanceSnapshots.firstCycle());

        GridData grid = model.cpu().processorsProperty().get();
        assertThat(grid.cells()).hasSize(16);
        assertThat(grid.cells()).allSatisfy(cell -> assertThat(cell.value()).isEqualTo("\u2013"));
        assertThat(model.cpu().processorsStatusProperty().get()).isEmpty();
        // Nothing is drawn yet, so no scale is claimed.
        assertThat(model.cpu().processorsScaleProperty().get()).isEmpty();
        assertThat(model.cpu().total().stateProperty().get()).isEqualTo(MetricState.WAITING);
    }

    @Test
    void withoutAnyProcessorHistoryTheGridSaysWhyItIsEmpty() {
        model.update(Snapshots.firstCycle());
        assertThat(model.cpu().processorsProperty().get()).isEqualTo(GridData.EMPTY);
        assertThat(model.cpu().processorsStatusProperty().get()).isEqualTo(READING);

        model.update(Snapshots.nothingAvailable());
        assertThat(model.cpu().processorsProperty().get()).isEqualTo(GridData.EMPTY);
        assertThat(model.cpu().processorsStatusProperty().get()).isEqualTo(UNAVAILABLE);
        assertThat(model.cpu().processorsScaleProperty().get()).isEmpty();
    }

    /** A reading that fails for one cycle takes the current values and leaves the histories. */
    @Test
    void whenTheCpuReadingIsMissingTheCellsKeepTheirHistoriesAndLoseTheirValues() {
        SystemSnapshot live = PerformanceSnapshots.live(4);
        model.update(live);

        model.update(new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                Optional.empty(),
                live.memory(),
                live.fileSystems(),
                live.diskRates(),
                live.network(),
                live.activity(),
                live.history()));

        GridData grid = model.cpu().processorsProperty().get();
        assertThat(grid.cells()).hasSize(4);
        assertThat(grid.cells()).allSatisfy(cell -> assertThat(cell.value()).isEqualTo("\u2013"));
        assertThat(grid.cells())
                .extracting(GridData.Cell::history)
                .containsExactlyElementsOf(live.history().cpuPerProcessorPercent());
        assertThat(model.cpu().total().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
    }

    @Test
    void theProcessorFactsAreShownAsTheSystemReportsThem() {
        model.update(PerformanceSnapshots.live());

        assertThat(model.cpu().name().textProperty().get()).isEqualTo("AMD Ryzen 7 5800H with Radeon Graphics");
        assertThat(model.cpu().logicalProcessors().textProperty().get()).isEqualTo("16");
        assertThat(model.cpu().physicalCores().textProperty().get()).isEqualTo("8");
        assertThat(model.cpu().packages().textProperty().get()).isEqualTo("1");
        for (FactReadout fact : cpuFacts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.LIVE);
        }
    }

    /**
     * The facts are read once. One the system left out will not come with a later cycle, so it is
     * unavailable at once, also in the first cycle, and not "being read".
     */
    @Test
    void aProcessorFactTheSystemDoesNotReportIsUnavailableAtOnce() {
        SystemSnapshot first = PerformanceSnapshots.firstCycle();

        model.update(new SystemSnapshot(
                Optional.of(new ProcessorInfo(8, OptionalInt.empty(), OptionalInt.empty(), Optional.empty())),
                first.operatingSystem(),
                first.cpu(),
                first.memory(),
                first.fileSystems(),
                first.diskRates(),
                first.network(),
                first.activity(),
                first.history()));

        assertThat(model.cpu().logicalProcessors().textProperty().get()).isEqualTo("8");
        assertThat(model.cpu().logicalProcessors().stateProperty().get()).isEqualTo(MetricState.LIVE);
        for (FactReadout fact : List.of(
                model.cpu().name(), model.cpu().physicalCores(), model.cpu().packages())) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(fact.textProperty().get()).isEqualTo(UNAVAILABLE);
        }
    }

    @Test
    void whileTheProcessorFactsAreNotReadYetTheyAreBeingReadAndThenUnavailable() {
        MetricHistory one = MetricHistory.of(Double.NaN);
        model.update(new SystemSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SystemHistory(one, List.of(), one, one, one, one, one)));
        for (FactReadout fact : cpuFacts()) {
            assertThat(fact.textProperty().get()).isEqualTo(READING);
        }

        model.update(Snapshots.nothingAvailable());
        for (FactReadout fact : cpuFacts()) {
            assertThat(fact.textProperty().get()).isEqualTo(UNAVAILABLE);
        }
    }

    // Memory

    @Test
    void theMemorySectionShowsUseShareAvailableTotalAndSwap() {
        SystemSnapshot live = PerformanceSnapshots.live();
        model.select(Section.MEMORY);

        model.update(live);

        assertThat(model.memory().inUse().valueProperty().get()).isEqualTo("11.2");
        assertThat(model.memory().inUse().unitProperty().get()).isEqualTo("GiB");
        assertThat(model.memory().inUse().detailProperty().get()).isEqualTo("35.0 %");
        assertThat(model.memory().inUse().scaleProperty().get()).isEqualTo("32.0 GiB");
        assertThat(model.memory().inUse().graphProperty().get())
                .isEqualTo(GraphData.single(live.history().memoryUsedBytes(), 32.0 * GIB));
        assertThat(model.memory().available().textProperty().get()).isEqualTo("20.8 GiB");
        assertThat(model.memory().total().textProperty().get()).isEqualTo("32.0 GiB");
        assertThat(model.memory().swap().textProperty().get()).isEqualTo("1.3 GiB of 8.0 GiB");
    }

    /** No swap is a fact about the system, not a value that could not be read. */
    @Test
    void aSystemWithoutSwapSaysNoneAndNotUnavailable() {
        model.select(Section.MEMORY);

        model.update(PerformanceSnapshots.noSwap());

        assertThat(model.memory().swap().textProperty().get()).isEqualTo("None");
        assertThat(model.memory().swap().stateProperty().get()).isEqualTo(MetricState.LIVE);
    }

    @Test
    void whenMemoryCannotBeReadItsFactsAreUnavailableAndTheGraphKeepsItsScale() {
        model.select(Section.MEMORY);
        model.update(PerformanceSnapshots.live());

        model.update(Snapshots.liveWithoutMemory());

        assertThat(model.memory().inUse().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
        for (FactReadout fact : List.of(
                model.memory().available(),
                model.memory().total(),
                model.memory().swap())) {
            assertThat(fact.textProperty().get()).isEqualTo(UNAVAILABLE);
        }
        assertThat(model.memory().inUse().scaleProperty().get()).isEqualTo("32.0 GiB");
    }

    // Disk

    @Test
    void theDiskSectionShowsTheRatesOfThePhysicalDisksAndEveryFileSystem() {
        SystemSnapshot live = PerformanceSnapshots.live();
        model.select(Section.DISK);

        model.update(live);

        assertThat(model.disk().rates().valueProperty().get()).isEqualTo("1.5");
        assertThat(model.disk().rates().unitProperty().get()).isEqualTo("MiB/s");
        assertThat(model.disk().rates().secondValueProperty().get()).isEqualTo("512.0");
        assertThat(model.disk().rates().secondUnitProperty().get()).isEqualTo("KiB/s");
        assertThat(model.disk().rates().scaleProperty().get()).isEqualTo("2.0 MiB/s");
        assertThat(model.disk().fileSystems())
                .extracting(
                        row -> row.nameProperty().get(),
                        row -> row.mountPointProperty().get(),
                        row -> row.capacityProperty().get().text(),
                        row -> row.availableProperty().get().text())
                .containsExactly(
                        tuple("Local Fixed Disk (C:)", "C:\\", "475.0 GiB", "181.3 GiB"),
                        tuple("Local Fixed Disk (D:)", "D:\\", "931.0 GiB", "512.0 GiB"),
                        tuple("Removable Disk (E:)", "E:\\", "29.5 GiB", "4.0 MiB"));
    }

    /** From one cycle to the next the rows stay; the table then keeps its cells and its selection. */
    @Test
    void theSameFileSystemsKeepTheirRowsAndOnlyTheTextsChange() {
        model.select(Section.DISK);
        model.update(PerformanceSnapshots.live());
        List<FileSystemRow> before = List.copyOf(model.disk().fileSystems());
        AtomicInteger listChanges = new AtomicInteger();
        model.disk().fileSystems().addListener((ListChangeListener<FileSystemRow>)
                change -> listChanges.incrementAndGet());

        model.update(withFileSystems(
                PerformanceSnapshots.live(),
                List.of(
                        new FileSystemUsage("Local Fixed Disk (C:)", "C:\\", 475 * GIB, 100 * GIB),
                        new FileSystemUsage("Local Fixed Disk (D:)", "D:\\", 931 * GIB, 512 * GIB),
                        new FileSystemUsage("Removable Disk (E:)", "E:\\", 29 * GIB, 0))));

        assertThat(listChanges).hasValue(0);
        assertThat(model.disk().fileSystems()).hasSize(3);
        for (int index = 0; index < 3; index++) {
            assertThat(model.disk().fileSystems().get(index)).isSameAs(before.get(index));
        }
        assertThat(model.disk()
                        .fileSystems()
                        .getFirst()
                        .availableProperty()
                        .get()
                        .text())
                .isEqualTo("100.0 GiB");
        assertThat(model.disk()
                        .fileSystems()
                        .getLast()
                        .availableProperty()
                        .get()
                        .text())
                .isEqualTo("0 B");
    }

    @Test
    void aFileSystemThatAppearsOrDisappearsReplacesTheRows() {
        model.select(Section.DISK);
        model.update(PerformanceSnapshots.live());

        model.update(withFileSystems(
                PerformanceSnapshots.live(),
                List.of(new FileSystemUsage("Local Fixed Disk (D:)", "D:\\", 931 * GIB, 512 * GIB))));

        assertThat(model.disk().fileSystems())
                .extracting(row -> row.mountPointProperty().get())
                .containsExactly("D:\\");
    }

    /** Two file systems may carry the same label; the mount point tells them apart. */
    @Test
    void fileSystemsWithTheSameNameAreToldApartByTheirMountPoint() {
        model.select(Section.DISK);

        model.update(withFileSystems(
                PerformanceSnapshots.live(),
                List.of(new FileSystemUsage("tmpfs", "/run", GIB, GIB), new FileSystemUsage("tmpfs", "/tmp", GIB, 0))));
        FileSystemRow tmp = model.disk().fileSystems().get(1);
        // The same two in the other order: nothing may be written into the row of the other one.
        model.update(withFileSystems(
                PerformanceSnapshots.live(),
                List.of(new FileSystemUsage("tmpfs", "/tmp", GIB, 0), new FileSystemUsage("tmpfs", "/run", GIB, GIB))));

        assertThat(model.disk().fileSystems().getFirst()).isNotSameAs(tmp);
        assertThat(model.disk().fileSystems())
                .extracting(
                        row -> row.mountPointProperty().get(),
                        row -> row.availableProperty().get().text())
                .containsExactly(tuple("/tmp", "0 B"), tuple("/run", "1.0 GiB"));
    }

    @Test
    void aSystemThatListsNoFileSystemSaysSo() {
        model.select(Section.DISK);

        model.update(PerformanceSnapshots.noDevices());

        assertThat(model.disk().fileSystems()).isEmpty();
        assertThat(model.disk().fileSystemsStatusProperty().get()).isEqualTo("No local file system is reported.");
    }

    /** Capacities left in the table after a failed reading would look current. */
    @Test
    void whenTheFileSystemsCannotBeReadTheRowsGoAndTheTableSaysUnavailable() {
        model.select(Section.DISK);
        model.update(PerformanceSnapshots.live());

        model.update(Snapshots.live());

        assertThat(model.disk().fileSystems()).isEmpty();
        assertThat(model.disk().fileSystemsStatusProperty().get()).isEqualTo(UNAVAILABLE);
        // The rates are another reading and are still there.
        assertThat(model.disk().rates().stateProperty().get()).isEqualTo(MetricState.LIVE);
    }

    @Test
    void withoutADiskTotalTheRatesAreUnavailableAndTheFileSystemsAreStillListed() {
        model.select(Section.DISK);

        model.update(PerformanceSnapshots.unknownLayers());

        assertThat(model.disk().rates().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(model.disk().rates().scaleProperty().get()).isEmpty();
        assertThat(model.disk().fileSystems()).hasSize(2);
        // The reason is on the page, not only in a tooltip, and claims no more than is known.
        assertThat(model.disk().totalNoteProperty().get())
                .isEqualTo("No total: the system may not say which disks are physical.");
    }

    @Test
    void whileThereIsADiskTotalOrItIsStillAwaitedNothingIsSaidAboutAMissingOne() {
        model.select(Section.DISK);

        model.update(PerformanceSnapshots.firstCycle());
        assertThat(model.disk().rates().stateProperty().get()).isEqualTo(MetricState.WAITING);
        assertThat(model.disk().totalNoteProperty().get()).isEmpty();

        model.update(PerformanceSnapshots.live());
        assertThat(model.disk().rates().stateProperty().get()).isEqualTo(MetricState.LIVE);
        assertThat(model.disk().totalNoteProperty().get()).isEmpty();
    }

    // Network

    @Test
    void theNetworkSectionShowsTheTotalOfThePhysicalAdaptersWithItsHistory() {
        SystemSnapshot live = PerformanceSnapshots.live();
        model.select(Section.NETWORK);

        model.update(live);

        assertThat(model.network().rates().valueProperty().get()).isEqualTo("240.0");
        assertThat(model.network().rates().unitProperty().get()).isEqualTo("KiB/s");
        assertThat(model.network().rates().secondValueProperty().get()).isEqualTo("12.0");
        assertThat(model.network().rates().scaleProperty().get()).isEqualTo("500.0 KiB/s");
        assertThat(model.network().rates().graphProperty().get().primary())
                .isEqualTo(live.history().networkDownloadBytesPerSecond());
        assertThat(model.network().rates().graphProperty().get().secondary())
                .isEqualTo(live.history().networkUploadBytesPerSecond());
        assertThat(model.network().totalNoteProperty().get()).isEqualTo("The total counts physical adapters only.");
    }

    /** Every interface is listed, the physical adapters first, otherwise in the order of the system. */
    @Test
    void everyInterfaceIsListedWithItsOwnRatesPhysicalAdaptersFirst() {
        model.select(Section.NETWORK);

        model.update(PerformanceSnapshots.live());

        assertThat(model.network().interfaces())
                .extracting(
                        row -> row.displayNameProperty().get(),
                        row -> row.layerProperty().get(),
                        row -> row.receiveProperty().get().text(),
                        row -> row.sendProperty().get().text())
                .containsExactly(
                        tuple("Ethernet", "Physical", "200.0 KiB/s", "9.0 KiB/s"),
                        tuple("Wi-Fi", "Physical", "40.0 KiB/s", "3.0 KiB/s"),
                        tuple(
                                "Ethernet-WFP Native MAC Layer LightWeight Filter-0000",
                                "Logical",
                                "200.0 KiB/s",
                                "9.0 KiB/s"),
                        // No rate yet: shown as missing, never as zero.
                        tuple("vEthernet (Default Switch)", "Logical", "\u2013", "\u2013"),
                        // A rate of zero is a value.
                        tuple("Loopback Pseudo-Interface 1", "Logical", "0 B/s", "0 B/s"),
                        tuple("WireGuard Tunnel", "Logical", "1.5 MiB/s", "512.0 KiB/s"));
    }

    @Test
    void theSameInterfacesKeepTheirRowsAndOnlyTheTextsChange() {
        model.select(Section.NETWORK);
        model.update(PerformanceSnapshots.live());
        List<InterfaceRow> before = List.copyOf(model.network().interfaces());
        AtomicInteger listChanges = new AtomicInteger();
        model.network().interfaces().addListener((ListChangeListener<InterfaceRow>)
                change -> listChanges.incrementAndGet());

        model.update(withNetwork(
                PerformanceSnapshots.live(),
                new NetworkUsage(
                        PerformanceSnapshots.network().interfaces().stream()
                                .map(networkInterface -> new NetworkInterfaceUsage(
                                        networkInterface.name(),
                                        networkInterface.displayName(),
                                        networkInterface.layer(),
                                        Optional.of(new NetworkRates(7 * KIB, 0))))
                                .toList(),
                        Optional.of(new NetworkRates(14 * KIB, 0)))));

        assertThat(listChanges).hasValue(0);
        assertThat(model.network().interfaces()).hasSameSizeAs(before);
        for (int index = 0; index < before.size(); index++) {
            assertThat(model.network().interfaces().get(index)).isSameAs(before.get(index));
        }
        assertThat(model.network().interfaces())
                .allSatisfy(
                        row -> assertThat(row.receiveProperty().get().text()).isEqualTo("7.0 KiB/s"));
    }

    @Test
    void aSystemWithoutANetworkInterfaceSaysSoAndHasNoTotal() {
        model.select(Section.NETWORK);

        model.update(PerformanceSnapshots.noDevices());

        assertThat(model.network().interfaces()).isEmpty();
        assertThat(model.network().interfacesStatusProperty().get()).isEqualTo("No network interface is reported.");
        assertThat(model.network().rates().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(model.network().totalNoteProperty().get()).isEmpty();
    }

    /** As on Linux and macOS for now: every interface has its rates, and the page says why no total. */
    @Test
    void whenTheSystemDoesNotSayWhichInterfacesArePhysicalThereIsNoTotalAndThePageSaysWhy() {
        model.select(Section.NETWORK);

        model.update(PerformanceSnapshots.unknownLayers());

        assertThat(model.network().rates().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(model.network().totalNoteProperty().get())
                .isEqualTo("No total: the system does not say which interfaces are physical.");
        assertThat(model.network().interfaces())
                .extracting(
                        row -> row.displayNameProperty().get(),
                        row -> row.layerProperty().get(),
                        row -> row.receiveProperty().get().text())
                .containsExactly(
                        tuple("lo", "Unknown", "48.0 KiB/s"),
                        tuple("enp5s0", "Unknown", "240.0 KiB/s"),
                        tuple("docker0", "Unknown", "0 B/s"));
    }

    /**
     * Whether there is a total is decided where the rates are computed. With physical adapters
     * listed and no total, as in the first cycle or when an adapter came or went, the page must
     * not claim that there is one, and it has no reason to give.
     */
    @Test
    void withPhysicalAdaptersAndNoTotalThePageNeitherClaimsATotalNorInventsAReason() {
        model.select(Section.NETWORK);

        model.update(PerformanceSnapshots.firstCycle());

        assertThat(model.network().interfaces()).hasSize(2);
        assertThat(model.network().rates().stateProperty().get()).isEqualTo(MetricState.WAITING);
        assertThat(model.network().totalNoteProperty().get()).isEmpty();
    }

    @Test
    void whenNoInterfaceIsPhysicalThePageSaysThatNoPhysicalAdapterIsListed() {
        model.select(Section.NETWORK);

        model.update(withNetwork(
                PerformanceSnapshots.live(),
                new NetworkUsage(
                        List.of(new NetworkInterfaceUsage(
                                "loopback0", "Loopback Pseudo-Interface 1", DeviceLayer.LOGICAL, Optional.empty())),
                        Optional.empty())));

        assertThat(model.network().totalNoteProperty().get()).isEqualTo("No total: no physical adapter is listed.");
    }

    @Test
    void whenTheNetworkCannotBeReadTheRowsGoAndTheTableSaysUnavailable() {
        model.select(Section.NETWORK);
        model.update(PerformanceSnapshots.live());

        model.update(Snapshots.nothingAvailable());

        assertThat(model.network().interfaces()).isEmpty();
        assertThat(model.network().interfacesStatusProperty().get()).isEqualTo(UNAVAILABLE);
        assertThat(model.network().totalNoteProperty().get()).isEmpty();
    }

    // Selection

    /** A section that is not on screen costs nothing: it is not brought up to date. */
    @Test
    void onlyTheSelectedSectionIsBroughtUpToDate() {
        model.update(PerformanceSnapshots.live());

        assertThat(model.cpu().total().stateProperty().get()).isEqualTo(MetricState.LIVE);
        assertThat(model.memory().inUse().stateProperty().get()).isEqualTo(MetricState.WAITING);
        assertThat(model.disk().fileSystems()).isEmpty();
        assertThat(model.network().interfaces()).isEmpty();
    }

    @Test
    void selectingASectionFillsItFromTheNewestSnapshotBeforeItIsShown() {
        model.update(PerformanceSnapshots.firstCycle());
        model.update(PerformanceSnapshots.live());
        List<Integer> rowsWhenShown = new ArrayList<>();
        model.selectedProperty()
                .addListener((observable, before, after) ->
                        rowsWhenShown.add(model.network().interfaces().size()));

        model.select(Section.NETWORK);

        assertThat(model.selectedProperty().get()).isEqualTo(Section.NETWORK);
        assertThat(rowsWhenShown).containsExactly(6);
        assertThat(model.network().rates().valueProperty().get()).isEqualTo("240.0");
    }

    /** What a section held when it was last on screen must not be what it shows on return. */
    @Test
    void aSectionThatWasAwayShowsTheNewestSnapshotOnReturn() {
        model.update(PerformanceSnapshots.live());
        model.select(Section.MEMORY);
        model.update(PerformanceSnapshots.extreme());

        model.select(Section.CPU);

        assertThat(model.cpu().total().valueProperty().get()).isEqualTo("100.0");
        assertThat(model.cpu().processorsProperty().get().cells()).hasSize(64);
    }

    @Test
    void selectingTheSelectedSectionChangesNothing() {
        model.update(PerformanceSnapshots.live());
        AtomicInteger changes = new AtomicInteger();
        for (ObservableValue<?> property : everyProperty()) {
            property.addListener((observable, before, after) -> changes.incrementAndGet());
        }

        model.select(Section.CPU);

        assertThat(changes).hasValue(0);
    }

    @Test
    void selectingBeforeTheFirstSnapshotLeavesTheSectionBeingRead() {
        model.select(Section.DISK);

        assertThat(model.selectedProperty().get()).isEqualTo(Section.DISK);
        assertThat(model.disk().rates().stateProperty().get()).isEqualTo(MetricState.WAITING);
        assertThat(model.disk().fileSystemsStatusProperty().get()).isEqualTo(READING);
    }

    // Updates that change nothing

    /** One update per second must not touch the scene for values that did not change. */
    @Test
    void anIdenticalSnapshotChangesNothingInAnySection() {
        for (Section section : Section.values()) {
            model.select(section);
            model.update(PerformanceSnapshots.live());
            AtomicInteger changes = new AtomicInteger();
            for (ObservableValue<?> property : everyProperty()) {
                property.addListener((observable, before, after) -> changes.incrementAndGet());
            }
            model.disk().fileSystems().addListener((ListChangeListener<FileSystemRow>)
                    change -> changes.incrementAndGet());
            model.network().interfaces().addListener((ListChangeListener<InterfaceRow>)
                    change -> changes.incrementAndGet());

            model.update(PerformanceSnapshots.live());

            assertThat(changes).as("changes in section %s", section).hasValue(0);
        }
    }

    // Locale and extremes

    @Test
    void numbersFollowTheLocaleOfTheFormatter() {
        PerformanceViewModel turkish = new PerformanceViewModel(new ValueFormatter(Locale.forLanguageTag("tr-TR")));

        turkish.update(PerformanceSnapshots.live());
        turkish.select(Section.MEMORY);
        turkish.select(Section.DISK);
        turkish.select(Section.NETWORK);

        assertThat(turkish.cpu().total().valueProperty().get()).isEqualTo("12,4");
        assertThat(turkish.memory().available().textProperty().get()).isEqualTo("20,8 GiB");
        assertThat(turkish.memory().swap().textProperty().get()).isEqualTo("1,3 GiB of 8,0 GiB");
        assertThat(turkish.disk()
                        .fileSystems()
                        .getFirst()
                        .capacityProperty()
                        .get()
                        .text())
                .isEqualTo("475,0 GiB");
        assertThat(turkish.network()
                        .interfaces()
                        .getFirst()
                        .receiveProperty()
                        .get()
                        .text())
                .isEqualTo("200,0 KiB/s");
    }

    @Test
    void extremeValuesAreFormattedLikeAnyOther() {
        model.update(PerformanceSnapshots.extreme());
        model.select(Section.MEMORY);
        model.select(Section.DISK);
        model.select(Section.NETWORK);

        assertThat(model.cpu().total().valueProperty().get()).isEqualTo("100.0");
        assertThat(model.cpu().logicalProcessors().textProperty().get()).isEqualTo("64");
        assertThat(model.memory().inUse().detailProperty().get()).isEqualTo("100.0 %");
        assertThat(model.memory().available().textProperty().get()).isEqualTo("0 B");
        assertThat(model.memory().swap().textProperty().get()).isEqualTo("1.0 TiB of 1.0 TiB");
        assertThat(model.disk()
                        .fileSystems()
                        .getFirst()
                        .capacityProperty()
                        .get()
                        .text())
                .isEqualTo("8.0 PiB");
        assertThat(model.disk()
                        .fileSystems()
                        .getLast()
                        .availableProperty()
                        .get()
                        .text())
                .isEqualTo("1023.9 GiB");
        assertThat(model.network().interfaces()).hasSize(36);
        assertThat(model.network()
                        .interfaces()
                        .getFirst()
                        .receiveProperty()
                        .get()
                        .text())
                .isEqualTo("1023.9 MiB/s");
    }

    // Monitoring that is not running

    @Test
    void aFailedStartIsSaidAndEverySectionShowsNothing() {
        model.startupFailed("C:\\logs");

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("Monitoring could not start")
                .contains("Restart Prism")
                .contains("C:\\logs");
        assertNothingIsShown();
    }

    /**
     * System monitoring ended while values were on screen. Left there, the last values, the
     * histories and the rows would look current, in the section on screen and in the others.
     */
    @Test
    void whenSystemMonitoringHasEndedNoValueHistoryOrRowIsLeftInAnySection() {
        for (Section section : Section.values()) {
            model.select(section);
            model.update(PerformanceSnapshots.live());
        }

        model.monitoringStopped("C:\\logs");

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("System monitoring stopped unexpectedly")
                .contains("C:\\logs");
        assertNothingIsShown();
    }

    /** The snapshot from before the end must not come back with the next selection. */
    @Test
    void afterMonitoringHasEndedSelectingASectionBringsNoValuesBack() {
        model.update(PerformanceSnapshots.live());
        model.monitoringStopped("C:\\logs");

        model.select(Section.NETWORK);
        model.select(Section.CPU);

        assertNothingIsShown();
    }

    private void assertNothingIsShown() {
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(readout.statusProperty().get()).isEqualTo(UNAVAILABLE);
            assertThat(readout.valueProperty().get()).isEmpty();
            assertThat(readout.secondValueProperty().get()).isEmpty();
            assertThat(readout.graphProperty().get()).isEqualTo(GraphData.EMPTY);
            assertThat(readout.scaleProperty().get()).isEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(fact.textProperty().get()).isEqualTo(UNAVAILABLE);
        }
        assertThat(model.cpu().processorsProperty().get()).isEqualTo(GridData.EMPTY);
        assertThat(model.cpu().processorsStatusProperty().get()).isEqualTo(UNAVAILABLE);
        assertThat(model.cpu().processorsScaleProperty().get()).isEmpty();
        assertThat(model.disk().fileSystems()).isEmpty();
        assertThat(model.disk().fileSystemsStatusProperty().get()).isEqualTo(UNAVAILABLE);
        assertThat(model.disk().totalNoteProperty().get()).isEmpty();
        assertThat(model.network().interfaces()).isEmpty();
        assertThat(model.network().interfacesStatusProperty().get()).isEqualTo(UNAVAILABLE);
        assertThat(model.network().totalNoteProperty().get()).isEmpty();
    }

    private List<MetricReadout> readouts() {
        return List.of(
                model.cpu().total(),
                model.memory().inUse(),
                model.disk().rates(),
                model.network().rates());
    }

    private List<FactReadout> cpuFacts() {
        return List.of(
                model.cpu().name(),
                model.cpu().logicalProcessors(),
                model.cpu().physicalCores(),
                model.cpu().packages());
    }

    private List<FactReadout> facts() {
        List<FactReadout> facts = new ArrayList<>(cpuFacts());
        facts.add(model.memory().available());
        facts.add(model.memory().total());
        facts.add(model.memory().swap());
        return facts;
    }

    private List<ObservableValue<?>> everyProperty() {
        List<ObservableValue<?>> properties = new ArrayList<>();
        properties.add(model.status().stateProperty());
        properties.add(model.status().errorProperty());
        properties.add(model.selectedProperty());
        properties.add(model.cpu().processorsProperty());
        properties.add(model.cpu().processorsStatusProperty());
        properties.add(model.cpu().processorsScaleProperty());
        properties.add(model.disk().fileSystemsStatusProperty());
        properties.add(model.disk().totalNoteProperty());
        properties.add(model.network().interfacesStatusProperty());
        properties.add(model.network().totalNoteProperty());
        for (FactReadout fact : facts()) {
            properties.add(fact.stateProperty());
            properties.add(fact.textProperty());
        }
        for (MetricReadout readout : readouts()) {
            properties.add(readout.stateProperty());
            properties.add(readout.statusProperty());
            properties.add(readout.valueProperty());
            properties.add(readout.unitProperty());
            properties.add(readout.detailProperty());
            properties.add(readout.secondValueProperty());
            properties.add(readout.secondUnitProperty());
            properties.add(readout.scaleProperty());
            properties.add(readout.graphProperty());
        }
        for (FileSystemRow row : model.disk().fileSystems()) {
            properties.add(row.nameProperty());
            properties.add(row.mountPointProperty());
            properties.add(row.capacityProperty());
            properties.add(row.availableProperty());
        }
        for (InterfaceRow row : model.network().interfaces()) {
            properties.add(row.displayNameProperty());
            properties.add(row.layerProperty());
            properties.add(row.receiveProperty());
            properties.add(row.sendProperty());
        }
        return properties;
    }

    private static SystemSnapshot withFileSystems(SystemSnapshot base, List<FileSystemUsage> fileSystems) {
        return new SystemSnapshot(
                base.processor(),
                base.operatingSystem(),
                base.cpu(),
                base.memory(),
                Optional.of(fileSystems),
                base.diskRates(),
                base.network(),
                base.activity(),
                base.history());
    }

    private static SystemSnapshot withNetwork(SystemSnapshot base, NetworkUsage network) {
        return new SystemSnapshot(
                base.processor(),
                base.operatingSystem(),
                base.cpu(),
                base.memory(),
                base.fileSystems(),
                base.diskRates(),
                Optional.of(network),
                base.activity(),
                base.history());
    }
}
