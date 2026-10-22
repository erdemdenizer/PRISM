package prism.srm.domain;

/**
 * Network transfer rates in bytes per second, of one interface or summed over the {@link
 * DeviceLayer#PHYSICAL} interfaces.
 */
public record NetworkRates(double receiveBytesPerSecond, double sendBytesPerSecond) {

    public NetworkRates {
        if (!isRate(receiveBytesPerSecond) || !isRate(sendBytesPerSecond)) {
            throw new IllegalArgumentException("Expected finite, non-negative rates: receive=" + receiveBytesPerSecond
                    + ", send=" + sendBytesPerSecond);
        }
    }

    private static boolean isRate(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
