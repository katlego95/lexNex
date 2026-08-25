/**
 * Document identity: content_id extraction plus the SHA-256 of the received bytes.
 *
 * <p>The pair is the idempotency key: same id + same hash is a recorded no-op, same id with a
 * new hash publishes version N+1 with a supersession trail.
 */
package io.github.katlego95.lexpipeline.identity;
