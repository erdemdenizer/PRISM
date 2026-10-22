package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.process;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;

/** Which processes a filter text lets through. */
class ProcessFilterTest {

    private final Locale defaultLocale = Locale.getDefault();

    @AfterEach
    void restoreTheDefaultLocale() {
        Locale.setDefault(defaultLocale);
    }

    @Test
    void noTextAndBlankTextLetEveryProcessThrough() {
        ProcessSnapshot any = process(10, "anything").build();

        for (String typed : new String[] {null, "", "   ", "\t"}) {
            ProcessFilter filter = ProcessFilter.of(typed);

            assertThat(filter.isActive()).isFalse();
            assertThat(filter).isEqualTo(ProcessFilter.NONE);
            assertThat(matches(filter, any)).isTrue();
        }
    }

    @Test
    void theTextMayOccurAnywhereInTheName() {
        ProcessSnapshot chrome = process(10, "chrome.exe").build();

        assertThat(matches(ProcessFilter.of("chrome"), chrome)).isTrue();
        assertThat(matches(ProcessFilter.of("rome"), chrome)).isTrue();
        assertThat(matches(ProcessFilter.of(".exe"), chrome)).isTrue();
        assertThat(matches(ProcessFilter.of("chrome.exe"), chrome)).isTrue();
        assertThat(matches(ProcessFilter.of("chrome.exe2"), chrome)).isFalse();
        assertThat(matches(ProcessFilter.of("firefox"), chrome)).isFalse();
    }

    @Test
    void caseIsIgnoredInBothTheTextAndTheName() {
        assertThat(matches(ProcessFilter.of("CHROME"), process(10, "chrome.exe").build()))
                .isTrue();
        assertThat(matches(
                        ProcessFilter.of("searchhost"),
                        process(10, "SearchHost.exe").build()))
                .isTrue();
    }

    @Test
    void blanksAroundTheTextAreIgnoredAndBlanksInsideItCount() {
        ProcessSnapshot helper = process(10, "Chrome Helper").build();

        assertThat(matches(ProcessFilter.of("  chrome  "), helper)).isTrue();
        assertThat(matches(ProcessFilter.of("chrome helper"), helper)).isTrue();
        assertThat(matches(ProcessFilter.of("chrome  helper"), helper)).isFalse();
    }

    @Test
    void theTextMayBeThePidOrAPartOfIt() {
        ProcessSnapshot process = process(41_236, "svchost.exe").build();

        assertThat(matches(ProcessFilter.of("41236"), process)).isTrue();
        assertThat(matches(ProcessFilter.of("123"), process)).isTrue();
        assertThat(matches(ProcessFilter.of("41237"), process)).isFalse();
        // Digits are compared as typed: a PID is never shown with grouping.
        assertThat(matches(ProcessFilter.of("41,236"), process)).isFalse();
    }

    @Test
    void theTextMayBeAPartOfTheUserName() {
        assertThat(matches(
                        ProcessFilter.of("system"),
                        process(10, "svchost.exe").user("SYSTEM").build()))
                .isTrue();
        assertThat(matches(
                        ProcessFilter.of("system"),
                        process(10, "svchost.exe").user("alice").build()))
                .isFalse();
    }

    @Test
    void aProcessWithoutAUserOrWithoutANameIsMatchedByWhatItHas() {
        ProcessSnapshot nameless = process(588, "").noUser().build();

        assertThat(matches(ProcessFilter.of("588"), nameless)).isTrue();
        assertThat(matches(ProcessFilter.of("system"), nameless)).isFalse();
    }

    /**
     * In Turkish, the lower case of "I" is a dotless "i", so a filter that used the default locale
     * would not find "wininit.exe" for "WININIT" on a Turkish system. This one compares character
     * by character and gives the same answer everywhere.
     */
    @Test
    void theAnswerDoesNotDependOnTheDefaultLocale() {
        ProcessSnapshot wininit = process(10, "wininit.exe").user("SYSTEM").build();
        ProcessSnapshot upper = process(11, "WININIT.EXE").build();

        for (Locale locale : new Locale[] {Locale.ROOT, Locale.US, Locale.forLanguageTag("tr-TR")}) {
            Locale.setDefault(locale);

            assertThat(matches(ProcessFilter.of("WININIT"), wininit)).isTrue();
            assertThat(matches(ProcessFilter.of("wininit"), upper)).isTrue();
            assertThat(matches(ProcessFilter.of("system"), wininit)).isTrue();
        }
    }

    /** Typed on a Turkish keyboard, a dotted capital and a dotless small letter still find "i". */
    @Test
    void turkishLettersFindTheirPlainCounterparts() {
        ProcessSnapshot process = process(10, "wininit.exe").build();

        assertThat(matches(ProcessFilter.of("wİnİnİt"), process)).isTrue();
        assertThat(matches(ProcessFilter.of("wınınıt"), process)).isTrue();
    }

    @Test
    void filtersWithTheSameTextAreEqualWhateverBlanksSurroundedIt() {
        assertThat(ProcessFilter.of("chrome"))
                .isEqualTo(ProcessFilter.of(" chrome "))
                .hasSameHashCodeAs(ProcessFilter.of("chrome"));
        assertThat(ProcessFilter.of("chrome")).isNotEqualTo(ProcessFilter.of("Chrome"));
        assertThat(ProcessFilter.of("chrome").isActive()).isTrue();
    }

    private static boolean matches(ProcessFilter filter, ProcessSnapshot process) {
        return filter.matches(new ProcessRow(process));
    }
}
