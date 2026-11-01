# Parallel verification measurements

Run the opt-in workload with a full Java 25 JDK:

```sh
./gradlew :object-store:benchmark -PbenchmarkMode=write -PbenchmarkObjects=16 -PbenchmarkBytes=4194304
```

The workload builds deterministic owned inputs before timing, admits 16 concurrent
4 MiB puts, waits for committed results, records peak verification concurrency,
then rereads and byte-compares every payload. Temporary stores are removed. The
benchmark reports no user paths or machine names and never runs as part of test/check.
The default benchmark mode also measures reads, index rebuild and compaction;
maintenance resource evidence is tracked in #19.

Two illustrative Linux ARM64 runs on Java 25.0.5 with four reported processors:

| Payload | Objects | Elapsed write seconds | MiB/s | CPU workers | Observed active peak |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4 MiB | 16 | 0.085702 | 746.777 | 2 | 2 |
| 4 MiB | 16 | 0.236472 | 270.645 | 2 | 2 |

Filesystem caching and concurrent machine load affect elapsed values. Use identical
workloads and environment for comparisons. The observed peak confirms real CPU
preparation overlap within the configured half-CPU bound; byte comparisons confirm
unchanged stored payloads. Normal correctness tests impose no throughput threshold.
