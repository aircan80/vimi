# Object access (layer 2)

`com.vibeminer.access` ports the seven-layer C# object-access boundary. Layer 3
uses `ObjectSource` rather than directly opening stores or sending network requests.
Immutable layer 1 keys, history messages and route/location values are exposed through
Gradle `api` dependencies, matching the reference boundary.

## Build and checks

Install JDK 25 and SQLite 3 using the repository setup instructions. From the repository root:

```sh
./gradlew :object-access:check :object-access:assemble
```

If SQLite is not available under the platform loader's normal library name, supply its
absolute path, for example on Apple Silicon Homebrew:

```sh
./gradlew :object-access:check :object-access:assemble \
  -PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib
```

`test` runs independent fake/real-memory contracts and deterministic scheduler, CDN,
range, staging, cancellation and adapter cases. `integrationTest` runs the same public
contracts against a real disk store. `check` includes both suites, Checkstyle and
Spotless. `spotlessApply` formats Java. Dependency locks are committed.

## Composition and public contracts

Construct `CachingObjectSource` with a local `ObjectReader`, `ObjectWriter`,
`HistoryLog`, optional read-only shared store, `CdnClient` and observation adapters.
The expanded constructor accepts verifier/options and an optional separate interactive
CDN client. `localOnly(reader, history)` and `cdnOnly(client, adapters)` provide the
reference standalone modes. The composition root owns the underlying stores/clients;
`closeAsync()` drains accepted source work without closing those dependencies.

- `getKey` returns verified whole bytes when within the configured whole-read limit;
  large stored objects may return presence/length for subsequent bounded `getRange`.
- `getKeys` returns ordered, payload-free results. Local presence is an index-only
  fast path: length/origin may be null. A returned request-declared length on this
  path is not an independently measured value. Whole/range reads supply measured
  metadata. `hasKeys` and `countPresent` also remain index-only.
- Resolution checks local, then shared, then CDN. Successful shared/CDN data is
  verified and committed before publishing presence and stored-key notifications.
  Damaged local entries are removed before repair. Shared read/verification refusal
  falls through to CDN; a partial shared stage is abandoned before fallback.
- Archive locations are planned into merged range transfers. Every member is verified
  independently; damaged members retry loose while successful siblings remain usable.
  Large objects stream through bounded staging and reuse only verified persisted
  prefixes across host failures. Authenticated BLTE range reads can avoid whole-object
  download, including objects exceeding the stored-payload limit.
- `observe` adapts versions/CDNs/BGDL/summary and listfile clients; optional
  history recording and `record` validate canonical provenance before appending.
  `readHistory` exposes durable history.
- `StoreCommits` subscriptions report stored/removed keys after durable mutations.
  Notifications are process-local. A bounded subscriber that falls behind receives a
  missed flag and must reconcile; it cannot stall a writer.

`Cancellation` supports exactly-once listener registrations, isolates listener failures
so remaining listeners still run, and forwards cancellation to layer 1. Concurrent
same-key callers share work while each caller can stop waiting independently. Canceling
the final waiter stops its transfer. Canceling a returned Java future alone does not
replace the explicit cancellation token or release the close fence prematurely.

## Scheduling and bounds

Interactive and background queues are bounded independently, with reserved interactive
streams and adaptive background concurrency. Batch coordinators do not occupy a worker
while child transfers run; one batch can fetch multiple loose objects/archive ranges
concurrently, even with one background stream and one queue slot. Object order barriers
are preserved. Background work learns HTTP/2 host preference; interactive work preserves
advertised order. Host cooldown excludes not-found and range-mismatch replies.

Options bound pending batches, streams, queued tasks, estimated in-flight bytes and
whole-read/range sizes. Unknown-length transfers reserve an estimate; a reservation
larger than its byte budget runs alone, so these are admission controls rather than an
exact heap-accounting guarantee. Streaming buffers are bounded independently. The BLTE
table cache holds at most 16 entries/8 MiB; host metadata holds at most 256 entries.
Progress callbacks execute outside transfer workers and cannot fail committed results.

## Qualification and differences from the C# implementation

Core local/shared/CDN selection, verification, staging/resume, archive merge/member
fallback, range proof, observation/history, commits, scheduling, cancellation and
shutdown paths are implemented and covered by deterministic tests. Shared contracts
run against an independent fake, real memory composition and disk composition.

Presence queries currently visit keys sequentially rather than the reference's eight
lanes. Verified deliveries use individual durable puts rather than the reference's
4096-item delivery sink; the underlying store still groups concurrent writes. These
are throughput differences, not a claim of measured performance parity. Host metadata
is intentionally bounded where the reference map is unbounded. Real-world large-data
performance and live-service certification remain future work. Native Windows testing
is deferred by project decision; macOS deterministic checks are the current evidence.
