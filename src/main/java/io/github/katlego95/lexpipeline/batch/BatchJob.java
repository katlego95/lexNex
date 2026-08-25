package io.github.katlego95.lexpipeline.batch;

import io.github.katlego95.lexpipeline.pipeline.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live state of one batch: counters and timestamps, and deliberately nothing else.
 *
 * <p><strong>What this class does not hold is the point of it.</strong> No list of discovered
 * files, no list of results, no parsed documents. A batch of ten judgments and a batch of ten
 * million cost the same here — a handful of counters — so memory is flat in the size of the batch.
 * The moment this held a {@code List<PipelineResult>} the service would be one large backfill away
 * from an OOM, and the bounded queue in front of the workers would be pointless.
 *
 * <p>Every field is an atomic because workers report into it concurrently while an HTTP thread
 * reads it.
 */
public class BatchJob {

    private final String batchId;
    private final String inputDir;
    private final Instant startedAt;

    private final AtomicReference<BatchStatus> status =
            new AtomicReference<>(BatchStatus.SCANNING);
    private final AtomicInteger discovered = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();
    private final Map<Outcome, AtomicInteger> outcomes = new EnumMap<>(Outcome.class);
    private final AtomicLong processingNanos = new AtomicLong();
    private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicReference<String> lastSkipReason = new AtomicReference<>();

    BatchJob(String batchId, String inputDir, Instant startedAt) {
        this.batchId = batchId;
        this.inputDir = inputDir;
        this.startedAt = startedAt;
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, new AtomicInteger());
        }
    }

    void documentDiscovered() {
        discovered.incrementAndGet();
    }

    void documentProcessed(Outcome outcome, Duration took) {
        outcomes.get(outcome).incrementAndGet();
        processingNanos.addAndGet(took.toNanos());
        processed.incrementAndGet();
    }

    /**
     * A path the scan refused to read — today, one that resolves outside the input root. Counted
     * separately from {@code discovered} on purpose: it is not a document that failed, it is a
     * file this batch was never entitled to open, so folding it into the outcome counts would
     * misreport the corpus. Only the most recent reason is kept, so the job stays constant-size.
     */
    void documentSkipped(String reason) {
        skipped.incrementAndGet();
        lastSkipReason.set(reason);
    }

    void scanFinished() {
        status.compareAndSet(BatchStatus.SCANNING, BatchStatus.RUNNING);
    }

    void scanFailed(String reason) {
        failure.set(reason);
        status.set(BatchStatus.FAILED);
    }

    /** Called after every document; the batch is done when the scan is over and nothing is left. */
    void completeIfFinished(Instant now) {
        if (status.get() == BatchStatus.RUNNING && processed.get() >= discovered.get()
                && status.compareAndSet(BatchStatus.RUNNING, BatchStatus.COMPLETED)) {
            finishedAt.set(now);
        }
    }

    public String batchId() {
        return batchId;
    }

    public String inputDir() {
        return inputDir;
    }

    public BatchStatus status() {
        return status.get();
    }

    public int discovered() {
        return discovered.get();
    }

    public int processed() {
        return processed.get();
    }

    public String failure() {
        return failure.get();
    }

    public int skipped() {
        return skipped.get();
    }

    public String lastSkipReason() {
        return lastSkipReason.get();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt.get();
    }

    /** @return counts per outcome, zeroes omitted so the response says what happened, not what did not. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        outcomes.forEach((outcome, count) -> {
            if (count.get() > 0) {
                counts.put(outcome.name(), count.get());
            }
        });
        return counts;
    }

    public int count(Outcome outcome) {
        return outcomes.get(outcome).get();
    }

    /** Wall-clock since submission, or the total run time once finished. */
    public Duration elapsed(Instant now) {
        Instant end = finishedAt.get();
        return Duration.between(startedAt, end == null ? now : end);
    }

    /** Summed per-document processing time; divided by the worker count it shows pool utilisation. */
    public Duration totalProcessingTime() {
        return Duration.ofNanos(processingNanos.get());
    }

    public Duration averageProcessingTime() {
        int done = processed.get();
        return done == 0 ? Duration.ZERO : Duration.ofNanos(processingNanos.get() / done);
    }
}
