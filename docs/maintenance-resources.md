# Maintenance resource bounds and benchmark baseline

Replay keeps its merge heads in a SQLite FILE temporary table instead of retaining
one channel and heap node per pack. Compaction spools candidate ordering and moved
keys/offsets into FILE temporary tables. Saved read days also use an indexed
FILE temporary table. Temporary tables are removed after success or failure.

| Resource | Bound |
| --- | --- |
| Pack discovery, listed/corrupt pack identifiers | Fixed 125,000-byte bitmap each; IDs 1–999,999, lazy paths |
| Replay retained pack channels | Configured `packHandles` (default 64) |
| Replay cold acquisition | One additional channel while opening before idle eviction |
| Compaction pack copy | Two channels, source and target |
| Pack metadata and moved-key Java pages | At most 256 rows per page |
| Compaction candidate Java metadata | One candidate |
| Entry streaming buffer | 64 KiB |
| SQLite temporary page cache | 2 MiB target (`temp.cache_size=-2048`, FILE storage) |
| Open diagnostic strings | 99 details plus an explicit omission marker |

The cache target does not promise a hard bound on SQLite native allocations or
whole-process RSS. SQLite temporary tables and sorting can consume temporary disk
space proportional to the input. Regular concurrent reader leases additionally
follow the channel-cache retirement/overflow bounds described in the README;
replay releases each lease before taking the next. Resource test observations
measure retained handles after acquisition, so exclude the transient cold-open
scratch handle. Public verification reports retain complete corrupt/lost key
lists: their returned results can grow with the number of errors. Caller-retained
objects/results and payload buffers are outside maintenance's internal bounds.

The regression suite exercises 300-pack replay with a two-channel cache,
1,025-object compaction across multiple move pages, 270-pack rebuild/validation,
diagnostic truncation, temporary-table failures and consistent read snapshots
while a writer rolls packs. Existing crash tests continue to cover durable
switch ordering.

## Opt-in reproducible benchmark

Run with the documented JDK/Gradle setup:

```sh
./gradlew :object-store:benchmark -PbenchmarkObjects=64 -PbenchmarkBytes=65536
```

The benchmark generates deterministic unique objects, verifies every read, rebuilds
the index, deletes half the objects, then scans and compacts. It reports environment
metadata and per-phase elapsed time/throughput without paths or hostnames. Async
writes use a bounded producer window of 64 requests. Set `benchmarkMode=write` for
write-only measurements (payloads are still reread and verified). Supported sizes
are 2–4,096 objects, 4 bytes–16 MiB each, total at most 256 MiB.

Benchmarks never execute during unit tests or `check`; those tasks compile and
format-check benchmark sources. These are synthetic smoke baselines, not steady
state device throughput or cross-platform performance claims. Compare repeated
runs with the same JDK, hardware, filesystem and arguments.

Baseline measured on 2026-11-01 with JDK 25.0.5, Linux aarch64, four available
processors, 64 × 65,536-byte objects. Three independent JVM runs:

| Phase | Trial 1 MiB/s | Trial 2 MiB/s | Trial 3 MiB/s |
| --- | ---: | ---: | ---: |
| Write | 64.168 | 66.044 | 67.243 |
| Read | 125.364 | 105.888 | 115.336 |
| Rebuild | 154.183 | 174.181 | 152.446 |
| Compact | 398.517 | 420.585 | 409.092 |

All runs observed two simultaneous verification batches with two workers, and
verified the synthetic payloads. Compaction reports source bytes scanned (4,201,024),
including headers/dead entries; other phases report payload bytes (4,194,304).
These short trials include cache/JIT/filesystem effects and establish a reproducible
starting point; they do not establish a speedup over another implementation.
