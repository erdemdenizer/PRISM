package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.GIB;
import static prism.srm.ui.processes.ProcessListings.KIB;
import static prism.srm.ui.processes.ProcessListings.MIB;
import static prism.srm.ui.processes.ProcessListings.process;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.format.ValueFormatter;

/** The text of every value of a process: its notation, and what stands in for a missing value. */
class ProcessTextTest {

    private static final String DASH = "–";

    private final ProcessText text = new ProcessText(new ValueFormatter(Locale.US), ZoneOffset.UTC);

    @Test
    void cpuIsAPercentageWithOneDecimal() {
        assertThat(text.cpu(process(1, "a").cpu(0).build())).isEqualTo(new Quantity("0.0", "%"));
        assertThat(text.cpu(process(1, "a").cpu(6.25).build()).number()).isIn("6.2", "6.3");
        assertThat(text.cpu(process(1, "a").cpu(12.44).build())).isEqualTo(new Quantity("12.4", "%"));
        assertThat(text.cpu(process(1, "a").cpu(100).build())).isEqualTo(new Quantity("100.0", "%"));
    }

    /** A process that used no processor time and one whose time could not be compared differ. */
    @Test
    void zeroCpuAndNoCpuAreNotTheSameText() {
        assertThat(text.cpu(process(1, "a").cpu(0).build()).text()).isEqualTo("0.0 %");
        assertThat(text.cpu(process(1, "a").noCpu().build())).isEqualTo(Quantity.MISSING);
    }

    @Test
    void memoryIsTheResidentMemoryInTheUnitThatFitsIt() {
        assertThat(text.memory(process(1, "a").memory(0).build()).text()).isEqualTo("0 B");
        assertThat(text.memory(process(1, "a").memory(900 * KIB).build()).text())
                .isEqualTo("900.0 KiB");
        assertThat(text.memory(process(1, "a").memory(24 * MIB).build()).text()).isEqualTo("24.0 MiB");
        assertThat(text.memory(process(1, "a").memory(1023 * GIB + 921 * MIB).build())
                        .text())
                .isEqualTo("1023.9 GiB");
        assertThat(text.memory(process(1, "a").memory(4096 * GIB).build()).text())
                .isEqualTo("4.0 TiB");
        assertThat(text.memory(process(1, "a").noMemory().build())).isEqualTo(Quantity.MISSING);
    }

    /** The private part and the whole are different values: the column shows the whole. */
    @Test
    void theMemoryColumnIsNotThePrivateMemory() {
        ProcessSnapshot process =
                process(1, "a").memory(300 * MIB).privateMemory(120 * MIB).build();

        assertThat(text.memory(process).text()).isEqualTo("300.0 MiB");
        assertThat(text.privateMemory(process)).isEqualTo("120.0 MiB");
    }

    @Test
    void ioIsTheSumOfBothRatesAndMissingUnlessBothAreKnown() {
        assertThat(text.io(process(1, "a").io(0, 0).build()).text()).isEqualTo("0 B/s");
        assertThat(text.io(process(1, "a").io(1024, 512).build()).text()).isEqualTo("1.5 KiB/s");
        assertThat(text.io(process(1, "a").io(1023.9 * GIB, 1023.9 * MIB).build())
                        .text())
                .isEqualTo("1.0 TiB/s");
        assertThat(text.io(process(1, "a").noIo().build())).isEqualTo(Quantity.MISSING);
        assertThat(text.io(process(1, "a").ioReadOnly(4096).build())).isEqualTo(Quantity.MISSING);
    }

    @Test
    void theTwoIoRatesOfTheDetailsAreShownEachOnItsOwn() {
        ProcessSnapshot process = process(1, "a").io(2 * MIB, 512).build();

        assertThat(text.ioRead(process)).isEqualTo("2.0 MiB/s");
        assertThat(text.ioWrite(process)).isEqualTo("512 B/s");
        assertThat(text.ioRead(process(1, "a").noIo().build())).isEqualTo(DASH);
        assertThat(text.ioWrite(process(1, "a").ioReadOnly(4096).build())).isEqualTo(DASH);
        assertThat(text.ioRead(process(1, "a").ioReadOnly(4096).build())).isEqualTo("4.0 KiB/s");
    }

