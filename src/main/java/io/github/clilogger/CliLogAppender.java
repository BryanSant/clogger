package io.github.clilogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.github.kusoroadeolu.clique.Clique;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A Logback appender that renders INFO, WARN, and ERROR entries as managed
 * ANSI-colored lines in the terminal.
 *
 * <ul>
 *   <li>INFO — a single overwriting line (the previous INFO is replaced in-place).</li>
 *   <li>WARN — a persistent line that appears below INFO on the first WARN event.</li>
 *   <li>ERROR — a persistent line that appears below WARN on the first ERROR event.</li>
 *   <li>DEBUG / TRACE — silently ignored.</li>
 * </ul>
 *
 * <p>Two line formats are available via the {@code <format>} element:</p>
 * <ul>
 *   <li>{@code COMPACT} (default) — a colored bullet (●) followed by the message only.
 *       Light green for INFO, light yellow for WARN, light red for ERROR.</li>
 *   <li>{@code FULL} — timestamp, thread name, level badge, and message.</li>
 * </ul>
 *
 * Configure in {@code logback.xml}:
 * <pre>{@code
 * <!-- COMPACT (default) -->
 * <appender name="CLI" class="io.github.clilogger.CliLogAppender"/>
 *
 * <!-- FULL -->
 * <appender name="CLI" class="io.github.clilogger.CliLogAppender">
 *     <format>FULL</format>
 *     <datePattern>HH:mm:ss.SSS</datePattern>
 * </appender>
 * }</pre>
 */
