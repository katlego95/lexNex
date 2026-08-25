package io.github.katlego95.lexpipeline.identity;

/**
 * A document that passed validation could not be identified.
 *
 * <p>This should be unreachable: the schema makes {@code content_id} mandatory, so anything that
 * gets here has already been proven to have one. It is therefore a defect in this service rather
 * than in the submitted document, and the pipeline records it as such instead of letting it
 * escape as a stack trace.
 */
public class ContentIdentityException extends RuntimeException {

    public ContentIdentityException(String message) {
        super(message);
    }

    public ContentIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
