package io.github.katlego95.lexpipeline.transform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Fed the normalized artifact directly, with no XSLT in the way: chunks are a projection of that
 * JSON, so that is exactly the contract worth testing.
 */
class ChunkBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChunkBuilder builder = new ChunkBuilder();

    private static String normalizedJson() {
        try (var in = new ClassPathResource("expected/normalized-judgment.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture unreadable", e);
        }
    }

    private List<JsonNode> records(String jsonl) {
        return Arrays.stream(jsonl.split("\n")).map(line -> {
            try {
                return (JsonNode) MAPPER.readTree(line);
            } catch (IOException e) {
                throw new AssertionError("not a JSON object on its own line: " + line, e);
            }
        }).toList();
    }

    @Test
    void emitsOneRecordPerParagraphInDocumentOrder() {
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks).hasSize(4);
        assertThat(chunks.stream().map(c -> c.get("paragraph_id").asText()))
                .containsExactly("p1", "p2", "p3", "p4");
    }

    @Test
    void everyLineIsOneCompleteJsonObject() {
        String jsonl = builder.build(normalizedJson(), 1);

        assertThat(jsonl).endsWith("\n");
        assertThat(jsonl.lines()).allSatisfy(line ->
                assertThat(line).startsWith("{").endsWith("}"));
        // A pretty-printed record would break every consumer that reads the file line by line.
        assertThat(jsonl.lines()).hasSize(4);
    }

    @Test
    void chunkIdIsThePinpointCitationAnchor() {
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks.get(1).get("chunk_id").asText()).isEqualTo("FR-2024-CA-000123#p2");
        assertThat(chunks.get(1).get("content_id").asText()).isEqualTo("FR-2024-CA-000123");
    }

    @Test
    void seqIsOneBasedReadingOrder() {
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks.stream().map(c -> c.get("seq").asInt())).containsExactly(1, 2, 3, 4);
    }

    @Test
    void everyRecordCarriesItsOwnRetrievalMetadata() {
        // The whole point of the artifact: a vector store retrieves a chunk, not a document, so
        // filtering by court, jurisdiction or date must be possible without a join.
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.get("court").asText()).isEqualTo("Cour d'appel de Paris");
            assertThat(chunk.get("jurisdiction").asText()).isEqualTo("FR");
            assertThat(chunk.get("decision_date").asText()).isEqualTo("2024-03-12");
        });
    }

    @Test
    void sectionIsCarriedDownFromTheParagraph() {
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks.get(0).get("section").asText()).isEqualTo("facts");
        assertThat(chunks.get(1).get("section").asText()).isEqualTo("reasons");
        assertThat(chunks.get(3).get("section").asText()).isEqualTo("disposition");
    }

    @Test
    void citationsStayTypedOnEveryRecord() {
        JsonNode citations = records(builder.build(normalizedJson(), 1)).get(0).get("citations");

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).get("type").asText()).isEqualTo("ECLI");
        assertThat(citations.get(0).get("value").asText()).isEqualTo("ECLI:FR:CA12345");
    }

    /** A chunk has to say which revision it embeds, or stale vectors cannot be identified. */
    @Test
    void everyRecordStampsTheVersionBeingPublished() {
        assertThat(records(builder.build(normalizedJson(), 7)))
                .allSatisfy(chunk -> assertThat(chunk.get("version").asInt()).isEqualTo(7));
    }

    @Test
    void chunkTextIsExactlyTheParagraphTextThatWasPublished() {
        List<JsonNode> chunks = records(builder.build(normalizedJson(), 1));

        assertThat(chunks.get(1).get("text").asText()).isEqualTo("Considérant que...");
    }

    @Test
    void aJudgmentWithNoCitationsProducesAnEmptyArrayNotAMissingKey() {
        String minimal = """
                {"content_id":"FR-9","court":"Cour de cassation","jurisdiction":"FR",
                 "decision_date":"2024-06-05","citations":[],"parties":[],
                 "paragraphs":[{"id":"q1","section":"facts","text":"Texte."}],
                 "full_text":"Texte."}
                """;

        JsonNode chunk = records(builder.build(minimal, 1)).get(0);

        assertThat(chunk.has("citations")).isTrue();
        assertThat(chunk.get("citations")).isEmpty();
    }

    @Test
    void aJudgmentWithNoParagraphsProducesNoRecords() {
        String empty = "{\"content_id\":\"FR-9\",\"paragraphs\":[]}";

        assertThat(builder.build(empty, 1)).isEmpty();
    }

    @Test
    void unparseableInputFailsAsATransformOutcome() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> builder.build("not json", 1)))
                .isInstanceOf(TransformFailedException.class);
    }
}
