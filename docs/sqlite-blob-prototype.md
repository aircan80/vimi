# All-SQLite payload experiment

This experiment is isolated in `object-store/src/benchmark`. It is not linked into
production artifacts and does not open or migrate the application's stores.
The agreed candidate stores all object payloads in SQLite, including large objects.

## Schema and publication

`payloads(id INTEGER PRIMARY KEY, data BLOB NOT NULL)` contains only an immutable
payload and its row ID. `objects` holds the key, published payload reference, byte
length, SHA-256/block CRC metadata and descriptive original/derived classification. `build_objects` supplies
many-to-many build ownership. A classification does not determine retention.

An explicit write transaction allocates `zeroblob(length)`, fills it through the
incremental BLOB API, inserts metadata and ownership, and commits. Failure or
cancellation rolls back the payload and references together. Existing keys can
acquire another owner without duplicating bytes; conflicting metadata is rejected.
Readers use a deferred snapshot transaction. Handles close at the end of each
transfer, including errors. Transfers use 64 KiB Java and native buffers.

WAL and `synchronous=FULL` are explicit. Comparing NORMAL BLOB commits with fsynced
pack payloads would not provide equivalent power-loss durability. The smoke timer
includes a completed TRUNCATE checkpoint after each write.

The prototype currently expects already available local input. Holding the writer
through a CDN transfer is prohibited. An eventual acquisition implementation needs
bounded committed staging/resume and short publication transactions. This initial
single-thread-owned backend does not yet implement that scheduling.

## Commands

Use a full Java 25 JDK and the repository's normal native SQLite configuration:

```sh
./gradlew :object-store:integrationTest --tests '*SqliteBlobPrototypeTest'
./gradlew :object-store:sqliteBlobBenchmark
./gradlew :object-store:storageComparison
./gradlew :object-store:blobConcurrency
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
behind a large write transaction; bounded committed staging remains necessary
before production adoption, even though independent readers are responsive.

## Remaining evaluation gates

- Extend the verified comparison to large encoded streams, realistic distributions,
  production batching and independent JVM repetitions. The verified whole-object
  path exists, but the raw large-stream smoke path still lacks CDN verification.
- Full-inventory accounting latency, long reader snapshot/WAL retention, concurrent
  metadata writers and bounded writer occupancy measurements.
- Disk-full handling, deletion/reuse and space peaks including WAL. Process-kill
  tests do not establish physical power-loss behavior.
- Canonical ownership import at real inventory scale, global/library ownership,
  bounded acquisition queues, resumable staging and eventual deletion rules.
- Mac/Windows native checks and explicit compatibility/migration decision before
  changing any production format. No existing C# compatibility promise is waived.
