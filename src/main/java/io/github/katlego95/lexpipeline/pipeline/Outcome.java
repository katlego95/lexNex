package io.github.katlego95.lexpipeline.pipeline;

/**
 * What happened to one document. Every path through the pipeline ends in exactly one of these —
 * there is no path that ends in an exception reaching the caller.
 *
 * <p>Grouped by who has to act: the sender, nobody, or us.
 */
public enum Outcome {

    /** First version of this judgment is now in the corpus. */
    PUBLISHED,

    /** A later version was published and the previous one is now marked superseded. */
    SUPERSEDED,

    /** Byte-identical to the current version. Nothing was written; the delivery is recorded. */
    DUPLICATE_NOOP,

    /** Sender's problem: well-formed XML that violates the judgment schema. */
    SCHEMA_INVALID,

    /** Sender's problem: not well-formed XML. */
    MALFORMED_XML,

    /** Sender's problem: a DOCTYPE declaration, which the pipeline refuses on sight. */
    DOCTYPE_REJECTED,

    /** Sender's problem: larger than APP_MAX_DOC_BYTES. */
    OVERSIZE,

    /** Our problem: the document passed the trust gate and the transform still failed. */
    TRANSFORM_FAILED,

    /** Our problem: the artifacts could not be written. The one outcome worth retrying unchanged. */
    STORAGE_FAILED,

    /**
     * Our problem, unclassified: a defect in this service after the document was accepted. Exists
     * so that "every failure is a recorded outcome" holds even for the failures nobody predicted.
     */
    INTERNAL_ERROR;

    /** @return true if the document is now part of the corpus, whether new or superseding. */
    public boolean isPublished() {
        return this == PUBLISHED || this == SUPERSEDED;
    }

    /** @return true if the document was written to quarantine rather than published. */
    public boolean isQuarantined() {
        return switch (this) {
            case SCHEMA_INVALID, MALFORMED_XML, DOCTYPE_REJECTED, OVERSIZE, TRANSFORM_FAILED ->
                    true;
            default -> false;
        };
    }
}
