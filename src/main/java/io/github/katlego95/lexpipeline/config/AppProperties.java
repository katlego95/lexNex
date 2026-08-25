package io.github.katlego95.lexpipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the pipeline, bound from {@code app.*} and therefore from the
 * corresponding environment variables (Docker-friendly, no rebuild to reconfigure).
 *
 * <p>Grows one phase at a time; only the settings a shipped stage actually reads live here.
 *
 * @param xsdPath      Spring resource location of the judgment schema. Accepts {@code classpath:}
 *                     or {@code file:} URLs, so an operator can swap in a corrected schema
 *                     without a rebuild. Env var {@code APP_XSD_PATH}.
 * @param maxDocBytes  hard ceiling on a single document, enforced before any parsing begins.
 *                     Env var {@code APP_MAX_DOC_BYTES}.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String xsdPath, long maxDocBytes) {
}
