package io.github.clilogger;

import io.github.kusoroadeolu.clique.Clique;
import io.github.kusoroadeolu.clique.components.ProgressBar;
import io.github.kusoroadeolu.clique.configuration.ProgressBarConfiguration;
import io.github.kusoroadeolu.clique.configuration.ProgressBarPreset;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * A progress bar helper that renders two formats:
 * <ul>
 *   <li>{@link #toAnsi()} — ANSI-colored bar via Clique, ideal for {@code CliLogAppender} / TTY.</li>
 *   <li>{@link #toText()} / {@link #toString()} — plain ASCII bar, safe for file appenders.</li>
 * </ul>
 *
 * <p>Usage with {@code CliLogAppender}:</p>
 * <pre>{@code
 * CliProgressBar pb = new CliProgressBar(files.size());
 * for (File f : files) {
 *     process(f);
 *     logger.info("Uploading files … {}", pb.tick().toAnsi());
 * }
 * }</pre>
 *
 * <p>Usage with a file appender (or mixed):</p>
 * <pre>{@code
 * logger.info("Uploading files … {}", pb.tick());   // toString() → plain text
 * }</pre>
 *
 * <p>Custom style (controls fill characters and built-in ANSI colors):</p>
 * <pre>{@code
 * new CliProgressBar(100, ProgressBarPreset.BLOCKS)
 * new CliProgressBar(100, ProgressBarConfiguration.builder().length(30).build())
 * }</pre>
 */
public class CliProgressBar {

    private static final int DEFAULT_WIDTH = 10;

    // Clique's tick() auto-renders to System.out (prepends \r, and appends \n at 100%).
    // Those writes corrupt CliLogAppender's cursor-position bookkeeping.
    // We silence them by temporarily redirecting System.out to /dev/null during tick().
    private static final PrintStream NULL_STREAM =
            new PrintStream(OutputStream.nullOutputStream());

    private final int total;
    private int current = 0;
    private final int barWidth;
    private final ProgressBar cliqueBar;

    public CliProgressBar(int total) {
        this(total, ProgressBarPreset.LINES);
    }

    public CliProgressBar(int total, ProgressBarPreset preset) {
        this(total, ProgressBarConfiguration.fromPreset(preset).length(DEFAULT_WIDTH).build());
    }

    public CliProgressBar(int total, ProgressBarConfiguration config) {
        this.total = total;
        this.barWidth = config.getLength();
        this.cliqueBar = Clique.progressBar(total, config);
    }

    public CliProgressBar tick() {
        return tick(1);
    }

    public CliProgressBar tick(int amount) {
        int safe = Math.min(amount, total - current);
        if (safe > 0) {
            current += safe;
            PrintStream orig = System.out;
            System.setOut(NULL_STREAM);
            try {
                cliqueBar.tick(safe);
            } finally {
                System.setOut(orig);
            }
        }
        return this;
    }

    public CliProgressBar complete() {
        return tick(total - current);
    }

    public boolean isDone() {
        return current >= total;
    }

    /** ANSI-colored bar via Clique — use with {@code CliLogAppender} / TTY output. */
    public String toAnsi() {
        String raw = cliqueBar.get();
        int split = Math.min(barWidth, raw.length());
        return Clique.ink().cyan().on(raw.substring(0, split)) + raw.substring(split);
    }

    /** Plain ASCII bar — safe for file appenders and redirected output. */
    public String toText() {
        int filled = total > 0 ? (int) Math.round((double) current / total * DEFAULT_WIDTH) : 0;
        String bar = "#".repeat(filled) + "-".repeat(DEFAULT_WIDTH - filled);
        int percent = total > 0 ? (int) Math.round((double) current / total * 100) : 0;
        return "[" + bar + "] " + percent + "% (" + current + "/" + total + ")";
    }

    @Override
    public String toString() {
        return toText();
    }
}
