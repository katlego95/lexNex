package io.github.katlego95.lexpipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 0 smoke test: the context starts and the health endpoint reports UP.
 *
 * <p>Kept as a real HTTP call rather than a bare context-load test so the verification in the
 * README ({@code curl localhost:8080/actuator/health}) is covered by CI, not only by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LexPipelineApplicationTests {

    // The artifact store creates its directories at startup; keep them out of the working copy.
    @TempDir
    static Path outputDir;

    @DynamicPropertySource
    static void artifactStoreLocation(DynamicPropertyRegistry registry) {
        registry.add("app.output-dir", outputDir::toString);
    }

    private final TestRestTemplate restTemplate;

    @Autowired
    LexPipelineApplicationTests(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Test
    void healthEndpointReportsUp() {
        String body = restTemplate.getForObject("/actuator/health", String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }
}
