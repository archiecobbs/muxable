
/*
 * Copyright (C) 2021 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.muxable.simple;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug logging support class.
 */
public class LoggingSupport {

    protected final Logger log;
    protected final String logPrefix;

// Constructors

    /**
     * Default constructor.
     *
     * <p>
     * Uses the {@link Logger} returned by {@link LoggerFactory#getLogger(Class) LoggerFactory.getLogger()}
     * and the log prefix returned by {@link #simpleLogPrefix LogginSupport.simpleLogPrefix()}, when given
     * this instance.
     */
    public LoggingSupport() {
        this.log = LoggerFactory.getLogger(this.getClass());
        this.logPrefix = LoggingSupport.simpleLogPrefix(this);
    }

    /**
     * Constructor.
     *
     * <p>
     * Uses the {@link Logger} returned by {@link LoggerFactory#getLogger(Class) LoggerFactory.getLogger()}
     * and the log prefix returned by {@link #simpleLogPrefix LogginSupport.simpleLogPrefix()}, when given
     * the specified object.
     *
     * @param obj object about which to log
     * @throws IllegalArgumentException if {@code obj} is null
     */
    public LoggingSupport(Object obj) {
        this.logPrefix = LoggingSupport.simpleLogPrefix(obj);
        this.log = LoggerFactory.getLogger(obj.getClass());
    }

    /**
     * Primary constructor.
     *
     * @param log {@link Logger} to use
     * @param logPrefix prefix for all log messages, or null for empty string
     * @throws IllegalArgumentException if {@code log} is null
     */
    public LoggingSupport(Logger log, String logPrefix) {
        if (log == null)
            throw new IllegalArgumentException("null log");
        if (logPrefix == null)
            logPrefix = "";
        this.log = log;
        this.logPrefix = logPrefix;
    }

// Setup stuff

    /**
     * Build a simple log message prefix based on the given object.
     *
     * <p>
     * The implementation in {@link LoggingSupport} returns a prefix containing the given
     * instance's class' simple name and its {@link System#identityHashCode}.
     *
     * @param obj object about which to log
     * @return default log prefix
     * @throws IllegalArgumentException if {@code obj} is null
     */
    public static String simpleLogPrefix(Object obj) {
        if (obj == null)
            throw new IllegalArgumentException("null obj");
        return String.format("%s[%08x]: ", obj.getClass().getSimpleName(), System.identityHashCode(obj));
    }

// Methods

    /**
     * Log at trace level (if enabled).
     *
     * @param format format string for {@link String#format(String, Object[]) String.format()}
     * @param args format string arguments
     */
    protected void trace(String format, Object... args) {
        if (this.log.isTraceEnabled())
            this.log.trace(this.formatLog(format, args));
    }

    /**
     * Log at debug level (if enabled).
     *
     * @param format format string for {@link String#format(String, Object[]) String.format()}
     * @param args format string arguments
     */
    protected void debug(String format, Object... args) {
        if (this.log.isDebugEnabled())
            this.log.debug(this.formatLog(format, args));
    }

    /**
     * Log at info level (if enabled).
     *
     * @param format format string for {@link String#format(String, Object[]) String.format()}
     * @param args format string arguments
     */
    protected void info(String format, Object... args) {
        if (this.log.isInfoEnabled())
            this.log.info(this.formatLog(format, args));
    }

    /**
     * Log at warn level (if enabled).
     *
     * @param format format string for {@link String#format(String, Object[]) String.format()}
     * @param args format string arguments
     */
    protected void warn(String format, Object... args) {
        if (this.log.isWarnEnabled())
            this.log.warn(this.formatLog(format, args));
    }

    /**
     * Log at error level (if enabled).
     *
     * @param format format string for {@link String#format(String, Object[]) String.format()}
     * @param args format string arguments
     */
    protected void error(String format, Object... args) {
        if (this.log.isErrorEnabled())
            this.log.error(this.formatLog(format, args));
    }

    /**
     * Produce a debug-loggable {@link String} version of the given {@link ByteBuffer}.
     *
     * <p>
     * Examples: {@code "[0123456789abcdef0123456789abcdef]"}, {@code "[0123456789abcdef...8 more]"}
     *
     * @param data byte buffer
     * @param maxBytes maximum number of bytes to decode
     * @return initial bytes in {@code data}, or "null" of {@code data} is null
     * @throws IllegalArgumentException if {@code maxBytes} is negative
     */
    protected String toString(ByteBuffer data, int maxBytes) {
        if (maxBytes < 0)
            throw new IllegalArgumentException("maxBytes < 0");
        if (data == null)
            return "null";
        maxBytes = Math.min(maxBytes, data.remaining());
        final StringBuilder buf = new StringBuilder(1/*'['*/ + maxBytes * 2 + 3/*"..."*/ + 10/*remain*/ + 5/*" more"*/ + 1/*']'*/);
        buf.append('[');
        int pos = data.position();
        while (maxBytes-- > 0) {
            final int b = data.get(pos++) & 0xff;
            buf.append(Character.forDigit((b >> 4) & 0xf, 16));
            buf.append(Character.forDigit(b & 0xf, 16));
        }
        if (pos < data.limit())
            buf.append("...").append(data.limit() - pos).append(" more");
        buf.append(']');
        return buf.toString();
    }

    /**
     * Format a log message.
     *
     * <p>
     * The implementation in {@link LoggingSupport} delegates to {@link String#format(String, Object[]) String.format()}
     * and adds {@link #logPrefix} as a prefix.
     *
     * @param format {@link String#format(String, Object[]) String.format()} format string
     * @param args {@link String#format(String, Object[]) String.format()} format arguments
     * @return formatted log message
     */
    protected String formatLog(String format, Object... args) {
        return this.logPrefix + String.format(format, args);
    }
}
