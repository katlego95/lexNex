package io.github.katlego95.lexpipeline.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.config.HardenedXmlReaderFactory;
import io.github.katlego95.lexpipeline.validation.ValidationResult.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Validation is exercised without a Spring context: the service takes its two collaborators on the
 * constructor, so a plain JUnit test is both faster and a check that the class has no hidden
 * container dependencies.
 */
class XsdValidationServiceTest {

    private static final long TEN_MIB = 10L * 1024 * 1024;

    private final XsdValidationService service = serviceWithLimit(TEN_MIB);

    private static XsdValidationService serviceWithLimit(long maxDocBytes) {
        return new XsdValidationService(
                new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/", maxDocBytes),
                new DefaultResourceLoader(),
                new HardenedXmlReaderFactory());
    }

    private static Resource sample(String name) {
        return new ClassPathResource("samples/" + name);
    }

    @Test
    void validSamplePasses() {
        ValidationResult result = service.validate(sample("valid-judgment.xml"));

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(Status.VALID);
        assertThat(result.diagnostics()).isEmpty();
    }

    @Nested
    class SchemaViolations {

        @Test
        void invalidDateIsReportedWithItsSchemaCode() {
            ValidationResult result = service.validate(sample("invalid-date.xml"));

            assertThat(result.valid()).isFalse();
            assertThat(result.status()).isEqualTo(Status.SCHEMA_INVALID);
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .anySatisfy(code -> assertThat(code).startsWith("cvc-datatype-valid"));
            assertThat(result.diagnostics())
                    .allSatisfy(d -> {
                        assertThat(d.message()).contains("2024-13-45");
                        assertThat(d.line()).isPositive();
                        assertThat(d.column()).isPositive();
                    });
        }

        /**
         * The reason the error handler collects instead of throwing: one invalid date produces
         * two diagnostics (the datatype is wrong, therefore the element's content is wrong), and
         * a real feed error produces many. Fail-fast would make the sender fix them one round
         * trip at a time.
         */
        @Test
        void allErrorsAreCollectedNotJustTheFirst() {
            ValidationResult result = service.validate(sample("invalid-date.xml"));

            assertThat(result.diagnostics()).hasSizeGreaterThan(1);
        }

        @Test
        void duplicateParagraphIdIsReported() {
            ValidationResult result = service.validate(sample("duplicate-paragraph-id.xml"));

            assertThat(result.status()).isEqualTo(Status.SCHEMA_INVALID);
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .contains("cvc-id.2");
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::message)
                    .anySatisfy(message -> assertThat(message).contains("p2"));
        }

        @Test
        void missingRequiredAttributeIsReported() {
            ValidationResult result = service.validate(sample("missing-paragraph-id.xml"));

            assertThat(result.status()).isEqualTo(Status.SCHEMA_INVALID);
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .contains("cvc-complex-type.4");
            assertThat(result.diagnostics())
                    .extracting(Diagnostic::message)
                    .anySatisfy(message -> assertThat(message).contains("id"));
        }

