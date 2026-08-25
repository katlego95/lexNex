package io.github.katlego95.lexpipeline.batch;

/**
 * The batch request itself is wrong — a directory outside the configured input root, or one that
 * does not exist. A client error, distinct from any document-level failure inside a batch.
 */
public class InvalidBatchRequestException extends RuntimeException {

    public InvalidBatchRequestException(String message) {
        super(message);
    }
}
