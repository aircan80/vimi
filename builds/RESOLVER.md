# Build opening and metadata resolution

This layer 3 slice ports the pinned C# `VibeMiner.Builds` build-opening path.
`Builds` is the future-returning interface; `BuildOpener` uses only layer 2
`ObjectSource` for I/O. Immutable layer 1 key/context messages are available
transitively through that interface, but the opener never calls a store or HTTP
implementation directly.

## Behavior

An open chooses a usable route from stored CDN observations, gets the build/CDN
configs, validates and decodes ENCODING and ROOT, then validates archive indexes
and the optional loose file-index. No route is required when metadata is already
stored. Source failures preserve their state, code, part and observation time.
Encrypted metadata, unsupported layouts, corrupt data and store failures remain
separate outcomes. The opener does not retry a failed source operation.

Successful opened builds are immutable. FileDataID/path lookups and locale ID
enumeration perform no I/O. ROOT keeps all memberships in file order, including
memberships absent from ENCODING. The selected EKey is ENCODING's first candidate;
encoded size falls back to the chosen archive or loose-index size when its
encoded row is absent. Archive location chooses the first configured archive,
then the lowest offset; conflicting sizes invalidate the index set.

`ObjectLedger` lazily reads ENCODING's encoded rows for downloaded figures and
jobs. Its sizes are ENCODING sizes, not cached byte lengths. The ledger is private
to layer 3.

## Formats

The pure parsers cover strict TACT configs and build/CDN declarations; whole BLTE
metadata with N/Z modes and authenticated E/F/unknown classification; EN v1 with
all page/index/specification checks; TSFM ROOT v1/v2; archive/file indexes with
footer identity, inner checksum, TOC/page hashes, ordering and padding checks.
ROOT name hashes use Jenkins lookup3 and .NET-compatible invariant simple casing,
including dotless i and non-expanding sharp s. Golden ASCII/Unicode values were
obtained by compiling the original C# `RootNameHash.cs` without modification.

This is the byte-array BLTE metadata decode path consumed by the C# opener. The
reference's internal streaming BLTE overload is not exposed here. Layer 4's
range-aware file decoding remains separate work.

## Resource and lifecycle bounds

Defaults retain four successful opens, run two build opens and four index readers,
queue at most 64 distinct opens, and read large objects in 8 MiB ranges. Maximum
encoded metadata is 512 MiB and decoded BLTE metadata is 1 GiB; ROOT separately
limits decoded bytes to 512 MiB. Parser options validate declared counts before
allocation and check explicit cancellation during reads/pages/records.

Repeated opens share one flight. Each caller can cancel its wait independently;
the shared operation is canceled only when its last waiter leaves. Failed or
abandoned opens are not cached. Completed successes use LRU eviction. Overflow
reports `BUILD_OPEN_LIMIT`; calls after close report `BUILD_OPEN_CLOSED`.
`closeAsync` drains accepted opens and index work. User continuations run off the
bounded open workers, including reentrant close calls.

Java objects/collections add overhead relative to C#'s compact value arrays; these
bounds are not a claim of equal memory/performance parity. Parsed ENCODING owns a
copy of its input and rechecks page hashes at lookup. Lookup results and metadata
are immutable snapshots.

## Checks

```sh
./gradlew :builds:test
./gradlew :builds:check :builds:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
```

The resolver's 44 fast tests require no SQLite, HTTP or .NET. They include shared
contracts against `BuildOpener` over a scripted layer 2 boundary and
`MemoryBuilds`, complete synthetic binary metadata, corruption/bounds tests,
source-state mapping, range-only reads, shared-open cancellation, FIFO admission,
LRU reuse and reentrant close. The full builds module also includes separate
catalog persistence/interop checks documented by its owner.
