# Native prepared-row issue certificates — design proposal

Status: both independent architecture and WoW-data reviewers approved exact
contract `09533d58b41e825999ce392c4317e4ba3b6a60a9` before implementation.
Based on `cfaa297`; root integration also includes reviewed Items increment A. This proposal extends prepared-local input
evidence; it does not authorize persistent comparison reports, acquisition,
cache repair during comparison, or an upgrade of existing graphs.

## Intended behavior

A newly prepared graph produced by the audited native DB2 reader can attest that
its issue detector ran for every returned physical/copy row. At the captured-row
boundary, a null native issue list may then become an explicit empty list. This
makes a valid localized string assessable instead of losing it solely because
the native decoder represents "no detected issues" as null.

The certificate does **not** attest that the table is complete, that all encrypted
sections were decoded, that a missing row is absent, or that the selected locale
contains every name. An explicit invalid text or absent-relationship issue stays
an issue. Readable cells of PARTIAL tables remain usable independently.

Generic producers and old graphs retain the current distinction between null
and an explicitly empty issue list. Reader revision, row codec version, aggregate
counts, a successful read, and EOF cannot upgrade that distinction.

## Audited path and limitations in the current implementation

All paths below are within `formats-tables/src/main/java/com/vibeminer/formats/`.
The references describe inspected code, not proposed changes already present.

| Current symbol | Observation and implication |
| --- | --- |
| `db2/Db2Reader.java`, selected block binding and final `new Db2Table(...)` | The actual selected DBD block, flattened columns and reader limits are known here. This is the appropriate place to mint native-origin evidence. |
| `db2/Db2Table.java`, `decode` | Enumerates bound cells, records `DB2_RELATION_ABSENT`, and returns null when its issue list is empty. Sparse invalid UTF-8 can become `DB2_TEXT_ENCODING_INVALID` with raw evidence when enabled. |
| `Db2Table.decodeCopy` | Calls the same decoder for resolved copy rows with target/source identities; the certificate must cover this path too. Protected and unresolved copies are not silently synthesized. |
| `Db2Table.stringAt` | Nonsparse invalid UTF-8 currently throws; the certificate must not promise recovery of these cells. A failed row/build is not a certified successful row. Changing that recovery policy is separate work. |
| `tables/ClientBuildTables.java`, constructor and `buildContent` | Enables partial encrypted sections and raw text evidence in effective limits, opens the real source/reader, then passes `table::readOrdinals` to generated storage. The effective limits, not caller defaults, matter. |
| `generated/GeneratedTableContent.java` | Public record accepts arbitrary `Rows` callbacks. A public boolean or policy string added to this constructor would not distinguish native from custom producers. |
| `generated/GeneratedTableSource.java` | Current v2 identity contains locale/CKey/resolved-block/options/reader/row-codec, plus optional encrypted encoding scope. There is no native-producer policy domain. |
| `generated/GeneratedTables.java`, `storeCore` | Generic rows are stored through `RowCodec`; metadata/completion are assembled before enumeration. Completion counts do not certify issue detection, and are not the finalized emitted-row count. |
| `GeneratedTables.openFile` and `savedArtifacts` | Check identity, keys and graph integrity; ordinary reads can repair, prepared-local reads cannot. Both validation paths must understand the new native metadata format. |
| `generated/GeneratedTable.java`, `readPage` | Decodes saved row bytes without altering nullable issues. Leave this generic reader unchanged. |
| `tables/ClientBuildTables.capturePreparedLocal` | Selects local definition/block, freezes effective key fingerprints, opens one saved graph without repair, and constructs the receipt. This must report the actual chosen native or legacy graph. |
| `tables/CapturedRowsHandle.java`, `read` / `bound` | Reads one ordinal at a time. Caps cell values and issue evidence; uses `CellIssue` at column -1 when the issue channel is truncated. This is the normalization boundary. |
| `generated/RowCodec.java` and `db2/Db2Types.Row` | Preserve null versus empty issue channels explicitly. These types, flags and bytes remain unchanged. |

The certificate is an application integrity/provenance contract, not a digital
signature against an attacker who can rewrite the application and all cached
objects. The existing generated-store content/integrity checks still apply.

## Concrete API contracts

Names below are proposed APIs and must be reviewed before implementation.

