package io.github.clogger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visual integration test: wires SLF4J → Logback → TuiLogAppender and simulates
 * a realistic mix of TRACE/DEBUG diagnostics, slow INFO progress updates, WARN
 * alerts, and ERROR events across all five log levels.
 *
 * Run with: ./gradlew test --tests "*.TuiLogAppenderSimulationTest" -i
 * (redirect stdout to the terminal so ANSI rendering is visible)
 */
class TuiLogAppenderSimulationTest {

    private static final Logger log = LoggerFactory.getLogger(TuiLogAppenderSimulationTest.class);

    @Test
    void simulateSlowProgressWithWarningsAndErrors() throws InterruptedException {
        // ── Phase 1: initial ingestion (INFO × 5, then first WARN) ───────────

        log.trace("JDBC driver loaded: org.postgresql.Driver v42.7.1");
        sleep();

        log.debug("Resolved JDBC URL from $DATABASE_URL → jdbc:postgresql://db.internal:5432/analytics");
        sleep();

        log.info("Connecting to data source at jdbc:postgresql://db.internal:5432/analytics");
        sleep();

        log.trace("TCP handshake complete with db.internal:5432 (12 ms)");
        sleep();

        log.debug("Connection pool initialized: min=2, max=8, idle=2");
        sleep();

        log.info("Schema validation started — scanning 142 tables");
        sleep();

        log.trace("Inspecting table public.events — 47 columns");
        sleep();

        log.trace("Inspecting table public.users — 23 columns");
        sleep();

        log.info("Schema validation complete — 142 tables OK, 0 issues found");
        sleep();

        log.debug("Pipeline YAML parsed: 8 stages, 14 transforms, 3 sinks");
        sleep();

        log.info("Loading pipeline configuration from /etc/etl/pipeline.yaml");
        sleep();

        log.info("Pipeline ready — 8 stages configured, parallelism=4");
        sleep();

        log.warn("Partition 'events_2023_q1' has not been refreshed in 72h — stale data risk");
        sleep();

        // ── Phase 2: record processing (INFO × 10) ────────────────────────────

        TuiProgressBar pb = new TuiProgressBar(10_000);

        log.debug("Spawning 4 worker threads for record processing");
        sleep();

        log.info("Processing records [  1 – 500 / 10 000]  {}", pb.tick(500));
        sleep();

        log.trace("Batch checksum: 9f3a…c21 (500 rows)");
        sleep();

        log.info("Processing records [501 – 1000 / 10 000]  {}", pb.tick(500));
        sleep();

        log.info("Processing records [1001 – 2000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.debug("Connection pool stats: active=4 idle=0 waiters=1");
        sleep();

        log.info("Processing records [2001 – 3000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.info("Processing records [3001 – 4000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.warn("Slow query detected on table 'clickstream' — took 4 312 ms",
                new java.sql.SQLException("query exceeded threshold of 2 000 ms",
                        new java.net.SocketTimeoutException("read timed out")));
        sleep();

        log.info("Processing records [4001 – 5000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.trace("GC pause: young gen, 18 ms");
        sleep();

        log.info("Processing records [5001 – 6000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.info("Processing records [6001 – 7000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.error("Deserialization failed for record id=7 412",
                new java.io.IOException("invalid JSON envelope",
                        new IllegalStateException("unexpected token '<' at position 0")));
        sleep();

        log.debug("Retrying record id=7 412 with relaxed parser");
        sleep();

        log.info("Processing records [7001 – 8000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.info("Processing records [8001 – 9000 / 10 000] {}", pb.tick(1000));
        sleep();

        // ── Phase 3: final flush and aggregation ──────────────────────────────

        log.warn("Retry budget exhausted for batch 19 — 3 records skipped, written to DLQ");
        sleep();

        log.info("Processing records [9001 – 10 000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.error("Stage 'aggregate-by-region' timed out after 30 s — partial results written");
        sleep();

        log.debug("Flushing 9 997 records to output sink");
        sleep();

        log.info("Writing output to s3://data-lake/analytics/run-20260425T143000Z/part-0001.parquet");
        sleep();

        log.trace("Output file fsync complete (412 ms)");
        sleep();

        log.info("Run complete — 9 997 records processed, 3 skipped, 1 stage degraded");
        sleep();
    }

    private static void sleep() throws InterruptedException {
        Thread.sleep(500);
    }
}
