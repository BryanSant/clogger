package io.github.clogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.IThrowableProxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Standalone CLI entry point. Reads Logback-formatted log lines from stdin
 * and renders them through {@link TuiLogAppender}, so an existing tool that
 * already prints in Logback's default pattern can be piped through clogger
 * for the in-place TUI experience without changing its logging setup.
 *
 * <p>Pipeline:</p>
 * <ol>
 *   <li>A daemon thread reads lines from stdin into a blocking queue.</li>
 *   <li>The main thread takes lines off the queue and buffers them. Whenever
 *       the buffer is non-empty it waits up to {@value #CONTINUATION_TIMEOUT_MS}
 *       ms for the next line — if a continuation arrives within that window
 *       it joins the buffer; if the window elapses, the buffer flushes.</li>
 *   <li>On flush, the buffered lines are inspected: if any continuation
 *       line looks like a stack frame ({@code "\tat "}) or starts with
 *       {@code "Caused by:"}, an exception chain is reconstructed as a
 *       {@link SyntheticThrowableProxy}; otherwise the continuations are
 *       joined onto the message with single spaces (so the appender's
 *       no-wrap render still works).</li>
 * </ol>
 *
 * <p>Configuration precedence (lowest → highest):
 * defaults &lt; {@code CLOGGER_*} env vars &lt; CLI args. CLI args are
 * applied <em>after</em> {@link TuiLogAppender#applyEnvOverrides()} so they
 * win; the appender's start-time env-override pass is idempotent and
 * becomes a no-op on the second call.</p>
 *
 * <p>Run with:</p>
 * <pre>{@code
 * tail -f app.log | java -jar clogger-cli.jar --lines 30 --format full
 * }</pre>
 */
public final class Main {

    /**
     * Max time the consumer waits for a continuation line before flushing
     * the buffered entry. Picked to be invisible to a human but long enough
     * that a Java stack trace's frames (typically printed in a tight loop)
     * all arrive within one window.
     */
    private static final long CONTINUATION_TIMEOUT_MS = 25;

    /** Sentinel pushed onto the queue when stdin closes. */
    private static final String EOF_SENTINEL = new String("__CLOGGER_EOF__");

    private Main() {}

    public static void main(String[] argv) throws InterruptedException {
        CliArgs args = CliArgs.parse(argv);
        if (args.help) {
            printHelp();
            return;
        }

        TuiLogAppender appender = buildAppender(args);
        appender.start();

        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> readStdin(queue), "clogger-stdin");
        reader.setDaemon(true);
        reader.start();

        try {
            processLoop(queue, appender);
        } finally {
            appender.stop();
        }
    }

    // ── Appender construction ────────────────────────────────────────────────

    private static TuiLogAppender buildAppender(CliArgs args) {
        TuiLogAppender appender = new TuiLogAppender();
        // Env vars first — sets the idempotent flag so start() won't redo it.
        appender.applyEnvOverrides();
        // CLI args on top: highest priority.
        if (args.totalLines != null)  appender.setTotalLines(args.totalLines);
        if (args.order != null)       appender.setOrder(args.order);
        if (args.dim != null)         appender.setDim(args.dim);
        if (args.markup != null)      appender.setMarkup(args.markup);
        if (args.format != null)      appender.setFormat(args.format);
        if (args.datePattern != null) appender.setDatePattern(args.datePattern);
        return appender;
    }

    // ── Stdin reader (daemon thread) ─────────────────────────────────────────

    private static void readStdin(LinkedBlockingQueue<String> queue) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                queue.put(line);
            }
        } catch (Exception ignored) {
            // Best-effort; on read errors we still want EOF pushed below.
        }
        try {
            queue.put(EOF_SENTINEL);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Main loop: collect entries with 25ms continuation window ─────────────

    private static void processLoop(LinkedBlockingQueue<String> queue,
                                    TuiLogAppender appender) throws InterruptedException {
        List<String> buffer = new ArrayList<>();
        while (true) {
            String line = buffer.isEmpty()
                    ? queue.take()
                    : queue.poll(CONTINUATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (line == null) {
                // Continuation timeout — flush whatever we have.
                flush(buffer, appender);
                buffer.clear();
                continue;
            }
            if (line == EOF_SENTINEL) {
                if (!buffer.isEmpty()) flush(buffer, appender);
                return;
            }
            if (LogbackLineParser.isEntryStart(line)) {
                if (!buffer.isEmpty()) {
                    flush(buffer, appender);
                    buffer.clear();
                }
                buffer.add(line);
            } else {
                buffer.add(line);
            }
        }
    }

    // ── Flush: turn buffered lines into one ILoggingEvent ────────────────────

    private static void flush(List<String> buffer, TuiLogAppender appender) {
        if (buffer.isEmpty()) return;
        appender.doAppend(buildEvent(buffer));
    }

    private static SyntheticLoggingEvent buildEvent(List<String> lines) {
        String header = lines.get(0);
        LogbackLineParser.Parsed parsed = LogbackLineParser.parse(header);
        List<String> continuations = lines.size() > 1
                ? lines.subList(1, lines.size())
                : List.of();

        Level level   = parsed != null ? parsed.level()   : Level.INFO;
        String thread = parsed != null ? parsed.thread()  : "stdin";
        String logger = parsed != null ? parsed.logger()  : "stdin";
        String message = parsed != null ? parsed.message() : header;

        IThrowableProxy tp = null;
        if (!continuations.isEmpty()) {
            if (looksLikeStackTrace(continuations)) {
                tp = parseExceptionChain(continuations);
            } else {
                message = joinMultiLine(message, continuations);
            }
        }

        return new SyntheticLoggingEvent(
                level, System.currentTimeMillis(), thread, logger, message, tp);
    }

    /**
     * Joins a multi-line message body onto the header message with single
     * spaces — preserving content while keeping the result on one visual
     * row, since the appender disables auto-wrap and renders one event per
     * row.
     */
    private static String joinMultiLine(String header, List<String> continuations) {
        StringBuilder sb = new StringBuilder(header);
        for (String c : continuations) {
            String stripped = c.strip();
            if (stripped.isEmpty()) continue;
            sb.append(' ').append(stripped);
        }
        return sb.toString();
    }

    // ── Stack trace recognition + chain parsing ──────────────────────────────

    private static boolean looksLikeStackTrace(List<String> lines) {
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("at ")) return true;
            if (line.startsWith("Caused by: ")) return true;
            if (stripped.startsWith("... ") && stripped.contains("more")) return true;
        }
        return false;
    }

    /**
     * Walks the continuation lines and builds an exception chain. Lines
     * starting with {@code "Caused by: "} become chain links; the top-level
     * line is the first non-frame, non-indented line. Stack-frame lines
     * ({@code "\tat ..."}, {@code "\t... N more"}, {@code "Suppressed: ..."})
     * are skipped — the appender's render shows the class chain only, not
     * individual frames.
     */
    private static IThrowableProxy parseExceptionChain(List<String> lines) {
        List<String[]> chain = new ArrayList<>();
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("at ")
                    || stripped.startsWith("... ")
                    || stripped.startsWith("Suppressed: ")) {
                continue;
            }
            String header;
            if (line.startsWith("Caused by: ")) {
                header = line.substring("Caused by: ".length());
            } else if (line.startsWith("\t") || line.startsWith(" ")) {
                continue;
            } else {
                header = line;
            }
            int colon = header.indexOf(": ");
            String className = colon < 0 ? header : header.substring(0, colon);
            String msg       = colon < 0 ? null   : header.substring(colon + 2);
            chain.add(new String[] { className, msg });
        }
        if (chain.isEmpty()) return null;
        // Build from innermost cause outward so each link's cause is already wired.
        IThrowableProxy proxy = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            proxy = new SyntheticThrowableProxy(chain.get(i)[0], chain.get(i)[1], proxy);
        }
        return proxy;
    }

    // ── CLI args ─────────────────────────────────────────────────────────────

    /**
     * Mutable holder for parsed CLI args. A null field means "not specified"
     * — leave whatever env vars or defaults set.
     */
    private static final class CliArgs {
        Integer totalLines;
        String  order;
        Boolean dim;
        Boolean markup;
        String  format;
        String  datePattern;
        boolean help;

        static CliArgs parse(String[] argv) {
            CliArgs args = new CliArgs();
            int i = 0;
            while (i < argv.length) {
                String a = argv[i];
                String inlineVal = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) {
                    inlineVal = a.substring(eq + 1);
                    a = a.substring(0, eq);
                }
                switch (a) {
                    case "-h", "--help" -> { args.help = true; i++; }
                    case "--lines", "--total-lines", "-n" -> {
                        args.totalLines = Integer.parseInt(takeValue(argv, i, inlineVal, a));
                        i += inlineVal != null ? 1 : 2;
                    }
                    case "--order" -> {
                        args.order = takeValue(argv, i, inlineVal, a).toUpperCase();
                        i += inlineVal != null ? 1 : 2;
                    }
                    case "--dim" -> {
                        if (inlineVal != null) {
                            args.dim = Boolean.parseBoolean(inlineVal); i++;
                        } else if (i + 1 < argv.length && isBoolValue(argv[i + 1])) {
                            args.dim = Boolean.parseBoolean(argv[i + 1]); i += 2;
                        } else { args.dim = true; i++; }
                    }
                    case "--no-dim" -> { args.dim = false; i++; }
                    case "--markup" -> {
                        if (inlineVal != null) {
                            args.markup = Boolean.parseBoolean(inlineVal); i++;
                        } else if (i + 1 < argv.length && isBoolValue(argv[i + 1])) {
                            args.markup = Boolean.parseBoolean(argv[i + 1]); i += 2;
                        } else { args.markup = true; i++; }
                    }
                    case "--no-markup" -> { args.markup = false; i++; }
                    case "--format" -> {
                        args.format = takeValue(argv, i, inlineVal, a).toUpperCase();
                        i += inlineVal != null ? 1 : 2;
                    }
                    case "--date-pattern" -> {
                        args.datePattern = takeValue(argv, i, inlineVal, a);
                        i += inlineVal != null ? 1 : 2;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + argv[i]);
                }
            }
            return args;
        }

        private static String takeValue(String[] argv, int i, String inlineVal, String flag) {
            if (inlineVal != null) return inlineVal;
            if (i + 1 >= argv.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return argv[i + 1];
        }

        private static boolean isBoolValue(String s) {
            return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
        }
    }

    private static void printHelp() {
        String help = """
                clogger — Logback log stream renderer for the terminal.

                Usage:
                  ... | clogger [options]
                  clogger [options] < some.log

                Reads lines from stdin assumed to be in Logback's default pattern
                (HH:mm:ss.SSS [thread] LEVEL logger - msg). Continuation lines
                are joined into the preceding entry; stack traces are reformatted
                into clogger's two-line "main : exception / ╰─ Class → Class"
                layout. Markup tags ([bold], [red], [#ff77ac], [link=URL]) in
                the message body are parsed.

                Options:
                  -n, --lines N          Buffer size (overrides CLOGGER_LINES)
                      --order MODE       newest_first | oldest_first
                      --dim [true|false] Progressive dim of older entries
                      --no-dim           Disable dimming
                      --markup [t|f]     Parse inline markup tags
                      --no-markup        Disable markup parsing
                      --format MODE      compact | full
                      --date-pattern P   SimpleDateFormat pattern for --format full
                  -h, --help             Show this help

                Configuration precedence (lowest → highest):
                  built-in defaults < CLOGGER_* env vars < CLI args

                NO_COLOR is honored: setting it suppresses color output.
                """;
        System.out.print(help);
    }
}
