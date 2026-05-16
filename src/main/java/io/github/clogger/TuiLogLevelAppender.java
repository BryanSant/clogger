package io.github.clogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.helpers.MessageFormatter;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Logback appender that renders TRACE, DEBUG, INFO, WARN, and ERROR entries
 * as managed ANSI-colored, blockquote-style sections in the terminal.
 *
 * <p>For each level the appender keeps the most recent {@value #LINES_PER_LEVEL}
 * entries and renders them as a stack: newest at the top of the section, oldest
 * at the bottom. Older entries are progressively dimmed.</p>
 *
 * <p>Sections are stacked top-to-bottom in descending severity:
 * ERROR, WARN, INFO, DEBUG, TRACE.</p>
 *
 * <p>Each line is prefixed with a single-letter level indicator
 * ({@code T}/{@code D}/{@code I}/{@code W}/{@code E}) followed by a thin
 * vertical bar ({@code │}), both colored by severity — dim gray for TRACE,
 * brown for DEBUG, blue for INFO, yellow for WARN, red for ERROR.</p>
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
 * <appender name="CLI" class="io.github.clogger.TuiLogLevelAppender"/>
 *
 * <!-- FULL -->
 * <appender name="CLI" class="io.github.clogger.TuiLogLevelAppender">
 *     <format>FULL</format>
 *     <datePattern>HH:mm:ss.SSS</datePattern>
 * </appender>
 * }</pre>
 */
