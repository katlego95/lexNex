<!-- DRAFT for Katli's edit — structure follows 05_README_TEMPLATE.md; every command below was run
     against this build. Delete this comment before submitting. -->

# Legal Content Transformation Service

Ingests French legal judgment XML, validates it against an XSD (JDK/Xerces), transforms valid
documents to normalized JSON and RAG-ready text with XSLT 3.0 (Saxon-HE), and publishes versioned
artifacts keyed by content_id. Invalid documents are quarantined with full diagnostics. Duplicate
submissions are idempotent; changed content under the same id is versioned with an audit trail.

Design decisions and trade-offs: see [SOLUTION.md](SOLUTION.md).

## Requirements

Java 21+ and Maven (or just Docker).

## Run

Local:

    ./mvnw spring-boot:run

Docker:

    docker build -t lexpipeline .
    mkdir -p data/in data/out
    docker run --rm -p 8080:8080 \
      -v "$PWD/samples/demo:/data/in:ro" \
      -v "$PWD/data/out:/data/out" \
      -e APP_CONCURRENCY=4 -e APP_QUEUE_CAPACITY=64 \
      lexpipeline

The input mount is read-only: the service never writes to the feed directory.

Configuration (env vars, all optional, sensible defaults):

| Variable | Default | Purpose |
|---|---|---|
| APP_INPUT_DIR | ./data/in (container: /data/in) | batch source folder; nothing outside it is read |
| APP_OUTPUT_DIR | ./data/out (container: /data/out) | artifact store root |
| APP_CONCURRENCY | 4 | batch worker count |
| APP_QUEUE_CAPACITY | 64 | bounded work queue; full means batch blocks, single POST returns 429 |
| APP_MAX_DOC_BYTES | 10485760 | oversize guard |
| APP_XSD_PATH / APP_XSLT_PATH | classpath defaults | schema / stylesheet locations |
| SERVER_PORT | 8080 | HTTP port |

## Use

Four demo documents are committed in [`samples/demo/`](samples/demo) — a valid judgment, a
corrected version of it, a schema-invalid variant and a malformed file — each with a comment
stating the outcome it produces.

Submit one document (synchronous, returns the outcome):

    curl -X POST localhost:8080/api/v1/documents \
      -H 'Content-Type: application/xml' \
      --data-binary @samples/demo/valid-judgment.xml
    # {"contentId":"FR-2024-CC-000777","outcome":"PUBLISHED","version":1,"links":{...}}

    curl -X POST localhost:8080/api/v1/documents \
      -H 'Content-Type: application/xml' \
      --data-binary @samples/demo/valid-judgment-corrected.xml
    # {"contentId":"FR-2024-CC-000777","outcome":"SUPERSEDED","version":2,...}

    curl -X POST localhost:8080/api/v1/documents \
      -H 'Content-Type: application/xml' \
      --data-binary @samples/demo/invalid-date.xml
    # 400 application/problem+json, outcome SCHEMA_INVALID, one diagnostic per problem
    # with line, column and the cvc- error code

Submit a batch (async, returns a batchId immediately):

    BATCH=$(curl -s -X POST localhost:8080/api/v1/batches \
            -H 'Content-Type: application/json' -d '{}' | jq -r .batchId)
    curl -s localhost:8080/api/v1/batches/$BATCH | jq
    # {"status":"COMPLETED","discovered":4,"processed":4,
    #  "counts":{"PUBLISHED":1,"SUPERSEDED":1,"SCHEMA_INVALID":1,"MALFORMED_XML":1},...}

Check a document and fetch artifacts:

    curl -s localhost:8080/api/v1/documents/FR-2024-CC-000777 | jq
    curl -s localhost:8080/api/v1/documents/FR-2024-CC-000777/artifacts/normalized | jq
    curl -s localhost:8080/api/v1/documents/FR-2024-CC-000777/artifacts/chunks
    curl -s "localhost:8080/api/v1/documents/FR-2024-CC-000777/artifacts/fulltext?version=1"

Health and metrics:

    curl -s localhost:8080/actuator/health/readiness | jq
    curl -s localhost:8080/actuator/prometheus | grep '^documents_'
    # documents_received_total{application="lexpipeline"} 4.0
    # documents_published_total{application="lexpipeline"} 1.0
    # documents_quarantined_total{application="lexpipeline",reason="SCHEMA_INVALID"} 1.0

Outputs per published document: `normalized.json`, `fulltext.txt`, `chunks.jsonl` (one
self-contained paragraph record per line, built for a downstream embedding pipeline), and
`manifest.json` (versions and hashes). Quarantined documents keep the original XML plus
`diagnostics.json`.

## Test

    ./mvnw verify

## Research, topics and findings

Notes from working through this brief; full reasoning in [SOLUTION.md](SOLUTION.md).

- **Why the corpus pipeline matters here.** LexisNexis products are RAG systems grounded in curated
  legal content; retrieval quality is bounded by corpus quality. Shaped three choices: validation as
  a hard trust gate, paragraph IDs preserved as citation anchors, and the `chunks.jsonl` artifact
  emitted specifically for embedding.
- **Saxon-HE boundary.** Saxon-HE executes XSLT 3.0 but XSD validation and streaming XSLT are
  Saxon-EE features. Hence JDK (Xerces) validation plus Saxon-HE transformation, and a stated memory
  ceiling of one document tree per worker, bounded by `APP_MAX_DOC_BYTES` and `APP_CONCURRENCY`.
- **XSLT 3.0 JSON.** The stylesheet builds the W3C JSON XML vocabulary and finishes with
  `xml-to-json()`: the stylesheet never writes a brace or a quote, so malformed JSON and injection
  are structurally impossible, and the intermediate tree is inspectable when output looks wrong.
  Compiled `XsltExecutable` is thread-safe and built once; transformers are per-document.
- **Idempotency model.** content_id + SHA-256 of received bytes. Identical resubmission: recorded
  no-op. Same id, new content: version N+1 with supersession recorded, mirroring real legal feeds
  (corrections, GDPR re-anonymisation of the same decision).
- **French judgment structure.** facts / reasons (motifs, "Considérant/Attendu que") / disposition
  (dispositif, "Par ces motifs"); ECLI and NOR are typed identifiers, kept structured as future
  citation-graph edges.
- **Two findings that changed the design.** Disabling external entities but allowing DOCTYPE makes
  Xerces *skip* the entity silently — the document publishes with text missing — so the DOCTYPE
  declaration itself is refused, at the gate. And a multi-file publish needs a directory-level
  commit, not four atomic file writes: otherwise a failure mid-publish leaves a partial version
  sitting at its real name for the artifact endpoint to serve.
