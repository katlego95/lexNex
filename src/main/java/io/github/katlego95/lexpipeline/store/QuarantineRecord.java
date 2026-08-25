package io.github.katlego95.lexpipeline.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.katlego95.lexpipeline.validation.Diagnostic;
import java.time.Instant;
import java.util.List;

/**
 * What is written to {@code quarantine/{ingestId}/diagnostics.json} beside the original document.
 *
 * <p>This file is the product of the rejection path. The content team upstream cannot fix what
 * they cannot see, so it names the reason, the position of every problem, and the source it
 * arrived from.
 *
 * @param ingestId    generated per submission, because a rejected document may have no readable
 *                    content_id to file it under
 * @param sourceName  where it came from (file name, request description)
 * @param contentId   the judgment id if it could be read, absent otherwise
 * @param reason      the pipeline outcome, e.g. SCHEMA_INVALID or DOCTYPE_REJECTED
 * @param receivedAt  when the submission was processed
 * @param diagnostics every problem found, in the order they were reported
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuarantineRecord(
        String ingestId,
        String sourceName,
        String contentId,
        String reason,
        Instant receivedAt,
        List<Diagnostic> diagnostics) {

    public QuarantineRecord {
        diagnostics = List.copyOf(diagnostics);
    }
}
