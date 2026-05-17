package io.github.clogger;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/**
 * Minimal {@link IThrowableProxy} backed by parsed-from-text fields rather
 * than a real {@link Throwable}. Used by the standalone {@code Main} entry
 * point when reconstructing exception chains from Logback-formatted stdin
 * input — we can't easily instantiate the original exception class (it may
 * not be on the classpath, or lack a public {@code (String, Throwable)}
 * constructor), so we synthesize an {@link IThrowableProxy} directly.
 *
 * <p>The TUI appenders only call {@link #getClassName()}, {@link #getMessage()},
 * and {@link #getCause()} when rendering throwables; the remaining interface
 * methods return safe defaults (empty arrays, {@code 0}, {@code false}).</p>
 */
final class SyntheticThrowableProxy implements IThrowableProxy {

    private static final StackTraceElementProxy[] NO_FRAMES = new StackTraceElementProxy[0];
    private static final IThrowableProxy[] NO_SUPPRESSED = new IThrowableProxy[0];

    private final String className;
    private final String message;
    private final IThrowableProxy cause;

    SyntheticThrowableProxy(String className, String message, IThrowableProxy cause) {
        this.className = className;
        this.message = message;
        this.cause = cause;
    }

    @Override public String getMessage() { return message; }
    @Override public String getClassName() { return className; }
    @Override public IThrowableProxy getCause() { return cause; }
    @Override public IThrowableProxy[] getSuppressed() { return NO_SUPPRESSED; }
    @Override public StackTraceElementProxy[] getStackTraceElementProxyArray() { return NO_FRAMES; }
    @Override public int getCommonFrames() { return 0; }
    @Override public boolean isCyclic() { return false; }
}