### 1. Native-origin capability, without changing parser row DTOs

Add `db2.NativeRowIssueOrigin`, an immutable final class with **no public
constructor**. Only the audited `Db2Reader` construction path can mint it; a
package-private constructor is sufficient for this component's trusted-code
boundary. `Db2Table` is already final and its constructor is package-private.
Expose `Db2Table.nativeIssueOrigin()`.

The compact origin contains:

- `policyId = "native-row-issues-v1"`;
- `producerId = "java-db2-native-producer-v1"`;
- selected resolved-block SHA-256, computed from the actual selected block;
- effective reader-options SHA-256, computed from the actual limits used;
- ordered actual-column fingerprint, including interpretation and narrowing;
- actual schema version/table hash/layout hash/format and flattened column count.

No `Rows` callback, generic `Row`, user revision string, `Completion` value or
external API can mint this capability. The origin retains only bounded primitive
values/digests, not a full DBD document or schema. Use existing
`ResolvedDefinition.hash` and the existing reader-options serialization exactly;
extract a shared options-fingerprint helper if necessary, with byte-compatibility
fixtures. Do not invent a second definition/options hashing implementation.

This adds private native decoder metadata, not a property to `Db2Types.Row`,
`TextIssue`, `Header`, or the C# parser interchange format. Direct native parser
reads still return their existing nullable issue channel.

### 2. Generated content with separate trusted and generic construction

Replace the public `GeneratedTableContent` record with a final value class that
preserves its current public eight-argument constructor, accessor methods and
generic value equality/hash behavior. There are no repository record-pattern
consumers to migrate; the representation change must still be called out in API
release notes.
That constructor always sets its internal issue policy to UNKNOWN. Existing
custom callbacks remain supported with unchanged row semantics.

Add this audited factory:

```java
static GeneratedTableContent nativeDb2(
    GeneratedTableSource source,
    Db2Table table,
    List<String> keysUsed,
    AutoCloseable owner,
    List<String> holeKeys,
    List<String> chunkKeys);
```

The factory takes header/sections/completion and rows exclusively from that
`Db2Table`. It does not accept replacement rows, a replacement header, or a
caller-supplied certificate. It verifies the native origin against `source` and
actual columns, then installs a private origin seal and `table::readOrdinals`.
`source.definition`, effective options, expected native policy and origin must
match. CKey/source ownership remains established by the existing native
`ClientBuildTables.buildContent` file-opening path; origin evidence does not
independently authenticate a content hash of arbitrary caller-provided bytes.

Ownership remains the current contract: factory failure leaves cleanup to its
caller; successful returned content transfers owner cleanup to `store`, which
closes it on success, already-present hits, cancellation and failure. The owner
continues to close source, file handle and scheduler slot exactly once.

`GeneratedTables.storeCore` validates source-policy/content-seal agreement
**before** any already-present fast return or graph deletion. A generic content
callback cannot publish under a native-certified source. Existing certified hits
returned by `getOrBuild` are safe because their metadata is validated independently.

### 3. Source identity, certificate and immutable capture receipt

Add `GeneratedTableSource.IssuePolicy { UNKNOWN, NATIVE_V1 }`, with the existing
five-argument constructor and `forBlock(...)` explicitly retaining UNKNOWN.
Add `forNativeBlock(...)` for the audited native preparation path. It requests
a policy; it does not itself certify a graph.

UNKNOWN keeps **exactly** the current v2 identity text, reader-options digest,
node/metadata format and `rows-v1` bytes. Add a separate native identity domain:

- source identity format `vibeminer-generated-table-native v1`;
- node format `vibeminer-generated-table-native-node v1`;
- metadata format `vibeminer-generated-table-native-metadata v1`;
- policy and producer IDs are part of the source's base identity;
- existing locale/CKey/resolved-block/options/reader/row-codec components and
  optional encoding scope remain included;
- existing effective-key chain hashing and graph-node derivation operate on that
  distinct base identity, so native and generic graphs cannot alias.

Specify native identity serialization as a versioned, fixed-order tuple of
UTF-8 length-prefixed strings (explicit null tag, otherwise unsigned32 byte
length and bytes; integers big-endian). Do not change the legacy serialization.
Pin a fixture for native identity bytes and node derivation; do not regenerate
legacy C# goldens to bless an unrelated identity change.

