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

    private static final int DEFAULT_WIDTH = 10;

    private static final char FILLED_CHAR = '▰';
    private static final char EMPTY_CHAR  = '▱';

    private static final String CYAN  = "\033[36m";
    private static final String RESET = "\033[0m";

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

    private int percent() {
        return total > 0 ? (int) Math.round((double) current / total * 100) : 0;
    }

    /** ANSI-colored bar — use with {@code TuiLogAppender} / TTY output. */
    public String toAnsi() {
        int filled = filledChars();
        StringBuilder bar = new StringBuilder(barWidth);
        for (int i = 0; i < filled; i++) bar.append(FILLED_CHAR);
        for (int i = filled; i < barWidth; i++) bar.append(EMPTY_CHAR);
        return CYAN + bar + RESET + "  " + percent() + "%";
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
