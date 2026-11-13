package com.tuv.fanout.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Collects what a class logs through {@link System.Logger}, which the JDK routes to
 * {@code java.util.logging}. Lets a test assert on the level, text and stack trace of a log
 * line without depending on timing. Close it to detach.
 */
public final class LogCapture implements AutoCloseable {

    /** One captured log line; {@code message} has its parameters substituted. */
    public record Entry(Level level, String message, Throwable thrown) {
    }

    private final Logger logger;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
            entries.add(new Entry(record.getLevel(), formatter.formatMessage(record), record.getThrown()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private LogCapture(Class<?> source) {
        this.logger = Logger.getLogger(source.getName());
        logger.addHandler(handler);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The captured lines of exactly this level, in order. */
    public List<Entry> at(Level level) {
        return entries.stream().filter(entry -> entry.level().equals(level)).toList();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
