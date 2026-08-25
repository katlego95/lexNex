/**
 * Artifact persistence: atomic publish (temp file then move) and the versioned manifest.
 *
 * <p>Filesystem-backed here; the cloud equivalent (S3 objects plus a DynamoDB conditional write)
 * is documented in SOLUTION.md.
 */
package io.github.katlego95.lexpipeline.store;
