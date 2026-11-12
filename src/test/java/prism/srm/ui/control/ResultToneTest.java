package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.application.ControlMessages;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.OperationResult;

/** The line and the log never disagree on how grave a result is. */
class ResultToneTest {

    @ParameterizedTest
    @EnumSource(OperationResult.class)
    void theToneOfEveryResultIsItsSeverityInTheLog(OperationResult result) {
        ResultTone expected = switch (ControlMessages.severity(result)) {
            case INFO -> ResultTone.SUCCESS;
            case WARNING -> ResultTone.WARNING;
            case ERROR -> ResultTone.FAILURE;
        };
        assertThat(ResultTone.of(result)).isEqualTo(expected);
        assertThat(ControlMessages.severity(result))
                .isEqualTo(
                        switch (result) {
                            case SUCCESS -> ActivitySeverity.INFO;
                            case NATIVE_ERROR -> ActivitySeverity.ERROR;
                            case ACCESS_DENIED, PROCESS_NOT_FOUND, UNSUPPORTED, INVALID_ARGUMENT ->
                                ActivitySeverity.WARNING;
                        });
    }
}