public class TuiLogLevelAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

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

    /** Thin vertical bar prefix — box drawings light vertical (U+2502). */
    private static final String BAR = "│";

    /** Right arrow used to point at the next class in an exception cause chain. */
    private static final String CAUSE_ARROW = " → ";

    /**
     * Brightness multiplier applied to the {@code ": <throwable.getMessage()>"}
     * suffix on line 1, relative to the line's main color. Lower = darker (or
     * lighter, on a light background) so the throwable's own message is clearly
     * a continuation of the log message rather than part of it.
     */
    private static final double THROWABLE_DIM_FACTOR = 0.7;

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

    private static final int[] TRACE_BAR = {120, 120, 120};
    private static final int[] DEBUG_BAR = {180, 130, 75};
    private static final int[] INFO_BAR  = {110, 170, 240};
    private static final int[] WARN_BAR  = {255, 215, 100};
    private static final int[] ERROR_BAR = {255, 100, 100};
    private static final int[] META_RGB  = {160, 160, 160};

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

    // ── Terminal background detection ────────────────────────────────────────

    /**
     * True when the terminal background looks dark (so older entries should
     * fade toward black). False on a light background, where the fade is
     * reversed to go toward white instead — otherwise dimmed text gets darker
     * and stands out more, the opposite of what dimming should do.
     *
     * <p>Detected by querying the terminal with OSC 11 (the standard
     * "report background color" sequence). Falls back to the {@code COLORFGBG}
     * env var, then to dark.</p>
     */
    private static final boolean DARK_BACKGROUND = detectDarkBackground();

    private static boolean detectDarkBackground() {
        Boolean osc = queryOsc11Background();
        if (osc != null) return osc;
        Boolean env = parseColorFgBg();
        if (env != null) return env;
        return true;
    }

    /**
     * Asks the terminal for its background color via OSC 11 and returns
     * {@code true}/{@code false} for dark/light, or {@code null} if the
     * terminal didn't respond, isn't a TTY, or the reply couldn't be parsed.
     *
     * <p>Requires putting the TTY into raw -echo mode briefly so the reply
     * isn't swallowed by line discipline. The original stty state is always
     * restored in {@code finally}.</p>
     */
    private static Boolean queryOsc11Background() {
        String savedStty = shellCapture("stty -g < /dev/tty");
        if (savedStty == null) return null;
        try {
            if (shell("stty raw -echo < /dev/tty") != 0) return null;
            try (FileOutputStream out = new FileOutputStream("/dev/tty");
                 FileInputStream in   = new FileInputStream("/dev/tty")) {
                out.write("\033]11;?\033\\".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return parseOsc11(readWithTimeout(in, 200));
            }
        } catch (Exception e) {
            return null;
        } finally {
            try { shell("stty " + savedStty + " < /dev/tty"); } catch (Exception ignored) {}
        }
    }

    private static String readWithTimeout(FileInputStream in, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) break;
                sb.append((char) b);
                int len = sb.length();
                // Reply ends with ST (ESC \) or BEL.
                if (sb.charAt(len - 1) == 7) break;
                if (len >= 2 && sb.charAt(len - 2) == 27 && sb.charAt(len - 1) == '\\') break;
            } else {
                Thread.sleep(5);
            }
        }
        return sb.toString();
    }

    private static Boolean parseOsc11(String resp) {
        int idx = resp.indexOf("rgb:");
        if (idx < 0) return null;
        String body = resp.substring(idx + 4);
        int end = body.length();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == 27 || c == 7) { end = i; break; }
        }
        String[] parts = body.substring(0, end).trim().split("/");
        if (parts.length != 3) return null;
        try {
            int r = parseHexChannel(parts[0]);
            int g = parseHexChannel(parts[1]);
            int b = parseHexChannel(parts[2]);
            // Rec. 601 luma on the 0-255 scale.
            double y = 0.299 * r + 0.587 * g + 0.114 * b;
            return y < 128.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** OSC 11 channels are 1-4 hex digits; normalize to an 8-bit value. */
    private static int parseHexChannel(String hex) {
        int v = Integer.parseInt(hex, 16);
        return switch (hex.length()) {
            case 1 -> (v * 0xff) / 0xf;
            case 2 -> v;
            case 3 -> (v * 0xff) / 0xfff;
            case 4 -> (v * 0xff) / 0xffff;
            default -> v & 0xff;
        };
    }

    private static Boolean parseColorFgBg() {
        String env = System.getenv("COLORFGBG");
        if (env == null || env.isBlank()) return null;
        String[] parts = env.split(";");
        if (parts.length < 2) return null;
        try {
            int bg = Integer.parseInt(parts[parts.length - 1].trim());
            // xterm 16-color palette: 0-6 and 8 are dark; 7 and 9-15 are light.
            return bg < 7 || bg == 8;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int shell(String cmd) throws IOException, InterruptedException {
        return new ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    private static String shellCapture(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            return p.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
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
    private final Deque<ILoggingEvent> traceEvents = new ArrayDeque<>();
    private final Deque<ILoggingEvent> debugEvents = new ArrayDeque<>();
    private final Deque<ILoggingEvent> infoEvents  = new ArrayDeque<>();
    private final Deque<ILoggingEvent> warnEvents  = new ArrayDeque<>();
    private final Deque<ILoggingEvent> errorEvents = new ArrayDeque<>();

    /**
     * The progress bar currently pinned at the top of the INFO section.
     * Subsequent log events whose arguments contain this same instance overwrite
     * the pinned line in place rather than pushing a new entry onto the stack.
     */
    private TuiProgressBar liveBar = null;

    /** The most recent log event tied to {@link #liveBar}, or {@code null}. */
    private ILoggingEvent liveProgressEvent = null;

    /**
     * Plain-text snapshots of graduated progress bars (no color). A live
     * progress event re-renders against its bar's <em>current</em> state on
     * every draw; at graduation we freeze the bar body here so further ticks
     * on the bar don't mutate the historical line. Coloring (and age-based
     * dimming) is reapplied at render time, so a graduated bar still fades
     * along with its neighbors as it drifts down the INFO stack.
     */
    private final Map<ILoggingEvent, String> frozenBarPlains = new HashMap<>();

    /**
     * Per-line snapshot of the last redraw's output (line content only — no
     * cursor or erase escapes). Indexed by visual row, length always equals
     * {@link #currentLineCount} after a redraw completes. Used by
     * {@link #emitDiff} to skip unchanged rows and emit only the changed
     * character suffix on partially-changed rows.
     */
    private final List<String> prevLines = new ArrayList<>();

    /** Number of lines currently drawn in the managed area (0 before first draw). */
    private int currentLineCount = 0;

    private boolean cleanedUp = false;

    /**
     * The bar whose state currently drives the terminal/taskbar OSC 9;4
     * indicator — set on every {@link #append} that carries a progress bar.
     * Once a bar completes the reference is kept so the indicator stays at
     * 100% until another bar replaces it or {@link #cleanup} clears it.
     */
    private TuiProgressBar oscBar = null;

    /** Last percent emitted via OSC 9;4, or {@code -1} if currently cleared. */
    private int lastOscPercent = -1;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() {
        sdf = new SimpleDateFormat(datePattern);
        // Logback 1.3+ no longer auto-registers a JVM shutdown hook, so we
        // register one ourselves to guarantee the cursor lands on a fresh line
        // (and auto-wrap is restored) when the process exits.
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::cleanup, "TuiLogLevelAppender-shutdown"));
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
                if (lastOscPercent >= 0) {
                    TERMINAL.print(TuiProgressBar.OSC_PROGRESS_CLEAR);
                    lastOscPercent = -1;
                }
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
            if (level == Level.TRACE) {
                pushBucket(traceEvents, event);
            } else if (level == Level.DEBUG) {
                pushBucket(debugEvents, event);
            } else if (level == Level.INFO) {
                TuiProgressBar bar = findProgressBar(event);
                if (bar != null) {
                    if (liveBar != null && liveBar != bar) {
                        graduateLiveProgress();
                    }
                    liveBar = bar;
                    liveProgressEvent = event;
                    oscBar = bar;
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
            frozenBarPlains.remove(bucket.removeLast());
        }
    }

    private static TuiProgressBar findProgressBar(ILoggingEvent event) {
        Object[] args = event.getArgumentArray();
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof TuiProgressBar) return (TuiProgressBar) arg;
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
            frozenBarPlains.put(liveProgressEvent, liveBar.toPlainBar());
            pushBucket(infoEvents, liveProgressEvent);
        }
        liveBar = null;
        liveProgressEvent = null;
    }

    /**
     * Redraws the managed area in place.
     *
     * <p>Section order top-to-bottom: ERROR, WARN, INFO, DEBUG, TRACE. Within
     * each section, newest entry is on top and entries dim as they age.</p>
     *
     * <p>Auto-wrap is disabled for the duration of the draw so a long message
     * occupies exactly one visual row — keeping the cursor-up math correct —
     * then re-enabled before returning.</p>
     *
     * <p>Each row is diffed against its previous rendering: identical rows
     * emit nothing (cursor just advances), partially-changed rows emit only
     * the divergent suffix via {@link #emitDiff}, and brand-new rows do a
     * full erase + write. This keeps a per-tick progress update down to the
     * handful of characters that actually changed.</p>
     */
    private void redraw() {
        List<String> newLines = new ArrayList<>();
        collectBucket(newLines, errorEvents);
        collectBucket(newLines, warnEvents);
        collectInfoSection(newLines);
        collectBucket(newLines, debugEvents);
        collectBucket(newLines, traceEvents);

        StringBuilder sb = new StringBuilder();
        sb.append(DISABLE_WRAP);

        // Return to the start of the managed area. After the previous draw the
        // cursor sits at the end of the last line; going up (count - 1) rows
        // lands on the first line, then \r puts us at column 0.
        if (currentLineCount > 1) {
            sb.append("\033[").append(currentLineCount - 1).append('A');
        }
        sb.append('\r');

        int max = Math.max(prevLines.size(), newLines.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append('\n').append('\r');
            if (i >= newLines.size()) {
                sb.append(ERASE_LINE);
            } else if (i >= prevLines.size()) {
                sb.append(ERASE_LINE).append(newLines.get(i));
            } else {
                emitDiff(sb, prevLines.get(i), newLines.get(i));
            }
        }

        currentLineCount = newLines.size();
        prevLines.clear();
        prevLines.addAll(newLines);

        sb.append(ENABLE_WRAP);

        if (oscBar != null) {
            int pct = oscBar.percent();
            if (pct != lastOscPercent) {
                sb.append("\033]9;4;1;").append(pct).append("\033\\");
                lastOscPercent = pct;
            }
        }

        TERMINAL.print(sb);
        TERMINAL.flush();
    }

    private void collectInfoSection(List<String> out) {
        int posFromNewest = 0;
        if (liveProgressEvent != null) {
            for (String line : formatLines(liveProgressEvent, posFromNewest)) out.add(line);
            posFromNewest++;
        }
        int budget = LINES_PER_LEVEL - (liveProgressEvent != null ? 1 : 0);
        int drawn = 0;
        for (ILoggingEvent event : infoEvents) {
            if (drawn >= budget) break;
            for (String line : formatLines(event, posFromNewest)) out.add(line);
            posFromNewest++;
            drawn++;
        }
    }

    private void collectBucket(List<String> out, Deque<ILoggingEvent> bucket) {
        int posFromNewest = 0;
        for (ILoggingEvent event : bucket) {
            for (String line : formatLines(event, posFromNewest)) out.add(line);
            posFromNewest++;
        }
    }

    /**
     * Emits the minimum escape sequence + bytes needed to turn {@code oldLine}
     * (already on screen at this row, cursor parked at column 1) into
     * {@code newLine}. No-ops when the lines are equal.
     *
     * <p>Finds the longest common string prefix (raw bytes, including any
     * embedded ANSI escapes), walks it to compute the visible terminal column
     * where the lines diverge and the most recent SGR escape still in effect,
     * then emits cursor-horizontal-absolute → erase-to-EOL → SGR replay →
     * divergent suffix. SGR replay is required because by the time we land
     * mid-line the terminal's SGR state has already been reset by the
     * previous redraw's trailing {@code RESET}; without it the suffix would
     * render uncolored.</p>
     */
    private static void emitDiff(StringBuilder sb, String oldLine, String newLine) {
        if (oldLine.equals(newLine)) return;

        int common = 0;
        int min = Math.min(oldLine.length(), newLine.length());
        while (common < min && oldLine.charAt(common) == newLine.charAt(common)) common++;

        int col = 1;
        String lastSgr = null;
        int j = 0;
        while (j < common) {
            char c = oldLine.charAt(j);
            if (c == 0x1B) {
                // An escape sequence must be fully contained in the common
                // prefix to be useful. If the prefix ends mid-sequence, back
                // up so the divergent suffix begins with the new line's
                // complete escape sequence — otherwise the terminal renders
                // the bare parameter bytes as text.
                if (j + 1 >= common || oldLine.charAt(j + 1) != '[') {
                    common = j;
                    break;
                }
                int end = j + 2;
                while (end < common) {
                    char k = oldLine.charAt(end);
                    if (k == 'm' || Character.isLetter(k)) break;
                    end++;
                }
                if (end >= common) {
                    common = j;
                    break;
                }
                if (oldLine.charAt(end) == 'm') {
                    lastSgr = oldLine.substring(j, end + 1);
                }
                j = end + 1;
            } else {
                col++;
                j++;
            }
        }

        sb.append("\033[").append(col).append('G');
        sb.append("\033[K");
        if (lastSgr != null) sb.append(lastSgr);
        sb.append(newLine, common, newLine.length());
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /**
     * Renders an event as one or two physical lines. Events with a throwable
     * render as two lines: the main message line followed by a continuation
     * line showing the exception's cause-chain class names. Events without a
     * throwable render as a single line.
     */
    private String[] formatLines(ILoggingEvent event, int posFromNewest) {
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp == null) {
            return new String[] { formatLine(event, posFromNewest) };
        }
        return new String[] {
                formatMainLine(event, tp, posFromNewest),
                formatContinuationLine(event, tp, posFromNewest)
        };
    }

    private String formatLine(ILoggingEvent event, int posFromNewest) {
        return "FULL".equals(format)
                ? formatLineFull(event, posFromNewest)
                : formatLineCompact(event, posFromNewest);
    }

    private String formatLineCompact(ILoggingEvent event, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String message = renderMessage(event);
        String color = rgb(barColor(level), f);
        String barPart = renderBarVisual(event, f);
        return color + levelLetter(level) + BAR + " " + barPart + color + message + RESET;
    }

    private String formatLineFull(ILoggingEvent event, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String ts = sdf.format(new Date(event.getTimeStamp()));
        String th = "[" + event.getThreadName() + "]";
        String message = renderMessage(event);
        String color = rgb(barColor(level), f);
        String barPart = renderBarVisual(event, f);

        return color + levelLetter(level) + BAR + " " + barPart
                + rgb(META_RGB, f) + ts + " "
                + rgb(META_RGB, f) + th + " "
                + color + levelBadge(level) + " "
                + color + message
                + RESET;
    }

    /**
     * Line 1 for throwable-bearing events: same as {@link #formatLine} but
     * with the throwable's own {@code getMessage()} appended in a dimmer
     * shade after a {@code ": "} separator, so the boundary between the
     * developer's log message and the exception's detail is visible.
     */
    private String formatMainLine(ILoggingEvent event, IThrowableProxy tp, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        int[] base = barColor(level);
        String mainColor = rgb(base, f);
        String dimColor = rgb(base, f * THROWABLE_DIM_FACTOR);
        String message = renderMessage(event);
        String barPart = renderBarVisual(event, f);
        String raw = tp.getMessage() != null ? tp.getMessage() : tp.getClassName();
        String tMsg = raw.replaceAll("\\s*\\R\\s*", " ");

        if ("FULL".equals(format)) {
            String ts = sdf.format(new Date(event.getTimeStamp()));
            String th = "[" + event.getThreadName() + "]";
            return mainColor + levelLetter(level) + BAR + " " + barPart
                    + rgb(META_RGB, f) + ts + " "
                    + rgb(META_RGB, f) + th + " "
                    + mainColor + levelBadge(level) + " "
                    + mainColor + message
                    + dimColor + ": " + tMsg
                    + RESET;
        }
        return mainColor + levelLetter(level) + BAR + " " + barPart
                + mainColor + message
                + dimColor + ": " + tMsg
                + RESET;
    }

    /**
     * Line 2 for throwable-bearing events: a continuation line whose first
     * column is a space (not the level letter) so it visibly belongs to the
     * preceding line, followed by the exception's class chain — simple class
     * names joined by {@value #CAUSE_ARROW} as we walk {@code getCause()}.
     */
    private String formatContinuationLine(ILoggingEvent event, IThrowableProxy tp, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String color = rgb(barColor(level), f);
        return color + " " + BAR + " ╰─ " + formatChain(tp) + RESET;
    }

    private static String formatChain(IThrowableProxy tp) {
        StringBuilder sb = new StringBuilder();
        for (IThrowableProxy cursor = tp; cursor != null; cursor = cursor.getCause()) {
            if (sb.length() > 0) sb.append(CAUSE_ARROW);
            sb.append(simpleClassName(cursor.getClassName()));
        }
        return sb.toString();
    }

    private static String simpleClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String levelLetter(Level level) {
        if (level == Level.TRACE) return "T";
        if (level == Level.DEBUG) return "D";
        if (level == Level.INFO)  return "I";
        if (level == Level.WARN)  return "W";
        return "E";
    }

    private static String levelBadge(Level level) {
        if (level == Level.TRACE) return "TRACE";
        if (level == Level.DEBUG) return "DEBUG";
        if (level == Level.INFO)  return "INFO ";
        if (level == Level.WARN)  return "WARN ";
        return "ERROR";
    }

    private static int[] barColor(Level level) {
        if (level == Level.TRACE) return TRACE_BAR;
        if (level == Level.DEBUG) return DEBUG_BAR;
        if (level == Level.INFO)  return INFO_BAR;
        if (level == Level.WARN)  return WARN_BAR;
        return ERROR_BAR;
    }

    private static double dimFactor(int posFromNewest) {
        int i = Math.min(posFromNewest, DIM_FACTORS.length - 1);
        return DIM_FACTORS[i];
    }

    private static String rgb(int[] base, double factor) {
        int r, g, b;
        if (DARK_BACKGROUND) {
            r = clamp((int) Math.round(base[0] * factor));
            g = clamp((int) Math.round(base[1] * factor));
            b = clamp((int) Math.round(base[2] * factor));
        } else {
            // Light terminal: lerp from the base color toward white as factor drops.
            double t = 1.0 - factor;
            r = clamp((int) Math.round(base[0] + (255 - base[0]) * t));
            g = clamp((int) Math.round(base[1] + (255 - base[1]) * t));
            b = clamp((int) Math.round(base[2] + (255 - base[2]) * t));
        }
        return "\033[38;2;" + r + ";" + g + ";" + b + "m";
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Renders the event's message body, with any {@link TuiProgressBar}
     * argument substituted by the empty string — the bar visual is rendered
     * separately by {@link #renderBarVisual} and prepended at the line level,
     * so the {@code {}} placeholder for a bar argument contributes nothing
     * to the message body.
     */
    private String renderMessage(ILoggingEvent event) {
        Object[] args = event.getArgumentArray();
        if (args == null || args.length == 0) {
            return event.getFormattedMessage();
        }
        boolean hasBar = false;
        for (Object arg : args) {
            if (arg instanceof TuiProgressBar) {
                hasBar = true;
                break;
            }
        }
        if (!hasBar) {
            return event.getFormattedMessage();
        }
        Object[] rendered = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            rendered[i] = args[i] instanceof TuiProgressBar ? "" : args[i];
        }
        return MessageFormatter.arrayFormat(event.getMessage(), rendered).getMessage();
    }

    /**
     * Returns the ANSI bar visual for a progress event, dimmed against the
     * line's age via {@code dimFactor}, followed by a single space separator.
     * Returns {@code ""} for non-progress events.
     *
     * <p>For a live (pinned) bar we render against the bar's current state.
     * For a graduated bar we render against the plain-text snapshot captured
     * at graduation, so further ticks don't mutate historical lines — but the
     * color escape is still computed fresh each draw so the bar fades along
     * with its neighbors.</p>
     */
    private String renderBarVisual(ILoggingEvent event, double dimFactor) {
        String colorEscape = rgb(TuiProgressBar.BAR_RGB, dimFactor);
        String frozen = frozenBarPlains.get(event);
        if (frozen != null) return colorEscape + frozen + RESET + " ";
        TuiProgressBar bar = findProgressBar(event);
        return bar == null ? "" : bar.toAnsi(colorEscape) + " ";
    }
}
