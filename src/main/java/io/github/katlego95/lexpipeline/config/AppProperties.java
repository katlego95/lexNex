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
 * @param xsltPath     Spring resource location of the <em>directory</em> holding the stylesheets;
 *                     the pipeline produces more than one artifact, so this is a base location
 *                     rather than a single file, and each stylesheet is resolved by name beneath
 *                     it. Env var {@code APP_XSLT_PATH}.
 * @param outputDir    root of the artifact store: {@code published/} and {@code quarantine/} are
 *                     created beneath it. A plain filesystem path, not a Spring resource location,
 *                     because this one is written to as well as read. Env var
 *                     {@code APP_OUTPUT_DIR}.
 * @param inputDir     root the batch scanner reads from. A batch request may name a subdirectory
 *                     of it and nothing else — the request must not be able to point the service
 *                     at an arbitrary path on the host. Env var {@code APP_INPUT_DIR}.
 * @param concurrency  number of worker threads processing documents. Env var
 *                     {@code APP_CONCURRENCY}.
 * @param queueCapacity how many discovered documents may wait in front of the workers. Bounded on
 *                     purpose: it is the difference between backpressure and an OOM.
 *                     Env var {@code APP_QUEUE_CAPACITY}.
 * @param maxDocBytes  hard ceiling on a single document, enforced before any parsing begins.
 *                     Env var {@code APP_MAX_DOC_BYTES}.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String xsdPath, String xsltPath, String outputDir, String inputDir,
        int concurrency, int queueCapacity, long maxDocBytes) {

    /** Tolerates {@code classpath:xslt} as well as {@code classpath:xslt/} in the env var. */
    public String xsltPath() {
        return xsltPath.endsWith("/") ? xsltPath : xsltPath + "/";
    }
}
