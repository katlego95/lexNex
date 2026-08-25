# Solution

## 1. What this service is for

LexisNexis' moat is trusted, citable legal content feeding RAG systems, and retrieval quality is
bounded by corpus quality: an answer is only as good as the passage it cites. This service is where
that content is manufactured. It validates at the gate, normalizes deterministically, publishes
idempotently, and emits artifacts shaped for retrieval. Hence three themes below: validation is a
trust gate, because one bad document becomes a wrong answer with a citation attached; paragraph ids
are citation anchors, surviving unchanged into every artifact; and citations, court, jurisdiction
and date are retrieval infrastructure, kept typed and denormalised onto every chunk.

## 2. Architecture

![Architecture](docs/architecture.svg)

`POST /api/v1/documents` runs the pipeline synchronously and returns the outcome;
`POST /api/v1/batches` scans a directory into a bounded queue drained by a fixed worker pool. Both
funnel through the identical per-document path, so there is one error model and one set of metrics.

```
receive -> validate (Xerces, SAX) -> identify (StAX + SHA-256) -> dedupe
        -> transform (Saxon-HE) -> stage artifacts -> commit manifest
```

Outcomes are a closed set: `PUBLISHED`, `SUPERSEDED`, `DUPLICATE_NOOP`, `SCHEMA_INVALID`,
`MALFORMED_XML`, `DOCTYPE_REJECTED`, `OVERSIZE`, `TRANSFORM_FAILED`, `STORAGE_FAILED`,
`INTERNAL_ERROR`. Nothing throws out of the pipeline; every failure is a recorded outcome with a
quarantine record behind it, because a rejection nobody recorded is a document nobody can fix.

## 3. Decisions that carry the design

**Two XML stacks, deliberately.** Validation uses the JDK's `javax.xml.validation` (Xerces),
transformation uses Saxon-HE 12. Xalan, which the JDK ships, is XSLT 1.0 with no `xml-to-json()`;
Saxon-HE has no XSD validation, an EE feature. Each concern gets the processor that can do it, at
the price of two stacks to reason about.

**Validation collects every error instead of failing fast.** A custom SAX `ErrorHandler` records
problems and returns normally; the JAXP default throws on the first, so the content team upstream
would fix one broken paragraph per round trip. Diagnostics carry line, column and the spec-defined
code (`cvc-id.2`), which is stable across Xerces versions and locales and is what to alert on.

**A DOCTYPE is refused at the gate.** Every stage parses through one hardened reader factory: no
DOCTYPE, no external entities, no external DTD. Refusing the declaration is stricter than disabling
entities, which came from a finding here: with entities off but DOCTYPE allowed, Xerces silently
skips the reference and the judgment publishes with that text missing. A corpus that failed loudly
is recoverable; one that is quietly wrong is not. Refusing at the gate also makes the outcome
`DOCTYPE_REJECTED` rather than, as an earlier iteration had it, a security rejection filed under
`TRANSFORM_FAILED`, a bug in our own code.

**Normalization builds the W3C JSON vocabulary.** The stylesheet constructs the XPath 3.1 JSON
vocabulary and finishes with `xml-to-json()`, so it never writes a brace, a quote or a comma;
hand-rolled escaping breaks on the first quoted statute. `xsl:mode on-no-match="fail"` disables the
built-in rules that would otherwise copy unmatched source text into the artifact.

**Compile once, one transformer per document.** `Schema` and `XsltExecutable` are immutable and
expensive, so both compile at startup and a compile failure prevents startup. `Validator` and
`XsltTransformer` hold per-document state: sharing one interleaves two documents and produces wrong
artifacts silently, which a 64-document, 8-thread test exists to catch.

**`chunks.jsonl` is the artifact aimed at their business.** One self-contained record per
paragraph: `chunk_id` (`FR-2024-CA-000123#p2`), section, sequence, text, and denormalised court,
jurisdiction, date and typed citations. A vector store retrieves a chunk, not a document, so
without that metadata it cannot filter before ranking. Chunks are projected from `normalized.json`,
so their text is by construction what was published.

**Idempotency is content_id plus the SHA-256 of the received bytes.** The hash is of what arrived,
not of what we produced: hashing our own output would supersede the whole corpus on the next
stylesheet change. Same id and hash is a `DUPLICATE_NOOP`; a new hash publishes N+1 and marks its
predecessor `supersededBy`, leaving old artifacts so citations resolve. Accepted cost: a whitespace
reformat counts as a new version, with C14N deferred.

**A version becomes visible in two atomic steps.** A publish is four files, so per-file atomicity
only means each file appears whole, not the version. The artifacts go into a hidden staging
directory moved into place as `v{N}` in one rename, then `manifest.json` is replaced atomically:
that is the commit point. Without the staging step, a failure between `fulltext.txt` and
`chunks.jsonl` would leave a partial version at its real name for the artifact endpoint to serve.

