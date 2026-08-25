package io.github.katlego95.lexpipeline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.concurrency=4", "app.queue-capacity=2"})
@AutoConfigureMockMvc
class BatchApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
    BatchApiTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static String judgment(String contentId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1">
                  <header>
                    <content_id>%s</content_id>
                    <title>Cour d'appel de Paris</title>
                    <court>Cour d'appel de Paris</court>
                    <jurisdiction>FR</jurisdiction>
                    <decision_date>2024-03-12</decision_date>
                  </header>
                  <body><section type="facts"><p id="p1">Texte.</p></section></body>
                </judgment>
                """.formatted(contentId);
    }

    private JsonNode pollUntilFinished(String batchId) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String body = mvc.perform(get("/api/v1/batches/{id}", batchId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode status = MAPPER.readTree(body);
            if ("COMPLETED".equals(status.get("status").asText())
                    || "FAILED".equals(status.get("status").asText())) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("batch did not finish within " + TIMEOUT);
    }

    @Test
    void aBatchIsAcceptedImmediatelyAndPolledToCompletion() throws Exception {
        Path directory = inputDir.resolve("run-1");
        Files.createDirectories(directory);
        for (int i = 0; i < 20; i++) {
            Files.writeString(directory.resolve("j-" + i + ".xml"), judgment("FR-API-" + i),
                    StandardCharsets.UTF_8);
        }

        // 202, not 200: the scan has started, not finished. Waiting for it would hold the request
        // open for as long as the whole backlog takes.
        String accepted = mvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputDir\":\"run-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andExpect(jsonPath("$.links.self").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String batchId = MAPPER.readTree(accepted).get("batchId").asText();
        JsonNode finished = pollUntilFinished(batchId);

        assertThat(finished.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.get("discovered").asInt()).isEqualTo(20);
        assertThat(finished.get("processed").asInt()).isEqualTo(20);
        assertThat(finished.get("counts").get("PUBLISHED").asInt()).isEqualTo(20);
        assertThat(finished.get("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aBatchWithNoBodyScansTheConfiguredInputRoot() throws Exception {
        mvc.perform(post("/api/v1/batches"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"));
    }

    @Test
    void aBatchAimedOutsideTheInputRootIsRefused() throws Exception {
        mvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputDir\":\"../../etc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid batch request"));
    }

    @Test
    void anUnknownBatchIdIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/batches/{id}", "no-such-batch"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Batch not found"));
    }
}
