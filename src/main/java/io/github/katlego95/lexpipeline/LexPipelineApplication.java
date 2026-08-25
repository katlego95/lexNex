package io.github.katlego95.lexpipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the legal content transformation service.
 *
 * <p>The service ingests French legal judgment XML, validates it against an XSD trust gate,
 * transforms valid documents to normalized JSON and RAG-ready text, and publishes versioned
 * artifacts keyed by content_id.
 */
@SpringBootApplication
public class LexPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(LexPipelineApplication.class, args);
    }
}
