package io.github.clogger;

/**
 * A progress bar helper that renders two formats:
 * <ul>
 *   <li>{@link #toAnsi()} — ANSI-colored bar with a dark-green → light-green
 *       gradient across the fill, ideal for {@code TuiLogAppender} / TTY.</li>
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

    private static final String RESET = "\033[0m";

    /** Dark-green endpoint of the fill gradient (left side of the bar). */
    public static final int[] BAR_RGB_START = {0, 100, 0};

    /** Light-green endpoint of the fill gradient (right side of the bar). */
    public static final int[] BAR_RGB_END = {144, 238, 144};

    /** Bright-green used for the trailing percent text (e.g. "23%"). */
    public static final int[] PERCENT_RGB = {0, 255, 0};

    /**
     * Backwards-compatible single-color base — points at the dark-green
     * gradient start. Empty bar cells and the trailing percent text use this
     * color so they sit visually behind the gradient fill.
     */
    public static final int[] BAR_RGB = BAR_RGB_START;

    /**
     * Maps an RGB triple to an ANSI escape. Appenders supply this with
     * line-level context baked in (age-based dimming, light-background
     * inversion, {@code NO_COLOR} suppression) so each cell of the gradient
     * fades the same way the surrounding text does.
     */
    @FunctionalInterface
    public interface RgbColorizer {
        String escape(int r, int g, int b);
    }

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

    public int getBarWidth() {
        return barWidth;
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
        return toAnsiGradient((r, g, b) -> "\033[38;2;" + r + ";" + g + ";" + b + "m");
    }

    /**
     * ANSI-colored bar with a caller-supplied color escape applied uniformly
     * across the body — fill chars, empty chars, and percent. Retained for
     * callers that want a single flat color; new code should prefer
     * {@link #toAnsiGradient(RgbColorizer)} so the fill carries the
     * dark-green → light-green gradient.
     */
    public String toAnsi(String colorEscape) {
        return colorEscape + toPlainBar() + RESET;
    }

    /**
     * ANSI-colored bar with a dark-green → light-green gradient across the
     * fill. The caller-supplied {@code colorizer} converts each gradient
     * sample (and the dark-green base used for empty cells and percent text)
     * into a final ANSI escape, so age-based dimming, light-background
     * inversion, and {@code NO_COLOR} suppression all run per cell.
     */
    public String toAnsiGradient(RgbColorizer colorizer) {
        return applyGradient(toPlainBar(), barWidth, colorizer);
    }

    /**
     * Re-colors a previously captured {@link #toPlainBar()} snapshot with the
     * same dark→light gradient used by live bars, routing every cell through
     * {@code colorizer}. Used by the level-grouped appender to keep graduated
     * bars in lockstep with surrounding lines as they fade.
     */
    public static String applyGradient(String plainBar, int barWidth, RgbColorizer colorizer) {
        int[] start = BAR_RGB_START;
        int[] end = BAR_RGB_END;
        StringBuilder out = new StringBuilder(plainBar.length() + 64);
        String last = "";
        boolean anyEscape = false;
        int limit = Math.min(barWidth, plainBar.length());
        for (int i = 0; i < limit; i++) {
            char c = plainBar.charAt(i);
            String esc;
            if (c == FILLED_CHAR) {
                double t = barWidth <= 1 ? 0.0 : (double) i / (barWidth - 1);
                int r = lerp(start[0], end[0], t);
                int g = lerp(start[1], end[1], t);
                int b = lerp(start[2], end[2], t);
                esc = colorizer.escape(r, g, b);
            } else {
                esc = colorizer.escape(start[0], start[1], start[2]);
            }
            if (!esc.equals(last)) {
                out.append(esc);
                last = esc;
                if (!esc.isEmpty()) anyEscape = true;
            }
            out.append(c);
        }
        if (limit < plainBar.length()) {
            String tailEsc = colorizer.escape(PERCENT_RGB[0], PERCENT_RGB[1], PERCENT_RGB[2]);
            if (!tailEsc.equals(last)) {
                out.append(tailEsc);
                if (!tailEsc.isEmpty()) anyEscape = true;
            }
            out.append(plainBar, limit, plainBar.length());
        }
        if (anyEscape) out.append(RESET);
        return out.toString();
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
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
