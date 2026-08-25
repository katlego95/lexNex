package io.github.katlego95.lexpipeline.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.config.HardenedXmlReaderFactory;
import io.github.katlego95.lexpipeline.identity.ContentIdentityException;
import io.github.katlego95.lexpipeline.identity.ContentIdentityReader;
import io.github.katlego95.lexpipeline.store.ArtifactStore;
import io.github.katlego95.lexpipeline.store.Manifest;
import io.github.katlego95.lexpipeline.store.StorageFailedException;
import io.github.katlego95.lexpipeline.transform.ChunkBuilder;
import io.github.katlego95.lexpipeline.transform.TransformFailedException;
import io.github.katlego95.lexpipeline.transform.XsltTransformService;
import io.github.katlego95.lexpipeline.validation.Diagnostic;
import io.github.katlego95.lexpipeline.validation.XsdValidationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * End to end through the real validator, the real stylesheets and a real store on a temp
 * directory. Only the failure-injection tests use mocks, because a disk that is full and a
 * stylesheet that explodes cannot be arranged with a fixture.
 */
class DocumentPipelineTest {

    private static final Instant FIXED = Instant.parse("2026-08-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SAMPLE_ID = "FR-2024-CA-000123";

    @TempDir
    Path outputDir;

    private final ResourceLoader loader = new DefaultResourceLoader();
    private final HardenedXmlReaderFactory readers = new HardenedXmlReaderFactory();
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private ArtifactStore store;
    private DocumentPipeline pipeline;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(properties(10L << 20), clock);
        pipeline = pipelineWith(store, transformService(10L << 20), new ContentIdentityReader(),
                10L << 20);
    }

