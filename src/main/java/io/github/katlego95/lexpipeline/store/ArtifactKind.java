package io.github.katlego95.lexpipeline.store;

import java.util.Optional;

/**
 * The three published artifacts, and the names the API exposes them under.
 *
 * <p>The API name is deliberately not the file name: {@code normalized} is the resource a client
 * asks for, {@code normalized.json} is how this store happens to keep it. Moving to S3 changes
 * the second and not the first.
 */
public enum ArtifactKind {

    NORMALIZED("normalized", "normalized.json", "application/json"),
    FULLTEXT("fulltext", "fulltext.txt", "text/plain;charset=UTF-8"),
    CHUNKS("chunks", "chunks.jsonl", "application/x-ndjson");

    private final String apiName;
    private final String fileName;
    private final String contentType;

    ArtifactKind(String apiName, String fileName, String contentType) {
        this.apiName = apiName;
        this.fileName = fileName;
        this.contentType = contentType;
    }

    public static Optional<ArtifactKind> fromApiName(String name) {
        for (ArtifactKind kind : values()) {
            if (kind.apiName.equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public String apiName() {
        return apiName;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }
}
