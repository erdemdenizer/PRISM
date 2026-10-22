package prism.srm.ui.performance;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.SystemReadouts;

/**
 * The Network section: the download and upload rate summed over the physical adapters, with their
 * 60-second history, and every interface with its own receive and send rate.
 *
 * <p>The total counts physical adapters only, because a filter driver, a virtual adapter or a
 * tunnel counts traffic that the adapter underneath counts again. It is absent when the system
 * does not say which interfaces are physical, and the section then says so (ADR-0005). The
 * history is that of the total: a snapshot has no history of a single interface.
 */
final class NetworkModel implements SectionModel {

    /** Shown in place of the rows when the system lists no network interface. */
    static final String NO_INTERFACES = "No network interface is reported.";

    static final String TOTAL_OF_PHYSICAL = "The total counts physical adapters only.";
    static final String NO_TOTAL_UNKNOWN_LAYER = "No total: the system does not say which interfaces are physical.";
    static final String NO_TOTAL_NOTHING_PHYSICAL = "No total: no physical adapter is listed.";

    /** Physical adapters first: they are what the total is made of. Otherwise the order of the system. */
    private static final Comparator<NetworkInterfaceUsage> PHYSICAL_FIRST =
            Comparator.comparing(networkInterface -> networkInterface.layer() != DeviceLayer.PHYSICAL);

    private final ValueFormatter formatter;
    private final SystemReadouts readouts;
    private final MetricReadout rates = new MetricReadout();
    private final ObservableList<InterfaceRow> interfaces = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper interfacesStatus = new ReadOnlyStringWrapper(MetricState.WAITING.placeholder());
    private final ReadOnlyStringWrapper totalNote = new ReadOnlyStringWrapper("");

    NetworkModel(ValueFormatter formatter, SystemReadouts readouts) {
        this.formatter = formatter;
        this.readouts = readouts;
    }

    /** Download and upload rate summed over the physical adapters. */
    MetricReadout rates() {
        return rates;
    }

    /** One row per interface, physical adapters first. */
    ObservableList<InterfaceRow> interfaces() {
        return interfaces;
    }

    /** What to show in place of the rows while there are none. */
    ReadOnlyStringProperty interfacesStatusProperty() {
        return interfacesStatus.getReadOnlyProperty();
    }

    /** What the total is made of, or why there is none when the interfaces show why; else empty. */
    ReadOnlyStringProperty totalNoteProperty() {
        return totalNote.getReadOnlyProperty();
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        readouts.showNetwork(rates, snapshot);
        Optional<List<NetworkInterfaceUsage>> listed = snapshot.network().map(NetworkUsage::interfaces);
        // A reading that is missing takes the rows with it: rates left in place would look current.
        List<NetworkInterfaceUsage> ordered =
                listed.orElse(List.of()).stream().sorted(PHYSICAL_FIRST).toList();
        RowSync.sync(
                interfaces,
                ordered,
                InterfaceRow::key,
                NetworkInterfaceUsage::name,
                InterfaceRow::new,
                (row, networkInterface) -> row.update(networkInterface, formatter));
        interfacesStatus.set(
                listed.isPresent()
                        ? NO_INTERFACES
                        : MetricState.of(false, SystemReadouts.cycles(snapshot)).placeholder());
        totalNote.set(noteFor(snapshot.network().flatMap(NetworkUsage::total).isPresent(), ordered));
    }

    @Override
    public void showNothing() {
        SystemReadouts.showNothing(rates);
        interfaces.clear();
        interfacesStatus.set(MetricState.UNAVAILABLE.placeholder());
        totalNote.set("");
    }

    /**
     * Says what a total is made of, or explains one that is missing. Whether there is a total is
     * decided where the rates are computed and is taken from the snapshot, never worked out here;
     * a reason is given only when the interfaces of the snapshot show one.
     */
    private static String noteFor(boolean hasTotal, List<NetworkInterfaceUsage> interfaces) {
        if (hasTotal) {
            return TOTAL_OF_PHYSICAL;
        }
        boolean anyPhysical = false;
        for (NetworkInterfaceUsage networkInterface : interfaces) {
            if (networkInterface.layer() == DeviceLayer.UNKNOWN) {
                return NO_TOTAL_UNKNOWN_LAYER;
            }
            anyPhysical = anyPhysical || networkInterface.layer() == DeviceLayer.PHYSICAL;
        }
        // With physical adapters and no total, a sample was skipped: the first cycle, or an
        // adapter that came or went. The readout says "Reading..." or "Unavailable" for that.
        return interfaces.isEmpty() || anyPhysical ? "" : NO_TOTAL_NOTHING_PHYSICAL;
    }
}