        @Test
        void everyDiagnosticCarriesSeverityAndPosition() {
            ValidationResult result = service.validate(sample("duplicate-paragraph-id.xml"));

            assertThat(result.diagnostics())
                    .isNotEmpty()
                    .allSatisfy(d -> {
                        assertThat(d.severity()).isEqualTo(Diagnostic.Severity.ERROR);
                        assertThat(d.line()).isPositive();
                        assertThat(d.message()).isNotBlank();
                    });
        }
    }

    @Nested
    class MalformedInput {

        @Test
        void nonXmlInputIsReportedNotThrown() {
            assertThatCode(() -> {
                ValidationResult result = service.validate(sample("malformed.xml"));

                assertThat(result.valid()).isFalse();
                assertThat(result.status()).isEqualTo(Status.MALFORMED_XML);
                assertThat(result.diagnostics())
                        .isNotEmpty()
                        .anySatisfy(d ->
                                assertThat(d.severity()).isEqualTo(Diagnostic.Severity.FATAL));
            }).doesNotThrowAnyException();
        }

        @Test
        void wellFormednessErrorsCarryNoSchemaCode() {
            ValidationResult result = service.validate(sample("malformed.xml"));

            assertThat(result.diagnostics())
                    .extracting(Diagnostic::code)
                    .containsOnlyNulls();
        }

        @Test
        void emptyInputIsMalformedNotValid() {
            ValidationResult result = service.validate(new ByteArrayResource(new byte[0]));

            assertThat(result.status()).isEqualTo(Status.MALFORMED_XML);
        }
    }

    @Nested
    class SizeGuard {

        @Test
        void oversizeIsRejectedBeforeParsing() {
            ValidationResult result = serviceWithLimit(64).validate(sample("valid-judgment.xml"));

            assertThat(result.valid()).isFalse();
            assertThat(result.status()).isEqualTo(Status.OVERSIZE);
            assertThat(result.diagnostics())
                    .singleElement()
                    .satisfies(d -> assertThat(d.message()).contains("APP_MAX_DOC_BYTES"));
        }

        /**
         * Backstop for sources that cannot state their length up front (a chunked request body).
         * Without the bounded stream the guard would be advisory only.
         */
        @Test
        void oversizeIsStillCaughtWhenTheLengthIsUnknown() {
            byte[] content = readAll(sample("valid-judgment.xml"));
            Resource lengthUnknown = new ByteArrayResource(content) {
                @Override
                public long contentLength() {
                    return -1; // as a chunked request body would report it
                }
            };

            ValidationResult result = serviceWithLimit(64).validate(lengthUnknown);

            assertThat(result.status()).isEqualTo(Status.OVERSIZE);
        }

        @Test
        void documentAtTheLimitIsAccepted() {
            byte[] content = readAll(sample("valid-judgment.xml"));

            ValidationResult result = serviceWithLimit(content.length).validate(
                    new ByteArrayResource(content));

            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    class XxeHardening {

        /**
         * The payload declares an external entity pointing at a local file and references it in a
         * paragraph. It is refused at the gate, on the DOCTYPE, before the entity is even read —
         * unhardened, the file contents would be substituted into content we transform, index
         * and serve.
         */
        @Test
        void aDoctypeIsRejectedAtTheGateWithItsOwnStatus() {
            ValidationResult result = service.validate(sample("xxe-external-entity.xml"));

            assertThat(result.valid()).isFalse();
            // Its own status, not MALFORMED_XML: this is a policy rejection, and it must be
            // attributed to the stage whose job it is rather than surfacing later as a transform
            // failure with nothing useful in the quarantine record.
            assertThat(result.status()).isEqualTo(Status.DOCTYPE_REJECTED);
        }

        @Test
        void theRejectionCarriesAnActionableDiagnostic() {
            ValidationResult result = service.validate(sample("xxe-external-entity.xml"));

            assertThat(result.diagnostics())
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.code()).isEqualTo("lex-doctype-not-allowed");
                        assertThat(d.severity()).isEqualTo(Diagnostic.Severity.FATAL);
                        assertThat(d.message())
                                .contains("DOCTYPE")
                                .contains("Resubmit without it");
                        assertThat(d.line()).isPositive();
                    });
        }

        /** A DOCTYPE with no entities at all is still refused: the rule is the declaration. */
        @Test
        void anInnocuousDoctypeIsRejectedToo() {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE judgment>
                    <judgment xmlns="urn:lex:content:1"/>
                    """;

            ValidationResult result = service.validate(
                    new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));

            assertThat(result.status()).isEqualTo(Status.DOCTYPE_REJECTED);
        }

        @Test
        void externalEntityContentNeverReachesTheDiagnostics() {
            ValidationResult result = service.validate(sample("xxe-external-entity.xml"));

            assertThat(result.diagnostics())
                    .extracting(Diagnostic::message)
                    .noneSatisfy(message -> assertThat(message).contains("root:"));
        }

        /**
         * Ordinary malformed XML must not be swept into DOCTYPE_REJECTED: the two statuses have
         * to keep meaning different things for the quarantine record to be worth reading.
         */
        @Test
        void ordinaryMalformedInputKeepsItsOwnStatus() {
            assertThat(service.validate(sample("malformed.xml")).status())
                    .isEqualTo(Status.MALFORMED_XML);
        }
    }

    private static byte[] readAll(Resource resource) {
        try (var in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("test fixture unreadable", e);
        }
    }

    @Test
    void schemaIsCompiledOnceAndReusedAcrossCalls() {
        // Two calls on the same instance must not interfere: the Schema is shared and immutable,
        // the Validator is created per call.
        assertThat(service.validate(sample("valid-judgment.xml")).valid()).isTrue();
        assertThat(service.validate(sample("invalid-date.xml")).valid()).isFalse();
        assertThat(service.validate(sample("valid-judgment.xml")).valid()).isTrue();
    }

    @Test
    void unicodeContentSurvivesValidation() {
        String xml = new String(readAll(sample("valid-judgment.xml")), StandardCharsets.UTF_8);

        assertThat(xml).contains("Considérant");
        assertThat(service.validate(new ByteArrayResource(
                xml.getBytes(StandardCharsets.UTF_8))).valid()).isTrue();
    }
}