    @Test
    void threadsAreGroupedAndPrioritiesAreShownAsTheSystemNumbersThem() {
        assertThat(text.threads(process(1, "a").threads(8).build())).isEqualTo("8");
        assertThat(text.threads(process(1, "a").threads(123_456).build())).isEqualTo("123,456");
        assertThat(text.threads(process(1, "a").noThreads().build())).isEqualTo(DASH);
        assertThat(text.priority(process(1, "a").priority(8).build())).isEqualTo("8");
        assertThat(text.priority(process(1, "a").priority(-20).build())).isEqualTo("-20");
        assertThat(text.priority(process(1, "a").priority(0).build())).isEqualTo("0");
        assertThat(text.priority(process(1, "a").noPriority().build())).isEqualTo(DASH);
    }

    @Test
    void nameAndUserAreShownAsTheyAreAndAsADashWhenTheSystemGivesNone() {
        assertThat(text.name(process(1, "svchost.exe").build())).isEqualTo("svchost.exe");
        assertThat(text.name(process(1, "").build())).isEqualTo(DASH);
        assertThat(text.user(process(1, "a").user("NETWORK SERVICE").build())).isEqualTo("NETWORK SERVICE");
        assertThat(text.user(process(1, "a").noUser().build())).isEqualTo(DASH);
    }

    /** Windows has no state for a process as a whole: the cell says so with the dash, not a word. */
    @Test
    void aProcessWithoutAStateShowsTheDashAndNeverAnInventedState() {
        assertThat(text.state(process(1, "a").build())).isEqualTo(DASH);
    }

    @ParameterizedTest
    @EnumSource(ProcessState.class)
    void everyStateTheSystemReportsHasAWord(ProcessState state) {
        String word = text.state(process(1, "a").state(state).build());

        assertThat(word).isNotBlank().isNotEqualTo(DASH).matches("[A-Z][a-z]+");
    }

    @Test
    void theStartTimeIsShownInTheGivenTimeZoneToTheSecond() {
        ProcessSnapshot process = process(3600, "a").build();

        assertThat(text.started(process)).isEqualTo("2026-10-06 07:00:00");
        assertThat(new ProcessText(new ValueFormatter(Locale.US), ZoneId.of("Europe/Istanbul")).started(process))
                .isEqualTo("2026-10-06 10:00:00");
    }

    @Test
    void theParentPidIsAnIdentifierWithoutGrouping() {
        assertThat(text.parentPid(process(1, "a").parent(1_234_567).build())).isEqualTo("1234567");
        assertThat(text.parentPid(process(1, "a").nothingElse().build())).isEqualTo(DASH);
    }

    @Test
    void sizesOfTheDetailsAreMissingWhenTheSystemDoesNotGiveThem() {
        ProcessSnapshot bare = process(1, "a").nothingElse().build();

        assertThat(text.privateMemory(bare)).isEqualTo(DASH);
        assertThat(text.virtualSize(bare)).isEqualTo(DASH);
        assertThat(text.virtualSize(process(1, "a").virtualSize(131_072 * GIB).build()))
                .isEqualTo("128.0 TiB");
    }

    /** Numbers follow the locale handed in; identifiers and unit symbols do not change. */
    @Test
    void numbersFollowTheLocaleOfTheFormatterAndNotTheDefaultOne() {
        ProcessText turkish = new ProcessText(new ValueFormatter(Locale.forLanguageTag("tr-TR")), ZoneOffset.UTC);
        ProcessSnapshot process = process(1, "a")
                .cpu(12.44)
                .memory(1536 * MIB)
                .threads(123_456)
                .parent(1_234_567)
                .build();

        assertThat(turkish.cpu(process)).isEqualTo(new Quantity("12,4", "%"));
        assertThat(turkish.memory(process)).isEqualTo(new Quantity("1,5", "GiB"));
        assertThat(turkish.threads(process)).isEqualTo("123.456");
        assertThat(turkish.parentPid(process)).isEqualTo("1234567");
        assertThat(turkish.started(process)).isEqualTo("2026-10-06 06:00:01");
    }
}
