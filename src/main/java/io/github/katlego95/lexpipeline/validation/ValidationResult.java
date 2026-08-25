package io.github.katlego95.lexpipeline.validation;

import java.util.List;

/**
 * The verdict on one document: a status plus every diagnostic that was collected.
 *
 * <p>A bare boolean would be enough to decide publish-or-quarantine, but not enough to record
 * <em>why</em>: the three failure statuses map to three different quarantine categories in
 * ARCHITECTURE section 6, and an operator triaging a bad feed needs to tell "the sender shipped
 * broken XML" apart from "the sender shipped valid XML that breaks our contract".
 *
 * @param status      outcome category
 * @param diagnostics every problem found, in the order the parser reported them; empty when valid
 */
public record ValidationResult(Status status, List<Diagnostic> diagnostics) {

    public enum Status {
        /** Well-formed and schema-valid. The only status the pipeline may publish. */
        VALID,
        /** Not well-formed XML at all: the parser could not get far enough to check the schema. */
        MALFORMED_XML,
        /**
         * The document carries a DOCTYPE declaration, which the pipeline refuses on sight.
         *
         * <p>Its own status rather than a flavour of MALFORMED_XML, because the two mean
         * different things to whoever is on call: malformed XML is a broken sender, a DOCTYPE is
         * a policy rejection and a spike in them is a security signal, not a feed-quality one.
         */
        DOCTYPE_REJECTED,
        /** Well-formed, but it violates the judgment schema. */
        SCHEMA_INVALID,
        /** Larger than APP_MAX_DOC_BYTES; rejected without being parsed. */
        OVERSIZE
    }

    public ValidationResult {
        diagnostics = List.copyOf(diagnostics);
    }

    /** The valid flag the pipeline branches on; the status carries the reason when it is false. */
    public boolean valid() {
        return status == Status.VALID;
    }

    /** Named {@code ok} rather than {@code valid} because {@link #valid()} is the flag accessor. */
    static ValidationResult ok() {
        return new ValidationResult(Status.VALID, List.of());
    }

    static ValidationResult schemaInvalid(List<Diagnostic> diagnostics) {
        return new ValidationResult(Status.SCHEMA_INVALID, diagnostics);
    }

    static ValidationResult malformed(List<Diagnostic> diagnostics) {
        return new ValidationResult(Status.MALFORMED_XML, diagnostics);
    }

    /**
     * Carries a stable code of our own rather than the parser's, since this rejection is the
     * pipeline's policy rather than a finding about the XML. The parser's own message is kept
     * alongside so the sender can see exactly what was refused and where.
     */
    static ValidationResult doctypeRejected(Diagnostic parserDiagnostic) {
        return new ValidationResult(Status.DOCTYPE_REJECTED, List.of(new Diagnostic(
                Diagnostic.Severity.FATAL,
                parserDiagnostic.line(),
                parserDiagnostic.column(),
                "lex-doctype-not-allowed",
                "A DOCTYPE declaration is not permitted: judgments are governed by the XSD and "
                        + "have no legitimate use for a DTD. Resubmit without it. "
                        + "Parser reported: " + parserDiagnostic.message())));
    }

    static ValidationResult oversize(long actualBytes, long limitBytes) {
        String message = actualBytes < 0
                ? "Document exceeds the configured limit of %d bytes (APP_MAX_DOC_BYTES)"
                        .formatted(limitBytes)
                : "Document is %d bytes, over the configured limit of %d bytes (APP_MAX_DOC_BYTES)"
                        .formatted(actualBytes, limitBytes);
        return new ValidationResult(
                Status.OVERSIZE,
                List.of(new Diagnostic(Diagnostic.Severity.FATAL, -1, -1, null, message)));
    }
}
