package io.github.katlego95.lexpipeline.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.katlego95.lexpipeline.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The filesystem artifact store: versioned published output, plus quarantine for rejections.
 *
 * <pre>
 * {output}/published/{content_id}/manifest.json
 *                                /v{N}/normalized.json
 *                                /v{N}/fulltext.txt
 *                                /v{N}/chunks.jsonl
 * {output}/quarantine/{ingest_id}/original.xml
 *                                /diagnostics.json
 * </pre>
 *
 * <p><strong>Publishing a version is two atomic steps, and a version becomes visible at the
 * second one.</strong> A publish is four files, so "each file is written atomically" is not enough
 * — it would only mean each file appears whole, not that the <em>version</em> does.
 *
 * <ol>
 *   <li>The three artifacts are written into a hidden staging directory beside the target, and
 *       that directory is moved into place as {@code v{N}} in a single rename. {@code v{N}/} is
 *       therefore never observable with a file missing: it is absent or complete. This is what
 *       protects readers that resolve an artifact <em>by path</em> rather than through the
 *       manifest — the artifact endpoint, a sync job, an operator running {@code ls}.</li>
 *   <li>manifest.json is replaced, atomically. <strong>This is the commit point.</strong> Until
 *       it lands, {@code v{N}} is unreferenced garbage that no consumer has any reason to look
 *       at, and that the retry removes and rewrites. Committing first would publish a version
 *       pointing at files that do not exist yet.</li>
 * </ol>
 *
 * <p>Every individual write uses the same primitive: a temp file in the destination directory,
 * then {@code Files.move} with {@code ATOMIC_MOVE} — {@code rename(2)} on POSIX, which either
 * happened or did not. Both the temp file and the staging directory must be siblings of their
 * target because an atomic move cannot cross filesystems.
 *
 * <p>What this does <em>not</em> buy: durability. Nothing is {@code fsync}ed, so a power loss can
 * still lose a rename the OS had not flushed. The ordering guarantees survive it — a lost manifest
 * write leaves an uncommitted version, not a corrupt one — but a lost artifact write would need
 * the retry to notice, which today it does only because the manifest never named it.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private static final String PUBLISHED = "published";
    private static final String QUARANTINE = "quarantine";
    private static final String MANIFEST = "manifest.json";
    private static final String NORMALIZED_JSON = "normalized.json";
    private static final String FULL_TEXT = "fulltext.txt";
    private static final String CHUNKS = "chunks.jsonl";
    private static final String ORIGINAL = "original.xml";
    private static final String DIAGNOSTICS = "diagnostics.json";

    /**
     * Built here rather than injected. The on-disk format of an artifact must not change because
     * someone edits {@code spring.jackson.*} for an HTTP response somewhere else in the service.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // ISO-8601, readable in a diff
            .build();

    private final Path outputDir;
    private final Clock clock;

    public ArtifactStore(AppProperties properties, Clock clock) {
        this.outputDir = Path.of(properties.outputDir());
        this.clock = clock;
        createDirectories(outputDir.resolve(PUBLISHED));
        createDirectories(outputDir.resolve(QUARANTINE));
        log.info("Artifact store rooted at {}", outputDir.toAbsolutePath());
    }

    /** @return the manifest for this judgment, or empty if nothing has ever been published for it. */
    public Optional<Manifest> readManifest(String contentId) {
        Path manifest = publishedDir(contentId).resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(manifest, StandardCharsets.UTF_8),
                    Manifest.class));
        } catch (IOException e) {
            throw new StorageFailedException("Could not read manifest for " + contentId, e);
        }
    }

    /**
     * Publishes the next version of a judgment: writes the three artifacts, then commits by
     * replacing the manifest.
     *
     * <p>Callers must already hold the per-content_id lock — this method computes the next version
     * number from what it reads, so two concurrent callers would otherwise both compute the same
     * one and the second would overwrite the first.
     *
     * @return the manifest entry that was committed
     */
    public Manifest.Version publish(String contentId, int expectedVersion, String sha256,
            ArtifactSet artifacts) {
        Manifest current = readManifest(contentId).orElseGet(() -> Manifest.empty(contentId));
        int version = current.nextVersion();
        if (version != expectedVersion) {
            // The caller derived expectedVersion from the manifest it read; if the store now
            // disagrees, the lock was not held and two publishers are racing. Refuse rather than
            // overwrite someone else's version.
            throw new StorageFailedException("Expected to publish %s v%d but the store is at v%d"
                    .formatted(contentId, expectedVersion, version), null);
        }

        stageThenMove(contentId, version, artifacts);

        Manifest.Version entry =
                new Manifest.Version(version, sha256, Instant.now(clock).truncatedTo(
                        java.time.temporal.ChronoUnit.MILLIS), null);
        // The commit. Until this move lands, version N does not exist as far as any reader knows.
        writeAtomically(publishedDir(contentId).resolve(MANIFEST),
                serialize(current.withNewVersion(entry)));

        log.info("Published {} v{} ({})", contentId, version, sha256);
        return entry;
    }

    /**
     * Stages the whole version in a hidden sibling directory, then moves that directory into place
     * in one operation.
     *
     * <p>This is the step that makes a version <em>appear</em>. Writing the three artifacts
     * straight into {@code v{N}/} would publish them one at a time: a failure between the second
     * and the third leaves a directory sitting at its real name with a file missing, and anything
     * that resolves an artifact by path — the artifact endpoint, an operator with {@code ls}, a
     * sync job — would serve two thirds of a version as if it were whole. Renaming a directory is
     * a single filesystem operation, so {@code v{N}/} is either absent or complete.
     *
     * <p>The staging directory is a sibling (inside {@code published/{content_id}/}) because an
     * atomic move cannot cross filesystems, and is dot-prefixed so a directory listing cannot
     * mistake it for a version.
     */
    private void stageThenMove(String contentId, int version, ArtifactSet artifacts) {
        Path contentDir = publishedDir(contentId);
        createDirectories(contentDir);
        Path versionDir = contentDir.resolve("v" + version);
        Path staging = null;
        try {
            staging = Files.createTempDirectory(contentDir, ".staging-v" + version + "-");
            writeArtifacts(staging, artifacts);

            if (Files.exists(versionDir)) {
                // An orphan from an earlier crash: the manifest does not name this version (the
                // expectedVersion check above proved it), so nothing can be citing it. Remove it
                // wholesale rather than moving onto it — a rename onto a non-empty directory
                // fails, and merging into it is how a stale file survives a retry.
                log.warn("Replacing orphaned {} left by an earlier failed publish", versionDir);
                deleteRecursively(versionDir);
            }
            Files.move(staging, versionDir, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
        } catch (IOException e) {
            throw new StorageFailedException(
                    "Could not stage " + contentId + " v" + version, e);
        } finally {
            // A failed publish leaves no trace: no partial version, no staging directory.
            deleteRecursivelyQuietly(staging);
        }
    }

    /**
     * Package-private so a test can fail partway through and prove the store is left untouched;
     * plain writes, because the atomicity of this version is the directory move, not each file.
     */
    void writeArtifacts(Path stagingDir, ArtifactSet artifacts) throws IOException {
        Files.writeString(stagingDir.resolve(NORMALIZED_JSON), artifacts.normalizedJson(),
                StandardCharsets.UTF_8);
        Files.writeString(stagingDir.resolve(FULL_TEXT), artifacts.fullText(),
                StandardCharsets.UTF_8);
        Files.writeString(stagingDir.resolve(CHUNKS), artifacts.chunksJsonl(),
                StandardCharsets.UTF_8);
    }

    /**
     * Records a rejection: the document exactly as received, beside the reasons it was refused.
     *
     * @param original the submitted document, streamed rather than buffered so that quarantining
     *                 does not undo the memory guarantees the pipeline just made. May be null for
     *                 a document the service refused to read at all (OVERSIZE) — we do not store
     *                 what we declined to accept.
     */
    public void quarantine(QuarantineRecord record, Resource original) {
        Path dir = outputDir.resolve(QUARANTINE).resolve(sanitize(record.ingestId()));
        createDirectories(dir);

        if (original != null) {
            writeAtomically(dir.resolve(ORIGINAL), original);
        }
        writeAtomically(dir.resolve(DIAGNOSTICS), serialize(record));

        log.info("Quarantined {} as {} ({} diagnostics)",
                record.sourceName(), record.reason(), record.diagnostics().size());
    }

    public Path publishedDir(String contentId) {
        return outputDir.resolve(PUBLISHED).resolve(sanitize(contentId));
    }

    public Path versionDir(String contentId, int version) {
        return publishedDir(contentId).resolve("v" + version);
    }

    public Path quarantineDir(String ingestId) {
        return outputDir.resolve(QUARANTINE).resolve(sanitize(ingestId));
    }

    private void writeAtomically(Path target, String content) {
        writeAtomically(target, out -> Files.write(out, content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Copies the source through, never holding it whole in memory. */
    private void writeAtomically(Path target, Resource source) {
        writeAtomically(target, out -> {
            try (var in = source.getInputStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return out;
        });
    }

    private void writeAtomically(Path target, TempFileWriter writer) {
        Path directory = target.getParent();
        Path temp = null;
        try {
            // Same directory, therefore same filesystem, therefore the move can be atomic.
            temp = Files.createTempFile(directory, ".tmp-", ".part");
            writer.writeTo(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
        } catch (IOException e) {
            throw new StorageFailedException("Could not write " + target, e);
        } finally {
            deleteQuietly(temp); // a failed write must not leave .part files behind
        }
    }

    @FunctionalInterface
    private interface TempFileWriter {
        Path writeTo(Path temp) throws IOException;
    }

    private String serialize(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize " + value.getClass(), e);
        }
    }

    private void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new StorageFailedException("Could not create " + directory, e);
        }
    }

    private void deleteRecursively(Path directory) throws IOException {
        try (var tree = Files.walk(directory)) {
            for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path); // children before their parent
            }
        }
    }

    private void deleteRecursivelyQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            deleteRecursively(directory);
        } catch (IOException e) {
            log.warn("Could not remove staging directory {}", directory, e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove temporary file {}", path, e);
        }
    }

    /**
     * content_id comes from a submitted document, and it becomes a directory name. Anything that
     * could climb out of the store ({@code ../}, a leading slash, a null byte) is refused rather
     * than escaped, because a judgment id has no business containing those characters.
     */
    private String sanitize(String id) {
        if (id == null || id.isBlank() || !id.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new StorageFailedException(
                    "Refusing to use \"" + id + "\" as a path segment", null);
        }
        return id;
    }
}
