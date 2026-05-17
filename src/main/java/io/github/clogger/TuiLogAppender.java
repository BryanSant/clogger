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
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Chronological TUI log appender — renders TRACE/DEBUG/INFO/WARN/ERROR entries
 * in the order they arrived. By default the newest entry sits at the top
 * (configurable via {@code <order>}); the newest entry is brightest and older
 * entries dim as they age (configurable via {@code <dim>}).
 *
 * <p>Holds the most recent {@value #DEFAULT_TOTAL_LINES} entries by default
 * (overridable via the {@code <totalLines>} setter, or the
 * {@code CLOGGER_LINES} environment variable as a default). When the buffer
 * fills, the oldest entry is dropped from the top.</p>
 *
 * <p>Each line is prefixed with a single-letter level indicator
 * ({@code 𝘛}/{@code 𝘋}/{@code 𝘐}/{@code 𝘞}/{@code 𝘌} — mathematical
 * sans-serif italic) followed by a heavy right-pointing angle bracket
 * ({@code ❯}), both colored by severity — dim gray for TRACE, brown for
 * DEBUG, blue for INFO, yellow for WARN, red for ERROR.</p>
 *
 * <p>A {@link TuiProgressBar} passed as a log argument is anchored to its
 * deque position on first emission. Subsequent log events carrying the same
 * bar instance overwrite that entry in place — even after newer events have
 * pushed the bar's line upward. Once the bar reports {@code isDone()} its
 * state is locked (further ticks are no-ops), so the historical line
 * remains stable; it does continue to dim along with its neighbors as it
 * drifts upward. If the bar's anchor line scrolls off the top of the
 * buffer, a subsequent log event carrying that bar starts a fresh entry at
 * the bottom.</p>
 *
 * <p>Terminal auto-wrap is temporarily disabled around every redraw so that a
 * long message never wraps onto a second visual row and desyncs the cursor
 * math.</p>
 *
 * <p>Every line is prefixed with a timestamp formatted via {@code datePattern}
 * (continuation lines of a throwable entry get equal-width whitespace padding
 * so the bar stays aligned). Two line formats are available via the
 * {@code <format>} element:</p>
 * <ul>
 *   <li>{@code COMPACT} (default) — timestamp, bar prefix, message.</li>
 *   <li>{@code FULL} — timestamp, bar prefix, thread name, level badge, message.</li>
 * </ul>
 *
 * <p>Configurable properties (all optional, settable via {@code logback.xml}
 * child elements):</p>
 * <ul>
 *   <li>{@code <totalLines>}{@code N}{@code </totalLines>} — buffer capacity
 *       (default 25; {@code CLOGGER_LINES} env var overrides the default).</li>
 *   <li>{@code <order>NEWEST_FIRST|OLDEST_FIRST</order>} — visual order; default
 *       {@code NEWEST_FIRST} (newest at top).</li>
 *   <li>{@code <dim>true|false</dim>} — whether older entries are progressively
 *       dimmed (default {@code true}).</li>
 *   <li>{@code <markup>true|false</markup>} — whether inline {@code [color]}/
 *       {@code [bold]}-style markup is parsed in messages (default
 *       {@code true}).</li>
 *   <li>{@code <format>COMPACT|FULL</format>} — line format (default
 *       {@code COMPACT}).</li>
 *   <li>{@code <datePattern>} — timestamp prefix format
 *       (default {@code HH:mm:ss.SSS}).</li>
 * </ul>
 *
 * Configure in {@code logback.xml}:
 * <pre>{@code
 * <!-- Defaults -->
 * <appender name="CLI" class="io.github.clogger.TuiLogAppender"/>
 *
 * <!-- Customized -->
 * <appender name="CLI" class="io.github.clogger.TuiLogAppender">
 *     <totalLines>40</totalLines>
 *     <order>OLDEST_FIRST</order>
 *     <dim>false</dim>
 *     <markup>false</markup>
 *     <format>FULL</format>
 *     <datePattern>HH:mm:ss.SSS</datePattern>
 * </appender>
 * }</pre>
 */
public class TuiLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Default total entries retained if no XML setter or env-var override is set. */
    private static final int DEFAULT_TOTAL_LINES = 25;

    /** Bar prefix glyph — heavy right-pointing angle quotation mark ornament (U+276F). */
    private static final String BAR = "❯";

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
    private static final double MIN_DIM_FACTOR = 0.45;

    private static double[] computeDimFactors(int n) {
        double[] factors = new double[n];
        if (n == 1) {
            factors[0] = 1.0;
            return factors;
        }
        // Concave (t²) ramp — newer half stays near full brightness; the fade
        // concentrates in the oldest entries so most of the buffer remains
        // easily legible while age is still visually obvious at the top.
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            factors[i] = 1.0 - t * t * (1.0 - MIN_DIM_FACTOR);
        }
        return factors;
    }

    // ── ANSI escape sequences ────────────────────────────────────────────────

    private static final String DISABLE_WRAP = "\033[?7l";
    private static final String ENABLE_WRAP  = "\033[?7h";
    private static final String ERASE_LINE   = "\033[2K";
    private static final String RESET        = "\033[0m";

    // ── Base RGB colors per level ────────────────────────────────────────────

    private static final int[] TRACE_BAR = {120, 120, 120};
    private static final int[] DEBUG_BAR = {180, 130, 75};
    private static final int[] INFO_BAR  = {110, 170, 240};
    private static final int[] WARN_BAR  = {255, 215, 100};
    private static final int[] ERROR_BAR = {255, 100, 100};
    private static final int[] META_RGB  = {160, 160, 160};

    // ── Terminal output stream ───────────────────────────────────────────────

    private static final PrintStream TERMINAL = openTerminal();

    private static PrintStream openTerminal() {
        try {
            return new PrintStream(new FileOutputStream("/dev/tty"), false, StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            return System.out;
        }
    }

    // ── NO_COLOR honor (https://no-color.org) ────────────────────────────────

    /**
     * {@code true} when the {@code NO_COLOR} env var is set to any non-empty
     * value. In that mode {@link #rgb(int[], double)} short-circuits to the
     * empty string so no color escape sequences are emitted. Non-color signals
     * (bold/italic/underline styles, OSC 8 hyperlinks, OSC 9;4 taskbar
     * progress) still pass through — per the NO_COLOR spec, only color is
     * suppressed.
     */
    private static final boolean NO_COLOR = resolveNoColor();

    private static boolean resolveNoColor() {
        String nc = System.getenv("NO_COLOR");
        return nc != null && !nc.isEmpty();
    }

    // ── Terminal background detection ────────────────────────────────────────

    private static final boolean DARK_BACKGROUND = detectDarkBackground();

    private static boolean detectDarkBackground() {
        Boolean osc = queryOsc11Background();
        if (osc != null) return osc;
        Boolean env = parseColorFgBg();
        if (env != null) return env;
        return true;
    }

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
            double y = 0.299 * r + 0.587 * g + 0.114 * b;
            return y < 128.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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

    /**
     * Buffer capacity — max entries retained and rendered. Default is
     * {@value #DEFAULT_TOTAL_LINES}; the XML setter overrides it, and the
     * {@code CLOGGER_LINES} env var overrides that (applied in
     * {@link #start()}). Values {@code < 1} fall back to the default.
     */
    private int totalLines = DEFAULT_TOTAL_LINES;

    public void setTotalLines(int totalLines) {
        this.totalLines = totalLines >= 1 ? totalLines : DEFAULT_TOTAL_LINES;
    }

    public int getTotalLines() {
        return totalLines;
    }

    /** {@code NEWEST_FIRST} (default — newest at top) or {@code OLDEST_FIRST}. */
    private String order = "NEWEST_FIRST";

    public void setOrder(String order) {
        this.order = order.toUpperCase(Locale.ROOT);
    }

    public String getOrder() {
        return order;
    }

    /** When {@code true} (default), older entries are progressively dimmed. */
    private boolean dim = true;

    public void setDim(boolean dim) {
        this.dim = dim;
    }

    public boolean isDim() {
        return dim;
    }

    /** When {@code true} (default), inline markup tags in messages are parsed. */
    private boolean markup = true;

    public void setMarkup(boolean markup) {
        this.markup = markup;
    }

    public boolean isMarkup() {
        return markup;
    }

    // ── Internal state (all access guarded by lock) ──────────────────────────

    private final Object lock = new Object();

    private SimpleDateFormat sdf;

    /**
     * Character width of a formatted timestamp (computed once in {@link #start()}
     * from {@link #datePattern}). Drives the whitespace padding used in front of
     * continuation lines so the bar column stays aligned with the main line.
     */
    private int tsWidth;

    /**
     * Brightness multipliers indexed by position from newest (0) to oldest,
     * linearly interpolated from 1.0 down to {@link #MIN_DIM_FACTOR} across
     * {@link #totalLines} positions. Computed in {@link #start()}.
     */
    private double[] dimFactors;

    /** Chronological buffer — index 0 = oldest, last = newest. Capped at {@link #totalLines}. */
    private final List<ILoggingEvent> events = new ArrayList<>();

    /**
     * Active progress bars and the deque entry they're anchored to. A bar's
     * entry stays at its original position until {@code isDone()} retires it
     * from this map, or until eviction (oldest-drops) clears the anchor.
     */
    private final IdentityHashMap<TuiProgressBar, ILoggingEvent> liveBars = new IdentityHashMap<>();

    /**
     * Per-line snapshot of the last redraw's output (line content only — no
     * cursor or erase escapes). Indexed by visual row, length always equals
     * {@link #currentLineCount} after a redraw completes. Used by
     * {@link #emitDiff} to skip unchanged rows and emit only the changed
     * character suffix on partially-changed rows.
     */
    private final List<String> prevLines = new ArrayList<>();

    private int currentLineCount = 0;

    private boolean cleanedUp = false;

    /**
     * Tracks whether env-var overrides have already been applied to this
     * instance, so {@link #applyEnvOverrides()} is idempotent. Standalone
     * callers (like {@code io.github.clogger.Main}) invoke
     * {@code applyEnvOverrides()} themselves before applying CLI overrides on
     * top, then call {@link #start()} — which would normally re-apply env
     * vars and clobber the CLI values. The flag makes the second call a
     * no-op so CLI &gt; env precedence is preserved.
     */
    private boolean envOverridesApplied = false;

    /**
     * The bar whose state currently drives the terminal/taskbar OSC 9;4
     * indicator — set on every {@link #append} that carries a progress bar.
     * Once a bar completes, this reference is kept so the indicator stays at
     * 100% until another bar replaces it or {@link #cleanup} clears it.
     */
    private TuiProgressBar oscBar = null;

    /** Last percent emitted via OSC 9;4, or {@code -1} if currently cleared. */
    private int lastOscPercent = -1;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void start() {
        applyEnvOverrides();
        sdf = new SimpleDateFormat(datePattern);
        tsWidth = sdf.format(new Date(0)).length();
        dimFactors = computeDimFactors(totalLines);
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::cleanup, "TuiLogAppender-shutdown"));
        super.start();
    }

    /**
     * Applies {@code CLOGGER_*} environment-variable overrides on top of any
     * XML-configured values. Env vars are the highest-priority configuration
     * source for normal library use — values set here win over both Logback
     * XML setters and the built-in defaults. Idempotent: subsequent calls
     * are no-ops, so a standalone caller can invoke this first, then apply
     * higher-priority CLI overrides via the setters, and {@link #start()}'s
     * call won't clobber them.
     */
    void applyEnvOverrides() {
        if (envOverridesApplied) return;
        envOverridesApplied = true;
        EnvConfig.readInt("CLOGGER_LINES", 1).ifPresent(n -> totalLines = n);
        EnvConfig.readString("CLOGGER_ORDER")
                .ifPresent(s -> order = s.toUpperCase(Locale.ROOT));
        EnvConfig.readBool("CLOGGER_DIM").ifPresent(b -> dim = b);
        EnvConfig.readBool("CLOGGER_MARKUP").ifPresent(b -> markup = b);
        EnvConfig.readString("CLOGGER_FORMAT")
                .ifPresent(s -> format = s.toUpperCase(Locale.ROOT));
        EnvConfig.readString("CLOGGER_DATE_PATTERN")
                .ifPresent(s -> datePattern = s);
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
        synchronized (lock) {
            TuiProgressBar bar = findProgressBar(event);
            if (bar != null) {
                ILoggingEvent anchor = liveBars.get(bar);
                if (anchor != null) {
                    replaceInPlace(anchor, event);
                } else {
                    appendNew(event);
                }
                // A completed bar's state is locked (further ticks are no-ops),
                // so we just stop tracking it for in-place updates. Subsequent
                // redraws still re-render it via the event's bar argument and
                // pick up the current dim factor for its position.
                if (bar.isDone()) {
                    liveBars.remove(bar);
                } else {
                    liveBars.put(bar, event);
                }
            } else {
                appendNew(event);
            }

            if (bar != null) oscBar = bar;

            redraw();
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

    private void appendNew(ILoggingEvent event) {
        events.add(event);
        while (events.size() > totalLines) {
            ILoggingEvent dropped = events.remove(0);
            liveBars.values().removeIf(v -> v == dropped);
        }
    }

    private void replaceInPlace(ILoggingEvent oldEvent, ILoggingEvent newEvent) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) == oldEvent) {
                events.set(i, newEvent);
                return;
            }
        }
        // Anchor was evicted between tracking and replace — treat as new.
        appendNew(newEvent);
    }

    /**
     * Redraws the managed area in place. Oldest at top, newest at bottom;
     * brightness increases toward the newest entry. Auto-wrap is disabled for
     * the duration of the draw so each event occupies exactly one visual row.
     *
     * <p>Each row is diffed against its previous rendering: identical rows
     * emit nothing (cursor just advances), partially-changed rows emit only
     * the divergent suffix via {@link #emitDiff}, and brand-new rows do a
     * full erase + write. This keeps a per-tick progress update down to the
     * handful of characters that actually changed.</p>
     */
    private void redraw() {
        List<String> newLines = buildLines();

        StringBuilder sb = new StringBuilder();
        sb.append(DISABLE_WRAP);

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

    private List<String> buildLines() {
        List<String> out = new ArrayList<>();
        int total = events.size();
        boolean newestFirst = "NEWEST_FIRST".equals(order);
        for (int i = 0; i < total; i++) {
            int idx = newestFirst ? total - 1 - i : i;
            ILoggingEvent event = events.get(idx);
            int posFromNewest = total - 1 - idx;
            for (String line : formatLines(event, posFromNewest)) {
                out.add(line);
            }
        }
        return out;
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
        String color = rgb(barColor(level), f);
        String message = renderBody(event, color, f);
        String barPart = renderBarVisual(event, f);
        return tsPrefix(event, f) + color + "❬" + levelLetter(level) + "❭ " + barPart + color + message + RESET;
    }

    private String formatLineFull(ILoggingEvent event, int posFromNewest) {
        double f = dimFactor(posFromNewest);
        Level level = event.getLevel();
        String th = "[" + event.getThreadName() + "]";
        String color = rgb(barColor(level), f);
        String message = renderBody(event, color, f);
        String barPart = renderBarVisual(event, f);

        return tsPrefix(event, f)
                + color + "❬" + levelLetter(level) + "❭ " + barPart
                + rgb(META_RGB, f) + th + " "
                + color + levelBadge(level) + " "
                + color + message
                + RESET;
    }

    /**
     * Colored timestamp used as the prefix of every main (non-continuation)
     * rendered line. Immediately followed by the bracketed level marker
     * ({@code ❬L❭ }).
     */
    private String tsPrefix(ILoggingEvent event, double f) {
        return rgb(META_RGB, f) + sdf.format(new Date(event.getTimeStamp()));
    }

    /** Whitespace of equal width to {@link #tsPrefix} for continuation lines. */
    private String tsPadding() {
        return " ".repeat(tsWidth + 1);
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
        String message = renderBody(event, mainColor, f);
        String barPart = renderBarVisual(event, f);
        String raw = tp.getMessage() != null ? tp.getMessage() : tp.getClassName();
        String tMsg = raw.replaceAll("\\s*\\R\\s*", " ");

        if ("FULL".equals(format)) {
            String th = "[" + event.getThreadName() + "]";
            return tsPrefix(event, f)
                    + mainColor + "❬" + levelLetter(level) + "❭ " + barPart
                    + rgb(META_RGB, f) + th + " "
                    + mainColor + levelBadge(level) + " "
                    + mainColor + message
                    + dimColor + ": " + tMsg
                    + RESET;
        }
        return tsPrefix(event, f)
                + mainColor + "❬" + levelLetter(level) + "❭ " + barPart
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
        return tsPadding() + color + "    ╰─ " + formatChain(tp) + RESET;
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
        if (level == Level.TRACE) return "𝘛";
        if (level == Level.DEBUG) return "𝘋";
        if (level == Level.INFO)  return "𝘐";
        if (level == Level.WARN)  return "𝘞";
        return "𝘌";
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

    private double dimFactor(int posFromNewest) {
        if (!dim) return 1.0;
        int i = Math.min(posFromNewest, dimFactors.length - 1);
        return dimFactors[i];
    }

    /**
     * Renders the formatted message body for a line, applying inline markup
     * parsing when {@link #markup} is enabled. When disabled, returns the raw
     * formatted message so any {@code [tag]} characters appear verbatim.
     */
    private String renderBody(ILoggingEvent event, String currentColor, double f) {
        String raw = renderMessage(event);
        if (!markup) return raw;
        return MarkupParser.parse(raw, currentColor, dimTransform(f));
    }

    /**
     * Markup color transform bound to a line's dim factor — so {@code [red]}
     * and {@code [#ff77ac]} in a message body fade with age the same way the
     * line's level color does, instead of staying full-saturation while
     * everything around them dims.
     */
    private static MarkupParser.ColorTransform dimTransform(double factor) {
        return (r, g, b) -> rgb(new int[]{r, g, b}, factor);
    }

    private static String rgb(int[] base, double factor) {
        if (NO_COLOR) return "";
        int r, g, b;
        if (DARK_BACKGROUND) {
            r = clamp((int) Math.round(base[0] * factor));
            g = clamp((int) Math.round(base[1] * factor));
            b = clamp((int) Math.round(base[2] * factor));
        } else {
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
     * line's age (via {@code dimFactor}), followed by a single space
     * separator. Returns {@code ""} for non-progress events.
     *
     * <p>Live and completed bars use the same render path: a completed bar's
     * state is immutable, so {@code bar.toAnsiGradient(...)} produces the same
     * body every draw — only the dim factor varies as the entry drifts
     * upward, fading the gradient toward the background.</p>
     */
    private String renderBarVisual(ILoggingEvent event, double dimFactor) {
        TuiProgressBar bar = findProgressBar(event);
        if (bar == null) return "";
        return bar.toAnsiGradient((r, g, b) -> rgb(new int[]{r, g, b}, dimFactor)) + " ";
    }
}