    private AppProperties properties(long maxDocBytes) {
        return new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/",
                outputDir.toString(), maxDocBytes);
    }

    private XsltTransformService transformService(long maxDocBytes) {
        return new XsltTransformService(properties(maxDocBytes), loader, readers);
    }

    private DocumentPipeline pipelineWith(ArtifactStore artifactStore, XsltTransformService transform,
            ContentIdentityReader identity, long maxDocBytes) {
        return new DocumentPipeline(
                new XsdValidationService(properties(maxDocBytes), loader, readers),
                identity, transform, new ChunkBuilder(), artifactStore, clock);
    }

    private static Resource sample(String name) {
        return new ClassPathResource("samples/" + name);
    }

    private static Resource xml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    /** The sample judgment with one paragraph reworded: same content_id, different bytes. */
    private static Resource revisedSample() {
        return xml(read(sample("valid-judgment.xml"))
                .replace("Le litige porte sur...", "Le litige porte sur la vente du fonds."));
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture unreadable", e);
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + raw, e);
        }
    }

    @Nested
    class HappyPath {

        @Test
        void aValidDocumentIsPublishedAsVersionOne() {
            PipelineResult result = pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.PUBLISHED);
            assertThat(result.contentId()).isEqualTo(SAMPLE_ID);
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.sha256()).hasSize(64);
            assertThat(result.diagnostics()).isEmpty();
        }

        @Test
        void allThreeArtifactsLandOnDisk() {
            pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");

            Path v1 = store.versionDir(SAMPLE_ID, 1);
            assertThat(json(readFile(v1.resolve("normalized.json"))).get("content_id").asText())
                    .isEqualTo(SAMPLE_ID);
            assertThat(readFile(v1.resolve("fulltext.txt")))
                    .isEqualTo("Le litige porte sur... Considérant que... Attendu que... "
                            + "Par ces motifs...");
            assertThat(readFile(v1.resolve("chunks.jsonl")).lines()).hasSize(4);
        }

        @Test
        void chunksAreStampedWithThePublishedVersion() {
            pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");
            pipeline.process(revisedSample(), "feed/a-corrected.xml");

            String v2Chunks = readFile(store.versionDir(SAMPLE_ID, 2).resolve("chunks.jsonl"));

            assertThat(v2Chunks.lines()).allSatisfy(line ->
                    assertThat(json(line).get("version").asInt()).isEqualTo(2));
            assertThat(json(v2Chunks.lines().findFirst().orElseThrow()).get("text").asText())
                    .isEqualTo("Le litige porte sur la vente du fonds.");
        }

        @Test
        void twoDifferentJudgmentsGetTheirOwnDirectories() {
            pipeline.process(sample("valid-judgment.xml"), "a.xml");
            pipeline.process(sample("minimal-judgment.xml"), "b.xml");

            assertThat(store.versionDir(SAMPLE_ID, 1)).exists();
            assertThat(store.versionDir("FR-2024-CC-000999", 1)).exists();
        }
    }

    @Nested
    class Idempotency {

        @Test
        void resubmittingIdenticalBytesIsARecordedNoop() {
            pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");

            PipelineResult again = pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");

            assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE_NOOP);
            assertThat(again.version()).isEqualTo(1);
            assertThat(store.versionDir(SAMPLE_ID, 2))
                    .as("a duplicate must not create a new version")
                    .doesNotExist();
            assertThat(store.readManifest(SAMPLE_ID).orElseThrow().versions()).hasSize(1);
        }

        @Test
        void resubmittingChangedContentPublishesVersionTwoAndSupersedesVersionOne() {
            pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");

            PipelineResult revised = pipeline.process(revisedSample(), "feed/a-corrected.xml");

            assertThat(revised.outcome()).isEqualTo(Outcome.SUPERSEDED);
            assertThat(revised.version()).isEqualTo(2);

            List<Manifest.Version> versions =
                    store.readManifest(SAMPLE_ID).orElseThrow().versions();
            assertThat(versions).hasSize(2);
            assertThat(versions.get(0).supersededBy()).isEqualTo(2);
            assertThat(versions.get(1).supersededBy()).isNull();
            assertThat(versions.get(0).sha256()).isNotEqualTo(versions.get(1).sha256());
        }

        @Test
        void theSupersededVersionRemainsReadable() {
            pipeline.process(sample("valid-judgment.xml"), "feed/a.xml");
            pipeline.process(revisedSample(), "feed/a-corrected.xml");

            // A citation into v1 must keep resolving after v2 exists.
            assertThat(readFile(store.versionDir(SAMPLE_ID, 1).resolve("fulltext.txt")))
                    .startsWith("Le litige porte sur...");
        }

        @Test
        void aThirdDeliveryOfTheSecondVersionIsAgainANoop() {
            pipeline.process(sample("valid-judgment.xml"), "a.xml");
            pipeline.process(revisedSample(), "b.xml");

            PipelineResult third = pipeline.process(revisedSample(), "c.xml");

            assertThat(third.outcome()).isEqualTo(Outcome.DUPLICATE_NOOP);
            assertThat(third.version()).isEqualTo(2);
        }
    }

    @Nested
    class Rejections {

        @Test
        void schemaInvalidIsQuarantinedWithItsDiagnostics() {
            PipelineResult result =
                    pipeline.process(sample("duplicate-paragraph-id.xml"), "feed/bad.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.SCHEMA_INVALID);
            assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("cvc-id.2");

            Path dir = store.quarantineDir(result.ingestId());
            assertThat(readFile(dir.resolve("original.xml"))).contains("FR-2024-CA-000125");
            assertThat(readFile(dir.resolve("diagnostics.json")))
                    .contains("SCHEMA_INVALID")
                    .contains("cvc-id.2")
                    .contains("feed/bad.xml");
        }

        @Test
        void malformedInputIsQuarantined() {
            PipelineResult result = pipeline.process(sample("malformed.xml"), "feed/broken.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.MALFORMED_XML);
            assertThat(result.contentId()).as("nothing parseable to identify it by").isNull();
            assertThat(store.quarantineDir(result.ingestId()).resolve("original.xml")).exists();
        }

        @Test
        void aDoctypeIsQuarantinedAtTheGateNotAtTheTransform() {
            PipelineResult result =
                    pipeline.process(sample("xxe-external-entity.xml"), "feed/xxe.xml");

            // The whole point of moving the refusal forward: this is a security rejection with a
            // usable diagnostic, not a TRANSFORM_FAILED with nothing in it.
            assertThat(result.outcome()).isEqualTo(Outcome.DOCTYPE_REJECTED);
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .containsExactly("lex-doctype-not-allowed");
        }

        @Test
        void anOversizeDocumentIsQuarantinedWithoutStoringTheOriginal() {
            DocumentPipeline tiny =
                    pipelineWith(store, transformService(64), new ContentIdentityReader(), 64);

            PipelineResult result = tiny.process(sample("valid-judgment.xml"), "feed/huge.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.OVERSIZE);
            Path dir = store.quarantineDir(result.ingestId());
            assertThat(dir.resolve("diagnostics.json")).exists();
            assertThat(dir.resolve("original.xml"))
                    .as("we refused to read it, so we do not write it back out")
                    .doesNotExist();
        }

        @Test
        void nothingIsPublishedWhenADocumentIsRejected() {
            pipeline.process(sample("invalid-date.xml"), "feed/bad.xml");

            assertThat(outputDir.resolve("published").resolve("FR-2024-CA-000124")).doesNotExist();
        }
    }

    @Nested
    class FailuresInOurOwnCode {

        @Test
        void aTransformFailureIsQuarantinedAndRecorded() {
            XsltTransformService exploding = mock(XsltTransformService.class);
            when(exploding.toNormalizedJson(any()))
                    .thenThrow(new TransformFailedException("stylesheet exploded", null));
            DocumentPipeline broken =
                    pipelineWith(store, exploding, new ContentIdentityReader(), 10L << 20);

            PipelineResult result = broken.process(sample("valid-judgment.xml"), "feed/a.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.TRANSFORM_FAILED);
            assertThat(result.contentId()).isEqualTo(SAMPLE_ID);
            // Quarantined even though the fault is ours: without the original there is nothing to
            // reproduce the bug from.
            assertThat(store.quarantineDir(result.ingestId()).resolve("original.xml")).exists();
            assertThat(store.readManifest(SAMPLE_ID)).isEmpty();
        }

        @Test
        void aStorageFailureIsRecordedAndNotQuarantined() {
            ArtifactStore failing = mock(ArtifactStore.class);
            when(failing.readManifest(any()))
                    .thenThrow(new StorageFailedException("disk full", null));
            DocumentPipeline broken =
                    pipelineWith(failing, transformService(10L << 20), new ContentIdentityReader(),
                            10L << 20);

            PipelineResult result = broken.process(sample("valid-judgment.xml"), "feed/a.xml");

            // Storage is the thing that failed; writing a quarantine record would fail too.
            assertThat(result.outcome()).isEqualTo(Outcome.STORAGE_FAILED);
            assertThat(result.diagnostics()).isNotEmpty();
        }

        @Test
        void anIdentityFailureIsRecordedAsAnInternalError() {
            ContentIdentityReader broken = mock(ContentIdentityReader.class);
            when(broken.identify(any()))
                    .thenThrow(new ContentIdentityException("no content_id"));
            DocumentPipeline pipelineWithBrokenIdentity =
                    pipelineWith(store, transformService(10L << 20), broken, 10L << 20);

            PipelineResult result = pipelineWithBrokenIdentity
                    .process(sample("valid-judgment.xml"), "feed/a.xml");

            assertThat(result.outcome()).isEqualTo(Outcome.INTERNAL_ERROR);
        }

        @Test
        void noInputMakesThePipelineThrow() {
            List<String> everything = List.of("valid-judgment.xml", "minimal-judgment.xml",
                    "invalid-date.xml", "duplicate-paragraph-id.xml", "missing-paragraph-id.xml",
                    "malformed.xml", "xxe-external-entity.xml");

            assertThatCode(() -> everything.forEach(name -> {
                PipelineResult result = pipeline.process(sample(name), name);
                assertThat(result.outcome()).isNotNull();
            })).doesNotThrowAnyException();
        }
    }

    @Nested
    class Concurrency {

        /**
         * Without the per-content_id lock both workers read "no versions yet", both compute v1,
         * and the second overwrites the first — one delivery silently lost. With it, one publishes
         * and the other sees what the first committed.
         */
        @Test
        void concurrentDeliveriesOfChangedContentProduceTwoVersionsNotTwoV1s() throws Exception {
            List<Outcome> outcomes = runConcurrently(
                    () -> pipeline.process(sample("valid-judgment.xml"), "a.xml").outcome(),
                    () -> pipeline.process(revisedSample(), "b.xml").outcome());

            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.PUBLISHED, Outcome.SUPERSEDED);

            List<Manifest.Version> versions =
                    store.readManifest(SAMPLE_ID).orElseThrow().versions();
            assertThat(versions).hasSize(2);
            assertThat(versions.get(0).supersededBy()).isEqualTo(2);
            assertThat(store.versionDir(SAMPLE_ID, 1)).exists();
            assertThat(store.versionDir(SAMPLE_ID, 2)).exists();
        }

        @Test
        void concurrentIdenticalDeliveriesPublishExactlyOnce() throws Exception {
            List<Outcome> outcomes = runConcurrently(
                    () -> pipeline.process(sample("valid-judgment.xml"), "a.xml").outcome(),
                    () -> pipeline.process(sample("valid-judgment.xml"), "b.xml").outcome());

            assertThat(outcomes)
                    .containsExactlyInAnyOrder(Outcome.PUBLISHED, Outcome.DUPLICATE_NOOP);
            assertThat(store.readManifest(SAMPLE_ID).orElseThrow().versions()).hasSize(1);
        }

        @Test
        void manyConcurrentIdenticalDeliveriesStillPublishExactlyOnce() throws Exception {
            List<Callable<Outcome>> work = IntStream.range(0, 16)
                    .mapToObj(i -> (Callable<Outcome>) () ->
                            pipeline.process(sample("valid-judgment.xml"), "n" + i).outcome())
                    .toList();

            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Outcome> outcomes = pool.invokeAll(work).stream()
                        .map(DocumentPipelineTest::get)
                        .toList();

                assertThat(outcomes).filteredOn(Outcome.PUBLISHED::equals).hasSize(1);
                assertThat(outcomes).filteredOn(Outcome.DUPLICATE_NOOP::equals).hasSize(15);
            }
        }

        @SafeVarargs
        private List<Outcome> runConcurrently(Callable<Outcome>... tasks) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(tasks.length)) {
                List<Future<Outcome>> futures = List.of(tasks).stream()
                        .map(task -> pool.submit(() -> {
                            start.await();
                            return task.call();
                        }))
                        .toList();
                start.countDown(); // release both at once to maximise the overlap
                return futures.stream().map(DocumentPipelineTest::get).toList();
            }
        }
    }

    private static Outcome get(Future<Outcome> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("worker failed", e);
        }
    }
}
