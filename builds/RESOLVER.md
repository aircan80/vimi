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

Large metadata is not restricted to layer 2's whole-read limit. When layer 2
returns PRESENT with a known length and no whole byte array (the normal shape for
objects over its default 64 MiB whole-read boundary), the opener acquires bounded
ranges and opens the metadata normally, up to its configured metadata bounds.
This is the same public path as pinned C# `BuildOpener.ReadAsync`: it allocates a
bounded encoded array, fills it with ranges, and passes that array to BLTE decode.
C#'s byte-array BLTE overload itself wraps an input MemoryStream and collects the
decoded bytes; its separate internal stream overload is not used by public build
opening. There is no additional large-metadata capability missing because of that
overload. Neither implementation's public opener claims to avoid retaining the
full encoded metadata array; streaming it would be a memory optimization, not a
prerequisite for opening supported large metadata. Layer 4's range-aware file
decoding remains separate work.

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

The resolver's 49 fast tests require no SQLite, HTTP or .NET. They include shared
contracts against `BuildOpener` over a scripted layer 2 boundary and
`MemoryBuilds`, complete synthetic binary metadata, corruption/bounds tests,
source-state mapping, range-only reads, shared-open cancellation, FIFO admission,
LRU reuse and reentrant close. Five additional public-path tests model a configured
whole-read boundary smaller than the synthetic metadata: build config, CDN config,
ENCODING, ROOT, archive index and file-index all open through exact bounded ranges.
They verify request identity/declared length/interactive priority, no full encoded
body returned by layer 2, truncated-range refusal, failed range-proof propagation,
aggregate BLTE authentication and cancellation while a range is pending. The
boundary is deliberately small to test the same public result shape without a
large resident fixture; it is not a measurement of production-size memory use.
The full builds module also includes separate catalog persistence/interop checks
documented by its owner.
