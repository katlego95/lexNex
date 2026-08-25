package io.github.katlego95.lexpipeline.observability;

import io.github.katlego95.lexpipeline.pipeline.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The instruments from ARCHITECTURE section 8, in one place.
 *
 * <p>These are chosen to answer operational questions, not to count everything countable:
 *
 * <ul>
 *   <li><strong>quarantine rate</strong> — {@code documents_quarantined_total} over
 *       {@code documents_received_total} is the alert that matters most. A feed that starts
 *       shipping broken XML shows up here minutes before anyone reads a support ticket, and the
 *       {@code reason} tag says whether it is the sender's problem or ours.</li>
 *   <li><strong>duplicate rate</strong> — a redelivery storm looks identical to real traffic in a
 *       request count, and completely different here.</li>
 *   <li><strong>where the time goes</strong> — one timer per stage, so "ingestion got slow" is
 *       immediately either validation, transformation or storage rather than a guess.</li>
 * </ul>
 *
 * <p>Counters are pre-registered at startup rather than created on first use, so a dashboard shows
 * an honest zero instead of a missing series for an outcome that has not happened yet.
 */
@Component
public class PipelineMetrics {

    /** Prometheus renders these as documents_received_total, pipeline_stage_duration_seconds, ... */
    private static final String RECEIVED = "documents.received";
    private static final String PUBLISHED = "documents.published";
    private static final String SUPERSEDED = "documents.superseded";
    private static final String DUPLICATE = "documents.duplicate";
    private static final String QUARANTINED = "documents.quarantined";
    private static final String FAILED = "documents.failed";
    private static final String STAGE_DURATION = "pipeline.stage.duration";

    private final MeterRegistry registry;
    private final Counter received;
    private final Counter published;
    private final Counter superseded;
    private final Counter duplicate;
    private final ConcurrentHashMap<String, Counter> quarantinedByReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> failedByReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.received = counter(RECEIVED, "Documents submitted to the pipeline, by any entry point");
        this.published = counter(PUBLISHED, "Documents published as a new judgment (version 1)");
        this.superseded = counter(SUPERSEDED, "Documents published as a later version");
        this.duplicate = counter(DUPLICATE, "Deliveries identical to the current version: no-ops");

        // Pre-register every reason so the series exist from the first scrape.
        for (Outcome outcome : Outcome.values()) {
            if (outcome.isQuarantined()) {
                quarantinedCounter(outcome);
            }
        }
        for (Stage stage : Stage.values()) {
            stageTimer(stage);
        }
    }

    /** The stages worth timing separately, because they fail and slow down for different reasons. */
    public enum Stage {
        VALIDATE,
        TRANSFORM,
        PUBLISH;

        String tagValue() {
            return name().toLowerCase();
        }
    }

    public void documentReceived() {
        received.increment();
    }

    public void record(Stage stage, Duration duration) {
        stageTimer(stage).record(duration);
    }

    /** One call per document, at the end: exactly one counter moves per outcome. */
    public void record(Outcome outcome) {
        switch (outcome) {
            case PUBLISHED -> published.increment();
            case SUPERSEDED -> superseded.increment();
            case DUPLICATE_NOOP -> duplicate.increment();
            default -> {
                if (outcome.isQuarantined()) {
                    quarantinedCounter(outcome).increment();
                } else {
                    failedCounter(outcome).increment();
                }
            }
        }
    }

    private Counter quarantinedCounter(Outcome outcome) {
        return quarantinedByReason.computeIfAbsent(outcome.name(), reason -> Counter.builder(
                        QUARANTINED)
                .description("Documents refused and written to quarantine")
                .tag("reason", reason)
                .register(registry));
    }

    private Counter failedCounter(Outcome outcome) {
        return failedByReason.computeIfAbsent(outcome.name(), reason -> Counter.builder(FAILED)
                .description("Documents that failed for reasons on our side, not the sender's")
                .tag("reason", reason)
                .register(registry));
    }

    private Timer stageTimer(Stage stage) {
        return stageTimers.computeIfAbsent(stage.tagValue(), tag -> Timer.builder(STAGE_DURATION)
                .description("Time spent in one pipeline stage")
                .tag("stage", tag)
                .publishPercentileHistogram()
                .register(registry));
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}
