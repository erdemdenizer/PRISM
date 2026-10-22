package prism.srm.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Collects what one class logs while a test runs. Safe to read while another thread logs. */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    };

    public LogCapture(Class<?> loggingClass) {
        logger = (Logger) LoggerFactory.getLogger(loggingClass);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    public List<String> warnings() {
        return messagesAt(Level.WARN);
    }

    public List<String> errors() {
        return messagesAt(Level.ERROR);
    }

    private List<String> messagesAt(Level level) {
        return events.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
