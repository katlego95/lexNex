# Solution notes

> This file accretes as the service is built; the full write-up is assembled at the end.

## Concurrency model: a bounded pool of platform threads

The pipeline runs documents through a fixed pool of platform threads fed by a bounded
`ArrayBlockingQueue`. Both the pool size (`APP_CONCURRENCY`) and the queue depth
(`APP_QUEUE_CAPACITY`) are environment variables, so the shape of the machine is a deployment
decision rather than a code change.

### Why bounded, and what happens when the queue is full

When the queue is full, submission **blocks the scanner thread**. Discovery simply stops until a
worker frees a slot, and resumes when one does.

That is the whole backpressure mechanism, and it is deliberate in both halves:

- **Bounded rather than unbounded.** An unbounded queue does not make the service faster — it only
  moves the backlog from the filesystem, where it costs nothing, into the heap, where a large
  enough input directory becomes an `OutOfMemoryError`. The queue holds `Path` references, not
  parsed documents, but even those are unbounded if the queue is.
- **Blocking rather than rejecting.** For a batch, the input is a directory that is not going
  anywhere. Slowing the producer to the speed of the consumers is exactly right: the batch takes
  as long as it takes, and memory stays flat. Rejecting would mean losing discovered work or
  retrying it in a loop that burns CPU to no purpose.

The consequence worth stating plainly: **peak memory is a function of `queueCapacity` and
`concurrency`, not of batch size.** A batch of twenty documents and a batch of twenty million cost
the same, because nothing anywhere holds a list of documents — the batch job holds counters, the
scan is a lazy walk, and the queue is capped. There is a test that asserts exactly this: two
hundred documents through a queue of two, asserting the observed peak queue depth never exceeds
the bound.

The single-document endpoint takes the opposite branch, because its producer *is* going somewhere:
an HTTP client is waiting. Blocking it would hold a request thread against a machine already at
capacity until something timed out, with the document half-processed and nothing recorded. So when
the queue is saturated the endpoint sheds load — `429 Too Many Requests` with `Retry-After` — which
tells the client the truth (come back shortly) instead of failing slowly.

### Why platform threads, and where virtual threads would fit

The expensive stage of this pipeline is the XSLT transform, and it is **CPU-bound**: Saxon builds a
TinyTree and walks it, burning a core. Validation is SAX-driven and streaming; identity is a StAX
pull plus a digest. None of these stages spend meaningful time blocked.

Virtual threads solve a specific problem — a thread parked on IO holding an OS thread hostage — and
that problem does not appear here. Given N cores, N+ε threads is the most transformation throughput
available; more threads mean context switching, not parallelism. Worse, each concurrently
transforming document holds its own TinyTree, so thread count multiplies peak memory. The bounded
pool is therefore doing two jobs at once: capping CPU contention *and* capping memory.
`APP_CONCURRENCY` is the single number that expresses both.

Where virtual threads would genuinely change the design:

1. **The cloud version.** With S3 for artifacts and SQS for input, each document gains several
   blocking network calls (`GetObject`, two or three `PutObject`s, a DynamoDB conditional write).
   That is real blocked time, and a virtual thread per document would let a handful of carriers
   serve far more in-flight documents than platform threads could. The transform would still need
   its own bounded semaphore — the CPU limit does not go away just because the IO limit did.
2. **The HTTP tier.** Tomcat on virtual threads (`spring.threads.virtual.enabled=true`, one
   property) makes the request threads cheap. It changes how many synchronous submissions can be
   in flight; it does not change how fast they transform, and without a cap it would make the
   overload worse rather than better.
3. **A fan-out per document.** If a document produced many artifacts through independent IO,
   structured concurrency would express that neatly. Today it produces three, all in memory.

The honest summary: virtual threads would help the parts of this system that talk to the network,
which is the future version, and would do nothing for the part that does the work, which is the
current one. Adopting them now would be a fashionable change with no measurable effect, and it
would quietly remove the memory ceiling that the fixed pool provides.
