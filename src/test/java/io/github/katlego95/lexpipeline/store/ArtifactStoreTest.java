package io.github.katlego95.lexpipeline.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.validation.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class ArtifactStoreTest {

    private static final Instant FIXED = Instant.parse("2026-08-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path outputDir;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(
                new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/",
                        outputDir.toString(), "target/unused", 1, 1, 1024),
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static ArtifactSet artifacts(String marker) {
        return new ArtifactSet(
                "{\"content_id\":\"FR-1\",\"marker\":\"" + marker + "\"}",
                "full text " + marker,
                "{\"chunk_id\":\"FR-1#p1\",\"marker\":\"" + marker + "\"}\n");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private JsonNode manifestJson(String contentId) {
        try {
            return MAPPER.readTree(read(store.publishedDir(contentId).resolve("manifest.json")));
        } catch (IOException e) {
            throw new AssertionError("manifest is not valid JSON", e);
        }
    }

    @Nested
    class Publishing {

        @Test
        void writesTheThreeArtifactsAndAManifest() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            Path v1 = store.versionDir("FR-1", 1);
            assertThat(read(v1.resolve("normalized.json"))).contains("\"marker\":\"v1\"");
            assertThat(read(v1.resolve("fulltext.txt"))).isEqualTo("full text v1");
            assertThat(read(v1.resolve("chunks.jsonl"))).endsWith("\n");
            assertThat(store.publishedDir("FR-1").resolve("manifest.json")).exists();
        }

        @Test
        void theManifestRecordsVersionHashAndTimestamp() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            JsonNode versions = manifestJson("FR-1").get("versions");
            assertThat(versions).hasSize(1);
            assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
            assertThat(versions.get(0).get("sha256").asText()).isEqualTo("sha-a");
            // ISO-8601, not an epoch number: this file gets read by people.
            assertThat(versions.get(0).get("publishedAt").asText()).startsWith("2026-08-25T09:00");
            assertThat(versions.get(0).has("supersededBy"))
                    .as("a current version carries no supersededBy key")
                    .isFalse();
        }

        @Test
        void asecondPublishAddsV2AndMarksV1Superseded() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));
            store.publish("FR-1", 2, "sha-b", artifacts("v2"));

            JsonNode versions = manifestJson("FR-1").get("versions");
            assertThat(versions).hasSize(2);
            assertThat(versions.get(0).get("supersededBy").asInt()).isEqualTo(2);
            assertThat(versions.get(1).has("supersededBy")).isFalse();
            // The superseded version's artifacts stay on disk: a citation into v1 must not break.
            assertThat(read(store.versionDir("FR-1", 1).resolve("fulltext.txt")))
                    .isEqualTo("full text v1");
        }

        @Test
        void theManifestRoundTripsBackIntoTheRecord() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));
            store.publish("FR-1", 2, "sha-b", artifacts("v2"));

            Manifest manifest = store.readManifest("FR-1").orElseThrow();

            assertThat(manifest.contentId()).isEqualTo("FR-1");
            assertThat(manifest.versions()).hasSize(2);
            assertThat(manifest.latest().orElseThrow().version()).isEqualTo(2);
            assertThat(manifest.latest().orElseThrow().publishedAt()).isEqualTo(FIXED);
            assertThat(manifest.nextVersion()).isEqualTo(3);
        }

        @Test
        void readManifestIsEmptyForAJudgmentNeverPublished() {
            assertThat(store.readManifest("FR-UNKNOWN")).isEmpty();
        }

        /**
         * The caller computes the next version from the manifest it read under the per-content_id
         * lock. If the store has moved on, the lock was not held and two publishers are racing —
         * better to fail than to overwrite someone else's version.
         */
        @Test
        void publishingTheWrongVersionNumberIsRefused() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            assertThatThrownBy(() -> store.publish("FR-1", 1, "sha-b", artifacts("clash")))
                    .isInstanceOf(StorageFailedException.class)
                    .hasMessageContaining("the store is at v2");

            assertThat(manifestJson("FR-1").get("versions")).hasSize(1);
        }

        /**
         * Atomic writes go through a temp file in the destination directory. If one were left
         * behind, a consumer listing the directory would see it — and a crashed write would leave
         * one permanently.
         */
        @Test
        void noPartialFilesSurviveAPublish() throws IOException {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            try (Stream<Path> tree = Files.walk(outputDir)) {
                assertThat(tree.map(Path::getFileName).map(Path::toString))
                        .noneMatch(name -> name.startsWith(".tmp-") || name.endsWith(".part"));
            }
        }

        @Test
        void artifactsAreWrittenAsUtf8() {
            store.publish("FR-1", 1, "sha-a",
                    new ArtifactSet("{\"court\":\"Cour d'appel\"}", "Considérant que...", "{}\n"));

            assertThat(read(store.versionDir("FR-1", 1).resolve("fulltext.txt")))
                    .isEqualTo("Considérant que...");
        }
    }

    /**
     * A publish is four files. These tests are about the moment a <em>version</em> becomes
     * visible, not the moment each file does.
     */
    @Nested
    class CommitPoint {

        /**
         * Fails exactly where the hole would be: after fulltext.txt, before chunks.jsonl. The
         * store must look as though the publish never started.
         */
        private ArtifactStore storeThatDiesBetweenFulltextAndChunks() {
            return new ArtifactStore(
                    new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/",
                            outputDir.toString(), "target/unused", 1, 1, 1024),
                    Clock.fixed(FIXED, ZoneOffset.UTC)) {
                @Override
                void writeArtifacts(Path stagingDir, ArtifactSet artifacts) throws IOException {
                    Files.writeString(stagingDir.resolve("normalized.json"),
                            artifacts.normalizedJson(), StandardCharsets.UTF_8);
                    Files.writeString(stagingDir.resolve("fulltext.txt"),
                            artifacts.fullText(), StandardCharsets.UTF_8);
                    throw new IOException("disk full");
                }
            };
        }

        @Test
        void aFailureMidPublishLeavesNoVersionDirectoryAtAll() {
            ArtifactStore dying = storeThatDiesBetweenFulltextAndChunks();

            assertThatThrownBy(() -> dying.publish("FR-1", 1, "sha-a", artifacts("v1")))
                    .isInstanceOf(StorageFailedException.class);

            // Not "a v1 with two of three files" — no v1 at all.
            assertThat(dying.versionDir("FR-1", 1)).doesNotExist();
            assertThat(dying.readManifest("FR-1")).isEmpty();
        }

        @Test
        void aFailureMidPublishLeavesNoStagingDirectoryEither() throws IOException {
            ArtifactStore dying = storeThatDiesBetweenFulltextAndChunks();

            assertThatThrownBy(() -> dying.publish("FR-1", 1, "sha-a", artifacts("v1")))
                    .isInstanceOf(StorageFailedException.class);

            try (Stream<Path> tree = Files.walk(outputDir)) {
                assertThat(tree.map(p -> p.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".staging"));
            }
        }

        @Test
        void aFailedSecondPublishLeavesTheCurrentVersionUntouched() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));
            ArtifactStore dying = storeThatDiesBetweenFulltextAndChunks();

            assertThatThrownBy(() -> dying.publish("FR-1", 2, "sha-b", artifacts("v2")))
                    .isInstanceOf(StorageFailedException.class);

            assertThat(dying.versionDir("FR-1", 2)).doesNotExist();
            Manifest manifest = store.readManifest("FR-1").orElseThrow();
            assertThat(manifest.versions()).hasSize(1);
            assertThat(manifest.latest().orElseThrow().supersededBy())
                    .as("v1 must not be marked superseded by a version that never landed")
                    .isNull();
            assertThat(read(store.versionDir("FR-1", 1).resolve("fulltext.txt")))
                    .isEqualTo("full text v1");
        }

        /**
         * The other half of the ordering: a version directory that exists but is not named by the
         * manifest is garbage, and a retry must replace it wholesale rather than merge into it —
         * otherwise a stale file from the abandoned attempt survives into the published version.
         */
        @Test
        void anOrphanedVersionDirectoryIsReplacedNotMergedIntoByTheRetry() throws IOException {
            Path orphan = store.versionDir("FR-1", 1);
            Files.createDirectories(orphan);
            Files.writeString(orphan.resolve("fulltext.txt"), "stale text from a crashed publish");
            Files.writeString(orphan.resolve("leftover.txt"), "should not survive");

            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            assertThat(read(orphan.resolve("fulltext.txt"))).isEqualTo("full text v1");
            assertThat(orphan.resolve("leftover.txt"))
                    .as("the retry replaces the directory, it does not write into it")
                    .doesNotExist();
        }

        @Test
        void theCommittedVersionIsAlwaysCompleteWhenTheManifestNamesIt() {
            store.publish("FR-1", 1, "sha-a", artifacts("v1"));

            Manifest manifest = store.readManifest("FR-1").orElseThrow();

            for (Manifest.Version version : manifest.versions()) {
                Path dir = store.versionDir("FR-1", version.version());
                assertThat(dir.resolve("normalized.json")).exists();
                assertThat(dir.resolve("fulltext.txt")).exists();
                assertThat(dir.resolve("chunks.jsonl")).exists();
            }
        }
    }

    @Nested
    class Quarantine {

        private final QuarantineRecord record = new QuarantineRecord(
                "ingest-1", "feed/bad.xml", "FR-2", "SCHEMA_INVALID", FIXED,
                List.of(new Diagnostic(Diagnostic.Severity.ERROR, 7, 12, "cvc-id.2",
                        "There are multiple occurrences of ID value 'p2'.")));

        @Test
        void writesTheOriginalBesideTheDiagnostics() {
            Resource original = new ByteArrayResource("<judgment/>".getBytes(StandardCharsets.UTF_8));

            store.quarantine(record, original);

            Path dir = store.quarantineDir("ingest-1");
            assertThat(read(dir.resolve("original.xml"))).isEqualTo("<judgment/>");
            assertThat(read(dir.resolve("diagnostics.json")))
                    .contains("SCHEMA_INVALID")
                    .contains("cvc-id.2")
                    .contains("feed/bad.xml")
                    .contains("\"line\" : 7");
        }

        /** OVERSIZE: the service refused to read it, so it does not write it back out either. */
        @Test
        void writesOnlyDiagnosticsWhenThereIsNoOriginalToKeep() {
            store.quarantine(record, null);

            Path dir = store.quarantineDir("ingest-1");
            assertThat(dir.resolve("original.xml")).doesNotExist();
            assertThat(dir.resolve("diagnostics.json")).exists();
        }

        @Test
        void nothingIsPublishedByAQuarantine() throws IOException {
            store.quarantine(record, null);

            try (Stream<Path> published = Files.list(outputDir.resolve("published"))) {
                assertThat(published).isEmpty();
            }
        }
    }

    @Nested
    class PathSafety {

        /** content_id arrives inside a submitted document and becomes a directory name. */
        @Test
        void aContentIdThatWouldEscapeTheStoreIsRefused() {
            for (String hostile : List.of("../../etc/passwd", "/absolute", "a/b", "", "  ")) {
                assertThatThrownBy(() -> store.publish(hostile, 1, "sha", artifacts("x")))
                        .as("content_id %s", hostile)
                        .isInstanceOf(StorageFailedException.class)
                        .hasMessageContaining("Refusing to use");
            }
        }

        @Test
        void ordinaryJudgmentIdsAreAccepted() {
            assertThat(store.publish("FR-2024-CA-000123", 1, "sha", artifacts("v1")).version())
                    .isEqualTo(1);
        }
    }
}
