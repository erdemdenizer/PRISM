package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The address the user bus is opened at (risk D1) is the one {@code sd_bus_open_user} would use
 * ({@code bus_set_address_user}, systemd v255 sd-bus.c; {@code bus_address_escape}, bus-internal.c).
 */
class UserBusAddressTest {

    @Test
    void theSessionAddressIsUsedAsItIs() {
        Map<String, String> env = Map.of(
                "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus,guid=abc", "XDG_RUNTIME_DIR", "/elsewhere");

        assertThat(JnaSdBusApi.userBusAddress(env::get)).contains("unix:path=/run/user/1000/bus,guid=abc");
    }

    @Test
    void otherwiseTheRuntimeDirectoryIsEscapedAsLibsystemdEscapesIt() {
        assertThat(JnaSdBusApi.userBusAddress(Map.of("XDG_RUNTIME_DIR", "/run/user/1000")::get))
                .contains("unix:path=/run/user/1000/bus");
        // Letters, digits and _-/. stay; any other byte of the UTF-8 becomes %xx in lowercase.
        assertThat(JnaSdBusApi.userBusAddress(Map.of("XDG_RUNTIME_DIR", "/run/user/1000/a b,c=d")::get))
                .contains("unix:path=/run/user/1000/a%20b%2cc%3dd/bus");
        assertThat(JnaSdBusApi.userBusAddress(Map.of("XDG_RUNTIME_DIR", "/tmp/\u00e7")::get))
                .contains("unix:path=/tmp/%c3%a7/bus");
    }

    @Test
    void withNeitherVariableThereIsNoAddress() {
        assertThat(JnaSdBusApi.userBusAddress(Map.<String, String>of()::get)).isEmpty();
    }
}