**Backpressure rather than buffering, on a bounded pool of platform threads.** A full queue blocks
the batch scanner until a worker frees a slot; an unbounded queue would only move the backlog from
the filesystem into the heap. The single-document endpoint sheds load with `429` instead, since its
producer is an HTTP client that cannot wait. Peak memory is therefore set by queue capacity and
worker count, not batch size. Platform threads because the expensive stage is CPU-bound and each
transforming document holds its own Saxon tree, so `APP_CONCURRENCY` caps CPU contention and memory
at once; virtual threads solve blocked-on-IO, which arrives with the cloud version below.

**One judgment at a time, per judgment.** Read-check-write on a manifest is not atomic, so two
workers on the same judgment would both compute v1 and the second would overwrite the first. The
critical section is serialised per content_id by a `ConcurrentHashMap<String, ReentrantLock>`, and
`publish()` refuses to write if the store has moved past the version the caller computed. The
distributed equivalent is a DynamoDB conditional write on `content_id`.

**Other decisions**, with alternatives and trade-offs in the working notes. `content_id` is
validated before becoming a directory name, since the XSD types it `xs:string` and
`../../etc/passwd` is schema-valid; a batch `inputDir` is confined to `APP_INPUT_DIR` by
normalisation and `toRealPath()`, because normalisation cannot see through a symlink; errors are
RFC 7807 `problem+json`, since the client is a feed that must branch on why a document was refused;
counters are pre-registered so a dashboard shows zero rather than a missing series; readiness and
liveness are separate probes; and the container runs non-root.

## 4. Error model

| Outcome | HTTP | Whose problem | Quarantined |
|---|---|---|---|
| `PUBLISHED` / `SUPERSEDED` / `DUPLICATE_NOOP` | 200 | none | no |
| `SCHEMA_INVALID` / `MALFORMED_XML` / `DOCTYPE_REJECTED` | 400 | sender's | yes, with diagnostics |
| `OVERSIZE` | 413 | sender's | diagnostics only, since we refused to read it |
| `TRANSFORM_FAILED` | 500 | ours | yes, so the bug is reproducible |
| `STORAGE_FAILED` | 500 | ours, retryable | no, storage is what failed |
| `INTERNAL_ERROR` | 500 | ours | no |

## 5. Big documents: where the memory goes

| Stage | In memory | Bound |
|---|---|---|
| Size guard | nothing | rejects over `APP_MAX_DOC_BYTES` before parsing |
| Validation | SAX events, no tree | O(1) in document size |
| Identity | StAX pull stopping after the header, streamed digest | O(1) |
| Transform | one Saxon TinyTree | the real ceiling |
| Batch | `Path` references only | `APP_QUEUE_CAPACITY` |

Peak is roughly `APP_CONCURRENCY` times the largest document. True streaming XSLT is Saxon-EE; if
documents outgrow this, the options are EE streaming or splitting upstream.

## 6. Known limits, stated deliberately

1. Writes are atomic but not durable: no `fsync`, so a power loss can lose a rename the OS had not
   flushed. Ordering survives it, leaving an uncommitted version rather than a corrupt one.
2. Hashing is over raw bytes, not canonical XML, so a whitespace-only reformat is a new version.
3. The per-content_id lock map is never pruned and protects a single JVM.
4. Batch state is in memory and dies with the process; per-document detail is in the log and in
   quarantine records, not the API.
5. The readiness check is shallow: compilation is a startup invariant, so it is UP whenever the
   process is. Its value is reporting which schema and stylesheets the instance loaded.
6. Orphaned version directories from crashed publishes are replaced on retry, but nothing sweeps them.

## 7. Cloud evolution on AWS

A change of adapters, not of pipeline. An S3 landing bucket feeds SQS and the batch endpoint
becomes a backfill tool; at-least-once delivery is what the idempotency model already handles,
since a redelivery is a `DUPLICATE_NOOP`. Compute is a container on EKS with an HPA scaling on
queue depth, the gauge already emitted. Dedupe moves to a DynamoDB table keyed by `content_id` with
a conditional write on `sha256`, replacing the in-process lock with a guarantee that holds across
pods and turning `manifest.json` into an item. Artifacts move to an S3 bucket, onto which the
two-step commit maps cleanly. Monitoring stays Micrometer into Prometheus or CloudWatch, alarming
on quarantine rate and queue age. `chunks.jsonl` feeds an embedding job into an OpenSearch vector
index, and typed citations become graph edges for a citator.

## 8. Testing

148 tests, with mocks only where a real failure cannot be arranged, such as a full disk. The
stylesheets are tested as units with JSONAssert STRICT against the brief's target output; the trust
gate for every rejection class, asserting the specific `cvc-` code and position; idempotency by
resubmitting identical then changed bytes; concurrency by 16 simultaneous identical deliveries
publishing exactly once. Publication ordering is tested by failing a publish on purpose between
`fulltext.txt` and `chunks.jsonl` and asserting nothing was left behind; those tests were run
against the pre-fix code first, where three failed. Path safety uses real symlink fixtures pointing
outside the input root, and observability asserts against the real Prometheus scrape text. The demo
set in `samples/demo` is run through the pipeline so its documented outcomes cannot rot.
