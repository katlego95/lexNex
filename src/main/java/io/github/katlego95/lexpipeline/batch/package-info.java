/**
 * Batch ingestion: lazy folder scan, bounded submission to a fixed worker pool, job registry.
 *
 * <p>A bounded queue is backpressure rather than an out-of-memory failure mode; no stage ever
 * holds a list of parsed documents.
 */
package io.github.katlego95.lexpipeline.batch;
