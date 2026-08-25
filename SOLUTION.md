> **DRAFT — for Katli's edit.** Assembled from ARCHITECTURE sections 1, 3, 9 and the decision log.
> Everything here is claimed only where the code and tests back it; the sections marked
> *Known limits* are deliberate and stated rather than hidden. Delete this block before submitting.

# Solution

## 1. What this service is for

LexisNexis' product moat is trusted, citable legal content feeding RAG systems. Retrieval quality
is bounded by corpus quality — an answer can only be as good as the passage it cites. This service
is the point where trusted content is manufactured: **validate at the gate, normalize
deterministically, publish idempotently, and emit artifacts shaped for retrieval** rather than
merely converted files.

Three consequences run through every decision below:

- **Validation is a trust gate, not a formality.** One bad document in the corpus becomes a wrong
  answer with a citation attached. Nothing that fails validation is ever published.
- **Paragraph ids are citation anchors.** They survive from source XML to `normalized.json` to
  `chunks.jsonl` unchanged, because a pinpoint citation has to resolve to exactly one passage.
- **Citations and jurisdiction are retrieval infrastructure.** ECLI and NOR stay typed rather than
  collapsing into strings, so they can become graph edges; court, jurisdiction and date are
  denormalised onto every chunk so retrieval can filter before it ranks.

## 2. The pipeline

Every document — whether it arrives by `POST /api/v1/documents` or is discovered by a batch scan —
takes the identical path. One code path means one error model and one set of metrics.

```
receive → validate (Xerces, SAX) → identify (StAX + SHA-256) → dedupe → transform (Saxon-HE)
        → stage artifacts → commit manifest
```

Outcomes are a closed set: `PUBLISHED`, `SUPERSEDED`, `DUPLICATE_NOOP`, `SCHEMA_INVALID`,
`MALFORMED_XML`, `DOCTYPE_REJECTED`, `OVERSIZE`, `TRANSFORM_FAILED`, `STORAGE_FAILED`,
`INTERNAL_ERROR`. **Nothing throws out of the pipeline.** Every failure — the sender's, the disk's,
or ours — is a recorded outcome with a quarantine record behind it, because a rejection nobody
recorded is a document nobody can fix.

## 3. Decisions and trade-offs

### 3.1 Two XML stacks, deliberately

Validation uses the JDK's `javax.xml.validation` (Xerces); transformation uses Saxon-HE 12.

The JDK ships Xalan, which is **XSLT 1.0 only** — no `xml-to-json()`, no maps and arrays, no
`xsl:mode`, all of which the normalization depends on. Conversely Saxon-**HE** has no XSD
validation at all; that is an EE feature. So each concern gets the processor that can do it, at
zero licence cost.

*Trade-off:* two XML stacks to reason about, and the streaming XSLT that would remove the
transform's memory ceiling (`xsl:mode streamable`) is also EE-only. That ceiling is stated in §5.

### 3.2 Validation collects every error instead of failing fast

A custom SAX `ErrorHandler` records problems and returns normally rather than throwing. With the
JAXP default, the first schema violation throws and validation stops — so the upstream content team
learns about one broken paragraph, fixes it, resubmits, and discovers the next one. One round trip
per error.

Diagnostics are the *product* of the rejection path: every problem, with line, column, severity and
the spec-defined error code (`cvc-id.2`, `cvc-complex-type.4`). Codes are stable across Xerces
versions and locales, so they are what to alert and report on; the prose beside them is for humans.

`MALFORMED_XML` and `SCHEMA_INVALID` are told apart by the SAX severity the parser chose —
`fatalError` versus `error` — not by pattern-matching messages.

### 3.3 XXE: one policy, enforced at the gate

Every stage that parses a submitted document parses it through one `HardenedXmlReaderFactory`: no
DOCTYPE, no external entities, no external DTD, secure processing on.

Refusing the DOCTYPE *declaration* is stricter than merely disabling external entities, and that is
deliberate. With entities disabled but DOCTYPE allowed, Xerces silently **skips** the reference and
the judgment publishes with that text missing — verified while building this: the XXE fixture
transformed successfully into a paragraph with empty text until the declaration itself was refused.
A corpus that failed loudly is recoverable; a corpus that is quietly wrong is not.

The refusal lives at the **trust gate**, so the outcome is `DOCTYPE_REJECTED` with an actionable
diagnostic. An earlier iteration enforced it only at the transform, which meant a hostile document
passed validation, died downstream, and was recorded as `TRANSFORM_FAILED` — a security rejection
filed as a bug in our own code. The transform keeps its refusal as defence in depth.

### 3.4 Normalization builds the W3C JSON vocabulary

