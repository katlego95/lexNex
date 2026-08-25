package io.github.katlego95.lexpipeline.pipeline;

import io.github.katlego95.lexpipeline.validation.Diagnostic;
import java.util.List;

/**
 * The recorded result of processing one document.
 *
 * @param outcome     what happened; never null
 * @param contentId   the judgment id, absent when the document could not be identified
 * @param version     the version published, absent unless something was published
 * @param sha256      digest of the received bytes, absent when the document was never read past
 *                    the gate
 * @param ingestId    the quarantine folder this was filed under, absent unless quarantined
 * @param diagnostics why it failed; empty on success
 */
public record PipelineResult(
        Outcome outcome,
        String contentId,
        Integer version,
        String sha256,
        String ingestId,
        List<Diagnostic> diagnostics) {

    public PipelineResult {
        diagnostics = List.copyOf(diagnostics);
    }

    static PipelineResult published(Outcome outcome, String contentId, int version, String sha256) {
        return new PipelineResult(outcome, contentId, version, sha256, null, List.of());
    }

    static PipelineResult duplicate(String contentId, int currentVersion, String sha256) {
        return new PipelineResult(
                Outcome.DUPLICATE_NOOP, contentId, currentVersion, sha256, null, List.of());
    }

    static PipelineResult quarantined(Outcome outcome, String contentId, String ingestId,
            List<Diagnostic> diagnostics) {
        return new PipelineResult(outcome, contentId, null, null, ingestId, diagnostics);
    }

    static PipelineResult failed(Outcome outcome, String contentId, List<Diagnostic> diagnostics) {
        return new PipelineResult(outcome, contentId, null, null, null, diagnostics);
    }
}
