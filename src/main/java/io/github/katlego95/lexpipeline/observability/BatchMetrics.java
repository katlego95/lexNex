package io.github.katlego95.lexpipeline.observability;

import io.github.katlego95.lexpipeline.batch.BatchService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The two gauges that describe saturation: how much work is waiting, and how much is in progress.
 *
 * <p>Separate from {@link PipelineMetrics} to keep the dependency graph acyclic — the batch service
 * depends on the pipeline, which depends on the pipeline's metrics, so the batch's own instruments
 * have to be registered from outside both.
 *
 * <p>Read together they are the scaling signal: {@code queue_depth} pinned at capacity with
 * {@code active_workers} at {@code APP_CONCURRENCY} means the workers are the bottleneck and more
 * of them (or more pods) would help. A deep queue with idle workers means something downstream is
 * blocking instead. In the cloud chapter, queue depth is exactly what an HPA would scale on.
 */
@Component
public class BatchMetrics {

    public BatchMetrics(BatchService batchService, MeterRegistry registry) {
        Gauge.builder("queue.depth", batchService, BatchService::queueDepth)
                .description("Documents waiting in front of the workers")
                .register(registry);

        Gauge.builder("queue.capacity", batchService, BatchService::queueCapacity)
                .description("Configured bound on the queue; depth at capacity means backpressure")
                .register(registry);

        Gauge.builder("active.workers", batchService, BatchService::activeWorkers)
                .description("Workers currently processing a document")
                .register(registry);

        Gauge.builder("worker.pool.size", batchService, BatchService::concurrency)
                .description("Configured worker count (APP_CONCURRENCY)")
                .register(registry);
    }
}
