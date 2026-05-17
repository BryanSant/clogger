package io.github.clogger;

import ch.qos.logback.classic.Level;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single line of stdin against Logback's canonical default pattern
 * {@code %d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n}, with an
 * optional {@code yyyy-MM-dd} date prefix to also accept Spring Boot's
 * default ({@code %d{yyyy-MM-dd HH:mm:ss.SSS}}). The {@code [thread]} and
 * {@code level} fields may appear in either order — both {@code [%thread]
 * %-5level} and {@code %-5level [%thread]} are common in real-world configs.
 *
 * <p>A successful match yields a {@link Parsed} record with level, thread,
 * logger, and message. Lines that don't match are continuation lines —
 * either a multi-line message body or part of a stack trace; the caller
 * (the standalone {@code Main}) accumulates them and decides what to do at
 * flush time.</p>
 */
final class LogbackLineParser {

    private static final Pattern ENTRY = Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2}[T ])?"                    // optional yyyy-MM-dd prefix
                    + "(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})"        // 1: HH:mm:ss.SSS or comma
                    + "\\s+(?:"
                    + "\\[([^\\]]+)\\]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)"  // 2,3: [thread] LEVEL
                    + "|"
                    + "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\[([^\\]]+)\\]"  // 4,5: LEVEL [thread]
                    + ")"
                    + "\\s+(\\S+)"                              // 6: logger
                    + "\\s+-\\s+(.*)$");                        // 7: message

    private LogbackLineParser() {}

    /**
     * Returns {@code true} if {@code line} looks like the start of a new
     * Logback entry. Used by the line consumer to decide whether to flush
     * the buffered entry and start a new one.
     */
    static boolean isEntryStart(String line) {
        return ENTRY.matcher(line).matches();
    }

    /**
     * Parses {@code line} as a Logback entry header. Returns {@code null} if
     * the line doesn't match the expected pattern — the caller treats it
     * as a free-form message with default level.
     */
    static Parsed parse(String line) {
        Matcher m = ENTRY.matcher(line);
        if (!m.matches()) return null;
        // Either branch of the alternation populates one (thread, level) pair; pick whichever fired.
        String thread = m.group(2) != null ? m.group(2) : m.group(5);
        String levelStr = m.group(3) != null ? m.group(3) : m.group(4);
        return new Parsed(parseLevel(levelStr), thread, m.group(6), m.group(7));
    }

    private static Level parseLevel(String s) {
        return switch (s) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            default -> Level.INFO;
        };
    }

    record Parsed(Level level, String thread, String logger, String message) {}
}
