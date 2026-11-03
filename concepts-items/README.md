# Item concepts (layer 6)

`ItemsService` implements `Items` over layer 5 `Tables`, layer 4 `Files`, and `BlpImages`/`BuildModels`. It does not read lower object-store implementations. `forBuild` owns the tables handle; Files opens for explicitly requested gameplay calculation inputs and icon/model detail. Close rejects new work, drains accepted operations, and closes both owned handles. Canceling a returned future does not bypass resource cleanup; explicit `Cancellation` reaches lower operations.

```java
Items items = new ItemsService(tables, files, images);
try (BuildItems build = items.forBuild(ref, "enUS", Cancellation.NONE).join().items()) {
    ItemsPage page = build.list(new ItemQuery("sword"),
        TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
    ItemResult detail = build.get(19019, TableTypes.Priority.INTERACTIVE,
        Cancellation.NONE).join();
}
```

Check `ItemsOpenResult.state()` and `items()` before using an opened result. `MemoryItems` is an independent seeded consumer fake. `ItemsContract` supplies reusable public contract tests and runs against the real service and fake.

## Source rules and provenance

The anchor is `Item` left joined to `ItemSparse` by unsigned 32-bit ID, including ID zero and copied rows. An empty query scans Item order; a name query scans ItemSparse order, joins Item, and excludes only proven absent Items. Missing, unreadable, invalid-text, protected and empty names stay distinct. Whitespace is a literal valid name. Search uses pinned .NET ordinal case-insensitive substring semantics, with no wildcard or linguistic expansion. Source-bound cursors bind query, limit, locale, build and identities and report stale data explicitly.

Summary values retain exact canonical integer cells and source table/column/index. Detail adds all 48 `item-facts-v1` facts, ordered source cells, reviewed `item-labels-v1` labels, original row IDs, and agree/disagree/separate provenance. Raw facts remain unscaled. The additive `ItemDetail.attributes()` projection pairs explicit stat array slots and formats direct flat base attributes with provenance; allocation-based stats return `REQUIRES_CALCULATION`. It does not calculate scaled gameplay values. See [item attributes](../docs/item-attributes.md). Classification labels join reviewed signed 8-bit semantic keys, rather than target row IDs; duplicates, text issues, unsupported widths, partial targets and bounded scans have typed outcomes.

Appearance provenance follows ItemModifiedAppearance -> ItemAppearance -> ItemDisplayInfo -> ModelFileData/TextureFileData -> component tables. Complete and partial results, row/index caps, hidden keys, missing and invalid keys remain explicit. The inherited common modifier-zero rule identifies a default only when exactly one modifier-zero row exists in a complete result. Unknown product-specific/default choices remain `UNKNOWN`; no likely default is inferred.

`ItemDisplayRef.armorMaterials()` fills the pinned backend's previously missing ItemDisplayInfoMaterialRes join. The selected schema must have verified plain fields and foreign keys ItemDisplayInfoID -> ItemDisplayInfo.ID and MaterialResourcesID -> TextureFileData.MaterialResourcesID, with the reviewed 8-bit ComponentSection. Each result retains the source row, raw numeric section, material resource and texture/component provenance. The nested resource's `slot == -1` denotes a material relation row, not one of the two display model slots. No body-section labels, texture application, race selection or rendering is inferred. Unsupported schema, partial/protected sources and resource/row caps stay visible. The bundled reviewed DBD document SHA-256 is `d225d285e89b727566b3fe16f632156800a866d80fdfb5e88fbb1c7b9a42c9dc`.

Icon inspection reads BLP headers/mip availability through Files and preserves file state, content key and protected-hole keys. Detail also inspects the distinct model references in source order, up to the explicit model cap. Further models are `NOT_READ`; metadata references do not fetch skins or textures or evaluate geometry/materials. Item effects are a separate concept service.

