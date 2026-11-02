# All-SQLite payload experiment

This experiment is isolated in `object-store/src/benchmark`. It is not linked into
production artifacts and does not open or migrate the application's stores.
The agreed candidate stores all object payloads in SQLite, including large objects.

## Schema and publication

`payloads(id INTEGER PRIMARY KEY, data BLOB NOT NULL)` contains only an immutable
payload and its row ID. `objects` holds the key, published payload reference, byte
length, SHA-256/block CRC metadata and descriptive original/derived classification. `build_objects` supplies
many-to-many build ownership. A classification does not determine retention.

The original monolithic comparison path allocates `zeroblob(length)`, fills it through the
incremental BLOB API, inserts metadata and ownership, and commits. Failure or
cancellation rolls back the payload and references together. Existing keys can
acquire another owner without duplicating bytes; conflicting metadata is rejected.
Readers use a deferred snapshot transaction. Handles close at the end of each
transfer, including errors. Transfers use 64 KiB Java and native buffers.

WAL and `synchronous=FULL` are explicit. Comparing NORMAL BLOB commits with fsynced
pack payloads would not provide equivalent power-loss durability. The smoke timer
includes a completed TRUNCATE checkpoint after each write.

Both write paths expect already available local input. Holding the writer through
a CDN transfer is prohibited. The bounded staging API described below commits one
fixed chunk per call so the caller can schedule another write or acquire more input
between calls. There is no general scheduler or network integration in this prototype.

## Commands

Use a full Java 25 JDK and the repository's normal native SQLite configuration:

```sh
./gradlew :object-store:integrationTest --tests '*SqliteBlobPrototypeTest' --tests '*BoundedBlobStagingTest' --tests '*StagingProcessRecoveryTest'
./gradlew :object-store:sqliteBlobBenchmark
./gradlew :object-store:storageComparison
./gradlew :object-store:blobConcurrency
./gradlew :object-store:boundedStagingBenchmark
```

The benchmark defaults to the ignored `.local/storage-evaluation` directory. Set
`-PblobBenchmarkDirectory=/path/on/a/real/disk` to choose another location. Avoid
RAM-backed temporary directories when measuring disk behavior. Each invocation
creates and removes its own subdirectory. Synthetic inputs contain no user content.
The task sets a 128 MiB Java heap and streams objects up to 512 MiB, verifying
SHA-256 equality on the complete readback. Hashing is included in both timings.

## Initial Linux smoke results

One warm-cache run, local Btrfs, synthetic uncompressed bytes, FULL durability,
128 MiB maximum Java heap. Each row adds another object to the same database.
These are feasibility checks, not statistically controlled throughput results.
An existing GUI remained open; no cache eviction or physical power-loss was tested.

| Payload | Write including checkpoint | Read and SHA-256 |
|---|---:|---:|
| 64 KiB | 34.490 ms | 1.476 ms |
| 1 MiB | 16.834 ms | 3.142 ms |
| 145 MiB | 772.330 ms | 121.080 ms |
| 256 MiB | 1531.753 ms | 149.262 ms |
| 512 MiB | 2358.155 ms | 324.732 ms |

The final database was 959,426,560 bytes for 958,464,000 payload bytes.
The first operation includes initialization effects. Reads may be served from OS
cache; these figures establish neither sustained disk throughput nor superiority
over packs. The prototype's local sequential writes also hold the SQLite writer
for the full payload, so foreground interference remains a key measurement.

## Sequential verified comparison

The comparison uses 32 unique synthetic configuration objects per size, identical
inputs for both implementations, three rounds and alternating backend order.
Production `PreparedPut` identity verification, SHA-256 and block CRC generation
also run on the SQLite path, which persists proofs in `objects`, not `payloads`.
Its verified whole reader checks SHA and block CRCs. Invalid identities and modified
payloads are covered by integration tests. Raw generic streaming smoke inputs remain
unchecked and must not be confused with this verified path.

Write timings include open/setup and one acknowledged put at a time. SQLite also
includes an explicit checkpoint. Pack payloads follow production fsync policy;
pack metadata retains its existing NORMAL/recoverable-from-pack design. This is
an end-to-end API comparison, not an identical native-write microbenchmark: the
production store has asynchronous admission/read machinery, pack records and extra
bookkeeping, while the prototype lacks those full production features. The SQLite
reader currently copies bounded whole objects and checks SHA on every verified
read; production may cache verification. Both paths compare returned bytes.

Warm rounds 1–2 (round 0 includes JVM/native initialization), milliseconds for all
32 objects; ranges show the two observations, not confidence intervals:

| Object size | SQLite writes | Pack writes | SQLite reads | Pack reads |
|---|---:|---:|---:|---:|
| 64 KiB | 35.9–36.0 | 61.6–68.1 | 2.4–2.9 | 8.2–9.9 |
| 128 KiB | 44.4–45.2 | 61.4–72.9 | 4.2–4.7 | 9.7–11.1 |
| 512 KiB | 94.6–95.0 | 94.8–96.3 | 14.0–15.7 | 20.2–23.9 |
| 1 MiB | 158.0–170.5 | 131.0–131.9 | 25.6–26.5 | 36.9–37.2 |

Final physical sizes were close: at 1 MiB/object, SQLite used 33,636,352 bytes
and packs 33,588,288 bytes, measured after close. These small warm-cache corpora
and sequential commits do not measure production group commit, a full build,
concurrent producers, cold storage, or large verified encoded-object ingest.
The results support continuing evaluation, not a general SQLite speed claim.

