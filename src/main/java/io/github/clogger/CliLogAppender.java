package io.github.clogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.helpers.MessageFormatter;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A Logback appender that renders INFO, WARN, and ERROR entries as managed
 * ANSI-colored, blockquote-style sections in the terminal.
 *
 * <p>For each level the appender keeps the most recent {@value #LINES_PER_LEVEL}
 * entries and renders them as a stack: newest at the top of the section, oldest
 * at the bottom. Older entries are progressively dimmed.</p>
 *
 * <p>Each line is prefixed with a thin vertical bar ({@code ▎}) colored by
 * severity — green for INFO, yellow for WARN, red for ERROR.</p>
 *
 * <p>Terminal auto-wrap is temporarily disabled around every redraw so that a
 * long message never wraps onto a second visual row and desyncs the cursor
 * math.</p>
 *
 * <p>Two line formats are available via the {@code <format>} element:</p>
 * <ul>
 *   <li>{@code COMPACT} (default) — bar prefix followed by the message.</li>
 *   <li>{@code FULL} — bar, timestamp, thread name, level badge, message.</li>
 * </ul>
 *
 * Configure in {@code logback.xml}:
 * <pre>{@code
 * <!-- COMPACT (default) -->
 * <appender name="CLI" class="io.github.clogger.CliLogAppender"/>
 *
 * <!-- FULL -->
 * <appender name="CLI" class="io.github.clogger.CliLogAppender">
 *     <format>FULL</format>
 *     <datePattern>HH:mm:ss.SSS</datePattern>
 * </appender>
 * }</pre>
 */
public class CliLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Default entries per level if {@code CLOGGER_LINES} is not set. */
    private static final int DEFAULT_LINES_PER_LEVEL = 5;

    /**
     * Max entries retained (and rendered) per severity level.
     * Overridden by the {@code CLOGGER_LINES} environment variable; defaults to
     * {@value #DEFAULT_LINES_PER_LEVEL}. Values below 1 fall back to the default.
     */
    static final int LINES_PER_LEVEL = resolveLinesPerLevel();

    private static int resolveLinesPerLevel() {
        String env = System.getenv("CLOGGER_LINES");
        if (env == null || env.isBlank()) return DEFAULT_LINES_PER_LEVEL;
        try {
            int n = Integer.parseInt(env.trim());
            return n >= 1 ? n : DEFAULT_LINES_PER_LEVEL;
        } catch (NumberFormatException e) {
            return DEFAULT_LINES_PER_LEVEL;
        }
    }

    /** Thin vertical bar prefix — left one-quarter block (U+258E). */
    private static final String BAR = "▎";

    /** Minimum brightness multiplier for the oldest visible entry. */
    private static final double MIN_DIM_FACTOR = 0.25;

    /**
     * Brightness multipliers indexed by position from newest (0) to oldest,
     * linearly interpolated from 1.0 down to {@link #MIN_DIM_FACTOR} across
     * {@link #LINES_PER_LEVEL} positions. Length always equals
     * {@link #LINES_PER_LEVEL}.
     */
    private static final double[] DIM_FACTORS = computeDimFactors(LINES_PER_LEVEL);

    private static double[] computeDimFactors(int n) {
        double[] factors = new double[n];
        if (n == 1) {
            factors[0] = 1.0;
            return factors;
        }
        for (int i = 0; i < n; i++) {
            factors[i] = 1.0 - ((double) i / (n - 1)) * (1.0 - MIN_DIM_FACTOR);
        }
        return factors;
    }

    // ── ANSI escape sequences ────────────────────────────────────────────────

    /** DECAWM off — terminal drops anything past the right margin. */
    private static final String DISABLE_WRAP = "\033[?7l";
    /** DECAWM on — restore normal wrapping behavior. */
    private static final String ENABLE_WRAP  = "\033[?7h";
    /** Erase the entire current line without moving the cursor. */
    private static final String ERASE_LINE   = "\033[2K";
    /** Reset all SGR (color/style) attributes. */
    private static final String RESET        = "\033[0m";

    // ── Base RGB colors per level ────────────────────────────────────────────

    private static final int[] INFO_BAR   = {120, 220, 130};
    private static final int[] INFO_TEXT  = {210, 210, 210};
    private static final int[] WARN_BAR   = {255, 215, 100};
    private static final int[] WARN_TEXT  = {255, 215, 100};
    private static final int[] ERROR_BAR  = {255, 100, 100};
    private static final int[] ERROR_TEXT = {255, 100, 100};
    private static final int[] META_RGB   = {160, 160, 160};

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

    /** Recent events per level — newest at head, oldest at tail. Capped at {@link #LINES_PER_LEVEL}. */
    private final Deque<ILoggingEvent> infoEvents  = new ArrayDeque<>();
    private final Deque<ILoggingEvent> warnEvents  = new ArrayDeque<>();
    private final Deque<ILoggingEvent> errorEvents = new ArrayDeque<>();

    /**
     * The progress bar currently pinned at the top of the INFO section.
     * Subsequent log events whose arguments contain this same instance overwrite
     * the pinned line in place rather than pushing a new entry onto the stack.
     */
    private CliProgressBar liveBar = null;

    /** The most recent log event tied to {@link #liveBar}, or {@code null}. */
    private ILoggingEvent liveProgressEvent = null;

    /**
     * Cached ANSI-rendered messages for graduated progress events. A progress
     * event re-renders against its bar's <em>current</em> state on every draw;
     * once an event is graduated into the INFO stack we freeze its rendering
     * here so further ticks on the bar don't mutate historical lines.
     */
    private final Map<ILoggingEvent, String> frozenMessages = new HashMap<>();

    /** Number of lines currently drawn in the managed area (0 before first draw). */
    private int currentLineCount = 0;

    private boolean cleanedUp = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() {
        sdf = new SimpleDateFormat(datePattern);
        // Logback 1.3+ no longer auto-registers a JVM shutdown hook, so we
        // register one ourselves to guarantee the cursor lands on a fresh line
        // (and auto-wrap is restored) when the process exits.
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
                // Re-enable wrap defensively in case a redraw left it off.
                TERMINAL.print(ENABLE_WRAP);
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
                CliProgressBar bar = findProgressBar(event);
                if (bar != null) {
                    if (liveBar != null && liveBar != bar) {
                        graduateLiveProgress();
                    }
                    liveBar = bar;
                    liveProgressEvent = event;
                } else {
                    if (liveBar != null && liveBar.isDone()) {
                        graduateLiveProgress();
                    }
                    pushBucket(infoEvents, event);
                }
            } else if (level == Level.WARN) {
                pushBucket(warnEvents, event);
            } else if (level.isGreaterOrEqual(Level.ERROR)) {
                pushBucket(errorEvents, event);
            } else {
                return;
            }

            redraw();
        }
    }

    private void pushBucket(Deque<ILoggingEvent> bucket, ILoggingEvent event) {
        bucket.addFirst(event);
        while (bucket.size() > LINES_PER_LEVEL) {
            frozenMessages.remove(bucket.removeLast());
        }
    }

    private static CliProgressBar findProgressBar(ILoggingEvent event) {
        Object[] args = event.getArgumentArray();
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof CliProgressBar) return (CliProgressBar) arg;
        }
        return null;
    }

    /**
     * Snapshot the live progress line's current rendering and move it into the
     * INFO stack. Called when the live bar finishes (a subsequent non-progress
     * INFO event triggers graduation) or when a different bar takes over the
     * live slot.
     */
    private void graduateLiveProgress() {
        if (liveProgressEvent != null) {
            frozenMessages.put(liveProgressEvent, renderMessage(liveProgressEvent));
            pushBucket(infoEvents, liveProgressEvent);
        }
        liveBar = null;
        liveProgressEvent = null;
    }

    /**
     * Redraws the managed area in place.
     *
     * <p>Section order top-to-bottom: INFO, WARN, ERROR. Within each section,
     * newest entry is on top and entries dim as they age.</p>
     *
     * <p>Auto-wrap is disabled for the duration of the draw so a long message
     * occupies exactly one visual row — keeping the cursor-up math correct —
     * then re-enabled before returning.</p>
     */
    private void redraw() {
        StringBuilder sb = new StringBuilder();

        sb.append(DISABLE_WRAP);

        // Return to the start of the managed area. After the previous draw the
        // cursor sits at the end of the last line; going up (count - 1) rows
        // lands on the first line, then \r puts us at column 0.
        if (currentLineCount > 1) {
            sb.append("\033[").append(currentLineCount - 1).append('A');
        }
        sb.append('\r');

        int lineIndex = 0;
        lineIndex = drawInfoSection(sb, lineIndex);
        lineIndex = drawBucket(sb, warnEvents, lineIndex);
        lineIndex = drawBucket(sb, errorEvents, lineIndex);

        currentLineCount = lineIndex;

        sb.append(ENABLE_WRAP);

        TERMINAL.print(sb);
        TERMINAL.flush();
    }

    private int drawInfoSection(StringBuilder sb, int lineIndex) {
        int posFromNewest = 0;
        if (liveProgressEvent != null) {
            if (lineIndex > 0) {
                sb.append('\n').append('\r');
            }
            sb.append(ERASE_LINE).append(formatLine(liveProgressEvent, posFromNewest));
            posFromNewest++;
            lineIndex++;
        }
        int budget = LINES_PER_LEVEL - (liveProgressEvent != null ? 1 : 0);
        int drawn = 0;
        for (ILoggingEvent event : infoEvents) {
            if (drawn >= budget) break;
            if (lineIndex > 0) {
                sb.append('\n').append('\r');
            }
            sb.append(ERASE_LINE).append(formatLine(event, posFromNewest));
            posFromNewest++;
            lineIndex++;
            drawn++;
        }
        return lineIndex;
    }

    private int drawBucket(StringBuilder sb, Deque<ILoggingEvent> bucket, int lineIndex) {
        int posFromNewest = 0;
        for (ILoggingEvent event : bucket) {
            if (lineIndex > 0) {
                sb.append('\n').append('\r');
            }
            sb.append(ERASE_LINE).append(formatLine(event, posFromNewest));
            posFromNewest++;
            lineIndex++;
        }
        return lineIndex;
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private String formatLine(ILoggingEvent event, int posFromNewest) {
        return "FULL".equals(format)
                ? formatLineFull(event, posFromNewest)
                : formatLineCompact(event, posFromNewest);
    }

    private String formatLineCompact(ILoggingEvent event, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String message = messageWithThrowable(event);
        return rgb(barColor(level), f) + BAR + " "
                + rgb(textColor(level), f) + message
                + RESET;
    }

    private String formatLineFull(ILoggingEvent event, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String ts = sdf.format(new Date(event.getTimeStamp()));
        String th = "[" + event.getThreadName() + "]";
        String message = messageWithThrowable(event);

        return rgb(barColor(level), f) + BAR + " "
                + rgb(META_RGB, f) + ts + " "
                + rgb(META_RGB, f) + th + " "
                + rgb(barColor(level), f) + levelBadge(level) + " "
                + rgb(textColor(level), f) + message
                + RESET;
    }

    private static String levelBadge(Level level) {
        if (level == Level.INFO) return "INFO ";
        if (level == Level.WARN) return "WARN ";
        return "ERROR";
    }

    private static int[] barColor(Level level) {
        if (level == Level.INFO) return INFO_BAR;
        if (level == Level.WARN) return WARN_BAR;
        return ERROR_BAR;
    }

    private static int[] textColor(Level level) {
        if (level == Level.INFO) return INFO_TEXT;
        if (level == Level.WARN) return WARN_TEXT;
        return ERROR_TEXT;
    }

    private static double dimFactor(int posFromNewest) {
        int i = Math.min(posFromNewest, DIM_FACTORS.length - 1);
        return DIM_FACTORS[i];
    }

    private static String rgb(int[] base, double factor) {
        int r = clamp((int) Math.round(base[0] * factor));
        int g = clamp((int) Math.round(base[1] * factor));
        int b = clamp((int) Math.round(base[2] * factor));
        return "\033[38;2;" + r + ";" + g + ";" + b + "m";
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private String messageWithThrowable(ILoggingEvent event) {
        String message = renderMessage(event);
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) {
            return message;
        }
        String tMsg = tp.getMessage();
        if (tMsg == null) {
            tMsg = tp.getClassName();
        }
        return message + ": " + tMsg.replaceAll("\\s*\\R\\s*", " ");
    }

    /**
     * Renders the event's message, substituting {@link CliProgressBar#toAnsi()}
     * for any {@link CliProgressBar} arguments. SLF4J's default substitution
     * uses {@code toString()} (plain ASCII), which is what file appenders see;
     * we re-substitute here so the terminal gets the colored bar.
     *
     * <p>Graduated progress events have their rendering frozen at graduation
     * time so further ticks on the bar don't mutate historical lines.</p>
     */
    private String renderMessage(ILoggingEvent event) {
        String frozen = frozenMessages.get(event);
        if (frozen != null) {
            return frozen;
        }
        Object[] args = event.getArgumentArray();
        if (args == null || args.length == 0) {
            return event.getFormattedMessage();
        }
        boolean hasBar = false;
        for (Object arg : args) {
            if (arg instanceof CliProgressBar) {
                hasBar = true;
                break;
            }
        }
        if (!hasBar) {
            return event.getFormattedMessage();
        }
        Object[] rendered = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            rendered[i] = args[i] instanceof CliProgressBar bar
                    ? bar.toAnsi()
                    : args[i];
        }
        return MessageFormatter.arrayFormat(event.getMessage(), rendered).getMessage();
    }
}
