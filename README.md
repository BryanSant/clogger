![Java 25](https://img.shields.io/badge/Java-25-blue) ![Logback 1.5](https://img.shields.io/badge/Logback-1.5-green) ![MIT License](https://img.shields.io/badge/license-MIT-blue)
<img width="850" height="310" alt="clogger" src="https://github.com/user-attachments/assets/8ac300f2-27fe-45bf-93d4-c1fc086a90d2" />


# Clogger

**Clogger — The CLI Logger.** A Logback appender for CLI and terminal applications that replaces the traditional scrolling log wall with a concise, dynamically-updating status display. By default Clogger keeps the most recent entries in a fixed-size chronological buffer and rewrites them in place as new events arrive — older entries dim and drift downward, the newest sits brightest at the top.


## How it works

`TuiLogAppender` (the default) writes directly to `/dev/tty` and uses ANSI escape sequences to erase and rewrite a managed area in place. By default entries appear with the newest at the top and the oldest at the bottom; older entries dim as they age. Once the buffer is full (default 25 lines) the oldest entry rolls off. Both the order and the dimming are configurable — see [Configuration](#configuration) below.

Every line is prefixed with a timestamp (formatted via `datePattern`, default `HH:mm:ss.SSS`), then an italic single-letter level indicator (`𝘛`/`𝘋`/`𝘐`/`𝘞`/`𝘌`) and a heavy right-pointing angle bracket (`❯`), both colored by severity:

| Level | Bar color |
|-------|-----------|
| ERROR | red       |
| WARN  | yellow    |
| INFO  | blue      |
| DEBUG | brown     |
| TRACE | dim gray  |

Terminal auto-wrap is disabled around each redraw so a long message never wraps to a second visual row and desyncs the cursor math.

`TuiProgressBar` is a companion that renders the same bar in two forms: an ANSI-colored cyan bar for the terminal, and plain ASCII for file appenders or log archives. `TuiLogAppender` auto-detects a `TuiProgressBar` argument in a log event and anchors that entry at its first emission point. Subsequent log events carrying the same bar instance overwrite that line in place — even after newer events have pushed it upward in the buffer. Once the bar reaches 100% the rendering freezes so the entry stays stable as later events scroll past.

## Alternative layout: TuiLogLevelAppender

`TuiLogLevelAppender` is an alternative that groups entries into stacked sections by severity instead of streaming them chronologically. Each level keeps its own most-recent-entries deque (default 5 per section); sections stack top-to-bottom in descending severity, so ERROR sits at the top of the display and TRACE at the bottom.

| Level | Position |
|-------|----------|
| ERROR | Top      |
| WARN  |          |
| INFO  | Middle   |
| DEBUG |          |
| TRACE | Bottom   |

In this mode a `TuiProgressBar` is pinned as a single live line at the top of the INFO section. Subsequent ticks overwrite that line in place until the bar reaches 100%, at which point it graduates into the INFO history.

To opt in, swap the appender class in `logback.xml`:

```xml
<appender name="CLI" class="io.github.clogger.TuiLogLevelAppender">
    <format>COMPACT</format>
</appender>
```

## Configuration

Clogger reads configuration from three sources, with later sources overriding earlier ones:

1. **Hard-coded defaults** — used when nothing else is set.
2. **Logback XML setters** — child elements inside `<appender>` are matched, via reflection, to bean-style setters on the appender class (e.g. `<totalLines>40</totalLines>` calls `setTotalLines(40)` during Logback startup). Type coercion is handled by Logback's Joran configurator.
3. **Environment variables** — `CLOGGER_*` env vars are applied at the top of `start()` and override both the XML setters and the defaults. This is the operator escape hatch: an end user can tweak a pre-built binary's TUI behavior without editing `logback.xml`.

No Java system properties are read directly. If you want to drive configuration from system properties anyway, use Logback's built-in `${name}` substitution inside the XML — for example `<totalLines>${myapp.tui.lines:-25}</totalLines>` resolves against system properties, environment variables, and Logback's own context properties (still subject to env-var override).

Both appenders accept the following child elements in `logback.xml`. Every property is optional — defaults are listed.

### `TuiLogAppender` (chronological)

| Element | Default | Purpose |
|---------|---------|---------|
| `<totalLines>` | `25` | Buffer capacity. Overridable by `CLOGGER_LINES`; values `< 1` fall back to `25`. |
| `<order>` | `NEWEST_FIRST` | `NEWEST_FIRST` puts the newest entry at the top; `OLDEST_FIRST` puts it at the bottom. |
| `<dim>` | `true` | When `true`, older entries are progressively dimmed; when `false`, every entry renders at full brightness. |
| `<markup>` | `true` | When `true`, inline `[color]…[/]` and `[bold]…[/]` markup is parsed in messages; when `false`, tags appear verbatim. |
| `<format>` | `COMPACT` | `COMPACT` or `FULL` (see [Format modes](#format-modes)). |
| `<datePattern>` | `HH:mm:ss.SSS` | Timestamp prefix pattern (`SimpleDateFormat`). |

### `TuiLogLevelAppender` (grouped by severity)

| Element | Default | Purpose |
|---------|---------|---------|
| `<lines>` | `5` | Entries retained per severity section. Overridable by `CLOGGER_LINES`; values `< 1` fall back to `5`. |
| `<dim>` | `true` | When `true`, older entries within a section are progressively dimmed. |
| `<markup>` | `true` | When `true`, inline markup tags in messages are parsed. |
| `<format>` | `COMPACT` | `COMPACT` or `FULL`. |
| `<datePattern>` | `HH:mm:ss.SSS` | Timestamp prefix pattern (`SimpleDateFormat`). |

Example with every option set:

```xml
<appender name="CLI" class="io.github.clogger.TuiLogAppender">
    <totalLines>40</totalLines>
    <order>OLDEST_FIRST</order>
    <dim>false</dim>
    <markup>true</markup>
    <format>FULL</format>
    <datePattern>HH:mm:ss.SSS</datePattern>
</appender>
```

### Environment variables

Every XML property has a matching `CLOGGER_*` env var. When set, the env var wins over both the XML setter and the built-in default. Values follow a lowercase convention (`true`/`false`, `newest_first`, `compact`, …); `CLOGGER_DATE_PATTERN` is the one case-sensitive exception since `SimpleDateFormat` patterns require mixed case (`HH:mm:ss.SSS`). Unset, blank, or unrecognized values are silently ignored — the XML/default value is kept.

| Variable | Applies to | Accepted values | Overrides |
|----------|------------|-----------------|-----------|
| `CLOGGER_LINES` | both | positive integer | `<totalLines>` on `TuiLogAppender`; `<lines>` on `TuiLogLevelAppender` |
| `CLOGGER_ORDER` | `TuiLogAppender` only | `newest_first` \| `oldest_first` | `<order>` |
| `CLOGGER_DIM` | both | `true` \| `false` | `<dim>` |
| `CLOGGER_MARKUP` | both | `true` \| `false` | `<markup>` |
| `CLOGGER_FORMAT` | both | `compact` \| `full` | `<format>` |
| `CLOGGER_DATE_PATTERN` | both | `SimpleDateFormat` pattern (case-sensitive) | `<datePattern>` |

Example:

```bash
CLOGGER_LINES=15 CLOGGER_ORDER=oldest_first CLOGGER_DIM=false ./my-cli-tool
```

### `NO_COLOR`

Clogger honors the [`NO_COLOR`](https://no-color.org/) convention. When the `NO_COLOR` environment variable is set to any non-empty value, both appenders suppress every ANSI color escape — level bars, message text, timestamps, progress-bar fills, and inline `[red]…[/]` / `[#ff77ac]…[/]` markup colors all render without color. Per the spec, only color is suppressed: bold, italic, underline, strike, OSC 8 hyperlinks, and the OSC 9;4 taskbar progress indicator still work. The italic level letter (`𝘛`/`𝘋`/`𝘐`/`𝘞`/`𝘌`) and the `❯` bar character remain so each line is still classifiable at a glance. `NO_COLOR` is read once at class load and is independent of the `CLOGGER_*` settings.

```bash
NO_COLOR=1 ./my-cli-tool          # no color, styles + structure preserved
NO_COLOR=1 CLOGGER_MARKUP=false   # additionally disable inline tag parsing entirely
```

## Format modes

Both appenders support two format modes, configured via `<format>` in `logback.xml`:

Every line begins with a timestamp prefix in both modes.

**COMPACT** (default) — timestamp, colored bar, and message:

```
10:42:31.005 ❯ Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
10:42:33.210 ❯ Slow query detected on fact_sales — 4.2 s
10:42:35.887 ❯ Deserialization failure on record 3847
```

**FULL** — timestamp, colored bar, thread, level badge, and message:

```
10:42:31.005 ❯ [main]          INFO  Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
10:42:33.210 ❯ [pipeline-pool] WARN  Slow query detected on fact_sales — 4.2 s
10:42:35.887 ❯ [pipeline-pool] ERROR Deserialization failure on record 3847
```

## When to use Clogger (and when not to)

Clogger is for **interactive terminal output** — CLI tools, local development, build scripts, anything where a human is watching the terminal and benefits from a calm, in-place summary instead of a flood of scrolling lines.

### Do not use Clogger in Kubernetes (or any container-based deployment)

In Kubernetes the platform collects every line your process writes to `stdout` and forwards it to a centralized logging backend (Loki, Elastic, Cloud Logging, Datadog, etc.). Those backends expect one structured log event per line. Clogger does the opposite of that: it rewrites lines in place using ANSI cursor-control escape sequences and only ever retains a bounded window of recent entries. None of that is what a log aggregator wants.

If you ship Clogger to production in a container, you will end up with:

- Cursor-control escape sequences embedded in your centralized logs.
- Missing history — only the last few entries inside the buffer are ever flushed.
- No structured timestamps, threads, or MDC by default.

In a container, configure plain stdout logging through Logback's standard `ConsoleAppender` instead.

### Recommended pattern for server-side frameworks (Spring Boot, Ktor, Quarkus, Micronaut)

Use **two separate `logback.xml` configurations** and pick between them by profile or environment:

1. **Local development** — Clogger writes the live summary to your terminal, and a `RollingFileAppender` captures everything (including DEBUG) to a local file you can `tail -f` or grep through.
2. **Production / Kubernetes** — a plain `ConsoleAppender` writes one structured line per event to stdout; the platform takes it from there. No Clogger, no file appender.

Spring Boot, Quarkus, and Micronaut all support profile-scoped Logback configs (e.g. `logback-spring.xml` with `<springProfile>` blocks, or `logback-dev.xml` / `logback-prod.xml` selected by `LOGBACK_CONFIGURATION_FILE`). Use whichever mechanism your framework provides — the point is that the production config should never reference any `io.github.clogger.*` appender (`TuiLogAppender` or `TuiLogLevelAppender`).

## Logback configuration

### CLI-only (standalone command-line tool)

Use `TuiLogAppender` as the sole appender when the application *is* the terminal session:

```xml
<configuration>
    <appender name="CLI" class="io.github.clogger.TuiLogAppender">
        <!-- COMPACT (default) or FULL -->
        <format>COMPACT</format>
        <!-- Optional: timestamp format used in FULL mode -->
        <datePattern>HH:mm:ss.SSS</datePattern>
    </appender>

    <root level="INFO">
        <appender-ref ref="CLI" />
    </root>
</configuration>
```

### CLI + file (recommended for local development)

Pair `TuiLogAppender` with a `RollingFileAppender` so you get a calm terminal view *and* a complete, timestamped, DEBUG-inclusive log on disk. This is the configuration you want when running a Spring Boot / Ktor / Quarkus / Micronaut service locally.

```xml
<configuration>

    <!-- Live terminal view: rewritten in place. Use a ThresholdFilter to keep
         noisy DEBUG/TRACE events out of the terminal stack — they still flow
         to the file appender below. -->
    <appender name="CLI" class="io.github.clogger.TuiLogAppender">
        <format>COMPACT</format>
        <datePattern>HH:mm:ss.SSS</datePattern>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
    </appender>

    <!-- Full-detail file: everything, including DEBUG, with rotation. -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Root at DEBUG so the file gets everything; the CLI appender's
         ThresholdFilter keeps the terminal at INFO. -->
    <root level="DEBUG">
        <appender-ref ref="CLI" />
        <appender-ref ref="FILE" />
    </root>

</configuration>
```

The file appender receives every event at full verbosity; the CLI appender shows only the live summary. DEBUG/TRACE go to the file only.

### Production / Kubernetes (do NOT use Clogger here)

For comparison, here is the corresponding *production* config — plain stdout, no Clogger:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

Select between the two configs by profile (Spring Boot `logback-spring.xml` with `<springProfile name="dev">` / `<springProfile name="!dev">`, Quarkus `quarkus.log.*` properties, Micronaut `logback-dev.xml` / `logback-prod.xml`, etc.).

## Using TuiProgressBar

Pass a `TuiProgressBar` as a log argument and every appender renders it the way it prefers:

```java
import io.github.clogger.TuiProgressBar;

TuiProgressBar bar = new TuiProgressBar(totalRecords);

for (Record r : records) {
    process(r);
    log.info("Processing records: {}", bar.tick());
}

bar.complete();
log.info("Done: {}", bar);
```

`TuiLogAppender` anchors the bar's entry at its first emission point and overwrites that line in place on each subsequent tick — even after newer events have pushed it upward in the buffer. Once the bar reaches 100% the rendering freezes so the entry stays stable as later events scroll past. (Under `TuiLogLevelAppender` the bar is instead pinned at the top of the INFO section until it reaches 100%, at which point it graduates into the INFO history.) File appenders and other backends see `bar.toString()` (plain ASCII), so no ANSI escapes leak into log files.

Plain text output looks like: `[########--------] 50% (500/1000)`

If you need the ANSI form yourself (e.g. for a custom encoder), call `bar.toAnsi()` explicitly.

## Standalone CLI

Clogger also ships a tiny stdin-driven CLI for re-rendering log streams from tools that already emit Logback's default pattern. Pipe their stdout through `clogger-cli.jar` and you get the in-place TUI experience without changing the upstream tool's logging configuration.

```bash
./gradlew shadowJar                                # build the fat jar (bundles logback)
tail -f app.log | java -jar build/libs/clogger-cli.jar
```

The CLI reads lines from stdin, parses each one against `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n` (Spring's leading `yyyy-MM-dd` date is also accepted), and feeds synthesized `ILoggingEvent`s into the same `TuiLogAppender` the library exposes — so every formatting feature (markup, dim, NO_COLOR, level coloring, progress-bar anchoring) is available unchanged.

### Multi-line entries

Lines that don't match the entry pattern are treated as continuations of the previous entry. After each line the consumer waits up to **25 ms** for the next; once that window elapses with no new input, the buffered entry flushes.

- **Plain multi-line messages** are joined onto the header with single spaces and rendered as one no-wrap row.
- **Java stack traces** are detected by the presence of `\tat ` frames or `Caused by:` headers, parsed into an exception chain, and routed through the appender's two-line throwable layout (the continuation line gets equal-width whitespace where the timestamp prefix would sit, so the bar stays aligned):

  ```
  10:42:35.887 E❯ Deserialization failed: invalid JSON envelope
                ❯ ╰─ IOException → IllegalStateException
  ```

  Stack frames themselves are discarded — the chain class names are what the TUI renders.

### Configuration precedence

`defaults < CLOGGER_* env vars < CLI args`. Every property documented in [Configuration](#configuration) has a matching flag:

| Flag | Equivalent |
|------|------------|
| `-n N`, `--lines N`, `--total-lines N` | `<totalLines>` / `CLOGGER_LINES` |
| `--order newest_first\|oldest_first` | `<order>` / `CLOGGER_ORDER` |
| `--dim [true\|false]` / `--no-dim` | `<dim>` / `CLOGGER_DIM` |
| `--markup [true\|false]` / `--no-markup` | `<markup>` / `CLOGGER_MARKUP` |
| `--format compact\|full` | `<format>` / `CLOGGER_FORMAT` |
| `--date-pattern PATTERN` | `<datePattern>` / `CLOGGER_DATE_PATTERN` |
| `-h`, `--help` | — |

Example:

```bash
tail -f app.log | java -jar build/libs/clogger-cli.jar --lines 30 --format full --no-dim
```

The rendered timestamp prefix uses the appender's `datePattern`, not the timestamp parsed off the input line — so an entry's displayed time is "now" rather than the upstream tool's recorded time. (Set `--date-pattern` to match the input format if you want them to look identical.)

## Building the project

Requires **Java 25** and the included Gradle wrapper.

```bash
# Build the library jar
./gradlew clean jar

# Build the standalone CLI fat jar (includes logback)
./gradlew shadowJar

# Run the unit/simulation tests
./gradlew test

# Run a single visual test (shows live terminal output)
./gradlew test --tests "*.TuiLogAppenderSimulationTest" -i
```

Outputs:
- `build/libs/clogger-1.0.0-SNAPSHOT.jar` — library jar (no `Main-Class`, no bundled deps).
- `build/libs/clogger-cli.jar` — standalone CLI fat jar (`Main-Class` set, logback bundled).

## Running the demo

`demo.sh` builds the library, builds the shadow jar for the demo application, and runs it:

```bash
./demo.sh
```

Or manually:

```bash
# 1. Build the library
./gradlew clean jar

# 2. Build the demo fat jar
cd demo
../gradlew clean build

# 3. Run
java -jar build/libs/demo.jar
```

The demo simulates a realistic ETL pipeline — database connection, schema validation, record processing with a live progress bar, and mixed WARN/ERROR events — so you can see exactly how the appender behaves during a long-running operation.

## Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `ch.qos.logback:logback-classic:1.5.x` | `compileOnly` | Logback SPI (provided by the host application) |

Logback is declared `compileOnly` — your application must provide it at runtime, which it will if you are already using SLF4J/Logback. ANSI rendering and the progress bar are hand-rolled with raw escape sequences, so there are no other runtime dependencies. This assumes a terminal that understands modern ANSI (Linux/macOS terminals, WSL, and Windows Terminal all do).

## License

MIT License. See [LICENSE](LICENSE) for details.
