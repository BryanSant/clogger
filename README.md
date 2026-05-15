# Clogger

**Clogger — The CLI Logger.** A Logback appender for CLI and terminal applications that replaces the traditional scrolling log wall with a concise, dynamically-updating status display. Instead of flooding the terminal, Clogger keeps a small stack of recent entries per level — INFO, WARN, and ERROR — and rewrites them in place as new events arrive.

![Java 25](https://img.shields.io/badge/Java-25-blue) ![Logback 1.5](https://img.shields.io/badge/Logback-1.5-green) ![MIT License](https://img.shields.io/badge/license-MIT-blue)

## How it works

`CliLogAppender` writes directly to `/dev/tty` and uses ANSI escape sequences to erase and rewrite lines in place. Each level keeps the five most recent entries as a stacked section; newest is on top, older entries are progressively dimmed.

| Level | Behavior |
|-------|----------|
| INFO  | Top section — last 5 INFO entries, newest on top, oldest dimmed |
| WARN  | Middle section — last 5 WARN entries |
| ERROR | Bottom section — last 5 ERROR entries |
| DEBUG / TRACE | Silently ignored |

Every line is prefixed with a thin vertical bar (`▎`) colored by severity — green for INFO, yellow for WARN, red for ERROR. Terminal auto-wrap is disabled around each redraw so a long message never wraps to a second visual row and desyncs the cursor math.

`CliProgressBar` is a companion that renders the same bar in two forms: an ANSI-colored cyan bar for the terminal, and plain ASCII for file appenders or log archives. `CliLogAppender` auto-detects a `CliProgressBar` argument in a log event and pins it as a single live line at the top of the INFO section — subsequent ticks overwrite that line in place until the bar reaches 100%, at which point it graduates into the INFO history.

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CLOGGER_LINES` | `5` | Maximum entries retained and rendered per severity section. `CLOGGER_LINES=10 ./your-app` shows the 10 most recent INFO, WARN, and ERROR entries instead of 5. Read once at class load; non-numeric or `<1` values fall back to the default. |

## Format modes

`CliLogAppender` supports two format modes, configured via `<format>` in `logback.xml`:

**COMPACT** (default) — the colored bar and the message only:

```
▎ Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
▎ Slow query detected on fact_sales — 4.2 s
▎ Deserialization failure on record 3847
```

**FULL** — bar, timestamp, thread, level badge, and message:

```
▎ 10:42:31.005 [main]          INFO  Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
▎ 10:42:33.210 [pipeline-pool] WARN  Slow query detected on fact_sales — 4.2 s
▎ 10:42:35.887 [pipeline-pool] ERROR Deserialization failure on record 3847
```

## When to use Clogger (and when not to)

Clogger is for **interactive terminal output** — CLI tools, local development, build scripts, anything where a human is watching the terminal and benefits from a calm, in-place summary instead of a flood of scrolling lines.

### Do not use Clogger in Kubernetes (or any container-based deployment)

In Kubernetes the platform collects every line your process writes to `stdout` and forwards it to a centralized logging backend (Loki, Elastic, Cloud Logging, Datadog, etc.). Those backends expect one structured log event per line. Clogger does the opposite of that: it rewrites lines in place using ANSI cursor-control escape sequences, it filters DEBUG/TRACE entirely, and it only ever shows the most recent few entries per level. None of that is what a log aggregator wants.

If you ship Clogger to production in a container, you will end up with:

- Cursor-control escape sequences embedded in your centralized logs.
- Dropped DEBUG/TRACE events that you wanted to keep.
- Missing history — only the last few entries per level are ever flushed.
- No structured timestamps, threads, or MDC by default.

In a container, configure plain stdout logging through Logback's standard `ConsoleAppender` instead.

### Recommended pattern for server-side frameworks (Spring Boot, Ktor, Quarkus, Micronaut)

Use **two separate `logback.xml` configurations** and pick between them by profile or environment:

1. **Local development** — Clogger writes the live summary to your terminal, and a `RollingFileAppender` captures everything (including DEBUG) to a local file you can `tail -f` or grep through.
2. **Production / Kubernetes** — a plain `ConsoleAppender` writes one structured line per event to stdout; the platform takes it from there. No Clogger, no file appender.

Spring Boot, Quarkus, and Micronaut all support profile-scoped Logback configs (e.g. `logback-spring.xml` with `<springProfile>` blocks, or `logback-dev.xml` / `logback-prod.xml` selected by `LOGBACK_CONFIGURATION_FILE`). Use whichever mechanism your framework provides — the point is that the production config should never reference `io.github.clogger.CliLogAppender`.

## Logback configuration

### CLI-only (standalone command-line tool)

Use `CliLogAppender` as the sole appender when the application *is* the terminal session:

```xml
<configuration>
    <appender name="CLI" class="io.github.clogger.CliLogAppender">
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

Pair `CliLogAppender` with a `RollingFileAppender` so you get a calm terminal view *and* a complete, timestamped, DEBUG-inclusive log on disk. This is the configuration you want when running a Spring Boot / Ktor / Quarkus / Micronaut service locally.

```xml
<configuration>

    <!-- Live terminal view: only INFO/WARN/ERROR, rewritten in place. -->
    <appender name="CLI" class="io.github.clogger.CliLogAppender">
        <format>COMPACT</format>
        <datePattern>HH:mm:ss.SSS</datePattern>
        <!-- The appender itself ignores DEBUG/TRACE, but keep a ThresholdFilter
             so events below INFO never even reach it. -->
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

## Using CliProgressBar

Pass a `CliProgressBar` as a log argument and every appender renders it the way it prefers:

```java
import io.github.clogger.CliProgressBar;

CliProgressBar bar = new CliProgressBar(totalRecords);

for (Record r : records) {
    process(r);
    log.info("Processing records: {}", bar.tick());
}

bar.complete();
log.info("Done: {}", bar);
```

`CliLogAppender` detects the `CliProgressBar` argument and pins it as a single live line at the top of the INFO section — subsequent ticks overwrite that line in place until the bar reaches 100%, at which point it graduates into the INFO history. File appenders and other backends see `bar.toString()` (plain ASCII), so no ANSI escapes leak into log files.

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
./gradlew test --tests "*.CliLogAppenderSimulationTest" -i
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
