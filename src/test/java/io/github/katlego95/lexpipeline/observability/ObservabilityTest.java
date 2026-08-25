package io.github.katlego95.lexpipeline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.katlego95.lexpipeline.pipeline.DocumentPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What an operator can see: the counters and timers from ARCHITECTURE section 8, the readiness
 * probe, and the Prometheus scrape that carries both.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot switches metrics export OFF in tests by default, so without this the registry is a
// SimpleMeterRegistry and /actuator/prometheus is not even registered — the scrape assertions
// below would be testing nothing. Opting back in is the whole point of this annotation.
@AutoConfigureObservability
class ObservabilityTest {

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
    private final DocumentPipeline pipeline;
    private final MeterRegistry meters;

    @Autowired
    ObservabilityTest(MockMvc mvc, DocumentPipeline pipeline, MeterRegistry meters) {
        this.mvc = mvc;
        this.pipeline = pipeline;
        this.meters = meters;
    }

    private static Resource sample(String name) {
        return new ClassPathResource("samples/" + name);
    }

    private static Resource revised(String replacement) {
        try (var in = sample("valid-judgment.xml").getInputStream()) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("Le litige porte sur...", replacement);
            return new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("fixture unreadable", e);
        }
    }

    private double counter(String name, String... tags) {
        var found = meters.find(name).tags(Tags.of(tags)).counter();
        return found == null ? -1 : found.count();
    }

    private long timerCount(String stage) {
        var timer = meters.find("pipeline.stage.duration").tag("stage", stage).timer();
        return timer == null ? -1 : timer.count();
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    class Counters {

        @BeforeEach
        void runSomeTraffic() {
            pipeline.process(sample("valid-judgment.xml"), "metrics/a.xml");       // PUBLISHED
            pipeline.process(sample("valid-judgment.xml"), "metrics/a.xml");       // DUPLICATE_NOOP
            pipeline.process(revised("Texte corrigé."), "metrics/a2.xml");         // SUPERSEDED
            pipeline.process(sample("invalid-date.xml"), "metrics/bad.xml");       // SCHEMA_INVALID
            pipeline.process(sample("malformed.xml"), "metrics/broken.xml");       // MALFORMED_XML
        }

        @Test
        void everyDocumentIsCountedOnceOnReceipt() {
            assertThat(counter("documents.received")).isGreaterThanOrEqualTo(5);
        }

        @Test
        void eachOutcomeMovesItsOwnCounter() {
            assertThat(counter("documents.published")).isGreaterThanOrEqualTo(1);
            assertThat(counter("documents.superseded")).isGreaterThanOrEqualTo(1);
            assertThat(counter("documents.duplicate")).isGreaterThanOrEqualTo(1);
        }

        /** The reason tag is what makes the quarantine rate actionable rather than just alarming. */
        @Test
        void quarantineIsCountedByReason() {
            assertThat(counter("documents.quarantined", "reason", "SCHEMA_INVALID"))
                    .isGreaterThanOrEqualTo(1);
            assertThat(counter("documents.quarantined", "reason", "MALFORMED_XML"))
                    .isGreaterThanOrEqualTo(1);
        }

        /** Zeroes are registered up front, so a dashboard shows 0 rather than a missing series. */
        @Test
        void everyQuarantineReasonExistsBeforeItEverHappens() {
            assertThat(counter("documents.quarantined", "reason", "DOCTYPE_REJECTED"))
                    .isGreaterThanOrEqualTo(0);
            assertThat(counter("documents.quarantined", "reason", "OVERSIZE"))
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        void eachStageIsTimedSeparately() {
            assertThat(timerCount("validate")).isGreaterThanOrEqualTo(5);
            assertThat(timerCount("transform")).isGreaterThanOrEqualTo(2);
            assertThat(timerCount("publish")).isGreaterThanOrEqualTo(2);
        }

        /** A rejected document is validated but never transformed — the timers should show it. */
        @Test
        void rejectedDocumentsAreNotTimedAsTransforms() {
            assertThat(timerCount("validate")).isGreaterThan(timerCount("transform"));
        }
    }

    @Nested
    class PrometheusScrape {

        @Test
        void carriesTheCountersUnderTheNamesTheArchitectureSpecifies() throws Exception {
            pipeline.process(sample("valid-judgment.xml"), "scrape/a.xml");

            assertThat(scrape())
                    .contains("documents_received_total")
                    .contains("documents_published_total")
                    .contains("documents_quarantined_total")
                    .contains("documents_duplicate_total")
                    .contains("documents_superseded_total")
                    .contains("pipeline_stage_duration_seconds");
        }

        @Test
        void carriesTheSaturationGauges() throws Exception {
            assertThat(scrape())
                    .contains("queue_depth")
                    .contains("active_workers");
        }

        @Test
        void seriesAreTaggedWithTheApplication() throws Exception {
            assertThat(scrape()).contains("application=\"lexpipeline\"");
        }
    }

    @Nested
    class Readiness {

        @Test
        void reportsWhichSchemaAndStylesheetsThisInstanceLoaded() throws Exception {
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.pipeline.status").value("UP"))
                    .andExpect(jsonPath("$.components.pipeline.details.schema")
                            .value("classpath:schema/judgment.xsd"))
                    .andExpect(jsonPath("$.components.pipeline.details.stylesheets")
                            .value(org.hamcrest.Matchers.hasItem("judgment-to-json.xsl")));
        }

        @Test
        void livenessIsSeparateFromReadiness() throws Exception {
            // Distinct probes on purpose: a pipeline that cannot compile its schema should leave
            // the load balancer, not be restarted into the same failure.
            mvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }
}
