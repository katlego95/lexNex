package io.github.katlego95.lexpipeline.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The committed demo set is documentation, and documentation rots. Each file in samples/demo
 * carries a comment claiming an outcome; this asserts the pipeline still produces it, so a schema
 * change cannot quietly turn the README into fiction.
 */
@SpringBootTest
class DemoSamplesTest {

    @TempDir
    static Path outputDir;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("app.output-dir", outputDir::toString);
    }

    private final DocumentPipeline pipeline;

    @Autowired
    DemoSamplesTest(DocumentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    private Outcome process(String file) {
        return pipeline.process(new FileSystemResource("samples/demo/" + file), file).outcome();
    }

    @Test
    void everyDemoFileProducesTheOutcomeItDocuments() {
        assertThat(process("valid-judgment.xml")).isEqualTo(Outcome.PUBLISHED);
        assertThat(process("valid-judgment.xml")).isEqualTo(Outcome.DUPLICATE_NOOP);
        assertThat(process("valid-judgment-corrected.xml")).isEqualTo(Outcome.SUPERSEDED);
        assertThat(process("invalid-date.xml")).isEqualTo(Outcome.SCHEMA_INVALID);
        assertThat(process("malformed.xml")).isEqualTo(Outcome.MALFORMED_XML);
    }
}
