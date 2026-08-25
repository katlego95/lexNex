/**
 * The per-document orchestrator: validate -> dedupe -> transform -> publish.
 *
 * <p>Every failure is a recorded outcome (MALFORMED_XML, SCHEMA_INVALID, TRANSFORM_FAILED,
 * STORAGE_FAILED, DUPLICATE_NOOP, SUPERSEDED), never an exception escaping the pipeline.
 */
package io.github.katlego95.lexpipeline.pipeline;
