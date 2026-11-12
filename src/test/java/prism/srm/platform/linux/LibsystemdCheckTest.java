package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The library and symbol check before the sd-bus binding exists (milestone 12, S3A, plan Rev 2.2 T-D2, F16): every
 * failure is a reason, nothing is loaded unless every symbol was found, and the binding is created at most once.
 */
class LibsystemdCheckTest {

    /** Libraries as the loader would answer, recording what was asked. */
    private static final class FakeLibraries implements SystemLibraries {
        Optional<String> glibc = Optional.of("2.39");
        Lookup lookup = Lookup.found(List.of());
        final List<String> asked = new ArrayList<>();

        @Override
        public Optional<String> glibcVersion() {
            asked.add("glibc");
            return glibc;
        }

        @Override
        public Lookup lookup(String soname, List<String> symbols) {
            asked.add(soname + " " + symbols);
            return lookup;
        }
    }

    private final FakeLibraries libraries = new FakeLibraries();
    private int bindings;

    private LibsystemdCheck.Outcome check(String architecture) {
        return LibsystemdCheck.check(architecture, libraries, () -> {
            bindings++;
            return new FakeSdBus();
        });
    }

    @Test
    void everythingFoundCreatesTheBindingOnceAfterAskingForEverySymbol() {
        LibsystemdCheck.Outcome outcome = check("amd64");

        assertThat(outcome.available()).isTrue();
        assertThat(bindings).isEqualTo(1);
        assertThat(libraries.asked).containsExactly("glibc", "libsystemd.so.0 " + SdBusApi.SYMBOLS);
        assertThat(check("aarch64").available()).isTrue();
    }

    @Test
    void anUnverifiedArchitectureAsksNothing() {
        LibsystemdCheck.Outcome outcome = check("riscv64");

        assertThat(outcome.available()).isFalse();
        assertThat(outcome.detail()).contains("riscv64");
        assertThat(libraries.asked).isEmpty();
        assertThat(bindings).isZero();
    }

    @Test
    void anotherCLibraryOrAnOldOrUnreadableGlibcStopsBeforeAnyLookup() {
        libraries.glibc = Optional.empty();
        assertThat(check("amd64").detail()).contains("does not run on the GNU C library");

        libraries.glibc = Optional.of("2.33");
        assertThat(check("amd64").detail()).contains("older than 2.34");

        libraries.glibc = Optional.of("glibc");
        assertThat(check("amd64").detail()).contains("cannot be read");

        assertThat(libraries.asked).containsOnly("glibc");
        assertThat(bindings).isZero();

        libraries.glibc = Optional.of("2.34");
        assertThat(check("amd64").available()).isTrue();
    }

    @Test
    void aMissingLibraryOrSymbolIsAReasonAndNothingIsLoaded() {
        libraries.lookup = SystemLibraries.Lookup.notOpened("libsystemd.so.0: cannot open shared object file");
        LibsystemdCheck.Outcome missing = check("amd64");
        assertThat(missing.available()).isFalse();
        assertThat(missing.detail()).contains("is not available").contains("cannot open shared object file");

        libraries.lookup = SystemLibraries.Lookup.found(List.of("sd_bus_message_read_array"));
        LibsystemdCheck.Outcome partial = check("amd64");
        assertThat(partial.available()).isFalse();
        assertThat(partial.detail()).contains("sd_bus_message_read_array");

        assertThat(bindings).isZero();
    }

    @Test
    void theGnuCLibraryIsRecognisedInTheMapsOfEveryVersion() {
        String prefix = "7f1c2a000000-7f1c2a028000 r--p 00000000 08:02 1048 ";
        assertThat(JnaSystemLibraries.mapsGlibc(List.of(prefix + "/usr/lib/x86_64-linux-gnu/libc.so.6")))
                .isTrue();
        assertThat(JnaSystemLibraries.mapsGlibc(List.of(prefix + "/lib/x86_64-linux-gnu/libc-2.31.so")))
                .isTrue();
        assertThat(JnaSystemLibraries.mapsGlibc(List.of(prefix + "/usr/lib/libc.so.6 (deleted)")))
                .isTrue();
        assertThat(JnaSystemLibraries.mapsGlibc(List.of(prefix + "/lib/ld-musl-x86_64.so.1")))
                .isFalse();
        assertThat(JnaSystemLibraries.mapsGlibc(List.of(prefix + "/usr/lib/libc.so.6.bak", "[heap]")))
                .isFalse();
        assertThat(JnaSystemLibraries.mapsGlibc(List.of())).isFalse();
    }

    @Test
    void theBindingHasNoSignalOrEventLoopFunction() {
        // Containers are built for the two state-changing calls (S3B); signals are never subscribed to: the start job
        // is
        // awaited by bounded polling of the unit, so no native code calls back into the JVM.
        assertThat(SdBusApi.SYMBOLS)
                .contains("sd_bus_message_open_container", "sd_bus_message_close_container")
                .doesNotContain("sd_bus_add_match", "sd_bus_process", "sd_bus_wait", "sd_bus_attach_event")
                .allMatch(symbol -> symbol.startsWith("sd_bus_"))
                .doesNotHaveDuplicates();
    }
}
