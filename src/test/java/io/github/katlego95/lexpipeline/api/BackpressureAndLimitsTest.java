package io.github.katlego95.lexpipeline.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.katlego95.lexpipeline.batch.BatchService;
import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.pipeline.DocumentPipeline;
import io.github.katlego95.lexpipeline.store.ArtifactStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The two ways the single-document endpoint refuses work: the queue is full, or the document is
 * bigger than the service will read.
 */
class BackpressureAndLimitsTest {

    private static byte[] sample() {
        try (var in = new ClassPathResource("samples/valid-judgment.xml").getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("fixture unreadable", e);
        }
    }

    /**
     * Saturation is arranged rather than raced for: a real full queue would need workers blocked
     * on something, which makes for a slow and flaky test. What matters is the contract — 429 plus
     * Retry-After, and the pipeline never invoked.
     */
    @Nested
    class WhenTheQueueIsFull {

        private final DocumentPipeline pipeline = mock(DocumentPipeline.class);
        private final BatchService saturated = mock(BatchService.class);
        private final MockMvc mvc;

        WhenTheQueueIsFull() {
            when(saturated.isSaturated()).thenReturn(true);
            when(saturated.queueDepth()).thenReturn(64);
            mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                            pipeline, mock(ArtifactStore.class), saturated,
                            new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/",
                                    "target/unused", "target/unused", 4, 64, 10L << 20)))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();
        }

        @Test
        void submissionIsShedWith429AndRetryAfter() throws Exception {
            mvc.perform(post("/api/v1/documents")
                            .contentType(MediaType.APPLICATION_XML)
                            .content(sample()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.title").value("Ingestion queue is full"));
        }

        @Test
        void theDocumentIsNotProcessedAtAll() throws Exception {
            mvc.perform(post("/api/v1/documents")
                    .contentType(MediaType.APPLICATION_XML)
                    .content(sample()));

            // Shedding means not doing the work. Accepting it and queueing internally would be
            // the same unbounded buffer the queue exists to prevent.
            verify(pipeline, never()).process(any(), any());
        }
    }

    /**
     * The size guard reached through HTTP: recorded as an outcome, answered as 413. The limit
     * is set just above a minimal judgment and well below the sample, so both sides are exercised.
     */
    @SpringBootTest(properties = "app.max-doc-bytes=500")
    @AutoConfigureMockMvc
    @Nested
    class WhenTheDocumentIsTooLarge {

        @TempDir
        static Path outputDir;
        @TempDir
        static Path inputDir;

        @DynamicPropertySource
        static void directories(DynamicPropertyRegistry registry) {
            registry.add("app.output-dir", outputDir::toString);
            registry.add("app.input-dir", inputDir::toString);
        }

        private final MockMvc mvc;

        @Autowired
        WhenTheDocumentIsTooLarge(MockMvc mvc) {
            this.mvc = mvc;
        }

        @Test
        void anOversizeBodyIs413AndNotStored() throws Exception {
            mvc.perform(post("/api/v1/documents")
                            .contentType(MediaType.APPLICATION_XML)
                            .content(sample()))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.outcome").value("OVERSIZE"))
                    .andExpect(jsonPath("$.ingestId").isNotEmpty());
        }

        @Test
        void aDocumentInsideTheLimitStillPublishes() throws Exception {
            String tiny = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <judgment xmlns="urn:lex:content:1"><header><content_id>FR-T</content_id>\
                    <title>T</title><court>C</court><jurisdiction>FR</jurisdiction>\
                    <decision_date>2024-03-12</decision_date></header>\
                    <body><section type="facts"><p id="p1">T.</p></section></body></judgment>""";

            mvc.perform(post("/api/v1/documents")
                            .contentType(MediaType.APPLICATION_XML)
                            .content(tiny.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("PUBLISHED"));
        }
    }
}
