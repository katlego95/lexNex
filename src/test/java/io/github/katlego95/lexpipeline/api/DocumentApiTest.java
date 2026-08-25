package io.github.katlego95.lexpipeline.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, exercised through the real application context.
 *
 * <p>Ordered, because the interesting assertions are about a sequence: publish, then resubmit the
 * same bytes, then resubmit changed ones — the idempotency story as a client experiences it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentApiTest {

    private static final String CONTENT_ID = "FR-2024-CA-000123";

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
    DocumentApiTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    private static byte[] sample(String name) {
        try (var in = new ClassPathResource("samples/" + name).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("fixture unreadable", e);
        }
    }

    private static byte[] revisedSample() {
        return new String(sample("valid-judgment.xml"), StandardCharsets.UTF_8)
                .replace("Le litige porte sur...", "Le litige porte sur la vente du fonds.")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @Order(1)
    void postingAValidJudgmentPublishesItAndReturnsLinks() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("valid-judgment.xml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PUBLISHED"))
                .andExpect(jsonPath("$.contentId").value(CONTENT_ID))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.links.normalized")
                        .value("/api/v1/documents/" + CONTENT_ID + "/artifacts/normalized?version=1"));
    }

    @Test
    @Order(2)
    void resubmittingTheSameBytesIsARecordedNoop() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("valid-judgment.xml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE_NOOP"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @Order(3)
    void resubmittingChangedContentSupersedes() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(revisedSample()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUPERSEDED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @Order(4)
    void theDocumentEndpointShowsEveryVersionAndItsSupersession() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}", CONTENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].supersededBy").value(2))
                .andExpect(jsonPath("$.versions[1].supersededBy").doesNotExist())
                .andExpect(jsonPath("$.versions[0].sha256").isNotEmpty());
    }

    @Test
    @Order(5)
    void artifactsAreServedWithTheirOwnMediaTypes() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}/artifacts/normalized", CONTENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content_id").value(CONTENT_ID));

        mvc.perform(get("/api/v1/documents/{id}/artifacts/fulltext", CONTENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));

        mvc.perform(get("/api/v1/documents/{id}/artifacts/chunks", CONTENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("application/x-ndjson")));
    }

    @Test
    @Order(6)
    void anOlderVersionStaysRetrievableByNumber() throws Exception {
        // A citation into v1 must keep resolving after v2 exists.
        mvc.perform(get("/api/v1/documents/{id}/artifacts/fulltext", CONTENT_ID)
                        .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "Le litige porte sur...")));
    }

    @Test
    @Order(7)
    void anUnpublishedVersionNumberIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}/artifacts/fulltext", CONTENT_ID)
                        .param("version", "99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void aSchemaInvalidDocumentComesBackAsProblemJsonWithDiagnostics() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("duplicate-paragraph-id.xml")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.outcome").value("SCHEMA_INVALID"))
                .andExpect(jsonPath("$.title").value("Schema validation failed"))
                .andExpect(jsonPath("$.ingestId").isNotEmpty())
                .andExpect(jsonPath("$.diagnostics[?(@.code == 'cvc-id.2')]").exists());
    }

    @Test
    void aRejectedDocumentIsRetrievableFromQuarantineByItsIngestId() throws Exception {
        String problem = mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("duplicate-paragraph-id.xml")))
                .andExpect(status().isBadRequest())
                // The 400 says where the rejection was filed, so a client that drops this response
                // can still come back for the original and the diagnostics.
                .andExpect(jsonPath("$.quarantine").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String ingestId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(problem).get("ingestId").asText();

        mvc.perform(get("/api/v1/quarantine/{id}", ingestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestId").value(ingestId))
                .andExpect(jsonPath("$.status").value("SCHEMA_INVALID"))
                .andExpect(jsonPath("$.sourceName").isNotEmpty())
                .andExpect(jsonPath("$.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.diagnostics[?(@.code == 'cvc-id.2')]").exists());
    }

    @Test
    void anUnknownQuarantineIdIsNotFoundInProblemJson() throws Exception {
        mvc.perform(get("/api/v1/quarantine/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Quarantine record not found"));
    }

    @Test
    void malformedXmlIsAClientError() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("malformed.xml")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("MALFORMED_XML"));
    }

    @Test
    void aDoctypeIsRefusedWithItsOwnProblemType() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(sample("xxe-external-entity.xml")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.outcome").value("DOCTYPE_REJECTED"))
                .andExpect(jsonPath("$.type").value(
                        org.hamcrest.Matchers.endsWith("/problems/doctype-rejected")));
    }

    @Test
    void anUnknownJudgmentIsNotFoundInProblemJson() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}", "FR-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Document not found"));
    }

    @Test
    void anUnknownArtifactKindIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}/artifacts/{kind}", CONTENT_ID, "pdf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown artifact"));
    }

    /** A hostile id must be refused by the API, not carried down into a path. */
    @Test
    void aHostileContentIdIsRejectedWithoutTouchingTheStore() throws Exception {
        mvc.perform(get("/api/v1/documents/{id}", "..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void anUnsupportedContentTypeAnswersInProblemJsonToo() throws Exception {
        mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
