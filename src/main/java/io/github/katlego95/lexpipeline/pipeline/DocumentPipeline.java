package io.github.katlego95.lexpipeline.pipeline;

import io.github.katlego95.lexpipeline.identity.ContentIdentity;
import io.github.katlego95.lexpipeline.identity.ContentIdentityException;
import io.github.katlego95.lexpipeline.identity.ContentIdentityReader;
import io.github.katlego95.lexpipeline.store.ArtifactSet;
import io.github.katlego95.lexpipeline.store.ArtifactStore;
import io.github.katlego95.lexpipeline.store.Manifest;
import io.github.katlego95.lexpipeline.store.QuarantineRecord;
import io.github.katlego95.lexpipeline.store.StorageFailedException;
import io.github.katlego95.lexpipeline.transform.ChunkBuilder;
import io.github.katlego95.lexpipeline.transform.TransformFailedException;
import io.github.katlego95.lexpipeline.transform.XsltTransformService;
import io.github.katlego95.lexpipeline.validation.Diagnostic;
import io.github.katlego95.lexpipeline.validation.ValidationResult;
import io.github.katlego95.lexpipeline.validation.XsdValidationService;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * One document, one code path: validate → identify → dedupe → transform → publish.
 *
 * <p>Both entry points (a single POST, a batch worker) funnel through here, so there is one place
 * where the rules live and one set of metrics describing them.
 *
 * <p><strong>Nothing throws out of {@link #process}.</strong> Every failure — the sender's, the
 * disk's, or ours — comes back as an {@link Outcome}. A pipeline that lets exceptions escape has
 * no record of what it refused, and a rejection nobody recorded is a document nobody can fix.
 *
 * <p><strong>Concurrency.</strong> Read-check-write on a manifest is not atomic, so two workers
 * handling the same judgment could both read "no versions yet" and both publish v1, the second
 * silently overwriting the first. The critical section — read manifest, compare hash, publish — is
 * therefore serialised per content_id by a lock held in a {@link ConcurrentHashMap}. Documents for
 * different judgments never wait for each other, which is the point: the lock is as narrow as the
 * invariant it protects. In a distributed deployment this exact guarantee is a DynamoDB conditional
 * write on {@code content_id} (put the version only if the manifest still shows the version we
 * read), which is the same compare-and-set moved to a store that several pods share.
 */
@Service
public class DocumentPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentPipeline.class);

    private final XsdValidationService validation;
    private final ContentIdentityReader identityReader;
    private final XsltTransformService transform;
    private final ChunkBuilder chunkBuilder;
    private final ArtifactStore store;
    private final Clock clock;

    /**
     * One lock per judgment, created on demand. Never removed: entries are small, bounded by the
     * number of distinct judgments this instance has seen, and removing them safely needs
     * reference counting that would buy nothing here. A long-lived instance would use a size-bound
     * cache or lock striping — worth saying out loud rather than pretending it is free.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DocumentPipeline(XsdValidationService validation, ContentIdentityReader identityReader,
            XsltTransformService transform, ChunkBuilder chunkBuilder, ArtifactStore store,
            Clock clock) {
        this.validation = validation;
        this.identityReader = identityReader;
        this.transform = transform;
        this.chunkBuilder = chunkBuilder;
        this.store = store;
        this.clock = clock;
    }

    public PipelineResult process(Resource document, String sourceName) {
        try {
            ValidationResult validationResult = validation.validate(document);
            if (!validationResult.valid()) {
                return quarantine(document, sourceName, null,
                        outcomeOf(validationResult.status()), validationResult.diagnostics());
            }

            ContentIdentity identity = identityReader.identify(document);
            return publishUnderLock(document, identity, sourceName);

        } catch (ContentIdentityException e) {
            // Unreachable in theory: validation proved a content_id exists.
            log.error("Could not identify a document that passed validation: {}", sourceName, e);
            return PipelineResult.failed(Outcome.INTERNAL_ERROR, null, diagnostic(e));
        } catch (StorageFailedException | UncheckedIOException e) {
            // Deliberately not quarantined: storage is the thing that just failed.
            log.error("Storage failure processing {}", sourceName, e);
            return PipelineResult.failed(Outcome.STORAGE_FAILED, null, diagnostic(e));
        } catch (RuntimeException e) {
            log.error("Unexpected failure processing {}", sourceName, e);
            return PipelineResult.failed(Outcome.INTERNAL_ERROR, null, diagnostic(e));
        }
    }

    /**
     * The critical section. Everything inside it reads or writes the state of one judgment, and
     * nothing inside it touches another.
     */
    private PipelineResult publishUnderLock(Resource document, ContentIdentity identity,
            String sourceName) {
        ReentrantLock lock = locks.computeIfAbsent(identity.contentId(), id -> new ReentrantLock());
        lock.lock();
        try {
            Manifest manifest = store.readManifest(identity.contentId())
                    .orElseGet(() -> Manifest.empty(identity.contentId()));
            Optional<Manifest.Version> current = manifest.latest();

            if (current.isPresent() && current.get().sha256().equals(identity.sha256())) {
                // Exactly what is already published. Feeds redeliver constantly — a corrections
                // run, an at-least-once queue - and doing nothing is the correct, cheap answer.
                log.info("Duplicate delivery of {} v{} ignored",
                        identity.contentId(), current.get().version());
                return PipelineResult.duplicate(
                        identity.contentId(), current.get().version(), identity.sha256());
            }

            int version = manifest.nextVersion();
            ArtifactSet artifacts = buildArtifacts(document, version);
            store.publish(identity.contentId(), version, identity.sha256(), artifacts);

            Outcome outcome = version == 1 ? Outcome.PUBLISHED : Outcome.SUPERSEDED;
            return PipelineResult.published(outcome, identity.contentId(), version,
                    identity.sha256());

        } catch (TransformFailedException e) {
            // It passed the trust gate and we still could not process it: our defect, but the
            // document is quarantined anyway so there is something to reproduce it from.
            log.error("Transform failed for {}", identity.contentId(), e);
            return quarantine(document, sourceName, identity.contentId(), Outcome.TRANSFORM_FAILED,
                    diagnostic(e));
        } finally {
            lock.unlock();
        }
    }

    private ArtifactSet buildArtifacts(Resource document, int version) {
        String normalizedJson = transform.toNormalizedJson(document);
        return new ArtifactSet(
                normalizedJson,
                transform.toFullText(document),
                // Derived from the normalized artifact, so a chunk's text is by construction the
                // text that was published.
                chunkBuilder.build(normalizedJson, version));
    }

    private PipelineResult quarantine(Resource document, String sourceName, String contentId,
            Outcome outcome, List<Diagnostic> diagnostics) {
        String ingestId = UUID.randomUUID().toString();
        QuarantineRecord record = new QuarantineRecord(ingestId, sourceName, contentId,
                outcome.name(), Instant.now(clock), diagnostics);

        // An oversize document is the one case where the original is not kept: the service
        // refused to read it, and writing it out would undo that refusal.
        store.quarantine(record, outcome == Outcome.OVERSIZE ? null : document);

        return PipelineResult.quarantined(outcome, contentId, ingestId, diagnostics);
    }

    private Outcome outcomeOf(ValidationResult.Status status) {
        return switch (status) {
            case SCHEMA_INVALID -> Outcome.SCHEMA_INVALID;
            case MALFORMED_XML -> Outcome.MALFORMED_XML;
            case DOCTYPE_REJECTED -> Outcome.DOCTYPE_REJECTED;
            case OVERSIZE -> Outcome.OVERSIZE;
            case VALID -> throw new IllegalStateException("VALID is not a failure outcome");
        };
    }

    private List<Diagnostic> diagnostic(Exception e) {
        return List.of(new Diagnostic(Diagnostic.Severity.FATAL, -1, -1,
                "lex-" + e.getClass().getSimpleName(), String.valueOf(e.getMessage())));
    }
}