public class CliLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final String MIDDLE_DOT = "●";

    // ── ANSI escape sequences ────────────────────────────────────────────────

    /** Erase the entire current line without moving the cursor. */
    private static final String ERASE_LINE = "\033[2K";

    // ── Terminal output stream ───────────────────────────────────────────────

    /**
     * Write directly to /dev/tty so that test frameworks (JUnit/Gradle) which
     * replace System.out with a line-buffered capture stream cannot insert
     * newlines between our ANSI cursor-control sequences.
     */
    private static final PrintStream TERMINAL = openTerminal();

    private static PrintStream openTerminal() {
        try {
            return new PrintStream(new FileOutputStream("/dev/tty"), false, StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            return System.out;
        }
    }

    // ── Configurable properties (Logback bean-style setters) ─────────────────

    /** {@code COMPACT} (default) or {@code FULL}. */
    private String format = "COMPACT";

    public void setFormat(String format) {
        this.format = format.toUpperCase(Locale.ROOT);
    }

    public String getFormat() {
        return format;
    }

    private String datePattern = "HH:mm:ss.SSS";

    public void setDatePattern(String datePattern) {
        this.datePattern = datePattern;
    }

    public String getDatePattern() {
        return datePattern;
    }

    // ── Internal state (all access guarded by lock) ──────────────────────────

    private final Object lock = new Object();

    private SimpleDateFormat sdf;

    /** Number of lines currently drawn in the managed area (0 before first draw). */
    private int currentLineCount = 0;

    private boolean hasInfoLine  = false;
    private boolean hasWarnLine  = false;
    private boolean hasErrorLine = false;

    private String lastInfoFormatted  = null;
    private String lastWarnFormatted  = null;
    private String lastErrorFormatted = null;

    private boolean cleanedUp = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() {
        sdf = new SimpleDateFormat(datePattern);
        // Logback 1.3+ no longer auto-registers a JVM shutdown hook, so we
        // register one ourselves to guarantee the cursor lands on a fresh line
        // when the process exits, preventing zsh's PROMPT_SP from printing %.
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::cleanup, "CliLogAppender-shutdown"));
        super.start();
    }

    @Override
    public void stop() {
        cleanup();
        super.stop();
    }

    private void cleanup() {
        synchronized (lock) {
            if (!cleanedUp && currentLineCount > 0) {
                cleanedUp = true;
                TERMINAL.println();
                TERMINAL.flush();
            }
        }
    }

    // ── Core append logic ────────────────────────────────────────────────────

    @Override
    protected void append(ILoggingEvent event) {
        Level level = event.getLevel();

        synchronized (lock) {
            if (level == Level.INFO) {
                hasInfoLine = true;
                lastInfoFormatted = formatLine(event);
            } else if (level == Level.WARN) {
                hasWarnLine = true;
                lastWarnFormatted = formatLine(event);
            } else if (level.isGreaterOrEqual(Level.ERROR)) {
                hasErrorLine = true;
                lastErrorFormatted = formatLine(event);
            } else {
                return;
            }

            redraw();
        }
    }

    /**
     * Redraws all managed lines in-place.
     *
     * <p>Uses cursor-up ({@code \033[NA}) to return to the top of the managed
     * area before rewriting it.  This is scroll-safe: unlike save/restore
     * ({@code \033[s}/{\code \033[u}), cursor-up is relative to the current
     * cursor position and remains correct even after the terminal scrolls when
     * a new line is added near the bottom of the screen.
     *
     * <p>After this method returns the cursor is at the end of the last written
     * line (no trailing newline).
     */
    private void redraw() {
        StringBuilder sb = new StringBuilder();

        // Return to the start of the managed area.
        // After the previous draw the cursor is at the end of the last line.
        // Going up (currentLineCount - 1) rows lands on the first line; \r
        // puts us at column 0.
        if (currentLineCount > 1) {
            sb.append("\033[").append(currentLineCount - 1).append('A');
        }
        sb.append('\r');

        int newLineCount = 0;
        boolean first = true;

        if (hasInfoLine) {
            sb.append(ERASE_LINE).append(lastInfoFormatted);
            first = false;
            newLineCount++;
        }
        if (hasWarnLine) {
            if (!first) sb.append('\n').append('\r');
            sb.append(ERASE_LINE).append(lastWarnFormatted);
            first = false;
            newLineCount++;
        }
        if (hasErrorLine) {
            if (!first) sb.append('\n').append('\r');
            sb.append(ERASE_LINE).append(lastErrorFormatted);
            newLineCount++;
        }

        currentLineCount = newLineCount;

        TERMINAL.print(sb);
        TERMINAL.flush();
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private String formatLine(ILoggingEvent event) {
        return "FULL".equals(format) ? formatLineFull(event) : formatLineCompact(event);
    }

    private String formatLineCompact(ILoggingEvent event) {
        Level  level   = event.getLevel();
        String message = event.getFormattedMessage();
        String line    = MIDDLE_DOT + " " + message;
        if (level == Level.INFO) {
            return Clique.ink().brightGreen().on(line);
        } else if (level == Level.WARN) {
            return Clique.ink().brightYellow().on(line);
        } else {
            return Clique.ink().brightRed().on(line);
        }
    }

    private String formatLineFull(ILoggingEvent event) {
        String timestamp  = sdf.format(new Date(event.getTimeStamp()));
        String threadName = event.getThreadName();
        Level  level      = event.getLevel();
        String message    = event.getFormattedMessage();

        // Timestamp — dark gray
        String ts = Clique.ink().rgb(90, 90, 90).on(timestamp);

        // Thread name — light gray
        String th = Clique.ink().rgb(160, 160, 160).on("[" + threadName + "]");

        // Level badge and message color vary by severity
        String badge;
        String msg;
        if (level == Level.INFO) {
            badge = Clique.ink().brightBlue().on("INFO ");
            msg   = Clique.ink().rgb(192, 192, 192).on(message);
        } else if (level == Level.WARN) {
            badge = Clique.ink().brightYellow().on("WARN ");
            msg   = Clique.ink().brightYellow().on(message);
        } else {
            badge = Clique.ink().brightRed().on("ERROR");
            msg   = Clique.ink().brightRed().on(message);
        }

        return ts + " " + th + " " + badge + " " + msg;
    }
}
