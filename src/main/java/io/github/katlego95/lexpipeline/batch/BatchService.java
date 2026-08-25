package io.github.katlego95.lexpipeline.batch;

import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.pipeline.DocumentPipeline;
import io.github.katlego95.lexpipeline.pipeline.Outcome;
import io.github.katlego95.lexpipeline.pipeline.PipelineResult;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/**
 * Discovers documents in a directory and feeds them to a fixed pool of workers through a bounded
 * queue.
 *
 * <p><strong>Backpressure, not buffering.</strong> The queue holds at most
 * {@code APP_QUEUE_CAPACITY} documents. When it is full, submission <em>blocks</em> the scanner
 * thread: discovery simply stops until a worker frees a slot. The alternative — an unbounded queue
 * — does not make the service faster, it only moves the backlog into the heap, where a large
 * enough input directory turns into an OutOfMemoryError. Blocking makes the input directory's size
 * irrelevant to memory: at any moment the process holds at most {@code queueCapacity} file
 * references plus {@code concurrency} documents actually being transformed.
 *
 * <p>Note what is queued: a {@link Path}, not a parsed document. Nothing is read until a worker
 * picks it up, and nothing is retained after it finishes.
 *
 * <p><strong>Why a bounded pool of platform threads.</strong> The expensive stage is the XSLT
 * transform, which is CPU-bound: it burns a core building a TinyTree and walking it. Threads
 * beyond the number of cores cannot make that faster — they add context switching and multiply
 * peak memory, since every concurrently transforming document holds its own tree. The worker count
 * is therefore a deliberate cap on both CPU contention and memory, which is exactly what
 * {@code APP_CONCURRENCY} is for. Where virtual threads would help is discussed in SOLUTION.md:
 * the short version is that they solve blocked-on-IO, which is not this pipeline's bottleneck.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final DocumentPipeline pipeline;
    private final JobRegistry registry;
    private final Clock clock;
    private final Path inputRoot;
    private final int concurrency;

    private final BlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor workers;
    private final ExecutorService scanners;

    /** Highest queue depth ever observed: the evidence that the bound is real. */
    private final AtomicInteger peakQueueDepth = new AtomicInteger();

    public BatchService(DocumentPipeline pipeline, JobRegistry registry, AppProperties properties,
            Clock clock) {
        this.pipeline = pipeline;
        this.registry = registry;
        this.clock = clock;
        this.inputRoot = Path.of(properties.inputDir()).toAbsolutePath().normalize();
        this.concurrency = properties.concurrency();

        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                queue, namedThreads("lex-worker-"), blockingSubmission());
        // One thread walking directories. It is the thread that blocks when the queue is full,
        // which is why the HTTP thread that started the batch never does.
        this.scanners = Executors.newSingleThreadExecutor(namedThreads("lex-scanner-"));

        log.info("Batch service ready: {} workers, queue capacity {}, input root {}",
                concurrency, properties.queueCapacity(), inputRoot);
    }

    /**
     * When the queue is full, {@link ThreadPoolExecutor} would normally reject the task. Instead
     * the submitting thread parks in {@code put} until a slot frees up. This is the whole
     * backpressure mechanism, and it is three lines.
     */
    private RejectedExecutionHandler blockingSubmission() {
        return (task, executor) -> {
            if (executor.isShutdown()) {
                throw new java.util.concurrent.RejectedExecutionException("Service is shutting down");
            }
            try {
                executor.getQueue().put(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.RejectedExecutionException("Interrupted", e);
            }
        };
    }

    /**
     * Starts a batch and returns immediately; the scan happens on its own thread.
     *
     * @param requestedDir a directory relative to APP_INPUT_DIR, or null for the root itself
     */
    public BatchJob submit(String requestedDir) {
        Path directory = resolveInsideInputRoot(requestedDir);
        String relative = inputRoot.relativize(directory).toString();
        BatchJob job = registry.create(relative.isEmpty() ? "." : relative);

        scanners.submit(() -> scan(job, directory));

        return job;
    }

    /**
     * A batch request names a directory, so an unchecked value would let a caller point the
     * service at any readable path on the host. Two checks, because they catch different things:
     *
     * <ul>
     *   <li><strong>Lexical</strong> — an absolute path, or a {@code ..} segment that normalises
     *       to somewhere outside the root. Cheap, and works on a path that does not exist yet.</li>
     *   <li><strong>Real path</strong> — {@code toRealPath()} resolves every symlink in the chain,
     *       so a link inside the root pointing outside it is caught. Normalisation alone cannot
     *       see through a symlink: {@code in/elsewhere} normalises to itself and looks perfectly
     *       contained right up until the filesystem follows it.</li>
     * </ul>
     *
     * <p>Both sides are compared as real paths deliberately — the root itself is often reached
     * through a link (on macOS {@code /tmp} is one), so comparing a resolved candidate against an
     * unresolved root would reject legitimate input.
     */
    private Path resolveInsideInputRoot(String requestedDir) {
        if (requestedDir == null || requestedDir.isBlank()) {
            return inputRoot;
        }
        Path candidate = Path.of(requestedDir);
        if (candidate.isAbsolute()) {
            throw new InvalidBatchRequestException(
                    "inputDir must be relative to the configured input directory");
        }
        Path resolved = inputRoot.resolve(candidate).toAbsolutePath().normalize();
        if (!resolved.startsWith(inputRoot)) {
            throw new InvalidBatchRequestException(
                    "inputDir must not escape the configured input directory");
        }
        if (Files.exists(resolved) && !isInsideRealInputRoot(resolved, realInputRoot())) {
            throw new InvalidBatchRequestException(
                    "inputDir resolves outside the configured input directory");
        }
        return resolved;
    }

    /**
     * The same check, applied to every file the walk produces.
     *
     * <p>{@code Files.walk} does not follow directory symlinks unless asked to, so a linked
     * <em>directory</em> is never descended into. That leaves file symlinks as the remaining
     * surface: a single link inside the input directory pointing at {@code /etc/passwd} or at
     * another tenant's feed would otherwise be read, transformed and published as content. Each
     * candidate is resolved to its real path and refused if that lands outside the root.
     *
     * @return true if the file may be processed
     */
    private boolean isInsideInputRoot(BatchJob job, Path file, Path realRoot) {
        if (isInsideRealInputRoot(file, realRoot)) {
            return true;
        }
        String reason = "refused: " + file + " resolves outside the input directory";
        log.warn("Batch {} skipped a path that escapes the input root: {}", job.batchId(), file);
        job.documentSkipped(reason);
        return false;
    }

    private boolean isInsideRealInputRoot(Path path, Path realRoot) {
        try {
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            // A path that cannot be resolved is not a path we are willing to read.
            return false;
        }
    }

    /** The root with its own symlinks resolved, so both sides of the comparison are real paths. */
    private Path realInputRoot() {
        try {
            return inputRoot.toRealPath();
        } catch (IOException e) {
            return inputRoot;
        }
    }

    /**
     * Walks the directory lazily and submits one task per file. Files are discovered as the stream
     * is consumed, so the scan never builds a list of what it found — and because submission
     * blocks, it does not run ahead of the workers either.
     */
    private void scan(BatchJob job, Path directory) {
        if (!Files.isDirectory(directory)) {
            job.scanFailed("Not a directory: " + directory);
            return;
        }
        Path realRoot = realInputRoot();
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    // Checked before the document is counted as discovered: a path we refuse to
                    // read is not a document this batch is responsible for.
                    .filter(path -> isInsideInputRoot(job, path, realRoot))
                    .forEach(path -> {
                        job.documentDiscovered();
                        recordPeakDepth();
                        workers.execute(() -> process(job, path)); // blocks when the queue is full
                    });
            job.scanFinished();
            job.completeIfFinished(Instant.now(clock));
            log.info("Batch {} discovered {} documents", job.batchId(), job.discovered());
        } catch (IOException | RuntimeException e) {
            log.error("Batch {} scan failed", job.batchId(), e);
            job.scanFailed(String.valueOf(e.getMessage()));
        }
    }

    private void process(BatchJob job, Path file) {
        Instant start = Instant.now(clock);
        Outcome outcome;
        try {
            PipelineResult result = pipeline.process(new FileSystemResource(file), file.toString());
            outcome = result.outcome();
        } catch (RuntimeException e) {
            // The pipeline is documented not to throw. If that ever stops being true, a worker
            // must still not die silently and leave the batch permanently unfinished.
            log.error("Pipeline threw for {}", file, e);
            outcome = Outcome.INTERNAL_ERROR;
        }
        job.documentProcessed(outcome, Duration.between(start, Instant.now(clock)));
        job.completeIfFinished(Instant.now(clock));
    }

    private void recordPeakDepth() {
        peakQueueDepth.accumulateAndGet(queue.size(), Math::max);
    }

    /** @return true when the queue is full — the signal the single-document endpoint sheds load on. */
    public boolean isSaturated() {
        return queue.remainingCapacity() == 0;
    }

    public int queueDepth() {
        return queue.size();
    }

    public int queueCapacity() {
        return queue.size() + queue.remainingCapacity();
    }

    public int peakQueueDepth() {
        return peakQueueDepth.get();
    }

    public int concurrency() {
        return concurrency;
    }

    public int activeWorkers() {
        return workers.getActiveCount();
    }

    private java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        scanners.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                // In-flight documents are idempotent: whatever was mid-publish is either committed
                // or invisible, and resubmitting it is a DUPLICATE_NOOP.
                log.warn("Workers did not finish within 30s; forcing shutdown");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
