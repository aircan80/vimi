# Files — layer 4

`com.vibeminer.files` exposes one locale-bound file handle per opened build. It uses
layer 3 build resolution and layer 2 object access; callers never handle BLTE or CDN
keys to read files. Community names are covered separately in [NAMES.md](NAMES.md).

## Build and verify

Use JDK 25 and the root Gradle wrapper:

```sh
./gradlew :files:check :files:assemble
```

For an explicit SQLite library (including the macOS Homebrew installation):

```sh
./gradlew :files:check :files:assemble \
  -PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib
```

`check` includes unit/shared contract tests, Checkstyle and Spotless. Format with
`:files:spotlessApply`. Dependencies and plugin resolution are locked.

## Compose and use

Construct `FileSystem(Builds, ObjectSource)` or supply `FileSystemOptions`. Call
`forBuild(buildRef, "enUS", cancellation)`; an OPEN result contains `BuildFiles`.
Close each handle and stream. A handle captures immutable build lookups. Normal listings include current storage
observations; the content-only inventory described below does not. An accepted listing or opened stream can outlive
its handle; subsequent handle operations fail after close. The composition root owns
and closes the lower layers.

`stat` and `exists` never fetch content. ROOT path hashes win over community labels.
A names fallback prefers the highest ID present in the locale, then the highest ID
in the build. File variants prefer ordinary encoded content, then high-resolution,
then low-violence, then a locale variant absent from ENCODING. Listings return
ascending IDs through bounded `AsyncCursor.next(limit, cancellation)` pages; an empty
page ends the walk. Criteria combine with AND. Names are snapshotted per internal
presence batch and walked in ascending bounded pages.

`listContentInventory(FileIdRange, cancellation)` returns only immutable
`ContentFile(FileDataID, decodedSize, contentKey)` facts from those captured root/
encoding lookups. It preserves locale selection, variant ranking, ascending IDs,
ID-range filtering and bounded/cancelable pages. Decoded size stays unknown when
no encoded variant supplies it. The native implementation never queries local
object presence, community names, format detection or file payloads. Older
implementations explicitly report `UnsupportedOperationException`; callers may
choose an existing metadata-listing fallback without claiming the optimization.
Canceling a returned future only abandons that caller observation; the supplied
`Cancellation` token controls the operation. A canceled observation cannot release
the cursor for another read until the accepted worker actually drains.
Neither inventory exhaustion nor this capability certifies complete selected-locale
membership. It does not avoid scanning merely because two build identities match.

An offline read-only Forever 1.60.1.70707 measurement found the same canonical
ID/size/CKey digest for a 65,536-file prefix: existing metadata listing took
13,238 ms and requested 65,536 object-presence checks, while the content-only cursor
took 1,693 ms with zero presence/name calls. The full content cursor enumerated
1,245,599 records in 33,800 ms. These are cursor measurements on one local machine,
not an end-to-end comparison guarantee; metadata opening and result classification
are separate work. Authored tests forbid all presence/name/payload clients, compare
locale and variant results with metadata listing, and retain unknown sizes.

`get` returns a whole decoded file up to `maxReadBytes` and checks its content MD5.
`readRange` clamps to the declared decoded size before fetching and decodes only
overlapping chunks. COMPLETE/PARTIAL/PROTECTED outcomes carry maximal segments and
named encrypted holes; bytes are never filled with zeros for missing keys. Failures
carry no bytes and preserve layer 2 codes/timestamps. Owned array results prevent
caller mutation of caches or other results.

`openRead` reads chunk envelopes to report holes and all encryption key names up
front. Its `FileReadStream` reads asynchronously into a `ByteBuffer`, returns -1 at
EOF, supports absolute `seek`, and retains at most one decoded chunk. Reading stops
at the chunk boundary and throws `FileHoleException` when starting in a hole.
Failures discovered while decoding throw `FileReadException`. Stream reads are not
concurrent; read-only destination buffers are rejected. Closing clears retained
decoded bytes; hole/key metadata remains readable.

