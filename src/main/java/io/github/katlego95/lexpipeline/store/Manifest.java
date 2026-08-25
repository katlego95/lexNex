package io.github.katlego95.lexpipeline.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The audit trail for one judgment: every version ever published, in order.
 *
 * <p>It is also the store's commit record. Artifacts are written first and the manifest last, so
 * a version exists, as far as any consumer is concerned, exactly when this file names it.
 *
 * @param contentId the judgment's identifier; the directory name is derived from it
 * @param versions  oldest first, so {@code versions.get(n - 1)} is version n
 */
public record Manifest(String contentId, List<Version> versions) {

    /**
     * @param version      1-based, monotonically increasing
     * @param sha256       digest of the received bytes that produced this version
     * @param publishedAt  when the manifest naming it was committed
     * @param supersededBy the version that replaced this one, absent while it is current. Written
     *                     rather than inferred from position so that a reader of a single entry
     *                     can see it is stale without scanning the rest of the list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(int version, String sha256, Instant publishedAt, Integer supersededBy) {

        Version supersededBy(int newer) {
            return new Version(version, sha256, publishedAt, newer);
        }
    }

    public Manifest {
        versions = List.copyOf(versions);
    }

    public static Manifest empty(String contentId) {
        return new Manifest(contentId, List.of());
    }

    public Optional<Version> latest() {
        return versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.get(versions.size() - 1));
    }

    public int nextVersion() {
        return latest().map(Version::version).orElse(0) + 1;
    }

    /**
     * Returns a manifest with {@code newVersion} appended and the previously current version
     * marked as superseded by it. Pure: the receiver is unchanged, which is what lets the caller
     * write the new state atomically and keep the old one until the move succeeds.
     */
    Manifest withNewVersion(Version newVersion) {
        List<Version> updated = new ArrayList<>(versions);
        if (!updated.isEmpty()) {
            int last = updated.size() - 1;
            updated.set(last, updated.get(last).supersededBy(newVersion.version()));
        }
        updated.add(newVersion);
        return new Manifest(contentId, updated);
    }
}
