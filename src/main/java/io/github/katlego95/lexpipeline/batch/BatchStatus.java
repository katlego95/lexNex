package io.github.katlego95.lexpipeline.batch;

public enum BatchStatus {

    /** Still walking the input directory; {@code discovered} is not final yet. */
    SCANNING,

    /** Everything is discovered and the workers are draining the queue. */
    RUNNING,

    /** Every discovered document reached an outcome. */
    COMPLETED,

    /** The scan itself failed — an unreadable directory, not a bad document. */
    FAILED
}
