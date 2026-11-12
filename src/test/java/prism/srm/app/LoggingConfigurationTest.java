package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LIFE-1 "flushes logs" and ADR-0007, on the logback.xml that ships: no line waits in a buffer, so a
 * shutdown has nothing to flush and a hard kill loses no line already logged.
 */
class LoggingConfigurationTest {

    @TempDir
    Path logs;

    /** A hook of Logback's own could stop the context before srm-exit-hook logs what it releases. */
    @Test
    void logbackHasNoShutdownHookOfItsOwn() throws Exception {
        URL configuration = LoggingConfigurationTest.class.getClassLoader().getResource("logback.xml");
        assertThat(configuration).isNotNull();
        try (InputStream in = configuration.openStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("<shutdownHook");
        }
    }

    @Test
    void theFileOfTheApplicationFlushesEveryLineAtOnce() throws Exception {
        URL configuration = LoggingConfigurationTest.class.getClassLoader().getResource("logback.xml");
        assertThat(configuration).isNotNull();
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            context.putProperty("srm.log.dir", logs.toString());
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(configuration);
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

            Appender<ILoggingEvent> file = root.getAppender("FILE");

            assertThat(file).isInstanceOf(OutputStreamAppender.class);
            assertThat(((OutputStreamAppender<ILoggingEvent>) file).isImmediateFlush())
                    .isTrue();

            root.info("a line that must be in the file before anything else happens");

            // Read while the appender is open: the line is there without a stop or a flush.
            List<String> lines;
            try (Stream<Path> listed = Files.list(logs)) {
                lines = Files.readAllLines(listed.findFirst().orElseThrow());
            }
            assertThat(lines).singleElement().asString().contains("a line that must be in the file");
        } finally {
            context.stop();
        }
    }
}
