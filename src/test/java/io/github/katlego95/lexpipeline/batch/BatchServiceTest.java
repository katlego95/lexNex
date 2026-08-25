package io.github.katlego95.lexpipeline.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.config.HardenedXmlReaderFactory;
import io.github.katlego95.lexpipeline.identity.ContentIdentityReader;
import io.github.katlego95.lexpipeline.pipeline.DocumentPipeline;
import io.github.katlego95.lexpipeline.pipeline.Outcome;
import io.github.katlego95.lexpipeline.store.ArtifactStore;
import io.github.katlego95.lexpipeline.transform.ChunkBuilder;
import io.github.katlego95.lexpipeline.transform.XsltTransformService;
import io.github.katlego95.lexpipeline.validation.XsdValidationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * The batch integration test the phase asks for: real pipeline, real store, real thread pool,
 * documents on a real directory.
 */
class BatchServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path inputDir;
    @TempDir
    Path outputDir;

    private final ResourceLoader loader = new DefaultResourceLoader();
    private final HardenedXmlReaderFactory readers = new HardenedXmlReaderFactory();
    private final Clock clock = Clock.system(ZoneOffset.UTC);

    private BatchService batchService;

    private BatchService serviceWith(int concurrency, int queueCapacity) {
        AppProperties properties = new AppProperties("classpath:schema/judgment.xsd",
                "classpath:xslt/", outputDir.toString(), inputDir.toString(),
                concurrency, queueCapacity, 10L << 20);
        DocumentPipeline pipeline = new DocumentPipeline(
                new XsdValidationService(properties, loader, readers),
                new ContentIdentityReader(),
                new XsltTransformService(properties, loader, readers),
                new ChunkBuilder(),
                new ArtifactStore(properties, clock),
                clock);
        batchService = new BatchService(pipeline, new JobRegistry(clock), properties, clock);
        return batchService;
    }

    @AfterEach
    void tearDown() {
        if (batchService != null) {
            batchService.shutdown();
        }
    }

    private BatchJob awaitCompletion(BatchJob job) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (job.status() == BatchStatus.COMPLETED || job.status() == BatchStatus.FAILED) {
                return job;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for batch", e);
            }
        }
        throw new AssertionError("batch did not finish within " + TIMEOUT
                + " (status " + job.status() + ", " + job.processed() + "/" + job.discovered() + ")");
    }

    private void write(String name, String content) {
        try {
            Files.writeString(inputDir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String judgment(String contentId, String paragraph) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1">
                  <header>
                    <content_id>%s</content_id>
                    <title>Cour d'appel de Paris, 12 mars 2024</title>
                    <court>Cour d'appel de Paris</court>
                    <jurisdiction>FR</jurisdiction>
                    <decision_date>2024-03-12</decision_date>
                  </header>
                  <body>
                    <section type="facts"><p id="p1">%s</p></section>
                  </body>
                </judgment>
                """.formatted(contentId, paragraph);
    }

    /**
     * Twenty documents, four workers, a queue far smaller than the batch: every document reaches
     * exactly one outcome, and the counts add up to what was written.
     */
    @Test
    void aBatchOfTwentyDocumentsAccountsForEveryOutcome() {
        for (int i = 0; i < 12; i++) {
            write("valid-" + i + ".xml", judgment("FR-BATCH-" + i, "Paragraphe " + i + "."));
        }
        // Three deliveries of the same judgment: one publishes, two are recorded no-ops.
        for (int i = 0; i < 3; i++) {
            write("duplicate-" + i + ".xml", judgment("FR-BATCH-DUP", "Même texte."));
        }
        for (int i = 0; i < 3; i++) {
            write("invalid-" + i + ".xml",
                    judgment("FR-BATCH-BAD-" + i, "Texte.").replace("2024-03-12", "2024-13-45"));
        }
        for (int i = 0; i < 2; i++) {
            write("malformed-" + i + ".xml", "this is not xml <judgment");
        }

        BatchJob job = awaitCompletion(serviceWith(4, 2).submit(null));

        assertThat(job.discovered()).isEqualTo(20);
        assertThat(job.processed()).isEqualTo(20);
        assertThat(job.counts().values().stream().mapToInt(Integer::intValue).sum())
                .as("every document reached exactly one outcome")
                .isEqualTo(20);
        assertThat(job.count(Outcome.PUBLISHED)).isEqualTo(13);      // 12 unique + 1 of the trio
        assertThat(job.count(Outcome.DUPLICATE_NOOP)).isEqualTo(2);  // the other two of the trio
        assertThat(job.count(Outcome.SCHEMA_INVALID)).isEqualTo(3);
        assertThat(job.count(Outcome.MALFORMED_XML)).isEqualTo(2);
        assertThat(job.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * The memory argument, made as an assertion rather than a comment. Two hundred documents
     * through a queue of two: if anything buffered the batch — a list of files, a list of results,
     * an unbounded queue — the observed depth would climb with the batch size. It cannot exceed
     * the bound, so peak memory is set by {@code queueCapacity + concurrency}, not by how many
     * documents are in the directory.
     */
    @Test
    void queueDepthStaysBoundedNoMatterHowLargeTheBatchIs() {
        for (int i = 0; i < 200; i++) {
            write("doc-" + i + ".xml", judgment("FR-FLAT-" + i, "Paragraphe " + i + "."));
        }

        BatchService service = serviceWith(4, 2);
        BatchJob job = awaitCompletion(service.submit(null));

        assertThat(job.processed()).isEqualTo(200);
        assertThat(job.count(Outcome.PUBLISHED)).isEqualTo(200);
        assertThat(service.peakQueueDepth())
                .as("the queue never held more than its capacity, so the scan blocked as intended")
                .isLessThanOrEqualTo(service.queueCapacity());
    }

    @Test
    void anEmptyDirectoryCompletesImmediately() {
        BatchJob job = awaitCompletion(serviceWith(2, 4).submit(null));

        assertThat(job.discovered()).isZero();
        assertThat(job.status()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void onlyXmlFilesAreSubmitted() {
        write("judgment.xml", judgment("FR-ONLY-1", "Texte."));
        write("notes.txt", "not a judgment");
        write("archive.zip", "neither");

        BatchJob job = awaitCompletion(serviceWith(2, 4).submit(null));

        assertThat(job.discovered()).isEqualTo(1);
    }

    @Test
    void subdirectoriesAreScannedToo() throws IOException {
        Files.createDirectories(inputDir.resolve("2024/mars"));
        Files.writeString(inputDir.resolve("2024/mars/j.xml"), judgment("FR-NESTED-1", "Texte."));

        BatchJob job = awaitCompletion(serviceWith(2, 4).submit(null));

        assertThat(job.count(Outcome.PUBLISHED)).isEqualTo(1);
    }

    @Test
    void aBatchCanTargetASubdirectoryOfTheInputRoot() throws IOException {
        Files.createDirectories(inputDir.resolve("batch-a"));
        Files.writeString(inputDir.resolve("batch-a/j.xml"), judgment("FR-SUB-1", "Texte."));
        write("outside.xml", judgment("FR-SUB-2", "Texte."));

        BatchJob job = awaitCompletion(serviceWith(2, 4).submit("batch-a"));

        assertThat(job.discovered()).as("only the named subdirectory").isEqualTo(1);
    }

    /**
     * Symlinks are the escape hatch that path normalisation cannot see: {@code in/leak.xml}
     * normalises to itself and looks perfectly contained, right up until the filesystem follows it
     * somewhere else. Every candidate is therefore resolved with {@code toRealPath()} before it is
     * read.
     */
    @Nested
    class SymlinksOutOfTheInputRoot {

        private Path linkTo(Path target, String linkName) {
            try {
                return Files.createSymbolicLink(inputDir.resolve(linkName), target);
            } catch (IOException | UnsupportedOperationException e) {
                org.junit.jupiter.api.Assumptions.abort(
                        "filesystem does not support symlinks: " + e.getMessage());
                throw new IllegalStateException("unreachable");
            }
        }

        @Test
        void aFileSymlinkPointingOutsideTheRootIsRefusedAndRecorded() throws IOException {
            // A judgment sitting outside the input root — another tenant's feed, /etc, anywhere.
            Path outside = Files.createTempDirectory("outside-the-root");
            Path secret = outside.resolve("secret.xml");
            Files.writeString(secret, judgment("FR-LEAKED", "Contenu confidentiel."));
            linkTo(secret, "leak.xml");
            write("legitimate.xml", judgment("FR-LEGIT", "Texte."));

            BatchJob job = awaitCompletion(serviceWith(2, 4).submit(null));

            assertThat(job.discovered()).as("the link is never counted as ours").isEqualTo(1);
            assertThat(job.skipped()).isEqualTo(1);
            assertThat(job.lastSkipReason())
                    .contains("leak.xml")
                    .contains("resolves outside the input directory");
            assertThat(job.count(Outcome.PUBLISHED)).isEqualTo(1);
        }

        @Test
        void theLinkedContentIsNeverPublished() throws IOException {
            Path outside = Files.createTempDirectory("outside-the-root");
            Files.writeString(outside.resolve("secret.xml"), judgment("FR-LEAKED", "Confidentiel."));
            linkTo(outside.resolve("secret.xml"), "leak.xml");

            awaitCompletion(serviceWith(2, 4).submit(null));

            // The real assertion: nothing from outside the root entered the corpus.
            assertThat(outputDir.resolve("published").resolve("FR-LEAKED")).doesNotExist();
        }

        /** Not a ban on symlinks — a ban on leaving the root. */
        @Test
        void aSymlinkPointingInsideTheRootIsProcessedNormally() throws IOException {
            Files.createDirectories(inputDir.resolve("real"));
            Path target = inputDir.resolve("real/j.xml");
            Files.writeString(target, judgment("FR-INSIDE-LINK", "Texte."));
            linkTo(target, "alias.xml");

            BatchJob job = awaitCompletion(serviceWith(2, 4).submit(null));

            // Discovered twice (the file and its alias), published once, second is a no-op.
            assertThat(job.discovered()).isEqualTo(2);
            assertThat(job.skipped()).isZero();
            assertThat(job.count(Outcome.PUBLISHED)).isEqualTo(1);
            assertThat(job.count(Outcome.DUPLICATE_NOOP)).isEqualTo(1);
        }

        /** Files.walk does not descend directory symlinks, and the request cannot name one either. */
        @Test
        void aDirectorySymlinkOutOfTheRootIsNeitherWalkedNorAddressable() throws IOException {
            Path outside = Files.createTempDirectory("outside-the-root");
            Files.writeString(outside.resolve("secret.xml"), judgment("FR-LEAKED-DIR", "Texte."));
            linkTo(outside, "elsewhere");

            BatchService service = serviceWith(2, 4);

            assertThatThrownBy(() -> service.submit("elsewhere"))
                    .isInstanceOf(InvalidBatchRequestException.class)
                    .hasMessageContaining("resolves outside");

            BatchJob job = awaitCompletion(service.submit(null));
            assertThat(job.discovered()).isZero();
            assertThat(outputDir.resolve("published").resolve("FR-LEAKED-DIR")).doesNotExist();
        }
    }

    /** The request names a directory, so it is the obvious place to try to escape from. */
    @Test
    void aBatchCannotBeAimedOutsideTheInputRoot() {
        BatchService service = serviceWith(2, 4);

        for (String hostile : new String[] {"../", "../../etc", "/etc", "a/../../.."}) {
            assertThatThrownBy(() -> service.submit(hostile))
                    .as("inputDir %s", hostile)
                    .isInstanceOf(InvalidBatchRequestException.class);
        }
    }

    @Test
    void aMissingDirectoryFailsTheBatchRatherThanTheService() {
        BatchJob job = awaitCompletion(serviceWith(2, 4).submit("does-not-exist"));

        assertThat(job.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(job.failure()).contains("Not a directory");
    }

    @Test
    void theRegistryKeepsCountersNotDocuments() {
        write("j.xml", judgment("FR-REG-1", "Texte."));
        JobRegistry registry = new JobRegistry(clock);
        AppProperties properties = new AppProperties("classpath:schema/judgment.xsd",
                "classpath:xslt/", outputDir.toString(), inputDir.toString(), 2, 4, 10L << 20);
        DocumentPipeline pipeline = new DocumentPipeline(
                new XsdValidationService(properties, loader, readers), new ContentIdentityReader(),
                new XsltTransformService(properties, loader, readers), new ChunkBuilder(),
                new ArtifactStore(properties, clock), clock);
        batchService = new BatchService(pipeline, registry, properties, clock);

        BatchJob job = awaitCompletion(batchService.submit(null));

        assertThat(registry.find(job.batchId())).containsSame(job);
        assertThat(registry.find("no-such-batch")).isEmpty();
        assertThat(job.averageProcessingTime()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(job.elapsed(clock.instant())).isGreaterThanOrEqualTo(Duration.ZERO);
    }
}
