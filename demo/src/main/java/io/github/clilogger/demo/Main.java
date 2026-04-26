package io.github.clilogger.demo;

import io.github.clilogger.CliProgressBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        // ── Phase 1: initial ingestion (INFO × 5, then first WARN) ──────────

        log.info("Connecting to data source at jdbc:postgresql://db.internal:5432/analytics");
        sleep();

        log.info("Schema validation started — scanning 142 tables");
        sleep();

        log.info("Schema validation complete — 142 tables OK, 0 issues found");
        sleep();

        log.info("Loading pipeline configuration from /etc/etl/pipeline.yaml");
        sleep();

        log.info("Pipeline ready — 8 stages configured, parallelism=4");
        sleep();

        log.warn("Partition 'events_2023_q1' has not been refreshed in 72h — stale data risk");
        sleep();

        // ── Phase 2: record processing (INFO × 10) ──────────────────────────

        CliProgressBar pb = new CliProgressBar(10_000);

        log.info("Processing records [  1 – 500 / 10 000]  {}", pb.tick(500).toAnsi());
        sleep();

        log.info("Processing records [501 – 1000 / 10 000]  {}", pb.tick(500).toAnsi());
        sleep();

        log.info("Processing records [1001 – 2000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.info("Processing records [2001 – 3000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.info("Processing records [3001 – 4000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.warn("Slow query detected on table 'clickstream' — took 4 312 ms (threshold: 2 000 ms)");
        sleep();

        log.info("Processing records [4001 – 5000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.info("Processing records [5001 – 6000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.info("Processing records [6001 – 7000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.error("Deserialization failed for record id=7 412: unexpected token '<' at position 0");
        sleep();

        log.info("Processing records [7001 – 8000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.info("Processing records [8001 – 9000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        // ── Phase 3: final flush and aggregation ────────────────────────────

        log.warn("Retry budget exhausted for batch 19 — 3 records skipped, written to DLQ");
        sleep();

        log.info("Processing records [9001 – 10 000 / 10 000] {}", pb.tick(1000).toAnsi());
        sleep();

        log.error("Stage 'aggregate-by-region' timed out after 30 s — partial results written");
        sleep();

        log.info("Writing output to s3://data-lake/analytics/run-20260425T143000Z/part-0001.parquet");
        sleep();

        log.info("Run complete — 9 997 records processed, 3 skipped, 1 stage degraded");
        sleep();
    }

    private static void sleep() throws InterruptedException {
        Thread.sleep(500);
    }
}
