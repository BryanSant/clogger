package io.github.clogger;

/**
 * A progress bar helper that renders two formats:
 * <ul>
 *   <li>{@link #toAnsi()} — ANSI-colored bar (cyan fill), ideal for {@code TuiLogAppender} / TTY.</li>
 *   <li>{@link #toText()} / {@link #toString()} — plain ASCII bar, safe for file appenders.</li>
 * </ul>
 *
 * <p>Pass the bar as a log argument and each appender picks the right rendering:
 * {@code TuiLogAppender} detects the {@code TuiProgressBar} argument and uses
 * {@link #toAnsi()}; every other appender sees {@link #toString()} (plain ASCII).</p>
 * <pre>{@code
 * TuiProgressBar pb = new TuiProgressBar(files.size());
 * for (File f : files) {
 *     process(f);
 *     logger.info("Uploading files … {}", pb.tick());
 * }
 * }</pre>
 *
 * <p>Custom width:</p>
 * <pre>{@code
 * new TuiProgressBar(100, 30)   // 30-character wide bar
 * }</pre>
 */
public class TuiProgressBar {

    private static final int DEFAULT_WIDTH = 20;

    private static final char FILLED_CHAR = '▰';
    private static final char EMPTY_CHAR  = '▱';

    private static final String CYAN  = "\033[36m";
    private static final String RESET = "\033[0m";

    /**
     * Base RGB approximation of the bar's cyan fill. Exposed so an appender
     * can derive a context-aware variant (e.g. age-based dimming) and feed it
     * back through {@link #toAnsi(String)}.
     */
    public static final int[] BAR_RGB = {60, 200, 220};

    /**
     * OSC 9;4 escape that hides the terminal/taskbar progress indicator.
     * Recognized by Windows Terminal, ConEmu, WezTerm, Ghostty, iTerm2, and
     * other terminals that support the ConEmu progress protocol; silently
     * ignored elsewhere.
     */
    public static final String OSC_PROGRESS_CLEAR = "\033]9;4;0\033\\";

    private final int total;
    private final int barWidth;
    private int current = 0;

    public TuiProgressBar(int total) {
        this(total, DEFAULT_WIDTH);
    }

    public TuiProgressBar(int total, int barWidth) {
        this.total = total;
        this.barWidth = barWidth;
    }

    public TuiProgressBar tick() {
        return tick(1);
    }

    public TuiProgressBar tick(int amount) {
        int safe = Math.min(amount, total - current);
        if (safe > 0) {
            current += safe;
        }
        return this;
    }

    public TuiProgressBar complete() {
        return tick(total - current);
    }

    public boolean isDone() {
        return current >= total;
    }

    private int filledChars() {
        return total > 0 ? (int) Math.round((double) current / total * barWidth) : 0;
    }

    public int percent() {
        return total > 0 ? (int) Math.round((double) current / total * 100) : 0;
    }

    /**
     * Returns an OSC 9;4 escape that sets the terminal/taskbar progress
     * indicator to this bar's current {@link #percent()} in the normal
     * (state = 1) style. Recognized by Windows Terminal, ConEmu, WezTerm,
     * Ghostty, iTerm2, and others; silently ignored by terminals that don't
     * implement the sequence. Pair with {@link #OSC_PROGRESS_CLEAR} to hide
     * the indicator when the work is done.
     */
    public String toOscProgress() {
        return "\033]9;4;1;" + percent() + "\033\\";
    }

    /** ANSI-colored bar — use with {@code TuiLogAppender} / TTY output. */
    public String toAnsi() {
        return CYAN + toPlainBar() + RESET;
    }

    /**
     * ANSI-colored bar with a caller-supplied color escape (e.g. a dimmed
     * cyan computed from the line's position in a log buffer). The escape
     * colors the entire bar body — fill chars, empty chars, and percent —
     * and is followed by a trailing {@code RESET}.
     */
    public String toAnsi(String colorEscape) {
        return colorEscape + toPlainBar() + RESET;
    }

    /**
     * The bar's plain visual: fill chars + empty chars + {@code "  NN%"}, no
     * color. Useful for snapshotting a bar's appearance at a moment in time
     * and re-rendering it later with different coloring.
     */
    public String toPlainBar() {
        int filled = filledChars();
        StringBuilder bar = new StringBuilder(barWidth);
        for (int i = 0; i < filled; i++) bar.append(FILLED_CHAR);
        for (int i = filled; i < barWidth; i++) bar.append(EMPTY_CHAR);
        return bar + "  " + percent() + "%";
    }

    /** Plain ASCII bar — safe for file appenders and redirected output. */
    public String toText() {
        int filled = filledChars();
        String bar = "#".repeat(filled) + "-".repeat(barWidth - filled);
        return "[" + bar + "] " + percent() + "% (" + current + "/" + total + ")";
    }

    @Override
    public String toString() {
        return toText();
    }
}
