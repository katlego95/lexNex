package io.github.katlego95.lexpipeline.store;

/**
 * The three files that make up one published version, produced together and published together.
 *
 * @param normalizedJson the structured artifact (normalized.json)
 * @param fullText       the plain-text artifact for indexing (fulltext.txt)
 * @param chunksJsonl    one self-contained record per paragraph, for the embedding pipeline
 *                       (chunks.jsonl)
 */
public record ArtifactSet(String normalizedJson, String fullText, String chunksJsonl) {
}
