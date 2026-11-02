# Unified storage performance measurements

These are local Linux/Btrfs measurements of the new services, not an end-to-end
application speed claim. Fixtures contain synthetic data and run in ignored,
isolated directories. Existing application data was never opened by the harnesses.
The application still uses its existing stores pending preservation and adoption.

## Accounting at build scale

```sh
./gradlew :object-store:libraryAccountingBenchmark
# Optional small smoke fixture:
./gradlew :object-store:libraryAccountingBenchmark -PaccountingBenchmarkObjects=10000
```

The full fixture has 2,209,282 expected EKey references per build, two canonical
builds with 90% overlap, 220,930 stored-object metadata rows for the selected build,
128 derived refs, 128 global/provider refs and a duplicate source with 512 refs.
There are no payload chunks: this measures ownership/presence queries, not ingest
or integrity. The database was about 724 MB. Seeding uses bounded set-based SQL,
not producer API ingestion, and is not counted as production import throughput.

The initial reporting query took 7.2–8.8 seconds. That run changed owner cardinality
between rounds, so it is only indicative and is not an exact speedup baseline.
A controlled follow-up fixture uses stable owners and three runs per condition:

| Query | Without ANALYZE median | With ANALYZE median |
|---|---:|---:|
| One aggregate for both roles; sharing checks only for stored objects | 2.763 s | 2.856 s |
| Stream largest source; deduplicate only secondary sources | **2.237 s** | **2.297 s** |

The selected query reduces latency about 19–20% versus that controlled intermediate
query, with identical counts. It relies on SQLite 3.35+ and does not cache stale
presence or require ANALYZE to achieve these results. The comparison uses separate
runs of the same deterministic fixture, not interleaved randomized independent JVMs.

Concurrent metadata reads remained 0.4–5.6 ms; catalog writes 0.24–1.33 ms. This
shows reporting does not reserve the writer or monopolize the payload read workers.
It does not establish behavior under heavy concurrent CDN ingestion or many builds.

Full-inventory figures remain a roughly 2.2-second **background refresh**. The UI
must show its prior completed snapshot while a coalesced refresh runs, and must not
wait for this query on navigation or block interactive work. First-open/refresh,
many-build sharing and actual producer import costs remain adoption measurements.

## Actual object backend versus packs

```sh
./gradlew :object-store:productionStorageComparison
```

This runs the actual `SqliteObjectStore` against `ObjectStore`, rather than the
previous experimental BLOB class. Both receive the same verified inputs, one put
at a time, 32 objects per size, three rounds with alternating backend order.
Write timings include setup and acknowledgements; SQLite also completes a TRUNCATE
checkpoint before the timer ends. SQLite uses FULL commits; the existing pack
engine retains fsynced payloads with recoverable NORMAL metadata. Read timings
include complete returned-byte comparison. Physical sizes are measured after close.

Final warm rounds 1–2, milliseconds for all 32 objects (ranges are two observations,
not confidence intervals), with a 512 MiB Java heap:

| Object size | SQLite writes | Pack writes | SQLite reads | Pack reads |
|---|---:|---:|---:|---:|
| 64 KiB | 62.4–67.3 | 56.7–65.2 | 6.8–7.2 | 9.1–9.6 |
| 128 KiB | 49.3–69.2 | 40.2–53.0 | 8.0–9.9 | 10.2–12.1 |
| 512 KiB | 95.9–108.0 | 89.9–90.0 | 22.8–25.4 | 20.9–26.6 |
| 1 MiB | 162.7–162.8 | 118.3–120.3 | 45.7–45.7 | 33.7–37.1 |

Small verified objects up to 1 MiB use one FULL publication transaction; the reader
workers reuse bounded connections. The earlier three-commit whole-put path was
slower and is no longer used for these objects. Larger and streaming writes retain
bounded staging. Arbitrary network fragments fill fixed payload rows; they do not
create one row per HTTP read.

The results are mixed: small reads improve on this workload, small writes are
competitive, and 1 MiB payload writes/reads remain slower. WAL/checkpoint write
amplification and durability work must not be hidden by weakening synchronization.
These tiny warm-cache runs do not establish cold reads, group-commit throughput,
real CDN traffic, or generated 8 MiB record-segment build performance. Other host
work can affect measurements; earlier rounds showed variation in both backends.

At 1 MiB/object the final SQLite files occupied 33,665,024 bytes versus 33,588,288
bytes for packs. These figures exclude the benchmark's in-memory input buffers.

## Adoption criteria

The architectural benefit is unified physical presence and ownership, independent
reporting readers, and simpler accounting. Application performance improvement is
an acceptance criterion, not an assumption following from using SQLite. Before
changing defaults, compare actual Overview navigation/refresh, preparation,
generated segment writes, foreground item/file reads and background downloads.
Keep relational query projections behind domain services, preserve saved intent,
and retain the old stores until activation and recovery tests pass. See
[the rollout plan](unified-storage-rollout.md) and GitHub story #69.
