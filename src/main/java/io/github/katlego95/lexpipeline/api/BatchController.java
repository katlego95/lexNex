package io.github.katlego95.lexpipeline.api;

import io.github.katlego95.lexpipeline.api.ApiResponses.BatchResponse;
import io.github.katlego95.lexpipeline.batch.BatchJob;
import io.github.katlego95.lexpipeline.batch.BatchService;
import io.github.katlego95.lexpipeline.batch.JobRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch ingestion: asynchronous, because a directory of ten thousand judgments is not an HTTP
 * request's worth of work.
 *
 * <p>The POST returns 202 with a batchId as soon as the scan is <em>started</em>, not finished —
 * the scan thread blocks whenever the queue is full, so waiting for it would mean holding a
 * request open for as long as the whole backlog takes. Progress is polled from the GET, which
 * reads counters rather than anything proportional to the batch size.
 */
@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchService batchService;
    private final JobRegistry registry;
    private final Clock clock;

    public BatchController(BatchService batchService, JobRegistry registry, Clock clock) {
        this.batchService = batchService;
        this.registry = registry;
        this.clock = clock;
    }

    /** @param request may be absent entirely, which means "scan the configured input directory". */
    @PostMapping
    public ResponseEntity<BatchResponse> submit(
            @RequestBody(required = false) BatchRequest request) {
        BatchJob job = batchService.submit(request == null ? null : request.inputDir());

        return ResponseEntity.accepted().body(toResponse(job));
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<?> status(@PathVariable String batchId) {
        return registry.find(batchId)
                .<ResponseEntity<?>>map(job -> ResponseEntity.ok(toResponse(job)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Problems.of(HttpStatus.NOT_FOUND, "batch-not-found",
                                "Batch not found",
                                "No batch with id \"" + batchId + "\". Batch state is held in "
                                        + "memory and does not survive a restart.")));
    }

    /** Body of POST /api/v1/batches. */
    public record BatchRequest(String inputDir) {
    }

    private BatchResponse toResponse(BatchJob job) {
        return new BatchResponse(
                job.batchId(),
                job.inputDir(),
                job.status().name(),
                job.discovered(),
                job.processed(),
                job.skipped(),
                job.lastSkipReason(),
                job.counts(),
                job.elapsed(Instant.now(clock)).toMillis(),
                job.averageProcessingTime().toMillis(),
                job.failure(),
                Map.of("self", "/api/v1/batches/" + job.batchId()));
    }
}
