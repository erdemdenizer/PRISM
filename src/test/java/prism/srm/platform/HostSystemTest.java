package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class HostSystemTest {

    @Test
    void recognizesTheSupportedSystemsFromTheOsNameProperty() {
        assertThat(HostSystem.fromOsName("Windows 11")).isEqualTo(HostSystem.WINDOWS);
        assertThat(HostSystem.fromOsName("Windows Server 2022")).isEqualTo(HostSystem.WINDOWS);
        assertThat(HostSystem.fromOsName("Linux")).isEqualTo(HostSystem.LINUX);
        assertThat(HostSystem.fromOsName("Mac OS X")).isEqualTo(HostSystem.MACOS);
    }

    @Test
    void anythingElseIsOther() {
        assertThat(HostSystem.fromOsName("FreeBSD")).isEqualTo(HostSystem.OTHER);
        assertThat(HostSystem.fromOsName("")).isEqualTo(HostSystem.OTHER);
    }

    @Test
    void doesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            // In Turkish, upper-case I lower-cases to dotless ı, so "WINDOWS" would not start
            // with "windows" if the default locale were used.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(HostSystem.fromOsName("WINDOWS 11")).isEqualTo(HostSystem.WINDOWS);
            assertThat(HostSystem.fromOsName("LINUX")).isEqualTo(HostSystem.LINUX);
        } finally {
            Locale.setDefault(original);
        }
    }
}
