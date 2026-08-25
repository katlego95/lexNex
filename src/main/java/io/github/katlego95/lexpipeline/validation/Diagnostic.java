package io.github.katlego95.lexpipeline.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.SAXParseException;

/**
 * One thing the validator found wrong, in a form that can be written to
 * {@code quarantine/{ingest_id}/diagnostics.json} and read by a human.
 *
 * <p>Diagnostics are the product of the quarantine path: a rejected document is only useful to
 * the upstream content team if the rejection says precisely where and why.
 *
 * @param severity WARNING and ERROR come from schema validation, FATAL from well-formedness
 * @param line     1-based line in the source document, or -1 if the parser could not locate it
 * @param column   1-based column, or -1
 * @param code     the schema error code parsed out of the message ({@code cvc-id.2},
 *                 {@code cvc-complex-type.4}, ...), or {@code null} for well-formedness errors,
 *                 which carry no code. Stable across Xerces message-wording changes and across
 *                 locales, so it is the field to alert or report on.
 * @param message  the full parser message, message wording included
 */
public record Diagnostic(Severity severity, int line, int column, String code, String message) {

    /**
     * Schema error codes are the "cvc-" (constraint validation) and "src-" (schema source)
     * identifiers the XSD spec defines; Xerces prefixes its message with one and a colon.
     * Well-formedness errors have no such prefix, which is why {@link #code()} may be null.
     */
    private static final Pattern ERROR_CODE = Pattern.compile("^((?:cvc|src)-[^:\\s]+):");

    public enum Severity {
        WARNING,
        ERROR,
        FATAL
    }

    static Diagnostic from(Severity severity, SAXParseException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return new Diagnostic(
                severity,
                exception.getLineNumber(),
                exception.getColumnNumber(),
                extractCode(message),
                message);
    }

    private static String extractCode(String message) {
        Matcher matcher = ERROR_CODE.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