`ItemsOptions` defaults bound list scans to 20,000 rows, label scans to 200,000, relation index entries to 4,000,000, appearances to 16, resource files to 32 and model reads to 8. Fact projection refuses more than 16 cells per fact. Each handle also admits at most 64 concurrent list/detail operations by default (`maxConcurrentOperations`); excess calls fail immediately with `ITEMS_OPERATION_LIMIT`, with no waiting queue. Every limit is explicit; facts are never silently truncated.

## Validation

Requires the repository Java 25 toolchain and Gradle wrapper. No new runtime third-party dependencies are introduced.

```sh
./gradlew :concepts-items:test
./gradlew :concepts-items:check :concepts-items:assemble -PsqliteLibrary=/absolute/path/to/sqlite3-library
./gradlew :concepts-items:interopTest -PreferenceRoot=/path/to/vibeminer -Pdotnet=/path/to/dotnet
```

Normal tests include checked original-C# fact goldens. Optional interop regenerates those bytes by compiling the unchanged pinned `ItemFacts.cs`, with type-only seams, using .NET 10 and its committed restore lock. The reference pin is `4fd92d2` (no source implementation is copied into the harness). `model-274.m2` is the original pinned C# model fixture already validated by formats-assets' interop suite. Golden TSV text is fixed to LF; binary fixtures are never newline-transformed.

The native integration wires real disk ObjectStore -> CachingObjectSource -> Files -> DB2/Tables -> DiskGeneratedStore -> Items. It closes/reopens both stores, deletes encoded DB2, then proves the same list/detail works from durable generated rows. Tests use authored fixtures, never live endpoints or credentials. This module adds complete backend item source/provenance APIs; it does not add HTTP/UI endpoints or a renderer.

## Preparation and on-demand media

Concept handles expose `warmup(priority, token)` with immutable per-lookup results.
Preparation builds only reviewed, bounded table indexes and reports unsupported,
partial or resource-limited inputs explicitly. Retain the same warmed handles in
the application context; closing and reopening them discards in-memory indexes.
Warmup performs no icon/model/texture I/O and does not claim universal table readiness.

`getData(id, priority, token)` returns table facts and declared media identities
without opening asset files. Existing `get` preserves full-detail compatibility
and can be requested separately for asynchronous media enrichment. Item icons
use NOT_REQUESTED and models NOT_READ; creature model IDs remain in display chains
with no inspected model payload. Local queries still execute asynchronously.

Java local query pages accept 1–500 rows (defaults remain 50). Existing request/cursor behavior within the C# range is unchanged; the Java browser uses 100-row pages.

## Persistent local item grid

Configure the additive `ItemsService(..., generated, indexDirectory, inputs)`
constructor and query `BuildItems.grid(ItemGridQuery, priority, token)`. A disposable
SQLite projection lives under the application's derived-data directory. Its identity
includes build, locale, source table identities/schema, definition/key enrichment
inputs and projection/label policy. Changed inputs produce a new index.

Search, class/subclass, quality, slot and stable sort run across the entire prepared
item catalog before SQL LIMIT/OFFSET. Responses contain the matching total, global
filter choices and only lightweight current-page rows. Class and subclass labels
come from the selected locale's tables; semantic IDs remain internal query values.
The first preparation joins source rows transiently, commits the completed index
atomically and releases that list. Reopen queries the saved index without scanning
Item/ItemSparse again. Protected or otherwise unreadable source rows remain absent
from this readable projection; source table coverage remains visible separately.

Selected records use `inspect` for facts and Layer 6 base attributes. The GUI loads
icons asynchronously for visible rows, independently of selection, with bounded
concurrency and cache. Full media inspection remains a separate request. Existing
`list`/`getData` APIs and C# fact goldens are preserved.

Native SQLite integration tests include 1,200 synthetic items, filters beyond the
first page, whole-catalog sorting/search, literal query values and a reopened index
whose source provider rejects any attempt to rescan rows.

