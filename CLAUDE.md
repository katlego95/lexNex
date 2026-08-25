# Project: Legal Content Transformation Service (LexisNexis take-home)

## What this is
A production-style Spring Boot service that ingests French legal judgment XML,
validates it against an XSD, transforms valid documents to normalized JSON and
RAG-ready text via XSLT 3.0 (Saxon-HE), and publishes versioned artifacts keyed
by content_id. Take-home for a Senior Java Engineer role, LexisNexis French
Content Systems. Followed by a technical discussion, so every decision must be
explainable.

## Business context (informs design, mention in code comments sparingly)
LexisNexis sells RAG-grounded legal AI (Lexis+ with Protege). This pipeline is
where trusted content is manufactured: validation is a trust gate, paragraph IDs
are citation anchors, typed citations and jurisdiction metadata are retrieval
infrastructure. A third artifact (chunks.jsonl, one record per paragraph with
denormalized metadata) is produced specifically for a future embedding pipeline.

## Hard rules for Claude Code
1. NEVER run `git commit`, `git push`, or any git write command. Katli reviews
   and commits every phase herself. `git status` and `git diff` are allowed.
2. Work ONLY on the phase Katli names. Do not run ahead, do not "also improve"
   other areas. Small, reviewable diffs.
3. You write all code including the XSLT stylesheets, but for the stylesheets
   you MUST additionally produce a line-by-line annotated walkthrough
   (notes/xslt-walkthrough.md, gitignored study material, never committed): every template, every XPath, why it exists,
   and what breaks if it is removed. Katli must be able to defend every line
   in an interview.
4. After each phase, output: files changed, why each exists, how to verify
   (exact commands), and 2-3 questions an interviewer might ask about it.
   Katli answers those questions before committing; if she cannot, explain
   deeper before the phase closes.
5. Decision log: every phase, append an entry to notes/decisions.md
   (gitignored) for each design decision made in that phase. Four lines per
   entry: Decision / Alternatives considered / Why / Trade-off accepted.
   Number entries sequentially across phases. Katli approves the entries as
   part of the phase close. Phase 6 distills this log into SOLUTION.md; do
   not wait until Phase 6 to write decisions down.
6. Stack constraints: Java 21, Spring Boot 3.x (NOT 4.x), Maven, Saxon-HE
   (net.sf.saxon:Saxon-HE latest 12.x), JUnit 5, Testcontainers not needed
   (no DB). Validation uses JDK javax.xml.validation, NOT Saxon.
7. No Lombok. Records and plain Java. Constructor injection only.
8. Every failure is a recorded outcome (quarantine/diagnostics), not a stack
   trace escaping the pipeline.
9. Config via @ConfigurationProperties bound to env vars:
   APP_INPUT_DIR, APP_OUTPUT_DIR, APP_CONCURRENCY, APP_QUEUE_CAPACITY,
   APP_XSD_PATH, APP_XSLT_PATH, APP_MAX_DOC_BYTES.

## Architecture
See 02_ARCHITECTURE.md. It and this file are committed deliberately, as a record of how the
solution was planned and built; the working notes under notes/ are not.
Package root: io.github.katlego95.lexpipeline
Pipeline per document: validate (Xerces) -> idempotency (content_id + SHA-256)
-> transform (Saxon-HE, XsltExecutable compiled once, XsltTransformer per doc)
-> atomic publish (temp file + move) with versioned manifest.
