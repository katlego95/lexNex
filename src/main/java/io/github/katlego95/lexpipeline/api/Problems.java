package io.github.katlego95.lexpipeline.api;

import io.github.katlego95.lexpipeline.pipeline.Outcome;
import io.github.katlego95.lexpipeline.pipeline.PipelineResult;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * RFC 7807 {@code application/problem+json} bodies.
 *
 * <p>Why a machine-readable error shape matters here rather than a string: the client of this
 * endpoint is a feed, not a person. It has to branch on <em>why</em> a judgment was refused — a
 * schema violation goes back to the content team, a 429 is retried in a minute, a 500 is paged —
 * and it should not do that by matching on English prose. {@code type} and {@code outcome} are
 * the fields to branch on; {@code detail} and {@code diagnostics} are for whoever reads the alert.
 */
final class Problems {

    private static final String TYPE_BASE = "https://github.com/katlego95/lexpipeline/problems/";

    private Problems() {
    }

    /** Maps a recorded pipeline outcome onto the HTTP status that says the same thing. */
    static HttpStatus statusFor(Outcome outcome) {
        return switch (outcome) {
            case PUBLISHED, SUPERSEDED, DUPLICATE_NOOP -> HttpStatus.OK;
            // The sender's problem: the request was understood and refused.
            case SCHEMA_INVALID, MALFORMED_XML, DOCTYPE_REJECTED -> HttpStatus.BAD_REQUEST;
            case OVERSIZE -> HttpStatus.PAYLOAD_TOO_LARGE;
            // Ours: the document was accepted and we failed to process or store it.
            case TRANSFORM_FAILED, STORAGE_FAILED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * A rejected or failed document, with everything a client needs to act: the outcome to branch
     * on, the quarantine id to quote in a support ticket, and the diagnostics to fix the file.
     */
    static ProblemDetail forResult(PipelineResult result) {
        HttpStatus status = statusFor(result.outcome());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detailFor(result));
        problem.setType(URI.create(TYPE_BASE + slug(result.outcome())));
        problem.setTitle(titleFor(result.outcome()));
        problem.setProperty("outcome", result.outcome().name());
        if (result.contentId() != null) {
            problem.setProperty("contentId", result.contentId());
        }
        if (result.ingestId() != null) {
            problem.setProperty("ingestId", result.ingestId());
            // Where the original document and the full diagnostics were filed, so a client can
            // come back for them without having kept this response.
            problem.setProperty("quarantine", "/api/v1/quarantine/" + result.ingestId());
        }
        if (!result.diagnostics().isEmpty()) {
            problem.setProperty("diagnostics", result.diagnostics().stream()
                    .map(d -> Map.of(
                            "severity", d.severity().name(),
                            "line", d.line(),
                            "column", d.column(),
                            "code", d.code() == null ? "" : d.code(),
                            "message", d.message()))
                    .toList());
        }
        return problem;
    }

    static ProblemDetail of(HttpStatusCode status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        return problem;
    }

    private static String detailFor(PipelineResult result) {
        List<String> messages = result.diagnostics().stream().map(d -> d.message()).toList();
        String summary = switch (result.outcome()) {
            case SCHEMA_INVALID -> "The document is well-formed XML but does not satisfy the "
                    + "judgment schema. It was quarantined and not published.";
            case MALFORMED_XML -> "The document is not well-formed XML. It was quarantined and not "
                    + "published.";
            case DOCTYPE_REJECTED -> "The document carries a DOCTYPE declaration, which is refused. "
                    + "It was quarantined and not published.";
            case OVERSIZE -> "The document exceeds the configured maximum size and was not read.";
            case TRANSFORM_FAILED -> "The document was accepted but could not be transformed. This "
                    + "is a defect on our side; the document has been quarantined for diagnosis.";
            case STORAGE_FAILED -> "The document was accepted but its artifacts could not be "
                    + "written. Retrying the same document is safe.";
            case INTERNAL_ERROR -> "The document was accepted and processing failed unexpectedly.";
            default -> result.outcome().name();
        };
        return messages.isEmpty() ? summary : summary + " " + String.join(" ", messages);
    }

    private static String titleFor(Outcome outcome) {
        return switch (outcome) {
            case SCHEMA_INVALID -> "Schema validation failed";
            case MALFORMED_XML -> "Malformed XML";
            case DOCTYPE_REJECTED -> "DOCTYPE declaration not permitted";
            case OVERSIZE -> "Document too large";
            case TRANSFORM_FAILED -> "Transformation failed";
            case STORAGE_FAILED -> "Storage failed";
            case INTERNAL_ERROR -> "Internal error";
            default -> outcome.name();
        };
    }

    private static String slug(Outcome outcome) {
        return outcome.name().toLowerCase().replace('_', '-');
    }
}
