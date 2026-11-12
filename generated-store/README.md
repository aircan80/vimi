# Generated store (layer 1)

Java port of the pinned C# `VibeMiner.Generated` component. It contains the
`GeneratedStore` boundary, `DiskGeneratedStore` adapter, and VMRF format 1 record
files. Its implementation depends on `object-store`; its public API exposes no
object-store or compression implementation types. Consumers use Gradle
`implementation project(':generated-store')` and do not need an object-store
compile dependency.

`DiskGeneratedStore` opens a separate GENERATED-kind pack store, normally in
`generated-v1/`, with 256 MiB packs. Existing keys keep their first bytes. Puts
validate the entire batch (up to 4096 objects, each 1–64 MiB), split at the engine's
256 MiB per-put bound and commit each part before completing. Earlier committed
parts survive a later failure/cancellation; retrying the batch is safe. Deletes
split into 4096-key parts. Stats, compaction and asynchronous close delegate to
the same durable engine used for CDN data. Store errors are translated to C#'s
`GENERATED_STORE_*` codes; damaged or missing indexed bytes read CORRUPT.

## Build and checks

Install a full Java 25 JDK and SQLite as described in the root README. Gradle
installs pinned, locked Java dependencies automatically, including zstd-jni.

```sh
./gradlew :generated-store:test
./gradlew :generated-store:check :generated-store:assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
./gradlew check assemble -PsqliteLibrary=/absolute/path/to/sqlite-library
```

The test-fixture variant publishes `MemoryGeneratedStore` and
`GeneratedStoreContract`; the same contract tests run against memory and disk.
Fast tests use no SQLite. They exercise compression using the native
zstd library bundled in the dependency JAR. Integration tests use isolated stores.

Hosted unit and integration CI runs on Linux, Windows and macOS.

## Use

All I/O returns `CompletableFuture`; never call `join()` on the JavaFX thread.
The following is an example for a background task:

```java
DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
try {
    GeneratedKey identity = GeneratedKey.hash(identityBytes);
    try (RecordFileWriter writer = new RecordFileWriter(store)) {
        writer.add(42, recordBytes).join();
        writer.complete(identity, metadataBytes).join();
    }
    RecordFile.Open opened = RecordFile.open(store, identity).join();
    if (opened.state() == GeneratedTypes.ReadState.PRESENT) {
        RecordFile file = opened.file();
        var page = file.read(0, 100).join();
        var record = file.find(42).join();
        try (RecordFile.Cursor cursor = file.readAll()) {
            while (true) {
                var batch = cursor.next().join();
                if (batch.isEmpty()) break;
                // Consume this batch before requesting another.
            }
        }
    }
} finally {
    store.closeAsync().join();
}
```

Use the generated module's `Cancellation` token to cancel operations explicitly;
canceling a future is not a substitute. Byte-array inputs/outputs are owned copies.
IDs are unsigned 32-bit integers represented by Java `long` (0–4294967295).

## Record files and bounds

The format follows C# `RecordFileFormat.cs`: little-endian VMRF header, compressed
manifest directories, zstd blocks with content size/checksum, 1024-entry sorted
ID chunks, and content-addressed segment objects. The four reserved bytes in each
28-byte block directory entry are retained. Different compressor versions may
produce different compressed bytes/segment keys; readers accept either.

Records keep insertion order; the sorted index resolves ID lookup separately.
A page reads only its blocks and returns at most 65,536 records. `findAtOrAfter`
finds the lowest ID greater than or equal to the requested ID. A cursor returns
one decoded block per `next()` and coalesces contiguous compressed reads up to
256 KiB, except when a single block exceeds that size. Manifest directories stay
in memory. All structures are validated before use; malformed manifests open as
CORRUPT, and malformed blocks/indexes throw `GENERATED_CORRUPT`.

Defaults: 4,194,304 records, 1 MiB per record, 16 MiB metadata, about
16 KiB per block, 8 MiB per segment, zstd level 9. Options validate these
bounds. Writer memory includes a block, a segment, compressed frame scratch,
4 bytes per record for IDs, an additional 8 bytes per record while sorting, and
an additional duplicate-ID set when input is unsorted. Java copies and collection
objects add overhead. This is bounded by options, not a constant-memory writer.
Compression/record work uses a shared pool of max(1, min(8, CPUs/2)) workers and
64 queued operations. Overflow reports `GENERATED_STORE_LIMIT`; no worker pool is
created per file. Future continuations run outside those workers. Each writer and
cursor permits one in-flight call; readers support concurrent independent calls.

Segments commit before the manifest, which is the publication point. Abandonment,
cancellation or failure can leave unreferenced segments, but no readable partial
file. Invalid arguments permit retry; failure after mutation finishes the writer.
An existing manifest wins and is reported by `manifestWasPresent`.

Segments can be shared by multiple manifests. Do not delete `file.keys()` as
ordinary file deletion without checking other live references. There is no
reachability collector in this component, matching the reference scope. Higher
layers decide identities, encode records, regenerate corrupt data and manage
cache retention. Nothing here downloads data or interprets table/game semantics.

## Compression provenance

The locked dependency `com.github.luben:zstd-jni:1.5.7-6` supplies JNI bindings and
architecture-specific native zstd binaries. Supported runner architectures are
exercised by CI; unsupported native environments require separate validation.
Unlike SQLite, zstd does not need a separately installed system library. The
module does not shade those binaries into its own JAR. Keep the dependency JAR
and notices when distributing. BSD 2-Clause binding and BSD 3-Clause zstd notices
are included under `src/main/resources/META-INF/` and in the built library JAR.


`DerivedDatabase` provides an additive asynchronous SQLite actor for disposable
query projections. Concept modules own their schemas and typed queries. It reuses
the object-store native SQLite adapter without changing object-store or generated
record schemas. Its bounded queue serializes work; scoped sessions cannot escape
the actor, transactions roll back on failure, explicit cancellation interrupts
native work, and close drains accepted requests before releasing the connection.
