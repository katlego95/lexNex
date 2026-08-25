package io.github.katlego95.lexpipeline.transform;

/**
 * A document could not be transformed.
 *
 * <p>Unchecked and specific: the Phase 3 orchestrator catches it and records TRANSFORM_FAILED for
 * that document, which is a quarantine outcome rather than an error escaping the pipeline. It is
 * deliberately not a {@code ValidationResult} status — by the time a document reaches the
 * transform it has already passed the trust gate, so a failure here is ours, not the sender's.
 */
public class TransformFailedException extends RuntimeException {

    public TransformFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
