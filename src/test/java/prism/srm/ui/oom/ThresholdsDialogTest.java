package prism.srm.ui.oom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import prism.srm.domain.OomThresholds;
import prism.srm.ui.oom.ThresholdsDialog.Field;

/** What the thresholds dialog takes (OOM-3): whole numbers in order, a floor in MiB; nothing else. */
class ThresholdsDialogTest {

    @Test
    void validNumbersInOrderAreTaken() {
        assertThat(ThresholdsDialog.parse("15", "8", "5", "256")).contains(OomThresholds.DEFAULTS);
        assertThat(ThresholdsDialog.parse(" 90 ", "2", "1", "0")).contains(new OomThresholds(90, 2, 1, 0));
    }

    @Test
    void numbersOutOfOrderOutOfRangeOrNotWholeAreNot() {
        assertThat(ThresholdsDialog.parse("5", "8", "15", "256")).isEmpty();
        assertThat(ThresholdsDialog.parse("91", "8", "5", "256")).isEmpty();
        assertThat(ThresholdsDialog.parse("15", "8", "0", "256")).isEmpty();
        assertThat(ThresholdsDialog.parse("15.5", "8", "5", "256")).isEmpty();
        assertThat(ThresholdsDialog.parse("15", "8", "5", "-1")).isEmpty();
        assertThat(ThresholdsDialog.parse("15", "8", "5", "")).isEmpty();
        assertThat(ThresholdsDialog.parse("١٥", "8", "5", "256"))
                .as("digits of another script")
                .isEmpty();
    }

    /** LO-16: the fields marked are the ones that break the rule, and none while the numbers are taken. */
    @Test
    void theFieldsThatBreakTheRuleAreMarked() {
        assertThat(ThresholdsDialog.invalidFields("15", "8", "5", "256")).isEmpty();
        assertThat(ThresholdsDialog.invalidFields("15", "20", "5", "256"))
                .as("Critical above Warning")
                .containsExactlyInAnyOrder(Field.WARNING, Field.CRITICAL);
        assertThat(ThresholdsDialog.invalidFields("15", "8", "8", "256"))
                .as("Emergency not below Critical")
                .containsExactlyInAnyOrder(Field.CRITICAL, Field.EMERGENCY);
        assertThat(ThresholdsDialog.invalidFields("91", "8", "5", "256")).containsExactly(Field.WARNING);
        assertThat(ThresholdsDialog.invalidFields("15", "8", "0", "256")).containsExactly(Field.EMERGENCY);
        assertThat(ThresholdsDialog.invalidFields("15", "8", "5", "")).containsExactly(Field.FLOOR);
        assertThat(ThresholdsDialog.invalidFields("15", "x", "5", "256")).containsExactly(Field.CRITICAL);
    }

    /** The marks and the dialog agree: no field is marked exactly when the numbers are taken. */
    @Test
    void noFieldIsMarkedExactlyWhenTheNumbersAreTaken() {
        String[] values = {"", "0", "1", "5", "8", "15", "90", "91", "a"};
        for (String w : values) {
            for (String c : values) {
                for (String e : values) {
                    for (String f : new String[] {"", "0", "256"}) {
                        assertThat(ThresholdsDialog.invalidFields(w, c, e, f).isEmpty())
                                .as("%s %s %s %s", w, c, e, f)
                                .isEqualTo(ThresholdsDialog.parse(w, c, e, f).isPresent());
                    }
                }
            }
        }
    }
}
