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
Close each handle and stream. A handle captures immutable build lookups and checks
current storage on every operation. An accepted listing or opened stream can outlive
its handle; subsequent handle operations fail after close. The composition root owns
and closes the lower layers.

`stat` and `exists` never fetch content. ROOT path hashes win over community labels.
A names fallback prefers the highest ID present in the locale, then the highest ID
in the build. File variants prefer ordinary encoded content, then high-resolution,
then low-violence, then a locale variant absent from ENCODING. Listings return
ascending IDs through bounded `AsyncCursor.next(limit, cancellation)` pages; an empty
page ends the walk. Criteria combine with AND. Names are snapshotted per internal
presence batch and walked in ascending bounded pages.

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
