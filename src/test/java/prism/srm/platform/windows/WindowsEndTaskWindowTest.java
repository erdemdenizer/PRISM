package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * End Task on a process that has a window: the controlled fixture {@link WindowedTarget}, never a
 * third-party application (SAFE-6). It needs a desktop, so it runs with the other tests that need a
 * display ({@code -Pui-snapshots}).
 */
@Tag("ui-snapshot")
@EnabledOnOs(OS.WINDOWS)
@org.junit.jupiter.api.Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class WindowsEndTaskWindowTest {

    @Test
    void aCloseRequestReachesTheWindowAndTheApplicationClosesItself() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(WindowedTarget.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        ProcessBuilder builder =
                new ProcessBuilder(java, "-cp", classes, WindowedTarget.class.getName()).redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process target = builder.start();
        try {
            BufferedReader output =
                    new BufferedReader(new InputStreamReader(target.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(output.readLine()).isEqualTo("shown");
            Instant start = target.toHandle().info().startInstant().orElseThrow();

            ControlOutcome outcome =
                    new WindowsProcessController().requestClose(new ProcessIdentity(target.pid(), start));

            assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
            assertThat(outcome.detail().get()).startsWith("close requested: 1 of 1");
            assertThat(output.readLine()).isEqualTo("asked to close");
            assertThat(target.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(target.exitValue()).isZero();
        } finally {
            target.destroyForcibly();
        }
    }
}
