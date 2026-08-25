package io.github.katlego95.lexpipeline.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.config.HardenedXmlReaderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * The stylesheets are tested as units: sample XML in, artifact out, no Spring context and no
 * pipeline around them. If one of these fails, the stylesheet is wrong — there is nowhere else
 * for the failure to have come from.
 */
class XsltTransformServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final XsltTransformService service = new XsltTransformService(
            new AppProperties("classpath:schema/judgment.xsd", "classpath:xslt/", 10L << 20),
            new DefaultResourceLoader(),
            new HardenedXmlReaderFactory());

    private static Resource sample(String name) {
        return new ClassPathResource("samples/" + name);
    }

    private static String expected(String name) {
        // stripTrailing so that an editor's final newline in the fixture does not masquerade as a
        // transform bug; the actual output is compared unmodified.
        return read(new ClassPathResource("expected/" + name)).stripTrailing();
    }

    private static String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("test fixture unreadable: " + resource, e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new AssertionError("transform did not produce parseable JSON: " + json, e);
        }
    }

    @Nested
    class NormalizedJson {

        @Test
        void matchesTheBriefsTargetOutputExactly() throws Exception {
            String json = service.toNormalizedJson(sample("valid-judgment.xml"));

            // STRICT: no extra fields tolerated, array order significant. Paragraph order is
            // reading order and citation order is source order, so both must be preserved.
            JSONAssert.assertEquals(expected("normalized-judgment.json"), json,
                    JSONCompareMode.STRICT);
        }

        @Test
        void paragraphCarriesTheSectionTypeOfItsParent() {
            JsonNode paragraphs = parse(service.toNormalizedJson(sample("valid-judgment.xml")))
                    .get("paragraphs");

            assertThat(field(paragraphs, "p1", "section")).isEqualTo("facts");
            assertThat(field(paragraphs, "p2", "section")).isEqualTo("reasons");
            assertThat(field(paragraphs, "p3", "section")).isEqualTo("reasons");
            assertThat(field(paragraphs, "p4", "section")).isEqualTo("disposition");
        }

        @Test
        void paragraphsAreFlattenedIntoDocumentOrder() {
            JsonNode paragraphs = parse(service.toNormalizedJson(sample("valid-judgment.xml")))
                    .get("paragraphs");

            assertThat(values(paragraphs, "id")).containsExactly("p1", "p2", "p3", "p4");
        }

        @Test
        void citationsKeepTheirTypeInsteadOfCollapsingToStrings() {
            JsonNode citations = parse(service.toNormalizedJson(sample("valid-judgment.xml")))
                    .get("citations");

            assertThat(citations.get(0).get("type").asText()).isEqualTo("ECLI");
            assertThat(citations.get(0).get("value").asText()).isEqualTo("ECLI:FR:CA12345");
            assertThat(citations.get(1).get("type").asText()).isEqualTo("NOR");
            assertThat(citations.get(1).get("value").asText()).isEqualTo("NOR:ABCD1234567");
        }

        @Test
        void partiesKeepTheirRole() {
            JsonNode parties = parse(service.toNormalizedJson(sample("valid-judgment.xml")))
                    .get("parties");

            assertThat(parties.get(0).get("role").asText()).isEqualTo("appellant");
            assertThat(parties.get(0).get("name").asText()).isEqualTo("Société ABC");
            assertThat(parties.get(1).get("role").asText()).isEqualTo("respondent");
        }

        @Test
        void optionalContainersStillProduceEmptyArrays() {
            JsonNode json = parse(service.toNormalizedJson(sample("minimal-judgment.xml")));

            // The source omits both containers entirely. A consumer should never have to tell
            // "absent" from "empty", so the keys are always present.
            assertThat(json.has("citations")).isTrue();
            assertThat(json.get("citations")).isEmpty();
            assertThat(json.has("parties")).isTrue();
            assertThat(json.get("parties")).isEmpty();
        }

        @Test
        void sourceFormattingIsNormalisedAway() {
            JsonNode paragraphs = parse(service.toNormalizedJson(sample("minimal-judgment.xml")))
                    .get("paragraphs");

            // q1 is pretty-printed across three indented lines in the source.
            assertThat(field(paragraphs, "q1", "text"))
                    .isEqualTo("Le demandeur soutient que la cour d'appel a violé "
                            + "l'article 1240 du code civil.");
        }

        @Test
        void frenchCharactersSurviveSerialization() {
            JsonNode json = parse(service.toNormalizedJson(sample("valid-judgment.xml")));

            // Asserted on parsed values, not on raw bytes: Saxon escapes "/" as "\/", which is
            // legal JSON and decodes back to "/", so the artifact is compared by meaning.
            assertThat(json.get("title").asText()).contains("n° 20/01234");
            assertThat(json.get("parties").get(0).get("name").asText()).isEqualTo("Société ABC");
            assertThat(json.get("paragraphs").get(1).get("text").asText())
                    .isEqualTo("Considérant que...");
        }

        @Test
        void accentedCharactersAreEmittedLiterallyNotAsUnicodeEscapes() {
            // UTF-8 in, UTF-8 out: no é noise in the published artifact.
            String json = service.toNormalizedJson(sample("valid-judgment.xml"));

            assertThat(json).contains("Considérant").doesNotContain("\\u00e9");
        }

        @Test
        void apostrophesAndPunctuationRoundTripThroughTheSerializer() {
            // The stylesheet never writes a quote itself, so xml-to-json owns escaping; parsing
            // the result back is the proof that it did the job.
            JsonNode json = parse(service.toNormalizedJson(sample("valid-judgment.xml")));

            assertThat(json.get("court").asText()).isEqualTo("Cour d'appel de Paris");
            assertThat(json.get("title").asText())
                    .isEqualTo("Cour d'appel de Paris, 12 mars 2024, n° 20/01234");
        }

        private String field(JsonNode paragraphs, String id, String field) {
            for (JsonNode paragraph : paragraphs) {
                if (id.equals(paragraph.get("id").asText())) {
                    JsonNode value = paragraph.get(field);
                    if (value == null) {
                        // Deliberate-breakage exercises delete a key; say so plainly rather than
                        // dying with a NullPointerException three lines later.
                        throw new AssertionError(
                                "paragraph " + id + " has no \"" + field + "\" field");
                    }
                    return value.asText();
                }
            }
            throw new AssertionError("no paragraph with id " + id);
        }

        private List<String> values(JsonNode array, String field) {
            List<String> values = new ArrayList<>();
            array.forEach(node -> values.add(node.get(field).asText()));
            return values;
        }
    }

    @Nested
    class FullText {

        @Test
        void matchesTheExpectedArtifact() {
            String text = service.toFullText(sample("valid-judgment.xml"));

            assertThat(text).isEqualTo(expected("fulltext-judgment.txt"));
        }

        @Test
        void paragraphsAreJoinedByExactlyOneSpaceInDocumentOrder() {
            String text = service.toFullText(sample("valid-judgment.xml"));

            assertThat(text).doesNotContain("  ").doesNotContain("\n");
            assertThat(text).startsWith("Le litige porte sur...").endsWith("Par ces motifs...");
        }

        @Test
        void sourceFormattingIsNormalisedAway() {
            String text = service.toFullText(sample("minimal-judgment.xml"));

            assertThat(text).isEqualTo(
                    "Le demandeur soutient que la cour d'appel a violé "
                            + "l'article 1240 du code civil. Rejette le pourvoi.");
        }

        /**
         * The two artifacts compute the full text in two stylesheets. This is the guard against
         * them drifting apart: fulltext.txt must always equal the full_text field in
         * normalized.json, or a RAG answer would cite a passage the indexed text does not contain.
         */
        @Test
        void agreesWithTheFullTextFieldInTheNormalizedJson() {
            for (String name : List.of("valid-judgment.xml", "minimal-judgment.xml")) {
                String fromJson = parse(service.toNormalizedJson(sample(name)))
                        .get("full_text").asText();

                assertThat(service.toFullText(sample(name)))
                        .as("fulltext artifact vs full_text field for %s", name)
                        .isEqualTo(fromJson);
            }
        }
    }

    @Nested
    class CompiledOnceTransformerPerDocument {

        @Test
        void repeatedTransformsOfTheSameDocumentAreIdentical() {
            String first = service.toNormalizedJson(sample("valid-judgment.xml"));
            String second = service.toNormalizedJson(sample("valid-judgment.xml"));

            assertThat(second).isEqualTo(first);
        }

        @Test
        void interleavedDocumentsDoNotContaminateEachOther() {
            String before = service.toNormalizedJson(sample("valid-judgment.xml"));
            service.toNormalizedJson(sample("minimal-judgment.xml"));
            String after = service.toNormalizedJson(sample("valid-judgment.xml"));

            assertThat(after).isEqualTo(before);
        }

        /**
         * The claim being tested is that one shared XsltExecutable plus a per-document
         * XsltTransformer is safe under the worker pool. Sharing a transformer instead would show
         * up here as garbled, empty or cross-contaminated output.
         */
        @Test
        void concurrentTransformsAllProduceTheCorrectArtifact() throws Exception {
            String reference = service.toNormalizedJson(sample("valid-judgment.xml"));
            List<Callable<String>> work = IntStream.range(0, 64)
                    .mapToObj(i -> (Callable<String>) () -> service.toNormalizedJson(
                            sample(i % 2 == 0 ? "valid-judgment.xml" : "minimal-judgment.xml")))
                    .toList();

            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Future<String>> results = pool.invokeAll(work);

                for (int i = 0; i < results.size(); i++) {
                    String output = results.get(i).get();
                    if (i % 2 == 0) {
                        assertThat(output).isEqualTo(reference);
                    } else {
                        assertThat(output).contains("FR-2024-CC-000999");
                    }
                }
            }
        }
    }

    @Nested
    class Hardening {

        /**
         * Defence in depth. The trust gate rejects a DOCTYPE first and records DOCTYPE_REJECTED,
         * so nothing carrying one should ever reach a transform; this test pins the second line
         * of defence for any path that gets here another way (a replayed artifact, a future
         * entry point, a bug upstream). Both stages read through the same
         * HardenedXmlReaderFactory, so the policy cannot drift between them.
         */
        @Test
        void externalEntitiesAreNotResolvedOnTheSecondParse() {
            assertThatThrownBy(() -> service.toNormalizedJson(sample("xxe-external-entity.xml")))
                    .isInstanceOf(TransformFailedException.class)
                    .hasMessageNotContaining("root:");
        }

        @Test
        void malformedInputFailsAsARecordedTransformOutcome() {
            assertThatThrownBy(() -> service.toFullText(sample("malformed.xml")))
                    .isInstanceOf(TransformFailedException.class);
        }
    }

    /** Guards the claim in the class javadoc that both stylesheets are compiled at startup. */
    @Test
    void aMissingStylesheetIsAStartupFailureNotAPerDocumentSurprise() {
        assertThatThrownBy(() -> new XsltTransformService(
                new AppProperties("classpath:schema/judgment.xsd", "classpath:no-such-dir/", 1024),
                new DefaultResourceLoader(),
                new HardenedXmlReaderFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("judgment-to-json.xsl");
    }

    @Test
    void bothArtifactsComeFromTheSameSourceDocument() {
        // Sanity check that the two entry points are wired to different stylesheets.
        Resource document = sample("valid-judgment.xml");

        assertThat(service.toNormalizedJson(document)).startsWith("{");
        assertThat(service.toFullText(document)).doesNotContain("{").doesNotContain("\"");
    }

    @Test
    void everyParagraphInTheSourceReachesTheArtifact() {
        long paragraphsInSource = StreamSupport
                .stream(parse(service.toNormalizedJson(sample("valid-judgment.xml")))
                        .get("paragraphs").spliterator(), false)
                .count();

        assertThat(paragraphsInSource).isEqualTo(4);
    }
}
