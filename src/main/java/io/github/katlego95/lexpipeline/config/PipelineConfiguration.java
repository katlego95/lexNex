package io.github.katlego95.lexpipeline.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans that are not components of their own.
 */
@Configuration
public class PipelineConfiguration {

    /**
     * Injected rather than calling {@code Instant.now()} at the point of use, so that a test can
     * fix time and assert on a published timestamp. UTC always: artifact timestamps are read by
     * people in other timezones and compared against each other.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
