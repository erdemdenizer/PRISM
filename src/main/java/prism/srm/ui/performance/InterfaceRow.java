package prism.srm.ui.performance;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.format.ValueFormatter;

/**
 * One network interface in the table of the Network section. Its texts are set in place from
 * each snapshot, on the JavaFX Application Thread only.
 */
final class InterfaceRow {

    private final String key;
    private final ReadOnlyStringWrapper displayName = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper layer = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<Quantity> receive = new ReadOnlyObjectWrapper<>(Quantity.MISSING);
    private final ReadOnlyObjectWrapper<Quantity> send = new ReadOnlyObjectWrapper<>(Quantity.MISSING);

    InterfaceRow(NetworkInterfaceUsage networkInterface) {
        this.key = networkInterface.name();
    }

    /** The name the system gives the interface, which identifies it from one snapshot to the next. */
    String key() {
        return key;
    }

    ReadOnlyStringProperty displayNameProperty() {
        return displayName.getReadOnlyProperty();
    }

    /** Whether the interface is a hardware adapter, as far as the system says. */
    ReadOnlyStringProperty layerProperty() {
        return layer.getReadOnlyProperty();
    }

    /** The receive rate; {@link Quantity#MISSING} while the snapshot has none. */
    ReadOnlyObjectProperty<Quantity> receiveProperty() {
        return receive.getReadOnlyProperty();
    }

    /** The send rate; {@link Quantity#MISSING} while the snapshot has none. */
    ReadOnlyObjectProperty<Quantity> sendProperty() {
        return send.getReadOnlyProperty();
    }

    void update(NetworkInterfaceUsage networkInterface, ValueFormatter formatter) {
        displayName.set(networkInterface.displayName());
        layer.set(describe(networkInterface.layer()));
        if (networkInterface.rates().isPresent()) {
            NetworkRates rates = networkInterface.rates().get();
            set(receive, formatter.bytesPerSecond(rates.receiveBytesPerSecond()));
            set(send, formatter.bytesPerSecond(rates.sendBytesPerSecond()));
        } else {
            // A rate that is missing is shown as missing, never as zero.
            set(receive, Quantity.MISSING);
            set(send, Quantity.MISSING);
        }
    }

    /** A value that did not change is not set again, so its cell is left alone. */
    private static void set(ReadOnlyObjectWrapper<Quantity> property, Quantity value) {
        if (!value.equals(property.get())) {
            property.set(value);
        }
    }

    private static String describe(DeviceLayer layer) {
        return switch (layer) {
            case PHYSICAL -> "Physical";
            case LOGICAL -> "Logical";
            case UNKNOWN -> "Unknown";
        };
    }
}