Readable classification candidates from PARTIAL tables retain their source row,
text and `INCOMPLETE_TARGET` state; a singleton readable match does not prove
uniqueness when other records are protected. Grid and list labels append
`(partial)` to these known candidates. Missing candidates remain uncertain,
and multiple readable matches remain ambiguous. The classification policy
revision invalidates older persisted grid projections.

## Pure prepared-source projection

`PreparedItemProjection` is a pure increment-A comparison capability. Its immutable
DTOs live in `PreparedItemTypes`; it performs no I/O and accepts no table services,
SQL, comparison DTOs or asset handles. The caller captures and verifies local
sources, owns all work/lifetimes, admits the paired dependency scope, and stages
results separately.

- `compile(SourceSchema)` qualifies one Item or ItemSparse source and returns
  identity/field admission plus a detached compact SourceProfile. The overload with
  Selection requires it to match SourceSchema.selection. Metadata must be≤64KiB,
  with at most64selected bindings. `estimatedRetainedBytes()` is a conservative
  profile/container/string estimate for the caller's shared metadata reservation.
  Profiles retain bounded stamps/bindings/signatures, not the source schema,
  definition document, header or capture handle.
- `projectSource(profile, row)` retains source-qualified known/empty/unknown/
  protected/invalid/limited cells independently. Scalar versus array slot, original
  source row/column and copy metadata remain explicit. A missing joined half needs
  no synthetic Item row. Unknown issue channels never fabricate empty names;
  valid numeric facts and unrelated families survive. A nonexact row width is
  ambiguous unless every omitted trailing column has an explicit typed override;
  extra/interior-shift ambiguity invalidates only that source half.
- `combine(id, item, sparse, selection)` verifies same endpoint/locale/ItemID and
  retains both source vectors with agreement/indeterminate status. It chooses no
  winner for conflicting values. The name is independently sourced from Sparse.
- `unobservedSource` creates a typed unavailable half without inventing known
  cells. MISSING and PROTECTED require explicit absence/protection evidence; the
  current captured comparison sources do not certify absence. SourceFacts retains
  PresenceEvidence(kind/identity/code); staging codecs must preserve it. Missing/
  protected constructors reject absent or contradictory evidence.

The default whitelist covers name, quality, class/subclass/inventory type, recorded
item level/required level and ordered flag words. Raw description is opt-in.
Unsupported names do not discard numeric facts. Raw/unverified/narrowed candidate
fields are unassessed rather than promoted to gameplay facts. No effect/spell
projection, tooltip substitution, stat scaling, eligibility, label-table loading,
model/icon read, report persistence or comparison completeness is implemented.

The authoritative ID is the decoder's unsigned32 Row.id, including4294967295 even
when the DBD ID declaration is signed32. Actual ID cells must agree with that ID.
Other signed values/sentinels (including `-1`) retain their recorded domain and are
not converted to missing/all-flags/unsigned values. Only verified captured profiles
qualify; source table identity, locale, decoder, layout/definition/interpretation
and field-family selection are fingerprinted. Endpoint CKeys/graph identities and
comments remain separate evidence, so they do not manufacture changed facts.

Shared ItemFacts/ItemLayout helpers supply aliases, source-cell access, canonical
integer checks and name rules. Existing ordinary item browsing/fact outputs retain
their behavior, including bundled golden/contract fixtures. Focused authored tests
cover sparse-only values, disagreements, source domains, signed/max-ID rules,
unknown/limited channels, local malformed cells, unsupported names/families,
compact metadata and immutable results. Run `./gradlew :concepts-items:check` for
unit and Java integration checks; optional C# regeneration is separate.

Prepared projection classifies DB2_TEXT_ENCODING_INVALID as INVALID; missing
relationships and unknown TextIssue codes remain UNKNOWN with bounded original
codes/raw evidence. A LOCSTRING field must match both schema and source locale;
an invalid name locale omits that field without discarding verified numeric facts.
These rules do not change ordinary ItemFacts/ItemLayout behavior. SourceFacts also
checks source-qualified cell table/kind/row identity, and issue lists enforce both
count and per-string bounds at their DTO boundary.
