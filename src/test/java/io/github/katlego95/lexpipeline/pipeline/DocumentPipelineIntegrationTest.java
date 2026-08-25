package io.github.katlego95.lexpipeline.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.katlego95.lexpipeline.store.ArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The wiring test: the same run, but with every collaborator built by Spring from
 * {@code application.yml} rather than by hand in a test. It is what would catch a missing bean, a
 * property that never binds, or a Clock nobody provided — none of which the unit tests can see.
 */
@SpringBootTest
class DocumentPipelineIntegrationTest {

    @TempDir
    static Path outputDir;

    @DynamicPropertySource
    static void artifactStoreLocation(DynamicPropertyRegistry registry) {
        registry.add("app.output-dir", outputDir::toString);
    }

    private final DocumentPipeline pipeline;
    private final ArtifactStore store;

    @Autowired
    DocumentPipelineIntegrationTest(DocumentPipeline pipeline, ArtifactStore store) {
        this.pipeline = pipeline;
        this.store = store;
    }

    @Test
    void theContextWiresAWorkingPipelineEndToEnd() {
        PipelineResult result = pipeline.process(
                new ClassPathResource("samples/valid-judgment.xml"), "integration/valid.xml");

        assertThat(result.outcome()).isEqualTo(Outcome.PUBLISHED);
        assertThat(result.contentId()).isEqualTo("FR-2024-CA-000123");

        Path v1 = store.versionDir("FR-2024-CA-000123", 1);
        assertThat(Files.exists(v1.resolve("normalized.json"))).isTrue();
        assertThat(Files.exists(v1.resolve("fulltext.txt"))).isTrue();
        assertThat(Files.exists(v1.resolve("chunks.jsonl"))).isTrue();
        assertThat(Files.exists(store.publishedDir("FR-2024-CA-000123").resolve("manifest.json")))
                .isTrue();
    }

    @Test
    void aRejectedDocumentIsQuarantinedByTheWiredPipelineToo() {
        PipelineResult result = pipeline.process(
                new ClassPathResource("samples/invalid-date.xml"), "integration/invalid.xml");

        assertThat(result.outcome()).isEqualTo(Outcome.SCHEMA_INVALID);
        assertThat(Files.exists(store.quarantineDir(result.ingestId()).resolve("diagnostics.json")))
                .isTrue();
    }
}