Native metadata adds a `NativeIssueCertificate` with these exact fields:

```text
format = native-row-issue-certificate-v1
policyId, producerId
sourceIdentitySha256, graphIdentity
resolvedBlockSha256, readerOptionsSha256, actualColumnsSha256
flattenedColumnCount, emittedRowCount
```

Strings are fixed known IDs or canonical hashes; the certificate is bounded to
4 KiB encoded / 8 KiB retained. `graphIdentity` is the actual final key-chain node,
not an alias node. The selected native source identity and effective-key chain
therefore bind locale, CKey, block, options, reader, encoding and key fingerprints.
`sourceIdentitySha256` hashes the actual selected native identity serialization:
use the unscoped identity tuple for an unscoped graph, and the scoped tuple with
its actual `encodingIn` for a scoped graph. Never hash the preferred request's
non-null `source.encodingScope()` merely because the requesting build supplies
one. The stored identity's scoped/unscoped mode and value are authoritative.
Unencrypted graphs shared across builds with identical source/definition/options
must validate in the unscoped domain without accidentally binding a build-specific
encoding scope. The receipt still records its actual requesting build separately.
No raw key material enters the certificate.

The store creates this certificate only after successfully enumerating and
writing the sealed native row stream. It uses the actual emitted count, not
pre-enumeration `Completion`. Existing metadata completion remains descriptive
and does not become a completeness proof. A failure publishes no native node.

Before publication, each native returned row must have exactly the origin's
flattened width, a valid unsigned32 ID/copy source, and well-formed issue locations
within that width. Invalid structure or exhausted native evidence limits fails
the native build with a typed parser/store error; never silently drop issues and
still certify that stream. Known decoder issues are preserved; a future unknown
issue code is not reclassified as valid text.

The certificate travels with that graph's RecordFile manifest. `openFile` validates
policy/producer allowlist, expected source identity, final node identity, actual
header-column digest/width and `file.count()`. `savedArtifacts` applies the same
metadata validation. Mismatch is CORRUPT; do not strip the certificate and pretend
the new native graph is a legacy graph. Ordinary repair behavior remains confined
to ordinary reads. Prepared-local failure does not delete or rebuild anything.

`GeneratedTable.issueCertificate()` returns immutable verified evidence or UNKNOWN
for legacy/generic graphs. It must not expose unvalidated parsed metadata.

Add `CapturedRowsTypes.IssueChannelEvidence` to `Receipt`:

```text
state = UNKNOWN | VERIFIED_NATIVE
policyId?, producerId?, graphIdentity?, sourceIdentitySha256?,
resolvedBlockSha256?, readerOptionsSha256?, actualColumnsSha256?
```

Keep the existing receipt constructor as an overload producing UNKNOWN for custom
providers/test doubles. `ClientBuildTables.capturePreparedLocal` copies only
validated graph evidence, and cross-checks receipt graph/source/block/options/
columns against the exact captured handle. Unsupported future policies cannot
promote null channels. A known native-format mismatch fails capture; an absent
legacy certificate is simply UNKNOWN.

## Bounded read semantics and independent cells

After raw saved-row decoding, `CapturedRowsHandle.bound` first applies existing
value and evidence bounds. Null-to-empty normalization is allowed only when:

1. the receipt has validated supported native evidence for the retained graph;
2. the page belongs to that same graph and the row has the captured width;
3. the original native row's issue channel is null;
4. no whole-row issue-channel limitation/invalidation was introduced.

Then return a new `Db2Types.Row` with the same ID, copy metadata and values, and
`List.of()` as issues. Generic `GeneratedTable.readPage`, `RowCodec.decode`, the
saved bytes and direct parser reads stay unchanged.

An explicit nonempty issue list remains explicit. The capture issue budget is
currently 65,536 retained bytes per row; truncating it retains the bounded prefix
and emits existing `CellIssue(ordinal, -1, LIMITED,
"CAPTURE_ISSUE_CHANNEL_LIMIT")`. This **overrides** any receipt-level native
certificate for that row. The effective row issue channel is LIMITED, matching the existing
`RawTableSchema` / `PreparedItemProjection` contract. A typed per-cell LIMITED
state applies only to a column actually bounded/truncated. An unaccounted TEXT
binding or relationship-dependent binding remains UNKNOWN when its issue channel
is incomplete; do not infer valid text or a valid NONINLINE relation placeholder
from an empty truncated prefix. Ordinary numeric bindings that do not depend on
that relationship/issue channel may retain independently verified VALUE facts.
This is not a blanket limitation of all cells. Null stays null when such an
override prevents normalization.