`judgment-to-json.xsl` constructs the XPath 3.1 JSON vocabulary (`map`/`array`/`string` elements in
the `xpath-functions` namespace) and finishes with `xml-to-json()`. **The stylesheet never writes a
brace, a quote or a comma.** String-concatenating JSON in XSLT means hand-rolling escape rules, and
one quoted statute in a French judgment produces a broken artifact. The vocabulary also validates
structure on the way out — a duplicate key becomes an error rather than a corrupt file — and the
intermediate tree is inspectable when output looks wrong.

`xsl:mode on-no-match="fail"` disables XSLT's built-in template rules, which would otherwise copy
unmatched source text into the result: a typo'd match pattern becomes a loud `XTDE0555` instead of
stray text in a published artifact.

*Known wart:* `xml-to-json()` escapes `/` as `\/`, so the title reads `n° 20\/01234`. That is legal
JSON (RFC 8259) and every parser decodes it back; Saxon's `escape-solidus` switch is not honoured
through the options map. Tests therefore compare **parsed** JSON with JSONAssert in STRICT mode
rather than comparing bytes.

### 3.5 Compile once, one transformer per document

`Schema` and `XsltExecutable` are immutable, thread-safe and expensive to build, so both are
compiled at startup — and a failure to compile prevents startup, because a service that cannot run
its trust gate has nothing safe to do with traffic. `Validator` and `XsltTransformer` hold
per-document state and are created per call.

Sharing a transformer across the worker pool does not crash; it interleaves two documents' state
and produces artifacts that are wrong with nothing thrown. A concurrency test (64 documents, 8
threads, two sources interleaved) exists to catch exactly that.

### 3.6 Three artifacts, and why `chunks.jsonl` is the interesting one

Per published version: `normalized.json` (structured), `fulltext.txt` (indexable text), and
`chunks.jsonl` — one self-contained JSON record per paragraph, carrying `chunk_id`
(`FR-2024-CA-000123#p2`), section, sequence, text, and denormalised court / jurisdiction /
decision_date / typed citations.

A vector store retrieves a chunk, not a document. Without denormalised metadata, retrieval cannot
filter by jurisdiction or date *before* ranking, and every hit needs a join back to its parent to
know whether it is binding authority. Disk is cheap; pre-filtered retrieval is the product.

Chunks are built from `normalized.json` with Jackson rather than by a third stylesheet, so a
chunk's text is *by construction* the text that was published — a second pass over the XML could
drift. The one place drift is possible is `fulltext.txt`, which computes the paragraph join
independently; a cross-artifact test asserts it always equals the `full_text` field.

### 3.7 Idempotency: content_id + SHA-256 of the received bytes

The hash is of **what arrived**, not of anything we produced. Hashing our own output would make the
fingerprint change whenever a stylesheet changes — the next deploy would supersede the entire
corpus with identical content and write an audit trail of changes that never happened.

- Same id, same hash as the current version → `DUPLICATE_NOOP`. Feeds redeliver constantly
  (corrections runs, at-least-once queues); doing nothing is the correct answer.
- Same id, different hash → version N+1, previous version marked `supersededBy`, its artifacts left
  in place so existing citations still resolve.

This mirrors real legal feeds: corrections and GDPR re-anonymisation redeliver the same decision.

*Trade-off:* a whitespace-only reformat upstream is a new version. XML canonicalization (C14N)
before hashing is the refinement, deliberately deferred; a test pins the current behaviour so it
stays a decision rather than folklore.

### 3.8 What makes a version visible

A publish is four files, so "each file is written atomically" is not enough — that would only mean
each *file* appears whole, not the *version*.

1. The three artifacts are written into a hidden staging directory and that directory is moved into
   place as `v{N}` in a single rename. `v{N}/` is therefore never observable with a file missing.
2. `manifest.json` is replaced atomically. **This is the commit point.** Until it lands, `v{N}` is
   unreferenced garbage that the retry deletes and rewrites.

Step 1 exists because the artifact endpoint resolves files by path: without it, a failure between
`fulltext.txt` and `chunks.jsonl` would leave a two-thirds-complete version at its real name, ready
to be served. The read path closes the loop by resolving only versions the manifest names.

*Known limit:* atomicity is not durability. Nothing is `fsync`ed, so a power loss can still lose a
rename the OS had not flushed. The ordering guarantees survive it — a lost manifest write leaves an
uncommitted version, not a corrupt one.

### 3.9 Concurrency: a bounded pool of platform threads

Documents run through a fixed pool fed by a bounded `ArrayBlockingQueue`; both sizes are env vars.

