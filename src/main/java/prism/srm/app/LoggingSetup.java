package prism.srm.app;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the log folder to the logging configuration and creates the first logger, in that order.
 *
 * <p>{@code logback.xml} takes the folder of its rolling file from the system property named
 * here. Creating a logger is what makes Logback read its configuration, so the property has to
 * be set first; a logger created too early would write to a folder named after the undefined
 * property, without any error. The order is therefore fixed in this one method, and this is the
 * only class of the package that may create a logger: {@code ArchitectureTest} checks that, and
 * that {@code App} does nothing before its {@code init()}.
 *
 * <p>Logback itself is never called. It is a runtime-only dependency.
 */
final class LoggingSetup {

    /** The name {@code logback.xml} refers to; a test keeps the two in agreement. */
    static final String LOG_FOLDER_PROPERTY = "srm.log.dir";

    private LoggingSetup() {}

    /**
     * Publishes the log folder, then creates and returns the logger of {@code owner}. Call it
     * once, before anything else that could log.
     */
    static Logger start(Path logFolder, Class<?> owner) {
        System.setProperty(LOG_FOLDER_PROPERTY, logFolder.toString());
        return LoggerFactory.getLogger(owner);
    }
}