A value truncated at a particular column receives its own LIMITED override;
other valid cells survive. A broken row shape or invalid issue location is a
structural source failure/invalidation, not an excuse to certify shifted columns.
Cancellation, logical deletion, missing/corrupt chunks and parent close retain
current drain/invalidation semantics and NOT_CERTIFIED membership.

Keep existing one-row short pages, cell/row budgets and native parser budgets.
This small certificate is charged to the source receipt/profile allowance, then
only its digest/policy IDs enter compact comparison snapshot metadata. It does
not enlarge the 32 MiB comparison allowance or claim to bound the native parser's
separate allocations. Unknown/custom rows are never scanned again to "prove"
that all their issue lists happened to be empty.

## Fingerprints and downstream projection

`actualColumnsSha256` hashes a fixed-order length-prefixed sequence of column
index, name, kind, ID flag, nullable `Interpretation` (all its existing fields)
and nullable `Narrowing` (both fields). No `toString`, map iteration order, or
header record count participates. Names are encoded strictly as UTF-8.

The captured source identity/evidence digest includes the issue policy/version
and whether the original graph was certified. The raw table input descriptor and
Items source metadata expose this once per dependency as interpretation/evidence
scope. Certificate UNKNOWN/VERIFIED_NATIVE state, policy/producer IDs and certificate
digest belong to source/evidence identity only. They are excluded from the raw
semantic schema/column-admission fingerprint and the Items identity/field-mask
admission fingerprint. Otherwise, pairing an old graph with a new graph would
incorrectly exclude the entire dependency instead of comparing its valid facts.
Item projection receives the effective explicit row issue channel plus LIMITED
overrides; it does not invent a new meaning for null itself.

A policy transition is an interpretation/evidence-availability change, not a
Blizzard gameplay change. Known name versus old unknown name must remain
INDETERMINATE rather than a fabricated added/changed name. Certified known-empty
text differs from unknown and from invalid-placeholder empty text. Existing
per-cell classification keeps `DB2_TEXT_ENCODING_INVALID` INVALID,
`DB2_RELATION_ABSENT` UNKNOWN, and unknown issue codes UNKNOWN. No membership
certificate is introduced, including for tables reporting READY or complete.

## Native/legacy lookup and normal preparation migration

Update audited `ClientBuildTables` source selection consistently, not just the
capture method. New native decodes/builds publish only in the native domain.
Normal preparation and prepared-local capture select in this order:

1. the exact native source with current supported policy;
2. if that graph is **absent**, the exact legacy UNKNOWN source;
3. if neither exists, prepared-local capture returns NOT_PREPARED; ordinary
   preparation may proceed using its existing acquisition/build permissions.

This search is bounded to two source variants, with existing scoped/unscoped key
lookup within each. If a present native graph is corrupt or has invalid evidence,
prepared-local capture reports that failure and must not silently fall back to a
legacy graph, delete it, decode a source, or retry a new key/definition snapshot.
Normal preparation retains its existing repair policy before considering a
legacy hit. The chosen source and identity, not the preferred candidate, populate
all receipts and saved-artifact ownership.

A valid legacy hit is reusable and stays UNKNOWN. Do not synchronously rebuild
large tables solely to upgrade issue evidence on application start or comparison.
The first ordinary build after a real cache miss creates a certified native graph.
A future explicit user-requested reprepare may upgrade a valid legacy table, but
adding that action is outside this slice. Existing legacy metadata is neither
rewritten in place nor inferred native from its old labels.

Update `prepare`, direct-native identity selection/background build publication,
`capturePreparedLocal`, `savedArtifacts`, and prepared-cache identity checks as a
coherent change. Generic GeneratedTables clients keep their current UNKNOWN
source behavior. Keep current serialized `GeneratedTables.Identity`, `Node`, and `Metadata`
records intact. Add distinct `NativeIdentity`, `NativeNode`, and `NativeMetadata`
records, selected by their explicit format tags and converted only after
validation to an internal immutable opened-graph view. `GeneratedTable` consumes
that normalized view plus verified evidence. This avoids relying on a reflection
mapper's behavior when extra record fields are missing from legacy JSON.