`TactKeys` is an explicit caller-supplied collection with owned 16-byte values.
Unknown keys become holes. Known keys that produce invalid inner data report WRONG_KEY.
BLTE authenticates headers and chunk MD5 before interpreting modes, supports raw,
strict single-member zlib and Salsa20 encrypted chunks, and reports unsupported
framed/ARC4 modes honestly. Whole decoded content is additionally authenticated by
its content key.

`ensure` accepts one ref, explicit refs, or a matching filter. It deduplicates selected
IDs and encoded keys, checks presence in batches and forwards priority to layer 2.
It decodes nothing unless matching requires a format filter. Explicit selections
have final totals before downloads; matching totals grow as pages are delivered.
Cancellation reconciles accepted downloads against current storage with an uncanceled
presence check and returns CANCELED rather than throwing. Progress has one callback
in flight and one replaceable pending snapshot; final progress is delivered before
completion. Subscriber failures do not fail durable outcomes.

Use `access.Cancellation` explicitly. Canceling a Java future alone does not cancel
underlying I/O. Source operations may run concurrently. The shared Files operation
admission limit is 1024; saturated admission fails immediately with
`FilesException("FILES_OPERATION_LIMIT", ...)`, including nested listing/detection
work, so parents never wait for an operation permit held by their own children.

## Bounds and qualification

Default presence batches are 4096, whole reads 256 MiB and progress intervals 100 ms.
Encoded range requests are split at 4 MiB. Chunked BLTE caps each encoded/decoded chunk
at 16 MiB, 65,536 chunks and the corresponding header size; headerless copies cap at
256MiB and must also fit the configured per-read bound to decode. Per-handle layout
LRU retains at most64 entries/262,144 chunks (newest always retained); format evidence
holds at most 1,000,000 IDs. Local format detection reads at most 64 decoded leading
bytes and refuses first chunks exceeding 1 MiB, with at most 8 detection jobs per
listing. Strong magic wins over names; weak evidence never upgrades an extension.

Public fake/real contracts cover metadata-only access, live stored state, ranges,
holes, streams, disposal, listing, ensure outcomes and progress. Additional cases pin
Salsa20 to eSTREAM vectors and test BLTE proofs, strict zlib, bad declarations,
wrong keys, admission and cancellation. `MemoryFiles` is an independent decoded-file
fake; `FilesScenario` composes the real Files implementation over lower-layer fakes.
Native Windows testing and measured large-data/live-service performance certification
remain deferred; deterministic macOS results are the current evidence.

`BuildFiles.listMetadata(filter, cancellation)` is the payload-free listing boundary for preparation
and data-only browsers. It uses build indexes, batched object presence and already available names;
it never reads encoded objects, BLTE frames or content headers, and never updates names. A known name
may provide an extension-only format hint. Without a name, format is `UNKNOWN`, even when a prior
content read cached a detected format. Format filters apply to these metadata-only hints. Existing
`list` retains its content-aware behavior. Providers that have not implemented metadata-only listing
explicitly throw `UnsupportedOperationException`; the default never falls back to sniffing content.

## Prepared immutable file catalog

`PreparedFileCatalog` stores selected-locale IDs, decoded/encoded lengths and keys,
locale flags and content flags in generated RecordFile segments. Source identity
includes product/configurations, ROOT/ENCODING, region/locale and format revision.
It uses the authenticated Build resolver, without payload, name or presence I/O.
The final manifest is published only after segments are durable. Cancelled passes
never publish a completed manifest. A compatible restore validates segment integrity
without resolving/enumerating files. Damage or identity changes cause a cache miss.
Names, inferred types and local presence remain independently changing facts.

Explicit synthetic disk measurement (not part of normal checks):

```sh
./gradlew :files:preparedCatalogBenchmark -PcatalogBenchmarkRecords=1200000 -PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib
```

Local Apple Silicon/JDK25 measurement: 1,200,000 synthetic records took 13.794s for
initial catalog+store close, 0.005s for store reopen, and 0.040s for restore with
integrity validation. Repeat enumerations and resolutions were both zero. OS file
cache was warm; this excludes real ROOT/index parsing, CDN and table/concept work.
It is algorithmic evidence, not a certified live-build performance claim.
