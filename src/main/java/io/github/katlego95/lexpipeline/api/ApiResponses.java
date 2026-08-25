package io.github.katlego95.lexpipeline.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.katlego95.lexpipeline.store.Manifest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response bodies for the API. Records, so the JSON shape is the type.
 *
 * <p>Every response that names a document carries links to its artifacts. A client should be able
 * to follow the pipeline's answer to the content it produced without knowing how the store lays
 * files out.
 */
final class ApiResponses {

    private ApiResponses() {
    }

    /** Result of submitting one document synchronously. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record IngestResponse(
            String contentId,
            String outcome,
            Integer version,
            String ingestId,
            Map<String, String> links) {
    }

    /** Status of one judgment: every version, newest last, exactly as the manifest records them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DocumentResponse(
            String contentId,
            int currentVersion,
            List<VersionResponse> versions,
            Map<String, String> links) {

        static DocumentResponse from(Manifest manifest) {
            String contentId = manifest.contentId();
            int current = manifest.latest().map(Manifest.Version::version).orElse(0);
            return new DocumentResponse(
                    contentId,
                    current,
                    manifest.versions().stream().map(v -> VersionResponse.from(contentId, v))
                            .toList(),
                    artifactLinks(contentId, current));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VersionResponse(
            int version,
            String sha256,
            Instant publishedAt,
            Integer supersededBy,
            Map<String, String> links) {

        static VersionResponse from(String contentId, Manifest.Version version) {
            return new VersionResponse(version.version(), version.sha256(), version.publishedAt(),
                    version.supersededBy(), artifactLinks(contentId, version.version()));
        }
    }

    /** Batch acknowledgement and status; the same shape for both, so a poller sees one contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BatchResponse(
            String batchId,
            String inputDir,
            String status,
            int discovered,
            int processed,
            int skipped,
            String skipReason,
            Map<String, Integer> counts,
            long elapsedMs,
            long averageDocumentMs,
            String failure,
            Map<String, String> links) {
    }

    static Map<String, String> artifactLinks(String contentId, int version) {
        Map<String, String> links = new LinkedHashMap<>();
        if (version <= 0) {
            return links;
        }
        String base = "/api/v1/documents/" + contentId;
        links.put("self", base);
        links.put("normalized", base + "/artifacts/normalized?version=" + version);
        links.put("fulltext", base + "/artifacts/fulltext?version=" + version);
        links.put("chunks", base + "/artifacts/chunks?version=" + version);
        return links;
    }
}
