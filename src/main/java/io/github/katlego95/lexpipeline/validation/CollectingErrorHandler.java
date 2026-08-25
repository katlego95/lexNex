package io.github.katlego95.lexpipeline.validation;

import java.util.ArrayList;
import java.util.List;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * A SAX {@link ErrorHandler} that records problems instead of throwing them.
 *
 * <p><strong>Why this class exists.</strong> If no error handler is installed, a
 * {@code Validator} uses the default behaviour: the first schema violation is thrown as a
 * {@link SAXParseException} and validation stops. The upstream content team then gets told about
 * one broken paragraph, fixes it, resubmits, and learns about the next one — a round trip per
 * error. Because these methods return normally instead of throwing, the parser carries on and we
 * end up with the complete list in one pass. Diagnostics are the product of the quarantine path,
 * so completeness is the feature.
 *
 * <p>Not thread-safe, and does not need to be: one instance per {@code validate} call, exactly
 * like the {@code Validator} it is attached to.
 */
final class CollectingErrorHandler implements ErrorHandler {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean fatal;

    /**
     * Recoverable schema violations (an invalid date, a duplicate ID, a missing attribute) arrive
     * here. Returning normally is what makes the validator keep going to the next one.
     */
    @Override
    public void error(SAXParseException exception) {
        diagnostics.add(Diagnostic.from(Diagnostic.Severity.ERROR, exception));
    }

    /**
     * Well-formedness failures arrive here: an unclosed tag, a stray {@code &}, bytes that are not
     * XML at all. Unlike {@link #error}, the parser is entitled to stop after this callback and
     * Xerces does — there is no valid parse state to continue from. Recording the flag is how the
     * service tells MALFORMED_XML apart from SCHEMA_INVALID; the SAX severity <em>is</em> the
     * distinction, so no message sniffing is needed.
     */
    @Override
    public void fatalError(SAXParseException exception) {
        fatal = true;
        diagnostics.add(Diagnostic.from(Diagnostic.Severity.FATAL, exception));
    }

    /** Kept for the record; a warning never blocks publication on its own. */
    @Override
    public void warning(SAXParseException exception) {
        diagnostics.add(Diagnostic.from(Diagnostic.Severity.WARNING, exception));
    }

    List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    boolean hasFatalError() {
        return fatal;
    }

    boolean hasErrors() {
        return diagnostics.stream()
                .anyMatch(d -> d.severity() != Diagnostic.Severity.WARNING);
    }
}
