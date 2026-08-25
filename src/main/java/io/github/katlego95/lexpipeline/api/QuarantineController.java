package io.github.katlego95.lexpipeline.api;

import io.github.katlego95.lexpipeline.store.ArtifactStore;
import io.github.katlego95.lexpipeline.store.QuarantineRecord;
import io.github.katlego95.lexpipeline.validation.Diagnostic;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to rejected documents.
 *
 * <p>A rejection is a processed item too. Without this, the diagnostics for a refused judgment
 * exist only in the response to the submission that produced it and on the operator's disk, so a
 * batch worker or a client that dropped the response has no way back to them. The ingest id
 * returned in the problem body is what addresses this resource.
 */
@RestController
@RequestMapping("/api/v1/quarantine")
public class QuarantineController {

    private final ArtifactStore store;

    public QuarantineController(ArtifactStore store) {
        this.store = store;
    }

    @GetMapping("/{ingestId}")
    public ResponseEntity<?> record(@PathVariable String ingestId) {
        return store.readQuarantineRecord(ingestId)
                .<ResponseEntity<?>>map(record -> ResponseEntity.ok(QuarantineResponse.from(record)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Problems.of(HttpStatus.NOT_FOUND, "quarantine-not-found",
                                "Quarantine record not found",
                                "No quarantined document with ingest id \"" + ingestId + "\".")));
    }

    /**
     * @param status the pipeline outcome that caused the rejection, which is what a client
     *               branches on; the rest is what a human needs to fix the document
     */
    record QuarantineResponse(
            String ingestId,
            String status,
            String contentId,
            String sourceName,
            Instant receivedAt,
            List<Diagnostic> diagnostics) {

        static QuarantineResponse from(QuarantineRecord record) {
            return new QuarantineResponse(record.ingestId(), record.reason(), record.contentId(),
                    record.sourceName(), record.receivedAt(), record.diagnostics());
        }
    }
}