**When the queue is full, the batch scanner blocks.** Discovery stops until a worker frees a slot.
An unbounded queue would not make the service faster — it moves the backlog from the filesystem,
where it costs nothing, into the heap, where a large enough directory becomes an OOM. The
single-document endpoint takes the opposite branch, because its producer is an HTTP client that
cannot wait: it sheds load with `429` and `Retry-After` rather than holding a request thread against
a saturated machine until something times out.

**Peak memory is a function of `queueCapacity` and `concurrency`, not batch size.** Nothing holds a
list of documents: the batch job holds counters, the scan is a lazy walk, the queue holds `Path`
references and is capped. A test runs 200 documents through a queue of 2 and asserts the observed
peak depth never exceeds the bound.

**Why platform threads.** The expensive stage is the XSLT transform, and it is CPU-bound: Saxon
builds a TinyTree and walks it. Virtual threads solve blocked-on-IO, which this pipeline does not
do — validation streams, identity streams, the transform computes. Beyond core count, more threads
buy context switching, not throughput, and each concurrently transforming document holds its own
tree, so thread count multiplies peak memory. `APP_CONCURRENCY` caps CPU contention and memory with
one number.

Where virtual threads *would* change the design:

1. **The cloud version.** With S3 and DynamoDB, each document gains several blocking network calls.
   That is real blocked time, and a virtual thread per document would serve far more in flight. The
   transform would still need its own bounded permit — the CPU limit does not disappear.
2. **The HTTP tier.** `spring.threads.virtual.enabled=true` makes request threads cheap. It changes
   how many synchronous submissions can be in flight; it does not change how fast they transform,
   and without a cap it would make overload worse.
3. **A per-document fan-out** over independent IO, which today does not exist.

Adopting them now would be a fashionable change with no measurable effect, and would quietly remove
the memory ceiling the fixed pool provides.

### 3.10 Two documents, one judgment: the race

Read-check-write on a manifest is not atomic. Two workers handling the same judgment would both
read "no versions yet", both compute v1, and the second would silently overwrite the first — one
delivery lost, and a manifest showing a version that is not the last one received.

The critical section is serialised **per content_id** by a `ConcurrentHashMap<String,
ReentrantLock>`, so documents for different judgments never wait for each other. `publish()`
additionally refuses to write if the store has moved past the version the caller computed, turning
a missing lock into a loud failure instead of data loss.

The distributed equivalent is a **DynamoDB conditional write** on `content_id` — the same
compare-and-set moved to a store several pods share. The in-process lock protects one JVM only;
two pods on a shared volume would need that.

*Known limit:* the lock map is never pruned. Entries are small and bounded by the number of
distinct judgments seen; a long-lived service would use a size-bound cache or lock striping.

### 3.11 Security posture

Beyond XXE (§3.3):

- **`content_id` becomes a directory name** and arrives inside a submitted document. The XSD types
  it `xs:string`, so `../../etc/passwd` is perfectly schema-valid. Anything outside
  `[A-Za-z0-9._:-]` is refused, not escaped.
- **`POST /batches {"inputDir": …}` is a request-controlled path.** Three escapes, all closed:
  absolute paths refused; `..` caught by normalisation; symlinks caught by `toRealPath()` on every
  candidate, compared against the resolved root. Normalisation alone cannot see through a symlink —
  `in/leak.xml` looks perfectly contained until the filesystem follows it. Refused paths are counted
  as `skipped` with a reason rather than as failed documents.
- **Non-root container (uid 1001).** This process parses attacker-supplied XML; if a parser bug ever
  reaches code execution, that is the difference between a contained blast radius and a host
  compromise.
- **Error bodies say enough to fix a document and nothing more** — the generic 500 handler reports
  only that the request failed, with detail in the log where the operator is.

### 3.12 Observability

Counters (`documents_received_total`, `_published_`, `_superseded_`, `_duplicate_`,
`_quarantined_{reason}`), per-stage timers (`pipeline_stage_duration_seconds{stage}`), and
saturation gauges (`queue_depth`, `active_workers`).

The alert that matters is the **quarantine rate**: a feed that starts shipping broken XML shows up
minutes before anyone reads a support ticket, and the `reason` tag says whether it is the sender's
problem or ours. Counters are pre-registered at startup — a missing series and a zero look identical
on a dashboard and mean opposite things, and an alert written against a series that does not exist
never fires. Stage timings are recorded in a `finally`, so a transform that fails after 30 seconds
still appears.

One structured log line per document (`contentId`, `outcome`, `version`, `sha256`, per-stage
millis, `ingestId`), same keys in the same order, because this is read by grep and by aggregators
far more often than by a person.

