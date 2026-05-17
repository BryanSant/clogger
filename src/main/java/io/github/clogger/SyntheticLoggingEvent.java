package io.github.clogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Minimal {@link ILoggingEvent} implementation backing the standalone
 * {@code Main} entry point. Holds the fields the TUI appenders actually read
 * — level, timestamp, thread, message, throwable proxy — and returns safe
 * defaults for everything else.
 *
 * <p>The appenders call {@code getLevel()}, {@code getTimeStamp()},
 * {@code getThreadName()}, {@code getFormattedMessage()}, {@code getMessage()},
 * {@code getArgumentArray()}, and {@code getThrowableProxy()}. The unused
 * methods (caller data, MDC, markers, key-value pairs, sequence numbers)
 * return {@code null}, empty collections, or {@code 0} so the
 * {@code UnsynchronizedAppenderBase#doAppend} dispatch path doesn't trip
 * on a null where it expects a value.</p>
 */
final class SyntheticLoggingEvent implements ILoggingEvent {

    private final Level level;
    private final long timestamp;
    private final String threadName;
    private final String message;
    private final IThrowableProxy throwableProxy;
    private final String loggerName;

    SyntheticLoggingEvent(Level level,
                          long timestamp,
                          String threadName,
                          String loggerName,
                          String message,
                          IThrowableProxy throwableProxy) {
        this.level = level;
        this.timestamp = timestamp;
        this.threadName = threadName;
        this.loggerName = loggerName;
        this.message = message;
        this.throwableProxy = throwableProxy;
    }

    @Override public String getThreadName() { return threadName; }
    @Override public Level getLevel() { return level; }
    @Override public String getMessage() { return message; }
    @Override public Object[] getArgumentArray() { return null; }
    @Override public String getFormattedMessage() { return message; }
    @Override public String getLoggerName() { return loggerName; }
    @Override public LoggerContextVO getLoggerContextVO() { return null; }
    @Override public IThrowableProxy getThrowableProxy() { return throwableProxy; }
    @Override public StackTraceElement[] getCallerData() { return new StackTraceElement[0]; }
    @Override public boolean hasCallerData() { return false; }
    @Override public List<Marker> getMarkerList() { return null; }
    @Override public Map<String, String> getMDCPropertyMap() { return Collections.emptyMap(); }
    @Override @SuppressWarnings("deprecation")
    public Map<String, String> getMdc() { return Collections.emptyMap(); }
    @Override public long getTimeStamp() { return timestamp; }
    @Override public int getNanoseconds() { return 0; }
    @Override public long getSequenceNumber() { return 0; }
    @Override public List<KeyValuePair> getKeyValuePairs() { return null; }
    @Override public void prepareForDeferredProcessing() {}
}
