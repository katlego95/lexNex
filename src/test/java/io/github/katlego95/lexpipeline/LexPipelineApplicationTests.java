package io.github.katlego95.lexpipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Phase 0 smoke test: the context starts and the health endpoint reports UP.
 *
 * <p>Kept as a real HTTP call rather than a bare context-load test so the verification in the
 * README ({@code curl localhost:8080/actuator/health}) is covered by CI, not only by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LexPipelineApplicationTests {

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
