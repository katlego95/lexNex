package io.github.katlego95.lexpipeline.api;

import io.github.katlego95.lexpipeline.api.ApiResponses.DocumentResponse;
import io.github.katlego95.lexpipeline.api.ApiResponses.IngestResponse;
import io.github.katlego95.lexpipeline.batch.BatchService;
import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.pipeline.DocumentPipeline;
import io.github.katlego95.lexpipeline.pipeline.Outcome;
import io.github.katlego95.lexpipeline.pipeline.PipelineResult;
import io.github.katlego95.lexpipeline.store.ArtifactKind;
import io.github.katlego95.lexpipeline.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single-document ingestion and read access to what was published.
 *
 * <p>The POST runs the pipeline <strong>synchronously, on the request thread</strong>. That is the
 * contract the caller wants for one document: the response is the outcome, not a promise of one,
 * so a feed can retry or escalate immediately instead of polling. It also means the request thread
 * is the concurrency limit for this path — Tomcat's pool — which is why the endpoint sheds load
 * when the batch queue is saturated rather than piling more work onto a machine already at
 * capacity.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    /** Long enough for a worker to free a slot, short enough that a feed does not stall. */
    private static final String RETRY_AFTER_SECONDS = "5";

    private final DocumentPipeline pipeline;
    private final ArtifactStore store;
    private final BatchService batchService;
    private final long maxDocBytes;

    public DocumentController(DocumentPipeline pipeline, ArtifactStore store,
            BatchService batchService, AppProperties properties) {
        this.pipeline = pipeline;
        this.store = store;
        this.batchService = batchService;
        this.maxDocBytes = properties.maxDocBytes();
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<?> ingest(HttpServletRequest request) {
        if (batchService.isSaturated()) {
            // Load shedding, not failure: the work queue is full, so accepting this document would
            // mean holding it in a request thread waiting for a worker. Telling the client to come
            // back is cheaper for both sides than timing out with the document half-processed.
            log.warn("Rejecting submission: queue is full ({} deep)", batchService.queueDepth());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(Problems.of(HttpStatus.TOO_MANY_REQUESTS, "queue-full",
                            "Ingestion queue is full",
                            "The pipeline is at capacity. Retry after "
                                    + RETRY_AFTER_SECONDS + " seconds."));
        }

        Resource document = new ByteArrayResource(readBounded(request));
        PipelineResult result = pipeline.process(document, describe(request));

        return result.outcome().isPublished() || result.outcome() == Outcome.DUPLICATE_NOOP
                ? ResponseEntity.ok(success(result))
                : ResponseEntity.status(Problems.statusFor(result.outcome()))
                        .body(Problems.forResult(result));
    }

    @GetMapping("/{contentId}")
    public ResponseEntity<?> document(@PathVariable String contentId) {
        if (!ArtifactStore.isSafeId(contentId)) {
            return notFound(contentId);
        }
        return store.readManifest(contentId)
                .<ResponseEntity<?>>map(manifest -> ResponseEntity.ok(
                        DocumentResponse.from(manifest)))
                .orElseGet(() -> notFound(contentId));
    }

    @GetMapping("/{contentId}/artifacts/{kind}")
    public ResponseEntity<?> artifact(@PathVariable String contentId, @PathVariable String kind,
            @RequestParam(required = false) Integer version) {
        if (!ArtifactStore.isSafeId(contentId)) {
            return notFound(contentId);
        }
        Optional<ArtifactKind> artifactKind = ArtifactKind.fromApiName(kind);
        if (artifactKind.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Problems.of(HttpStatus.NOT_FOUND, "unknown-artifact", "Unknown artifact",
                            "No artifact named \"" + kind + "\". Expected one of: normalized, "
                                    + "fulltext, chunks."));
        }

        // Served through the manifest, so an uncommitted version directory left by a failed
        // publish can never be handed to a client.
        return store.readArtifact(contentId, Optional.ofNullable(version), artifactKind.get())
                .<ResponseEntity<?>>map(resource -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, artifactKind.get().contentType())
                        .body(resource))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Problems.of(HttpStatus.NOT_FOUND, "artifact-not-found",
                                "Artifact not found",
                                "No published " + kind + " for " + contentId
                                        + (version == null ? "" : " version " + version) + ".")));
    }

    private IngestResponse success(PipelineResult result) {
        return new IngestResponse(
                result.contentId(),
                result.outcome().name(),
                result.version(),
                null,
                ApiResponses.artifactLinks(result.contentId(),
                        result.version() == null ? 0 : result.version()));
    }

    private ResponseEntity<ProblemDetail> notFound(String contentId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Problems.of(HttpStatus.NOT_FOUND, "document-not-found", "Document not found",
                        "Nothing has been published for content id \"" + contentId + "\"."));
    }

    /**
     * Reads at most one byte more than the limit.
     *
     * <p>The pipeline needs a re-readable source (validate, hash, transform each read it), so an
     * HTTP body has to be buffered — but only up to the size the service was going to accept
     * anyway. Reading one byte past the limit is what lets the size guard downstream see an
     * oversize document and record it as OVERSIZE, rather than the endpoint silently truncating
     * or the heap absorbing an arbitrary upload.
     */
    private byte[] readBounded(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            return in.readNBytes(Math.toIntExact(Math.min(maxDocBytes + 1, Integer.MAX_VALUE)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the request body", e);
        }
    }

    private String describe(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return "POST " + request.getRequestURI() + " from " + (remote == null ? "unknown" : remote);
    }
}