## Required file changes and acceptance gates

Production changes would be limited to these native/generated/capture boundaries
plus explicit comparison provenance consumption:

- new `db2/NativeRowIssueOrigin.java`; `Db2Reader` mints it and `Db2Table` exposes
  it without changing row decoding or public parser DTOs;
- `generated/GeneratedTableContent.java`: compatible generic constructor and
  audited native factory/seal;
- `GeneratedTableSource.java`: policy/producer-aware native identity variant;
- new native metadata/certificate value types; `GeneratedTables` publication,
  `openFile`, `savedArtifacts`, and `GeneratedTable.issueCertificate`;
- `ClientBuildTables.java`: audited factory, bounded native/legacy lookup,
  actual selected source/receipt evidence and ownership consistency;
- `CapturedRowsTypes.java` and `CapturedRowsHandle.java`: additive evidence and
  validated normalization with existing LIMITED overrides;
- comparison raw/Items source adapters: fingerprint/expose evidence version and
  retain per-row overrides, without using ordinary table APIs or a winner rule.

Before merge, require deterministic authored fixtures through **real
Db2Reader → audited content factory → store → reopen → capture**, not only custom
rows stamped with expected labels:

1. Valid localized UTF-8, known empty text and resolved copies normalize null to
   explicit empty only on certified capture; parser and saved `RowCodec` bytes
   still retain their original null channel.
2. Sparse malformed UTF-8 preserves INVALID issue/raw evidence and readable
   numeric/text siblings. Missing noninline relationship stays UNKNOWN. Nonsparse
   strict decode failure is not falsely advertised as recovered.
3. Generic custom callbacks with the same legacy reader/codec labels and null
   channels remain UNKNOWN; supplying generic content to a native source fails
   before mutation. Legacy explicit empty remains explicit empty.
4. Crossed graph/CKey/locale/block/options/columns/producer/policy certificates,
   wrong emitted counts and invalid issue locations cannot promote a channel.
   The same unencrypted native graph validates for two build endpoints with
   different requested encoding scopes, using the actual unscoped identity.
   A scoped graph validates only against its actual stored scope/key-chain node;
   exchanging scoped and unscoped source-identity digests fails.
5. Old v2 graph capture performs no write, repair, source-byte decode or network
   request; it stays readable UNKNOWN. Native absence permits exact legacy
   lookup, while native corruption is surfaced rather than hidden by fallback.
6. Evidence at the 65,536-byte limit, over it, and an oversized cell preserve
   valid siblings, including an empty kept issue prefix. Assert row channel
   LIMITED, affected bounded column LIMITED, unaccounted TEXT and NONINLINE
   relation bindings UNKNOWN, and independently safe ordinary numeric VALUE.
   Explicit retained invalid issues still classify their own cells; a native
   receipt certificate cannot override any row/cell limitation.
7. PARTIAL/protected-section tables remain NOT_CERTIFIED for membership, while
   their independently valid returned text is assessable.
8. Store cancellation/failure does not publish a certified node and always drains
   native owner resources; capture future cancellation/deletion/close preserves
   existing no-late-work guarantees.
9. Legacy generated identity/RowCodec null-empty fixtures and C# parser goldens
   remain byte-for-byte unchanged. Add new native identity/certificate fixtures
   instead of replacing legacy expectations.
10. Actual comparison assembly with charged compact evidence metadata stays
    within the reviewed 32 MiB / 512 MiB plan. Pair a legacy UNKNOWN graph with
    a certified native graph under the same semantic schema: a known numeric
    change is still CHANGED, while unknown-to-known name availability is
    INDETERMINATE. Do not globally exclude that dependency/field mask merely
    because issue provenance differs. Classify certificate changes as evidence
    availability, not fabricated gameplay differences.

Native source proof against a real selected build may follow in a separate
read-only or isolated-store probe after implementation approval. It must not
open or mutate the running application's writer-owned library, export raw keys,
or commit downloaded game data. No live-library probe was required to establish
this design: the current producer and capture semantics are visible in source.
