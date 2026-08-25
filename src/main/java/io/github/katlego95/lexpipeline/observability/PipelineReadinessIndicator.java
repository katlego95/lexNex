package io.github.katlego95.lexpipeline.observability;

import io.github.katlego95.lexpipeline.transform.XsltTransformService;
import io.github.katlego95.lexpipeline.validation.XsdValidationService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness: is this instance able to turn a judgment into trusted content?
 *
 * <p>Contributes to the {@code readiness} probe group, not to liveness. The distinction matters
 * under an orchestrator: a failed liveness probe restarts the container, a failed readiness probe
 * only takes it out of the load balancer. An instance that cannot compile its schema should stop
 * receiving documents, not be killed and restarted into the same failure.
 *
 * <p>The two things it reports on are the pipeline's startup invariants — the XSD and the
 * stylesheets are compiled once, and the application refuses to start if either fails. So today
 * this indicator is UP whenever the process is up. It is here for two reasons anyway: the details
 * say <em>which</em> schema and stylesheets this instance actually loaded, which is the first
 * question when one pod behaves differently from another; and it is where a reloadable stylesheet
 * would report if the compile step ever stopped being a startup invariant. Stated plainly rather
 * than dressed up as a deeper check than it is.
 */
@Component("pipeline")
public class PipelineReadinessIndicator implements HealthIndicator {

    private final XsdValidationService validation;
    private final XsltTransformService transform;

    public PipelineReadinessIndicator(XsdValidationService validation,
            XsltTransformService transform) {
        this.validation = validation;
        this.transform = transform;
    }

    @Override
    public Health health() {
        boolean schemaReady = validation.isReady();
        boolean stylesheetsReady = transform.isReady();

        Health.Builder status = schemaReady && stylesheetsReady ? Health.up() : Health.outOfService();
        return status
                .withDetail("schema", schemaReady ? validation.schemaLocation() : "not compiled")
                .withDetail("stylesheets",
                        stylesheetsReady ? transform.stylesheets() : "not compiled")
                .build();
    }
}
