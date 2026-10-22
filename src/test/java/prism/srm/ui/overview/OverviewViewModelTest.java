package prism.srm.ui.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.value.ObservableValue;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.GraphData;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.PageStatus.PageState;

/** Needs no JavaFX toolkit: the view model uses properties only. */
class OverviewViewModelTest {

    private static final String READING = "Reading\u2026";

    private final OverviewViewModel model = new OverviewViewModel(new ValueFormatter(Locale.US));

    @Test
    void beforeTheFirstSnapshotEveryPartIsBeingRead() {
        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LOADING);
        assertThat(model.status().errorProperty().get()).isEmpty();
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(readout.statusProperty().get()).isEqualTo(READING);
            assertThat(readout.graphProperty().get()).isEqualTo(GraphData.EMPTY);
            assertThat(readout.scaleProperty().get()).isEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(fact.textProperty().get()).isEqualTo(READING);
        }
    }

    /** Rates need two readings. Their absence from the first cycle must not read as a failure. */
    @Test
    void theFirstSnapshotShowsWhatItHasAndWaitsForTheRates() {
        model.update(Snapshots.firstCycle());

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LIVE);
        assertThat(model.memory().stateProperty().get()).isEqualTo(MetricState.LIVE);
        assertThat(model.memory().valueProperty().get()).isEqualTo("11.2");
        assertThat(model.memory().scaleProperty().get()).isEqualTo("32.0 GiB");
        for (MetricReadout rate : List.of(model.cpu(), model.disk(), model.network())) {
            assertThat(rate.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(rate.statusProperty().get()).isEqualTo(READING);
            assertThat(rate.valueProperty().get()).isEmpty();
            // Nothing is drawn yet, so no scale is claimed either.
            assertThat(rate.scaleProperty().get()).isEmpty();
        }
        assertThat(model.operatingSystem().textProperty().get()).isEqualTo("Microsoft Windows 11 (build 26200)");
        assertThat(model.operatingSystem().stateProperty().get()).isEqualTo(MetricState.LIVE);
        assertThat(model.uptime().textProperty().get()).isEqualTo("04:12:55");
        assertThat(model.processCount().textProperty().get()).isEqualTo("392");
    }

    @Test
    void aLiveSnapshotShowsEveryValueWithItsUnit() {
        model.update(Snapshots.live());

        assertThat(model.cpu().stateProperty().get()).isEqualTo(MetricState.LIVE);
        assertThat(model.cpu().statusProperty().get()).isEmpty();
        assertThat(model.cpu().valueProperty().get()).isEqualTo("12.4");
        assertThat(model.cpu().unitProperty().get()).isEqualTo("%");
        assertThat(model.cpu().scaleProperty().get()).isEqualTo("100 %");

        assertThat(model.memory().valueProperty().get()).isEqualTo("11.2");
        assertThat(model.memory().unitProperty().get()).isEqualTo("GiB");
        assertThat(model.memory().detailProperty().get()).isEqualTo("35.0 %");
        assertThat(model.memory().scaleProperty().get()).isEqualTo("32.0 GiB");

        assertThat(model.disk().valueProperty().get()).isEqualTo("1.5");
        assertThat(model.disk().unitProperty().get()).isEqualTo("MiB/s");
        assertThat(model.disk().secondValueProperty().get()).isEqualTo("512.0");
        assertThat(model.disk().secondUnitProperty().get()).isEqualTo("KiB/s");

        assertThat(model.network().valueProperty().get()).isEqualTo("240.0");
        assertThat(model.network().unitProperty().get()).isEqualTo("KiB/s");
        assertThat(model.network().secondValueProperty().get()).isEqualTo("12.0");
        assertThat(model.network().secondUnitProperty().get()).isEqualTo("KiB/s");

        assertThat(model.uptime().textProperty().get()).isEqualTo("3 d 04:12:55");
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.LIVE);
        }
    }

    @Test
    void theGraphsGetTheHistoriesOfTheSnapshotAgainstTheirScales() {
        SystemSnapshot live = Snapshots.live();

        model.update(live);

        assertThat(model.cpu().graphProperty().get())
                .isEqualTo(GraphData.single(live.history().cpuTotalPercent(), 100));
        assertThat(model.memory().graphProperty().get())
                .isEqualTo(GraphData.single(live.history().memoryUsedBytes(), 32.0 * Snapshots.GIB));
        // The largest disk sample is 1.5 MiB/s; the next step of the ladder is 2 MiB/s.
        assertThat(model.disk().graphProperty().get())
                .isEqualTo(new GraphData(
                        live.history().diskReadBytesPerSecond(),
                        live.history().diskWriteBytesPerSecond(),
                        2.0 * Snapshots.MIB));
        assertThat(model.disk().scaleProperty().get()).isEqualTo("2.0 MiB/s");
        assertThat(model.network().graphProperty().get().primary())
                .isEqualTo(live.history().networkDownloadBytesPerSecond());
        assertThat(model.network().graphProperty().get().secondary())
                .isEqualTo(live.history().networkUploadBytesPerSecond());
        assertThat(model.network().scaleProperty().get()).isEqualTo("500.0 KiB/s");
    }

    @Test
    void numbersFollowTheLocaleOfTheFormatter() {
        OverviewViewModel turkish = new OverviewViewModel(new ValueFormatter(Locale.forLanguageTag("tr-TR")));

        turkish.update(Snapshots.live());

        assertThat(turkish.cpu().valueProperty().get()).isEqualTo("12,4");
        assertThat(turkish.memory().detailProperty().get()).isEqualTo("35,0 %");
        assertThat(turkish.disk().valueProperty().get()).isEqualTo("1,5");
        assertThat(turkish.disk().scaleProperty().get()).isEqualTo("2,0 MiB/s");
    }

    @Test
    void whatTheSystemDoesNotReportAfterTheFirstCyclesIsUnavailable() {
        model.update(Snapshots.nothingAvailable());

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.LIVE);
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(readout.statusProperty().get()).isEqualTo("Unavailable");
            assertThat(readout.valueProperty().get()).isEmpty();
            assertThat(readout.unitProperty().get()).isEmpty();
            assertThat(readout.secondValueProperty().get()).isEmpty();
            assertThat(readout.secondUnitProperty().get()).isEmpty();
            // No sample was ever drawn, so no graph states a scale.
            assertThat(readout.scaleProperty().get()).isEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(fact.textProperty().get()).isEqualTo("Unavailable");
        }
        // The memory total was never seen, so its graph has no scale to draw against.
        assertThat(model.memory().graphProperty().get().maximum()).isZero();
    }

    @Test
    void aValueThatGoesMissingLosesItsReadoutAndKeepsItsGraph() {
        model.update(Snapshots.live());

        model.update(Snapshots.liveWithoutMemory());

        assertThat(model.memory().stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(model.memory().valueProperty().get()).isEmpty();
        assertThat(model.memory().detailProperty().get()).isEmpty();
        // The scale is the total last seen; the history still shows what was measured before.
        assertThat(model.memory().graphProperty().get().maximum()).isEqualTo(32.0 * Snapshots.GIB);
        assertThat(model.memory().scaleProperty().get()).isEqualTo("32.0 GiB");
        assertThat(model.cpu().stateProperty().get()).isEqualTo(MetricState.LIVE);
    }

    @Test
    void aValueThatComesBackIsShownAgain() {
        model.update(Snapshots.nothingAvailable());

        model.update(Snapshots.live());

        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.LIVE);
            assertThat(readout.statusProperty().get()).isEmpty();
            assertThat(readout.valueProperty().get()).isNotEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.LIVE);
        }
    }

    @Test
    void aFailedStartIsSaidWithWhatTheUserCanDoAndWhereTheLogIs() {
        model.startupFailed("C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs");

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("Monitoring could not start")
                .contains("Restart Prism")
                .contains("C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs");
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(readout.statusProperty().get()).isEqualTo("Unavailable");
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(fact.textProperty().get()).isEqualTo("Unavailable");
        }
    }

    /**
     * System monitoring ended while values were on screen. Left there, the last values and the
     * histories would look current, so they are taken away and the page says what happened.
     */
    @Test
    void whenSystemMonitoringHasEndedNoValueAndNoHistoryIsLeftOnThePage() {
        model.update(Snapshots.live());

        model.monitoringStopped("C:\\logs");

        assertThat(model.status().stateProperty().get()).isEqualTo(PageState.ERROR);
        assertThat(model.status().errorProperty().get())
                .startsWith("System monitoring stopped unexpectedly")
                .contains("Restart Prism")
                .contains("C:\\logs");
        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(readout.statusProperty().get()).isEqualTo("Unavailable");
            assertThat(readout.valueProperty().get()).isEmpty();
            assertThat(readout.secondValueProperty().get()).isEmpty();
            assertThat(readout.graphProperty().get()).isEqualTo(GraphData.EMPTY);
            assertThat(readout.scaleProperty().get()).isEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.UNAVAILABLE);
            assertThat(fact.textProperty().get()).isEqualTo("Unavailable");
        }
    }

    @Test
    void tellingThePageAgainThatMonitoringHasEndedChangesNothing() {
        model.update(Snapshots.live());
        model.monitoringStopped("C:\\logs");
        AtomicInteger changes = new AtomicInteger();
        for (ObservableValue<?> property : everyProperty()) {
            property.addListener((observable, before, after) -> changes.incrementAndGet());
        }

        model.monitoringStopped("C:\\logs");

        assertThat(changes).hasValue(0);
    }

    /** The two failures are told apart in words: one never ran, the other ran and ended. */
    @Test
    void aFailedStartAndAnEndWhileRunningAreSaidDifferently() {
        OverviewViewModel neverStarted = new OverviewViewModel(new ValueFormatter(Locale.US));
        neverStarted.startupFailed("C:\\logs");
        model.monitoringStopped("C:\\logs");

        assertThat(neverStarted.status().errorProperty().get()).startsWith("Monitoring could not start");
        assertThat(model.status().errorProperty().get()).startsWith("System monitoring stopped unexpectedly");
    }

    /** One update per second must not touch the scene for values that did not change. */
    @Test
    void anIdenticalSnapshotChangesNothing() {
        model.update(Snapshots.live());
        AtomicInteger changes = new AtomicInteger();
        for (ObservableValue<?> property : everyProperty()) {
            property.addListener((observable, before, after) -> changes.incrementAndGet());
        }

        model.update(Snapshots.live());

        assertThat(changes).hasValue(0);
    }

    @Test
    void aChangedValueChangesOnlyItsOwnProperties() {
        model.update(Snapshots.live());
        AtomicInteger memoryChanges = new AtomicInteger();
        AtomicInteger cpuChanges = new AtomicInteger();
        model.memory().stateProperty().addListener((observable, before, after) -> memoryChanges.incrementAndGet());
        model.cpu().valueProperty().addListener((observable, before, after) -> cpuChanges.incrementAndGet());
        model.cpu().stateProperty().addListener((observable, before, after) -> cpuChanges.incrementAndGet());

        model.update(Snapshots.liveWithoutMemory());

        assertThat(memoryChanges).hasValue(1);
        assertThat(cpuChanges).hasValue(0);
    }

    @Test
    void extremeValuesAreFormattedLikeAnyOther() {
        model.update(Snapshots.extreme());

        assertThat(model.cpu().valueProperty().get()).isEqualTo("100.0");
        assertThat(model.memory().valueProperty().get()).isEqualTo("1.0");
        assertThat(model.memory().unitProperty().get()).isEqualTo("TiB");
        assertThat(model.memory().detailProperty().get()).isEqualTo("100.0 %");
        assertThat(model.disk().valueProperty().get()).isEqualTo("1023.9");
        assertThat(model.disk().unitProperty().get()).isEqualTo("GiB/s");
        assertThat(model.disk().scaleProperty().get()).isEqualTo("1.0 TiB/s");
        assertThat(model.uptime().textProperty().get()).isEqualTo("400 d 23:59:59");
        assertThat(model.processCount().textProperty().get()).isEqualTo("99,999");
    }

    /** An idle disk is data: zero is a sample, and the graph states the scale it is drawn against. */
    @Test
    void aRateOfZeroIsASampleAndHasAScale() {
        MetricHistory idle = MetricHistory.of(0, 0, 0);
        MetricHistory skipped = MetricHistory.of(Double.NaN, Double.NaN, Double.NaN);
        SystemSnapshot idleDisk = new SystemSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SystemHistory(skipped, List.of(), skipped, idle, idle, skipped, skipped));

        model.update(idleDisk);

        assertThat(model.disk().scaleProperty().get()).isEqualTo("100.0 KiB/s");
        assertThat(model.network().scaleProperty().get()).isEmpty();
        assertThat(model.cpu().scaleProperty().get()).isEmpty();
    }

    @Test
    void aSnapshotWithoutAnyHistoryIsStillWaiting() {
        MetricHistory none = MetricHistory.empty();
        SystemSnapshot nothingYet = new SystemSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SystemHistory(none, List.of(), none, none, none, none, none));

        model.update(nothingYet);

        for (MetricReadout readout : readouts()) {
            assertThat(readout.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(readout.scaleProperty().get()).isEmpty();
        }
        for (FactReadout fact : facts()) {
            assertThat(fact.stateProperty().get()).isEqualTo(MetricState.WAITING);
            assertThat(fact.textProperty().get()).isEqualTo(READING);
        }
    }

    private List<MetricReadout> readouts() {
        return List.of(model.cpu(), model.memory(), model.disk(), model.network());
    }

    private List<FactReadout> facts() {
        return List.of(model.operatingSystem(), model.uptime(), model.processCount());
    }

    private List<ObservableValue<?>> everyProperty() {
        List<ObservableValue<?>> properties = new ArrayList<>();
        properties.add(model.status().stateProperty());
        properties.add(model.status().errorProperty());
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
        return properties;
    }
}
