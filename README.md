# CLILogger

A Logback appender for CLI and terminal applications that replaces the traditional scrolling log wall with a concise, dynamically-updating status display. Instead of flooding the terminal, CLILogger manages a small set of "live" lines — one for the current INFO status, one for the latest WARN, one for the latest ERROR — and rewrites them in-place as new events arrive.

![Java 25](https://img.shields.io/badge/Java-25-blue) ![Logback 1.5](https://img.shields.io/badge/Logback-1.5-green) ![MIT License](https://img.shields.io/badge/license-MIT-blue)

## How it works

`CliLogAppender` writes directly to `/dev/tty` and uses ANSI escape sequences to erase and rewrite lines in place. Log levels behave differently:

| Level | Behavior |
|-------|----------|
| INFO | Single overwriting line — each new INFO replaces the previous one |
| WARN | Persistent line pinned below INFO — updated in place on each new WARN |
| ERROR | Persistent line pinned below WARN — updated in place on each new ERROR |
| DEBUG / TRACE | Silently ignored |

The result is a compact, always-current snapshot of what your application is doing, without the noise of a full log tail.

`CliProgressBar` is a companion class that renders a progress bar in two formats: ANSI-colored for the terminal display, and plain ASCII for file appenders or log archives.

## Format modes

`CliLogAppender` supports two format modes, configured via `<format>` in `logback.xml`:

**COMPACT** (default) — a colored bullet and the message only:

```
● Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
● [WARNING] Slow query detected on fact_sales — 4.2 s
● [ERROR]   Deserialization failure on record 3847
```

**FULL** — timestamp, thread, level badge, and message:

```
10:42:31.005 [main          ] [INFO ] Connected to warehouse: jdbc:postgresql://dwh.prod:5432/analytics
10:42:33.210 [pipeline-pool ] [WARN ] Slow query detected on fact_sales — 4.2 s
10:42:35.887 [pipeline-pool ] [ERROR] Deserialization failure on record 3847
```

## Logback configuration

### CLI-only (console application)

Use `CliLogAppender` as the sole appender for interactive terminal programs:

```xml
<configuration>
    <appender name="CLI" class="io.github.clilogger.CliLogAppender">
        <!-- COMPACT (default) or FULL -->
        <format>COMPACT</format>
        <!-- Optional: customize timestamp format (used in FULL mode) -->
        <datePattern>HH:mm:ss.SSS</datePattern>
    </appender>

    <root level="INFO">
        <appender-ref ref="CLI" />
    </root>
</configuration>
```

### CLI + file (recommended for production)

Pair `CliLogAppender` with a standard file appender so operators get the clean terminal view while a full, timestamped log is preserved on disk:

```xml
<configuration>
    <appender name="CLI" class="io.github.clilogger.CliLogAppender">
        <format>COMPACT</format>
        <datePattern>HH:mm:ss.SSS</datePattern>
    </appender>

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

    <root level="INFO">
        <appender-ref ref="CLI" />
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

The file appender receives every log event at full verbosity; the CLI appender shows only the live summary lines. DEBUG logs go to the file only — add `<root level="DEBUG">` and configure the CLI appender's effective level separately if needed:

```xml
    <appender name="CLI" class="io.github.clilogger.CliLogAppender">
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
        <format>COMPACT</format>
    </appender>
```

## Using CliProgressBar

`CliProgressBar` wraps a progress bar and provides two rendering methods so the same object works with both appenders:

```java
import io.github.clilogger.CliProgressBar;

CliProgressBar bar = new CliProgressBar(totalRecords);

for (Record r : records) {
    process(r);
    bar.tick();

    // toAnsi()  → ANSI-colored bar for CliLogAppender / terminal
    // toText()  → plain ASCII bar safe for file appenders
    log.info("Processing records: {}", bar.toAnsi());
    fileLog.info("Processing records: {}", bar.toText());
}

bar.complete();
log.info("Done: {}", bar.toAnsi());
```

Plain text output looks like: `[########--------] 50% (500/1000)`

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

The built jar is placed at `build/libs/clilogger-1.0.0-SNAPSHOT.jar`.

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
| `io.github.kusoroadeolu:clique-core:4.0.1` | `implementation` | ANSI color rendering and progress bar primitives |

Logback is declared `compileOnly` — your application must provide it at runtime, which it will if you are already using SLF4J/Logback.

## License

MIT License. See [LICENSE](LICENSE) for details.
