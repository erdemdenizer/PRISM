package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * The log folder has one source of truth only as long as the configuration reads the property
 * this class sets. Nothing but this test ties the two names together.
 */
class LoggingSetupTest {

    @Test
    void theFolderIsPublishedUnderTheNameTheLoggingConfigurationReads() throws IOException {
        assertThat(resource("/logback.xml")).contains("${" + LoggingSetup.LOG_FOLDER_PROPERTY + "}/");
    }

    /** A test run must never write into the log folder of the person running it. */
    @Test
    void theConfigurationOfTheTestsWritesNoFile() throws IOException {
        assertThat(resource("/logback-test.xml"))
                .doesNotContain(LoggingSetup.LOG_FOLDER_PROPERTY)
                .doesNotContain("FileAppender");
    }

    @Test
    void startingPublishesTheFolderAndReturnsTheLoggerOfTheOwner() {
        String before = System.getProperty(LoggingSetup.LOG_FOLDER_PROPERTY);
        Path folder = Path.of("some", "folder", "logs");
        try {
            Logger logger = LoggingSetup.start(folder, LoggingSetupTest.class);

            assertThat(System.getProperty(LoggingSetup.LOG_FOLDER_PROPERTY)).isEqualTo(folder.toString());
            assertThat(logger.getName()).isEqualTo(LoggingSetupTest.class.getName());
        } finally {
            if (before == null) {
                System.clearProperty(LoggingSetup.LOG_FOLDER_PROPERTY);
            } else {
                System.setProperty(LoggingSetup.LOG_FOLDER_PROPERTY, before);
            }
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = LoggingSetupTest.class.getResourceAsStream(name)) {
            assertThat(in).as("resource %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
