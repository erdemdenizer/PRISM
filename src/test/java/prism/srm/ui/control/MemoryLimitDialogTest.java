package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import prism.srm.domain.FallbackOffer;

/** LIM-5: a memory limit is a commit limit of at least 1 MiB; the dialogs say what it and the fallback mean. */
class MemoryLimitDialogTest {

    private static final long MIB = 1L << 20;

    @Test
    void aWholeNumberOfMebibytesOrGibibytesOfAtLeastOneMebibyteIsALimit() {
        assertThat(MemoryLimitDialog.parse("256")).hasValue(256 * MIB);
        assertThat(MemoryLimitDialog.parse("256 MiB")).hasValue(256 * MIB);
        assertThat(MemoryLimitDialog.parse("256mb")).hasValue(256 * MIB);
        assertThat(MemoryLimitDialog.parse(" 2 GiB ")).hasValue(2048 * MIB);
        assertThat(MemoryLimitDialog.parse("2g")).hasValue(2048 * MIB);
        assertThat(MemoryLimitDialog.parse("1")).hasValue(MIB);
        assertThat(MemoryLimitDialog.parse("0")).isEmpty();
        assertThat(MemoryLimitDialog.parse("0 GiB")).isEmpty();
        assertThat(MemoryLimitDialog.parse("")).isEmpty();
        assertThat(MemoryLimitDialog.parse("MiB")).isEmpty();
        assertThat(MemoryLimitDialog.parse("-5")).isEmpty();
        assertThat(MemoryLimitDialog.parse("1.5 GiB")).isEmpty();
        assertThat(MemoryLimitDialog.parse("256 KiB")).isEmpty();
        assertThat(MemoryLimitDialog.parse("12345678")).isEmpty();
        assertThat(MemoryLimitDialog.parse(null)).isEmpty();
    }

    /** The units are read without the default locale: in Turkish, "MIB".toLowerCase() is "mıb". */
    @Test
    void theUnitIsReadTheSameInATurkishLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(MemoryLimitDialog.parse("512 MIB")).hasValue(512 * MIB);
            assertThat(MemoryLimitDialog.parse("1 GIB")).hasValue(1024 * MIB);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void theDialogSaysTheLimitIsOnCommittedMemoryAndThatReachingItMayMakeTheProcessFail() {
        assertThat(MemoryLimitDialog.MEANS)
                .contains("commit")
                .contains("not what it has in RAM")
                .contains("fail");
    }

    @Test
    void theOfferSaysWhyTheCostAndTheBoundAndCallsTheLimitApproximate() {
        String text = FallbackOfferDialog.text(new FallbackOffer("access is denied", 4, 16, true));

        assertThat(text)
                .contains("access is denied")
                .contains("approximate")
                .contains("a tenth of one logical processor")
                .contains("At most 4 emulated limits run");
        assertThat(FallbackOfferDialog.text(new FallbackOffer("refused", 1, 2, false)))
                .contains("At most 1 emulated limit runs")
                .doesNotContain("logical processor");
    }
}
