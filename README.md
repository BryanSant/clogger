![Java 25](https://img.shields.io/badge/Java-25-blue) ![Logback 1.5](https://img.shields.io/badge/Logback-1.5-green) ![MIT License](https://img.shields.io/badge/license-MIT-blue)
<img width="850" height="310" alt="clogger" src="https://github.com/user-attachments/assets/8ac300f2-27fe-45bf-93d4-c1fc086a90d2" />


# Clogger

**Clogger — The CLI Logger.** A Logback appender for CLI and terminal applications that replaces the traditional scrolling log wall with a concise, dynamically-updating status display. By default Clogger keeps the most recent entries in a fixed-size chronological buffer and rewrites them in place as new events arrive — older entries drift upward and dim, the newest sits brightest at the bottom.


## How it works

`TuiLogAppender` (the default) writes directly to `/dev/tty` and uses ANSI escape sequences to erase and rewrite a managed area in place. Entries appear in chronological order: oldest at the top, newest at the bottom. Older entries dim as they drift upward; once the buffer is full (default 25 lines) the oldest entry rolls off the top.

Every line is prefixed with a single-letter level indicator (`T`/`D`/`I`/`W`/`E`) and a thin vertical bar (`│`), both colored by severity:

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

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CLOGGER_LINES` | `25` (TuiLogAppender) / `5` (TuiLogLevelAppender) | Buffer capacity. For `TuiLogAppender` this is the total chronological buffer size; for `TuiLogLevelAppender` it's the per-severity-section entry count. Read once at class load; non-numeric or `<1` values fall back to the default. |

## Format modes

Both appenders support two format modes, configured via `<format>` in `logback.xml`:

**COMPACT** (default) — the colored bar and the message only:

```
│ Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
│ Slow query detected on fact_sales — 4.2 s
│ Deserialization failure on record 3847
```

**FULL** — bar, timestamp, thread, level badge, and message:

```
│ 10:42:31.005 [main]          INFO  Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
│ 10:42:33.210 [pipeline-pool] WARN  Slow query detected on fact_sales — 4.2 s
│ 10:42:35.887 [pipeline-pool] ERROR Deserialization failure on record 3847
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

## Building the project

Requires **Java 25** and the included Gradle wrapper.

```bash
# Build the library jar
./gradlew clean jar

# Run the unit/simulation tests
./gradlew test

# Run a single visual test (shows live terminal output)
./gradlew test --tests "*.TuiLogAppenderSimulationTest" -i
```

The built jar is placed at `build/libs/clogger-1.0.0-SNAPSHOT.jar`.

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
