package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.FallbackOffer;
import prism.srm.domain.LimitCapability;

/** LIM-5: a memory limit of at least 1 MiB; the dialogs say what it is for its mechanism and what the fallback means. */
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

    /**
     * Risk D16: the dialog says what the limit is for its mechanism, as LimitMessages does: a Job Object's commit limit,
     * a systemd scope's memory.high (held near the amount, slowed and reclaimed above it, never stopped, memory used
     * before the move not counted), a scope's memory.max, or the watched resident memory.
     */
    @Test
    void theDialogSaysWhatTheLimitIsForItsMechanism() {
        LimitCapability job = LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT);
        LimitCapability high = LimitCapability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.SYSTEMD_SCOPE);
        LimitCapability max = LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.SYSTEMD_SCOPE);
        LimitCapability watched = LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.RESIDENT_POLICY);

        assertThat(MemoryLimitDialog.means(job)).isEqualTo(MemoryLimitDialog.MEANS);
        assertThat(MemoryLimitDialog.heading(job)).isEqualTo("Limit the memory this process may commit");

        assertThat(MemoryLimitDialog.means(high))
                .contains("Holds the process near this amount")
                .contains("systemd scope")
                .contains("slows the process down and reclaims its memory")
                .contains("does not stop or terminate")
                .contains("before it was moved into its scope is not counted")
                .doesNotContain("commit")
                .doesNotContain("cannot allocate");
        assertThat(MemoryLimitDialog.heading(high)).isEqualTo("Limit the memory of this process");

        assertThat(MemoryLimitDialog.means(max))
                .contains("the kernel may terminate the process or one of the processes it started")
                .contains("not counted")
                .doesNotContain("commit");
        assertThat(MemoryLimitDialog.heading(max)).doesNotContain("commit");

        assertThat(MemoryLimitDialog.means(watched)).isEqualTo(MemoryLimitDialog.WATCHED_MEANS);
        assertThat(MemoryLimitDialog.heading(watched)).isEqualTo("Watch the resident memory of this process");
        assertThat(MemoryLimitDialog.watched(watched)).isTrue();
        assertThat(MemoryLimitDialog.watched(high)).isFalse();
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
