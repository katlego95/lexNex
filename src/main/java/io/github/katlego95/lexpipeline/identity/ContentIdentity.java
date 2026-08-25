package io.github.katlego95.lexpipeline.identity;

/**
 * What a document is, and what exactly arrived.
 *
 * <p>The pair is the idempotency key of the whole pipeline: {@code contentId} says which judgment
 * this is, {@code sha256} says which delivery of it. Same id and same hash is a recorded no-op;
 * same id with a new hash publishes version N+1 and supersedes its predecessor.
 *
 * @param contentId the publisher's identifier for the judgment, read from the document header
 * @param sha256    lowercase hex digest of the <em>raw received bytes</em>, not of any artifact we
 *                  produced. Identity has to be of what was received: hashing our own output would
 *                  make the fingerprint change whenever a stylesheet changes, turning every
 *                  redeploy into a corpus-wide false supersession.
 */
public record ContentIdentity(String contentId, String sha256) {
}