## Reader concurrency and process recovery

A separate read-only connection repeatedly read and verified a 64 KiB object and
queried its build total while a writer inserted a 512 MiB synthetic payload.
The bulk writer took 4433.336 ms including its checkpoint. There were 16,526
read/accounting samples: p50 0.073 ms, p95 0.238 ms, p99 4.355 ms, max 26.489 ms.
This tiny ownership table cannot establish full-build accounting latency. The bulk
payload used the unchecked streaming smoke path; it did not perform CDN validation.
A deterministic integration test also pauses an uncommitted writer and confirms
that a reader sees prior data, excludes the new object, and sees it after commit.

Child-process tests forcibly terminate writers after a first unpublished chunk
and after a completed commit. Reopening verifies ownership/payload presence and
`PRAGMA integrity_check`: uncommitted data disappears and committed data survives.
These test process termination, not power failure. Other catalog writes still wait
behind a large write transaction on this original monolithic path. The bounded
staging path below avoids that long transaction.

## Bounded staging implementation

The staging candidate uses fixed **1 MiB chunks**, with a smaller final chunk.
`begin` reserves only metadata: no full-file `zeroblob` allocation. Each `append`
allocates and fills one payload row and advances committed progress in the same
FULL-durability transaction. Input must be acquired before calling `append`, so
network waits hold no SQLite transaction. Offset checks prevent stale concurrent
appenders from overwriting a committed chunk. Resume returns the same stage ID
and its committed offset after reopening. There are at most 16 incomplete stages
and 2 GiB of total reserved declared lengths, including stages awaiting cleanup.
This bounds storage admitted by incomplete operations; it is not a total library
quota or a hard wall-clock guarantee for any disk operation.

The payload table still contains only IDs and bytes. Additional metadata tables:

- `chunk_sets`: identity of a chunk collection.
- `payload_chunks`: collection, byte offset, payload row and chunk length.
- `staging`: pending object key, length, classification, committed progress and
  cleanup state. Incomplete stages are excluded from objects and build totals.

Published `objects` references either one direct payload or a chunk collection.
Large objects remain chunked after publication. Thus publication never copies all
bytes into a second large BLOB, and no external files or storage tier are added.
Reads resolve ranges across chunks transparently. An incremental payload handle
never stays open across chunk commits or metadata mutations.

Publication streams committed chunks to verify SHA-256 and produce block CRCs
without a writer transaction. Typed publication also runs the existing CDN
verification, including encoded BLTE/EKey validation. A final small transaction
rechecks complete staging state, inserts object metadata/ownership and removes
the staging marker. Cancellation or an invalid identity leaves the stage resumable
and invisible. Explicit abandonment marks it for cleanup, then deletes at most one
chunk per transaction. Interrupted cleanup can be retried; it cannot delete an
already published object. Closing a store preserves incomplete stages.

Tests cover interleaved writes, stale offsets, restart/resume, cross-chunk ranges,
identity/corruption refusal, cancellation during chunk/publication/cleanup, bounded
admission, and SQLITE_FULL injected via a page-count limit. The latter checks SQLite
failure handling, not an actual full filesystem. Child-process termination during
a second chunk retains the first committed chunk; termination after publication
preserves readable data. A verification hook executes foreground writes during
verification and checks publication does not insert or update payload bytes.

Linux smoke benchmark, 128 MiB Java heap, synthetic valid unframed BLTE streams,
FULL chunk commits, SHA/CRC and CDN identity verification, checkpoint, and SHA-256
readback. A foreground write from another connection follows every chunk. These
are single-run feasibility measurements, not a statistically controlled comparison:

| Payload | Ingest + verification + checkpoint | Max chunk commit | Max interleaved foreground write | Verification + publication | Read + SHA |
|---|---:|---:|---:|---:|---:|
| 145 MiB | 3736.387 ms | 61.318 ms | 18.776 ms | 1126.945 ms | 129.382 ms |
| 512 MiB | 3573.856 ms | 25.311 ms | 3.837 ms | 1181.360 ms | 299.072 ms |

The verification/publication column includes the entire read-only verification
pass, not just the final write transaction. Input generation and foreground writes
are included in ingest time; initial MD5/SHA preparation for the known fixture key
is outside it. Sizes execute in order in one JVM; warmup/cache effects explain why
these numbers must not be extrapolated. Interleaved writes demonstrate release of
the writer between calls, not fairness under multiple competing bulk producers.
There were only 47 declared encoded objects above 16 MiB in the sampled inventory;
production routing/threshold decisions remain separate from this experiment.

This is an experimental schema with no migration from earlier disposable prototype
databases. Production data is never opened by these tasks.

## Remaining evaluation gates

- Extend the verified comparison to large encoded streams, realistic distributions,
  production batching and independent JVM repetitions. The verified whole-object
  and staged large-stream paths exist. Monolithic raw streaming smoke inputs remain
  unchecked; a direct staged-versus-pack large-object comparison is still needed.
- Full-inventory accounting latency, long reader snapshot/WAL retention, concurrent
  metadata writers and bounded writer occupancy measurements.
- Actual filesystem exhaustion, published-object deletion/reuse and space peaks
  including WAL. Process-kill tests do not establish physical power-loss behavior.
- Canonical ownership import at real inventory scale, global/library ownership,
  bounded acquisition queues, network/resume integration and eventual deletion rules.
- Mac/Windows native checks and explicit compatibility/migration decision before
  changing any production format. No existing C# compatibility promise is waived.
