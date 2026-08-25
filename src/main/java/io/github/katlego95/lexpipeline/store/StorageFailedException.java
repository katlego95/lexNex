package io.github.katlego95.lexpipeline.store;

/**
 * An artifact could not be written or read.
 *
 * <p>Distinct from every content failure on purpose: a full disk or a revoked permission says
 * nothing about the document, so it must not be recorded against the sender as a bad judgment.
 * The pipeline maps it to STORAGE_FAILED, which is the one outcome worth retrying unchanged.
 */
public class StorageFailedException extends RuntimeException {

    public StorageFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
