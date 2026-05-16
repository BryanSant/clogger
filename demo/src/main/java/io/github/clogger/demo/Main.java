package io.github.clogger.demo;

import io.github.clogger.TuiProgressBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        // ── Phase 1: initial ingestion (INFO × 5, then first WARN) ──────────

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

        // ── Phase 2: record processing (INFO × 10) ──────────────────────────

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

        log.warn("Slow query detected on table 'clickstream' — took 4 312 ms (threshold: 2 000 ms)");
        sleep();

        log.info("Processing records [4001 – 5000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.trace("GC pause: young gen, 18 ms");
        sleep();

        log.info("Processing records [5001 – 6000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.info("Processing records [6001 – 7000 / 10 000] {}", pb.tick(1000));
        sleep();

        try {
            throw new IllegalArgumentException("unexpected token '<' at position 0");
        } catch (Exception e) {
            log.error("Deserialization failed for record id=7 412", e);
        }
        sleep();

        log.debug("Retrying record id=7 412 with relaxed parser");
        sleep();

        log.info("Processing records [7001 – 8000 / 10 000] {}", pb.tick(1000));
        sleep();

        log.info("Processing records [8001 – 9000 / 10 000] {}", pb.tick(1000));
        sleep();

        // ── Phase 3: final flush and aggregation ────────────────────────────

        log.warn("Retry budget exhausted for batch 19 — 3 records skipped, written to DLQ");
        sleep();

        log.info("Processing records [9001 – 10 000 / 10 000] {}", pb.tick(1000));
        sleep();

        try {
            java.net.SocketTimeoutException sock = new java.net.SocketTimeoutException(
                    "read timed out after 10 000 ms");
            java.io.IOException io = new java.io.IOException(
                    "network read failed on shard us-east-1c", sock);
            java.sql.SQLException sql = new java.sql.SQLException(
                    "statement timeout reached", io);
            throw new java.util.concurrent.ExecutionException(
                    "timed out after 30 s — partial results written to staging", sql);
        } catch (Exception e) {
            log.error("Stage 'aggregate-by-region' failed", e);
        }
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