Readiness and liveness are separate probes: a failed liveness restarts the container, a failed
readiness only removes it from the load balancer. An instance that cannot compile its schema should
stop receiving documents, not be restarted into the same failure. *Honest caveat:* since
compilation is a startup invariant, the readiness check is close to tautological today; its value is
the detail it reports (which schema, which stylesheets this instance loaded) and the place it
reserves for a reloadable stylesheet.

## 4. Error model

| Outcome | HTTP | Whose problem | Quarantined |
|---|---|---|---|
| `PUBLISHED` / `SUPERSEDED` / `DUPLICATE_NOOP` | 200 | — | no |
| `SCHEMA_INVALID` / `MALFORMED_XML` / `DOCTYPE_REJECTED` | 400 | sender's | yes, with diagnostics |
| `OVERSIZE` | 413 | sender's | diagnostics only — we refused to read it, so we do not write it back out |
| `TRANSFORM_FAILED` | 500 | ours | yes, so the bug is reproducible |
| `STORAGE_FAILED` | 500 | ours (retryable) | no — storage is what failed |
| `INTERNAL_ERROR` | 500 | ours | no |

Errors are RFC 7807 `application/problem+json`. The client here is a feed, not a person: it must
branch on *why* a judgment was refused without matching on English prose, so `type` and `outcome`
are the machine-readable fields and `diagnostics` carries line, column and error code.

## 5. Big documents: where the memory goes

| Stage | In memory | Bound |
|---|---|---|
| Size guard | nothing | rejects over `APP_MAX_DOC_BYTES` before parsing |
| Validation | SAX events, no tree | O(1) in document size |
| Identity | StAX pull that stops after the header; streamed digest | O(1) |
| **Transform** | **one Saxon TinyTree** | the real ceiling |
| Batch | `Path` references only | `APP_QUEUE_CAPACITY` |

Peak ≈ `APP_CONCURRENCY` × largest document, bounded explicitly by the size cap and the worker
count. True streaming XSLT is Saxon-EE; if documents outgrow this, the options are EE streaming or
splitting upstream.

## 6. Known limits, stated deliberately

1. No `fsync` — atomic, not durable (§3.8).
2. Byte hashing, not C14N — a whitespace reformat is a new version (§3.7).
3. The per-content_id lock map is never pruned, and protects one JVM (§3.10).
4. Batch state is in memory and dies with the process; per-document detail lives in the log and in
   quarantine records, not in the API.
5. The readiness check is shallow (§3.12).
6. Orphaned version directories from crashed publishes are harmless and replaced on retry, but
   nothing sweeps them.

## 7. Cloud evolution (AWS)

The design was built so that this is a change of adapters, not of pipeline.

- **Input.** S3 landing bucket → event notification → SQS; the service consumes SQS and the batch
  endpoint becomes a backfill tool. SQS is at-least-once, which is exactly what the idempotency
  model already handles: a redelivery is a `DUPLICATE_NOOP`.
- **Compute.** Container on EKS, HPA scaling on **queue depth** — the gauge is already emitted.
  `APP_CONCURRENCY` stays an env var.
- **Dedupe and versioning at scale.** DynamoDB table keyed by `content_id`, conditional write on
  `sha256`; `manifest.json` becomes an item. This replaces the in-process lock with a guarantee that
  holds across pods.
- **Output.** S3 published bucket, prefix per `content_id/version`; quarantine prefix with
  diagnostics. The two-step commit maps cleanly: write objects, then conditionally write the
  manifest item.
- **Monitoring.** Micrometer → Prometheus / CloudWatch; alarm on quarantine rate and queue age;
  structured JSON logs.
- **RAG.** `chunks.jsonl` → embedding job (Step Functions or a consumer) → OpenSearch vector index;
  typed citations become graph edges for a Shepard's-style citator; jurisdiction and date become
  retrieval filters.

## 8. Testing

147 tests, no mocks except where a real failure cannot be arranged (a full disk, an exploding
stylesheet).

- **Stylesheets as units** — sample in, artifact out, JSONAssert STRICT against the brief's target.
- **Trust gate** — every rejection class, asserting the specific `cvc-` code and position.
- **Idempotency** — resubmit identical (no new version), resubmit changed (v2 + `supersededBy`).
- **Concurrency** — 16 concurrent identical deliveries publish exactly once; 64 concurrent
  transforms all produce correct artifacts.
- **Failure ordering** — a publish is failed on purpose between `fulltext.txt` and `chunks.jsonl`,
  asserting no version directory, no staging directory, and an untouched manifest. These were run
  against the pre-fix code first: three failed, which is what makes them worth having.
- **Path safety** — real symlink fixtures pointing outside the input root, asserting the file is
  skipped and its content never published.
- **Observability** — assertions against the real Prometheus scrape text, so the metric *names* a
  dashboard is written against are the contract being tested.
