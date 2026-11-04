# Optional prepared-row read limits

Strict source consumers can request limits before compressed blocks, selected
record arrays or decoded row values are allocated. Legacy calls and persisted
record/row formats are unchanged. These limits do not alter native issue evidence,
graph identities, producer options or membership uncertainty.

The four layers are:

- `RecordFile.ReadLimits` checks compressed and decoded block lengths from the
  directory before reading/decompressing a block, and selected record lengths
  before constructing record arrays. It applies per block/record, not to the sum
  of an arbitrary multi-row page. Skipped records are not materialized.
- `RowCodec.DecodeLimits` checks encoded bytes, value/issue counts and UTF-8 string
  lengths before their corresponding allocations. Malformed data remains corrupt;
  a valid record outside the requested policy returns `GENERATED_STORE_LIMIT`.
- `GeneratedTable.readPage` accepts both policies and uses the smaller encoded
  record ceiling before materializing records. A limit refusal never discards or
  repairs a graph.
- The optional `CapturedRows.readOrdinalPage` overload accepts
  `CapturedRowsTypes.ReadLimits`. Native captures still return at most one row per
  call. Refusal returns `LIMIT_EXCEEDED` for that call without invalidating the
  captured reader. Subsequent broader or legacy reads remain available. Custom
  providers default to an unsupported future; they never emulate this capability
  by performing an unbounded read first.

The planned World/Maps source profile is:

```java
var limits = new CapturedRowsTypes.ReadLimits(
        256 << 10, 128 << 10, 64 << 10, 128, 128, 64 << 10);
// Run on an owned worker and inspect the result state and cell limitations.
var page = capture.readOrdinalPage(firstOrdinal, 1, limits, token).join();
```

Callers must bound concurrent work and page counts independently. The profile is
not a universal table admission rule: legitimate larger rows/blocks may remain
available through existing explicit paths while the strict capability refuses
them. Pending work or refusal does not establish missing or encrypted source data.

The profile has a conservative separate 4 MiB post-open transient allowance for
compressed range copies, decoded block, record copies, bounded row strings and
issue/value objects. Opening a capture creates a prepared reader with manifest,
schema and certificate metadata; existing lower open paths can process up to
64 MiB manifest bodies and 16 MiB metadata JSON. Those are separate prepared-table
subsystem owners, not covered by the row limits or a 48 MiB texture-helper budget.
This is not a process heap/RSS limit or a pre-open metadata limit. Source consumers
must drain their single capture and account for their remaining source buffers.

Verification includes exact limits, rejection before payload I/O, UTF-8 byte
limits, decoded shape limits, unchanged native provenance, read recovery,
unsupported custom providers and cancellation/close drain. Negative controls
move the block check after I/O or remove the decoded value-count guard; the
corresponding tests fail. Restored combined checks pass.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew \
  :generated-store:check :formats-tables:check :concepts-comparison:check
```
