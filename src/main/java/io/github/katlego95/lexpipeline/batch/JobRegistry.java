package io.github.katlego95.lexpipeline.batch;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory index of batches, so a client that got a 202 can come back and ask how it went.
 *
 * <p>In-memory is a deliberate right-sizing, not an oversight: batch state is operational
 * telemetry, not content, and losing it on restart costs a status lookup rather than a judgment.
 * The cloud version is a DynamoDB item per batch, which is also what makes the status survive more
 * than one pod — noted in SOLUTION.md rather than built here.
 */
@Component
public class JobRegistry {

    private final ConcurrentHashMap<String, BatchJob> jobs = new ConcurrentHashMap<>();
    private final Clock clock;

    public JobRegistry(Clock clock) {
        this.clock = clock;
    }

    BatchJob create(String inputDir) {
        BatchJob job = new BatchJob(UUID.randomUUID().toString(), inputDir, Instant.now(clock));
        jobs.put(job.batchId(), job);
        return job;
    }

    public Optional<BatchJob> find(String batchId) {
        return Optional.ofNullable(jobs.get(batchId));
    }

    public int size() {
        return jobs.size();
    }
}
