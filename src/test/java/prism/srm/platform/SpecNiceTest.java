package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.PriorityLevel;

/**
 * The steps the Linux and macOS priority tests take from whatever nice value a runner starts them
 * at: lowering goes to a higher nice value, and a level to raise to is always left.
 */
class SpecNiceTest {

    @ParameterizedTest
    @ValueSource(ints = {-20, -15, -11, -10, -7, -5, 0, 5, 10, 15, 18, 19})
    void fromAnyStartALevelIsLeftToRaiseTo(int baseline) {
        Optional<PriorityLevel> lower = SpecNice.lowerThan(baseline);
        int now = lower.map(SpecNice.NICE::get).orElse(baseline);

        lower.ifPresent(level -> assertThat(SpecNice.NICE.get(level)).isGreaterThan(baseline));
        assertThat(SpecNice.higherThan(now)).isPresent();
        assertThat(SpecNice.NICE.get(SpecNice.higherThan(now).orElseThrow())).isLessThan(now);
    }

    @ParameterizedTest
    @ValueSource(ints = {-10, -5, 0, 10})
    void fromALevelOfTheTableLoweringIsToTheNextLevel(int baseline) {
        PriorityLevel lower = SpecNice.lowerThan(baseline).orElseThrow();

        assertThat(SpecNice.byNice().indexOf(lower))
                .isEqualTo(SpecNice.byNice().indexOf(SpecNice.levelOf(baseline).orElseThrow()) + 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-20, -15, -11})
    void belowHighLoweringStopsShortOfHigh(int baseline) {
        assertThat(SpecNice.lowerThan(baseline)).hasValue(PriorityLevel.ABOVE_NORMAL);
    }

    @Test
    void fromLowThereIsNothingLower() {
        assertThat(SpecNice.lowerThan(19)).isEmpty();
        assertThat(SpecNice.higherThan(19)).hasValue(PriorityLevel.BELOW_NORMAL);
    }
}
