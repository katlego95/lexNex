package io.github.katlego95.lexpipeline.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Builds {@code chunks.jsonl}: one self-contained JSON record per paragraph, the artifact an
 * embedding pipeline actually ingests.
 *
 * <p><strong>Why every record repeats the court, jurisdiction, date and citations.</strong> A
 * vector store retrieves a chunk, not a document. If the metadata lived only in normalized.json,
 * every retrieval would need a join back to the parent to know whether the hit is binding
 * authority, in date range, or from the right jurisdiction — and filtering would happen after
 * retrieval instead of inside it. Denormalising costs disk, which is cheap, and buys pre-filtered
 * retrieval, which is the product.
 *
 * <p><strong>Why Jackson and not a third stylesheet.</strong> The chunk record is a
 * <em>projection</em> of the normalized artifact: same facts, regrouped per paragraph with the
 * document-level metadata copied down. Deriving it from the normalized JSON guarantees the two
 * artifacts cannot disagree — a chunk's text is by construction the text that was published. A
 * third stylesheet would re-derive everything from the XML, and would then be free to drift from
 * normalized.json exactly as the fulltext stylesheet can (which is why that one needs a
 * cross-artifact test). It is also the right tool: this is a regrouping of JSON, not a
 * transformation of XML.
 */
@Component
public class ChunkBuilder {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * @param normalizedJson the artifact produced by judgment-to-json.xsl
     * @param version        the version being published; a chunk has to say which revision of the
     *                       judgment it embeds, or a re-embedding cannot tell stale vectors apart
     * @return newline-delimited JSON, one record per paragraph, in document order
     */
    public String build(String normalizedJson, int version) {
        JsonNode judgment = parse(normalizedJson);
        String contentId = judgment.path("content_id").asText();

        StringBuilder jsonl = new StringBuilder();
        int seq = 0;
        for (JsonNode paragraph : judgment.path("paragraphs")) {
            seq++;
            jsonl.append(write(chunk(judgment, paragraph, contentId, seq, version))).append('\n');
        }
        return jsonl.toString();
    }

    /** Field order follows the record shape in ARCHITECTURE section 5, so a diff stays readable. */
    private ObjectNode chunk(JsonNode judgment, JsonNode paragraph, String contentId, int seq,
            int version) {
        String paragraphId = paragraph.path("id").asText();

        ObjectNode chunk = MAPPER.createObjectNode();
        // The pinpoint citation anchor: document plus paragraph, stable across re-embedding.
        chunk.put("chunk_id", contentId + "#" + paragraphId);
        chunk.put("content_id", contentId);
        chunk.put("paragraph_id", paragraphId);
        chunk.put("section", paragraph.path("section").asText());
        chunk.put("seq", seq);
        chunk.put("text", paragraph.path("text").asText());
        // Denormalised retrieval filters.
        chunk.put("court", judgment.path("court").asText());
        chunk.put("jurisdiction", judgment.path("jurisdiction").asText());
        chunk.put("decision_date", judgment.path("decision_date").asText());
        chunk.set("citations", citations(judgment));
        chunk.put("version", version);
        return chunk;
    }

    private ArrayNode citations(JsonNode judgment) {
        JsonNode citations = judgment.path("citations");
        return citations.isArray()
                ? ((ArrayNode) citations).deepCopy()
                : MAPPER.createArrayNode();
    }

    private JsonNode parse(String normalizedJson) {
        try {
            return MAPPER.readTree(normalizedJson);
        } catch (JsonProcessingException e) {
            throw new TransformFailedException(
                    "Normalized JSON could not be parsed to build chunks", e);
        }
    }

    private String write(ObjectNode chunk) {
        try {
            // One line per record: no pretty printing, ever. A newline inside a record would
            // break the format for every consumer that reads it line by line.
            return MAPPER.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            throw new TransformFailedException("Could not serialize chunk record", e);
        }
    }
}
