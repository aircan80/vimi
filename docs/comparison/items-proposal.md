# Selected Items comparison: direction proposal

Design only for #117, based on `a794415`. No semantic adapter, data acquisition or
persistent comparison report is authorized by this document. Independent WoW and
architecture direction review must precede concrete capability approval and code.

## Useful first slice

Compare **recorded item facts** by stable unsigned ItemID, within the same product
lineage and requested locale. Begin with two captured dependencies, Item and
ItemSparse: exact recorded name, quality ID, class/subclass IDs, inventory type,
recorded item level and required level, and individual flags array cells where the
selected schema/profile supports them. Preserve source table/row/field/array slot.
Optional direct description text is raw localized text, not a complete tooltip.

The UI can say “Recorded item level changed” or “Use spell reference changed.” It
must not infer character-effective stats, usability, a dropped item, a new in-game
acquisition source, or gameplay balance from those facts alone. Differences in
file CKeys/definitions/readability are evidence dimensions, not a blanket item
change on every record. Known-equal assessed rows remain omitted.

A second small increment may add **verified item effect links and recorded effect
inputs**, using the existing reviewed effect profiles. It is explicitly separate
from a complete tooltip/spell-formula comparison. Assets, appearances, model/icon
bytes, computed stats, acquisition/loot, cross-layout crosswalks, external providers,
all-spell semantics, exports, durable reuse/resume and persistent reports are out
of scope for this slice.

## Existing APIs: what can and cannot be reused

- [BuildItems](../../concepts-items/src/main/java/com/vibeminer/concepts/items/BuildItems.java)
  exposes list/get/getData/inspect/grid and preparation helpers. **getData is not a
  prepared-local guarantee**: it prevents asset reads but still invokes normal
  table schema/get, classifications, appearances and relation readers.
- [ItemsService](../../concepts-items/src/main/java/com/vibeminer/concepts/items/ItemsService.java)
  requires a readable Item half for detail and returns early when it is missing.
  ItemLayout.resolve can reject an otherwise useful detail when Display_lang is
  unsupported. Its catalog builds an in-memory sparse-row map and its grid can
  populate a SQLite ItemGridStore. Do not call these paths behind a “local” flag.
- [ItemFacts.project](../../concepts-items/src/main/java/com/vibeminer/concepts/items/ItemFacts.java)
  is an existing public, pure fixed-source-cell projector with policy revision,
  aliases, per-source values, state and agreement. It never computes/scales stats.
  However it requires a non-null Item row; a mismatched row shape returns an empty
  SOURCE_INVALID fact set, and null textIssues is not treated as an unknown issue
  channel by the current helper. Reusing it unchanged cannot satisfy all new
  partial-source requirements.
- [ItemLayout](../../concepts-items/src/main/java/com/vibeminer/concepts/items/ItemLayout.java)
  already owns name selection, canonical integer checks and summary source cells,
  but is package-private and expects particular readable halves. Factor its pure
  policy rather than copy names, aliases, precedence and validation into the
  comparison component or fabricate a synthetic Item row.
- [ItemFactSet](../../concepts-items/src/main/java/com/vibeminer/concepts/items/ItemFactSet.java),
  ItemFact/ItemFactValue retain VALUE/UNSUPPORTED/MISSING/UNKNOWN/PROTECTED/
  INVALID_TEXT, SINGLE/AGREE/DISAGREE/SEPARATE and table/row/column evidence. Preserve
  those distinctions. Do not flatten two conflicting source cells into a winner.
- [ItemAttributes](../../concepts-items/src/main/java/com/vibeminer/concepts/items/ItemAttributes.java)
  and ItemStatCalculator are separate from recorded facts. Stat allocation inputs
  are not flat amounts; this slice must not duplicate their arithmetic or trigger
  their preparation. Existing catalog/grid/cache identities do not certify this
  comparison's dependency manifest or complete membership.

## Projection boundary: recommended direction

Prefer a small **explicit pure captured-row projector** in `concepts-items` over
wrapping the whole ItemsService in a captured-only BuildTables facade. The former
reuses/factors source-cell/name logic without pulling in hidden classifications,
appearance joins, stat preparation, catalog materialization or SQLite writes.
Existing item APIs keep their behavior; a shared pure implementation can serve
both after its compatibility tests pass.

The new projector's conceptual input is ItemID plus independently typed optional
Item and ItemSparse rows, their captured schemas/columns, their per-cell limits/
text issue channels, and source coverage/provenance. It returns immutable sourced
facts/name and per-dependency states, with no BuildTables/SQL/network access.
The application/comparison layer owns capture, sort/join, work and result paging;
existing concept libraries do not depend on comparison DTOs.

A generic captured-only BuildTables facade is an alternative only after specific
review: it must refuse all uncaptured sources and preparation/preload, expose
bounded session-local indexed reads, map misses to UNKNOWN, and never create
normal table services. It would also need to prove existing consumers retain
useful sparse-only facts and do not build hidden global indexes. Those consumers
currently do not meet that requirement, so a facade alone is not this slice.

## Field and join contracts

| Fact | Comparison rule |
| --- | --- |
| ItemID | Shared Item/ItemSparse ID under the reviewed source profile; preserve own copy IDs. Neither name nor display/model ID is identity. A readable sparse-only row remains an observed ItemID. |
| Name | Exact selected-locale Display_lang, with empty/invalid/unknown/protected/limited distinct. Unsupported name projection must not discard quality/class/level facts. No normalization, fallback locale, spell substitution or name matching. |
| Quality/class/subclass/slot | Compare recorded integer IDs first. Optional static/localized labels are display/interpretation evidence with their own policies; label refresh is not automatically a game-data change. No new classification table dependency in increment A. |
| Levels/requirements | Recorded ItemLevel/RequiredLevel/RequiredSkill/Rank only where individually admitted. Do not label raw item level as scaled/effective level, or masks/requirements as character eligibility. Extra requirement fields are opt-in after their source profile is reviewed. |
| Flags | Ordered individual recorded array elements with width/signedness evidence. Preserve zero and unknown separately; no invented flag labels or flattening all words into an untyped number. |
| Multiple source values | Key each by semantic fact plus table/source field and array slot. Preserve agreement/conflicts and every admitted source; Item versus ItemSparse disagreement cannot be resolved by source order. A source disappearing changes availability/agreement, not necessarily a value. |
| Sparse or Item row missing | Miss in an uncertified captured scope is UNKNOWN, not ABSENT. Protected remains PROTECTED only with specific evidence; an unlocatable excluded-section row is simply UNKNOWN. Preserve all facts from the other readable half. |
| Limited/invalid cells | CapturedRows.Page.cellIssues override affected cells as LIMITED; row-level limited issue channel and nullable Row.textIssues remain explicit unknown channels. Invalid text never becomes known empty. Retain unrelated valid numeric/name/source cells whenever alignment is unambiguous. |

Initial fact scope is an explicit subset of ItemFacts.KEYS, not every fact that
happens to appear in a layout. A semantic field requires verified schema kind,
width/signedness, array shape/reference meaning and an admitted projection policy.
Do not promote raw-physical/unverified candidate integer evidence to a gameplay
value. Aliases in the existing projector are reusable policy, not permission to
invent a cross-layout identity mapping.

Within each dependency pair, require the reviewed exact-layout/schema/definition/
interpretation admission used for raw rows, or a separately reviewed item-specific
profile. Assess each dependency independently: an incompatible Sparse layout can
leave compatible Item class facts useful. Globally unsupported/unavailable field
families are reported once as unassessed scope rather than manufacturing millions
of UNKNOWN filler cells. Within an admitted readable PARTIAL dependency, a missing
particular joined row remains per-item UNKNOWN and a useful finding alongside
other known facts. A field present on just one supported side is not a confirmed
field removal.

## Coverage and source capture

Capture Item and ItemSparse through **capturePreparedLocal only**, with exact
source identity/definition/reader/key receipts and dedicated comparison sessions.
No stat/check/schema/ordinary page calls, prepareKnown, normal item warmup/grid,
local DB2 decode or repair fallback. Missing prepared dependencies remain typed
capabilities/issues; useful captured halves can still be compared.

Reuse the raw slice's bounded session scratch and TempBudget machinery to sort
source rows and merge by ItemID; do not materialize either complete source as a
HashMap or invoke per-item scans/get calls against a normal service. Capture each
source once per endpoint; join streams in bounded stages. ItemID inventory is the
union of readable admitted Item/Sparse identities for that endpoint, labelled
“observed item records,” not the game's exhaustive item universe.

Every row/set membership stays UNKNOWN/NOT_CERTIFIED unless a later reviewed
certificate permits stronger claims. Current CapturedRows is BEST_EFFORT_INVALIDATING:
original graph loss invalidates capture; no silent reprepare/reopen. Sealed scratch
owns run inputs. The joined inventory cannot become COMPLETE just because both
readable streams exhausted or Entry was READY. Opposite unknown scope produces
observed-only, not Added/Removed. A missing joined half never erases an observed ID.

One active job, shared32MiB comparison-buffer and512MiB total live scratch policy,
input pages≤256/result pages≤100, canonical/result frames≤1MiB and bounded identity
counts remain in force. The extra table captures and simultaneous old/new join
runs count toward the **same** budget, not a fresh budget per dependency. Native
short pages and source parser/bootstrap budgets remain separate; no whole-process
32MiB claim. Limits/cancellation retain typed incomplete scope; all reads, captured
handles, adapters and scratch drain before borrowed endpoints close.

## Effects and item-use text: increment B

Reuse [ItemEffectProfiles](../../concepts-effects/src/main/java/com/vibeminer/concepts/effects/ItemEffectProfiles.java)
for supported ItemXItemEffect/ItemEffect layouts and their qualified bridge versus
ParentItemID relation. Reuse/factor its pure qualifies/fields and existing effect
interpretation code rather than reconstructing a relation from field names alone.
ItemEffectsService itself makes normal schema/get/relation calls and is not an
approved prepared-local execution path.

A link's identity retains bridge row ID and recorded parent/effect IDs, or the
reviewed direct ItemEffect record identity for ParentItemID profiles. Multiple
links can share a SpellID; do not key only by spell, collapse duplicate links, or
infer association presence from a loaded spell row. Preserve explicit source slot
when verified; list order/row ordinal is not an invented gameplay slot.

Admissible recorded fields include verified TriggerType, Charges, CoolDownMSec,
CategoryCoolDownMSec, SpellCategoryID, SpellID and declared condition/specialization
references under the existing exact profiles. Keep raw-physical/unverified fields
as unassessed evidence, not computed cooldowns/eligibility. State separately whether
a link was observed, its effect row readable, and its referenced spell name/text
available. Unknown relation-set completeness means an absent link is not confirmed
removed, and an empty readable prefix is not “no effects.”

SpellName/Spell raw localized text can be optional additional captured dependencies
in a later reviewed subincrement. Compare raw Description_lang and referenced IDs
as recorded facts; use the existing SpellTexts/SpellText policy only after its
pure resolver and all explicit SpellEffect/difficulty/context dependencies are
captured and reviewed. Do not duplicate `$s1` substitution formulas or call ordinary
SpellTexts against a network-capable BuildTables. Unresolved→resolved display text
after a key/parser/policy update is availability/interpretation, not a confirmed
item gameplay change. Initial use/equip text can remain unassessed while verified
use/equip link inputs already compare usefully.

## Reproducibility and provenance

Pin ordered endpoints (product/BuildRefs/configs/version/locale), all selected
captured graph/CKey/layout/schema/document/reader/key receipts, effective key
bootstrap/community snapshots, selected field profile and pure projector revisions.
Each record retains source table+row+column+slot and relevant copy provenance.
Source-field semantics are not inferred from matching labels. Policy/profile/
interpretation mismatches are capability differences; the same bytes under a new
formula do not prove a Blizzard change.

Evidence annotations remain explanatory and are not directly compared by the core.
If an assessed source winner, label interpretation or source policy changes, emit
an explicit EXTERNAL_EVIDENCE/INTERPRETATION field or one scoped capability delta.
Do not inject endpoint-only graph/CKey IDs into every content field and fill the
findings spool with unchanged items. Existing item catalog/grid caches are neither
a raw capture lease nor an authoritative coverage/caching shortcut. This slice
publishes no comparison objects, catalog references, reports or new persistent DB.

## Gates and practical delivery

1. Independent direction review of this minimal two-table sourced-fact boundary,
   sparse-only/partial preservation and explicit effects/text phasing.
2. Before implementation, specify and independently approve concrete APIs for
   pure ItemProjection input/output states, reuse of ItemFacts/ItemLayout policy,
   per-cell issue/limit propagation, and bounded multi-table snapshot/stream joins.
   If effects are included, approve its pure relation/field projector and separate
   membership scopes first. The public API shape must be reviewable before code;
   this direction proposal does not silently authorize a generic facade.
3. Implement increment A with authored offline fixtures: changed known name/quality/
   class/level/flags, equal values with different endpoint annotations, conflicting
   Item/Sparse source values, sparse-only identity, protected/missing Item half,
   unsupported name with valid numeric facts, null/limited issue channels, localized
   empty versus invalid text, cross-layout refusal per dependency, duplicate/copy
   IDs, quotas, invalidation, cancel/delivery/close drain and forbidden normal APIs.
4. Independently review the implementation and a read-only prepared-pair proof.
   Then add increment B with separate link identity/set-coverage and no guessed
   tooltip/formula or acquisition claims. Do not mark #117 complete at either step.

No production edits, app-data writes, tests requiring credentials, or persistent
reports are part of this design task. All references above are repository APIs;
concrete reviewed field profiles still determine which selected builds qualify.

## Concrete increment-A contracts for pre-code review

Both independent reviewers approved the direction at `1632baa`. The following
contracts refine that direction; they still require **specific independent approval
before implementation**. Increment B effects remains a later design, not part of
these DTOs or dependency set.

### Pure public boundary in concepts-items

Propose `PreparedItemProjection`, a pure public class with these operations:

- `compile(SourceSchema, Selection) -> Compilation`: qualify one source schema and
  build a compact immutable SourceProfile. No row reads, service calls or storage.
- `projectSource(SourceProfile, SourceRow) -> SourceFacts`: project one source row
  independently, retaining typed cells and provenance. No other ItemID lookup.
- `combine(itemId, SourceFacts item, SourceFacts sparse, Selection) -> ItemView`:
  combine already projected same-endpoint sources without selecting a value winner.
  Validate identical ItemID/build/locale. Missing optional input is represented by
  typed SourceFacts, never null meaning an empty dependency.
- `unobservedSource(itemId, SourceStamp, SourceState, RowState, presenceEvidence,
  Selection) -> SourceFacts`: construct a typed non-present half when no profile/
  row is available. It emits source state/omissions, never synthetic known cells;
  MISSING/PROTECTED require the corresponding explicit evidence.

All inputs/outputs are immutable DTOs in `concepts-items`, using existing public
formats/build metadata where suitable and **no comparison DTOs, BuildTables,
CapturedRows handles, SQL, files, JavaFX, network or GeneratedStore**. ItemView is
not ItemDetail and does not prepare classifications, appearances, media or stats.

Exact proposed DTO vocabulary:

| DTO | Fields / invariant |
| --- | --- |
| `SourceKind` | ITEM or ITEM_SPARSE. No provider priority/winner order. |
| `SourceState` | READABLE, PARTIAL, UNAVAILABLE, UNSUPPORTED, INCOMPATIBLE, INVALID, LIMITED. This is dependency availability, independent of the per-ID RowState. |
| `FactFamily` | NAME, QUALITY, CLASSIFICATION, LEVELS, FLAGS, RAW_DESCRIPTION. |
| `Selection` | Immutable selected families plus policy revision. Default NAME/QUALITY/CLASSIFICATION/LEVELS/FLAGS; RAW_DESCRIPTION opt-in. Family→fact whitelist is versioned, not layout-discovered. CLASSIFICATION means class/subclass/inventoryType; LEVELS means itemLevel/requiredLevel only. |
| `FactId` | NAME, QUALITY, CLASS_ID, SUBCLASS_ID, INVENTORY_TYPE, ITEM_LEVEL, REQUIRED_LEVEL, FLAGS_WORD, RAW_DESCRIPTION. Stable explicit tags, not serialized enum ordinals. |
| `SourceStamp` | SourceKind, BuildRef, locale, table FileDataID/name, CKey/layout, definition-document SHA-256, selected-block identity, reader/options/row-codec identities, graph identity and effective-key-set fingerprint. BuildRef/locale/SourceKind/FileDataID are required; unavailable captured identities are explicitly unknown optional fields, not fabricated empty hashes. Bounded scalar strings only; no key bytes, whole receipt/document/schema or mutable source object retained. |
| `SourceSchema` | Stamp, captured Db2Types.Header, TableSchema, actual flattened columns, source availability state and selected families. Caller owns temporary schema/receipt metadata until compile returns. |
| `Compilation` | IdentityAdmission, FieldAdmission, optional compact SourceProfile, bounded typed omissions. Identity can qualify while fields are unsupported. Invalid identity means no row stream can be published as ItemIDs. |
| `SourceProfile` | Compact stamp, identity-policy fingerprint, per-family/field signatures, immutable admitted bindings and omissions. At most64KiB conservative retained metadata and64selected scalar bindings per source. Exceeding either yields LIMITED for affected field scope, not a hidden giant profile. No whole SourceSchema/receipt retained. |
| `SourceQualifiedKey` | FactId, SourceKind, table FileDataID, exact source schema field name, scalar/array tag and zero-based arraySlot (scalar uses `-1`), versioned semantic signature. It excludes endpoint CKey/graph/build config and column ordinal. Actual column index remains source evidence. |
| `RowState` | PRESENT, MISSING, PROTECTED, UNKNOWN, INVALID, LIMITED. MISSING requires explicit certified absence evidence; current captured adapters cannot emit it. PROTECTED requires row-specific protection evidence, never a guess from a table-wide excluded section. |
| `IssueChannel` | KNOWN, UNKNOWN, LIMITED. UNKNOWN includes nullable Row.textIssues; LIMITED includes captured row-level issue-channel limits. These are not empty issue lists. |
| `SourceRow` | ItemID, RowState, optional Db2Types.Row (present iff PRESENT), IssueChannel, immutable per-column IssueOverride values, typed presence/protection evidence. IDs and stamp must match the profile. No row is fabricated for an absent half. |
| `CellState` | VALUE, KNOWN_EMPTY, MISSING, UNKNOWN, PROTECTED, UNSUPPORTED, INVALID, LIMITED. UNKNOWN/etc never carry a known value. Bounded raw evidence may remain independently available. |
| `ScalarKind` | INTEGER, TEXT, FLAG_WORD; recorded referenced IDs retain declared target metadata in the binding/evidence, without resolving labels. No inferred boolean/eligibility/stat amount. |
| `SourceCell` | SourceQualifiedKey, CellState, ScalarKind, optional canonical known value, bounded raw evidence, actual source row ID/column index, source-copy metadata/state and issue codes. Every source cell keeps its own state even when another source is invalid or conflicting. |
| `SourceFacts` | ItemID, compact SourceStamp, SourceState and RowState/IssueChannel, admitted source cells, source/field omissions and bounded issues. A valid source remains readable when the other source fails. |
| `FactGroup` | FactId, ordered source-qualified cells, agreement state and assessed-source scope. Agreement is AGREE/DISAGREE only for comparable known vectors; SINGLE_KNOWN_SOURCE means one known source was observed, not that an unknown source is absent. INDETERMINATE retains partial states. |
| `ItemView` | ItemID, per-source states/stamps, groups, separately sourced name cell, bounded issues and projection policy. It has no “winning stat/name” across Item/Sparse sources; the known name originates from Sparse by the verified policy. |

`IssueOverride` names the column and a restrictive state (LIMITED, INVALID,
PROTECTED or UNKNOWN) with a typed code. It cannot upgrade source unknown/invalid
facts to VALUE. A present row's null textIssues forces UNKNOWN for affected text/
placeholder-sensitive fields even if the caller supplies a more optimistic
IssueChannel. Independently valid numeric facts can survive. KNOWN_EMPTY text
requires an admitted text cell, a known issue channel and no issue/limit at that
cell. Row-level unknown/limited issue channel cannot silently become issue-free.

Keep per-source family limits explicit: reuse ItemFacts.MAXIMUM_VALUES16 for the
existing source-cell extraction bound. A source-family exceeding that bound is
LIMITED while unrelated families survive; two admitted source vectors may retain
up to16cells each under the total64bindings/source cap. No global all-facts erasure
because one optional family/name is unsupported. Name/description text is bounded
to8192characters, and raw issue evidence to a bounded code/prefix; these rules are
fingerprinted. Serialized source/item/result frames remain≤1MiB. If metadata or
an entire record cannot fit, produce a typed limited scope and stop/omit according
to the existing staged-source contract, never silently truncate known values.

### Reuse and compatibility obligations

Extract a shared pure internal source-cell/name helper from ItemFacts/ItemLayout:
field aliases and source qualification, array-slot expansion, canonical integer
checks, name validation and label lookup remain one implementation. The new
boundary uses the helper with independent optional halves and restrictive issue
states. Existing ItemFacts.project and ordinary ItemLayout/ItemsService delegate
only where that preserves their established public behavior. Do not force the
new partial-source agreement states into legacy enums or change ordinary browsing
as a side effect.

Compatibility fixtures must demonstrate existing valid ItemFactSet values,
source order, alias selection, agreement, labels and ordinary ItemName states are
unchanged. New fixtures separately demonstrate sparse-only facts, unsupported name
with known numeric facts, row-local limitations, independently invalid halves and
conflicting vectors. If a legacy aggregate cannot express the new state, keep the
legacy wrapper behavior and expose the richer state only through the new DTOs.
Ambiguous duplicate columns/aliases use the existing rejection rules; do not pick
a candidate by position or let one ambiguity erase unrelated admitted families.
No second stat formula implementation, duplicate label dictionary or imitation of
ItemStatCalculator is allowed.

### Unsigned identity and signed source values

The identity policy is `item-row-id-unsigned32-decoder-v1`: the authoritative ID is
Db2Types.Row.id in `[0,4294967295]`, including zero and the maximum. For an actual
ID column, compare its decoded text against that unsigned decoder ID; **do not
reject4294967295 merely because the selected DBD ID field declares signed32**.
The reader emits ID cells through its unsigned decoder identity path, separately
from ordinary signed scalar formatting. A mismatch between decoded ID cell and
row.id is invalid source evidence. Do not reinterpret a negative generic cell as
an ItemID or repair a mismatch by casting `-1` to unsigned.

Ordinary non-ID integer cells still obey their admitted schema width/signedness.
Recorded `-1` masks/sentinels remain signed VALUE `-1` with policy/source evidence;
they are not MISSING, unknown, zero, max unsigned or a fabricated “all classes”
set. Unknown enum/flag values remain known raw values if well typed. A width,
signedness, decoder-ID policy, reference meaning or sentinel-policy mismatch is
an incompatible field scope, not a numeric conversion/crosswalk. Source copies
keep their own unsigned ItemID; copySourceId is independently typed provenance,
never an alternate entity identity.

### Independent per-dependency pair admission

The comparison adapter proposes `ItemsPairScope.admit(leftProfiles, rightProfiles,
Selection) -> ItemsFieldScope`. These are comparison-layer contracts using the
pure compact item profiles; concepts-items does not depend on that adapter or the
comparison core. Output contains separate ITEM and ITEM_SPARSE identity admissions,
per-family source-qualified field masks, omissions and a descriptor-scope digest.

For each dependency independently, admit ItemID identity under the verified table
FileDataID/product lineage and decoder-ID policy. The pinned repository manifest
currently identifies Item as FileDataID841626/tableHash50238EC2 and ItemSparse as
FileDataID1572924/tableHash919BE54E. Verify the captured source against that selected
manifest/profile; do not pair an arbitrary named table or infer table identity from
its columns. Header/table identity mismatch is unsupported/invalid. The compact
profile retains only the bounded header identity/ID-policy fields, not the header's
whole columns list. A changed manifest mapping requires an explicitly reviewed
profile, not a fallback guess. Admit its selected fields only
under the raw slice's exact-layout/document/structural/interpretation policy and
the new projector policy. A Sparse field-schema mismatch suppresses Sparse field
comparison but does not suppress compatible Item facts or already qualified ItemID
streams. There is no attempt to map different Sparse layouts by column name, or
to reuse their numeric fields with guessed semantics. If its identity itself
cannot qualify, that dependency is unavailable for the identity union too.

Globally unavailable/incompatible/unsupported fields on either side are excluded
from the run's assessed field mask and listed once as unassessed descriptor scope.
Single-side known values can remain bounded detail evidence; they are not
manufactured as known-vs-empty changes. If no field family is assessable, show an
unsupported/unavailable Items capability, not “zero item changes.”

For a globally admitted field family, a particular joined row missing/protected/
limited within readable partial data remains a per-row UNKNOWN/PROTECTED/LIMITED
field and therefore a meaningful finding alongside other known cells. An Item
half missing for one ItemID cannot hide Sparse name/quality/level values. Membership
coverage of the union remains UNKNOWN; a field agreement moving from AGREE to
INDETERMINATE is an availability outcome, not a proven content-value change.

Field keys in ComparisonTypes.Record come from SourceQualifiedKey and the admitted
mask. Each raw source vector is compared independently; no “first available value”
comparison shortcut. A wrapper can show FactGroup agreement as interpretation/
availability evidence without converting disagreement into a made-up winner.
Schema/profile semantic signatures exclude endpoint availability state, CKey,
graph identity and observation time; those do not prevent known values from a
PARTIAL source comparing with a READY source. The ComparisonTypes.Descriptor
scope binds selected families, per-dependency
admissions/omissions, policy fingerprints and coverage. Endpoint-only stamps remain
annotations/dependency identity, not per-row changed content fields.

### Bounded capture, source projection, union and stream join

Propose a comparison-owned `PreparedItemsInputs.open(pairSessions, Selection,
TempBudget, token) -> ItemsInputsResult`, returning admitted descriptors, two owned
ComparisonInput adapters and typed per-dependency capability details. No resource
ownership transfers on failed admission. The implementation uses the existing
CapturedRows, ordered bounded source staging and result classifier; it does not
call BuildItems, ordinary table APIs or construct a normal ItemsService.

An internal `ProjectedItemSource` adapts one captured dependency into bounded
source records containing ItemID, SourceKind, typed SourceFacts and compact stamp
references. It calls only the pure projector. The existing sorter orders records
by fixed-width20decimal ItemID; duplicate IDs within one source invalidate that
source scope rather than selecting a row winner. It retains exact source-qualified
column/slot evidence and typed limitations, not an entire repeated schema/receipt
on every row. The stamp dictionary has at most four source entries and selected
binding dictionaries at most256entries total (four compact profiles×64bindings);
all dictionary strings/containers are charged to TempBudget. Row-specific source
IDs/column/slot/issue evidence remain in the records. No unbounded metadata or ID map.

An internal `ItemUnionJoin` consumes two sealed ordered source streams for one
endpoint. Its page contract is `next(maxRows≤256, maxBytes≤4MiB, token)` returning
immutable ItemViews/source states, stable snapshot identity, continuation and
UNKNOWN coverage; it may shorten the page. It owns at most one row head per source
plus bounded output. Equal IDs combine the two sources; an ID from just one source
produces typed UNKNOWN for the missing half. No per-ID source lookup or full map.
It can either seal a bounded candidate ItemView stream or feed an already admitted
mask; the initial schedule below seals candidates until both endpoints' compact
profiles are available. Core input adapters apply the final paired field mask when
reading candidates, before classifying/staging findings.

Memory phase schedule (one active comparison; allocations remain reserved from
one TempBudget, whose authoritative cap may shorten/refuse work):

| Phase | Heavy metadata / canonical work | Required release |
| --- | --- | --- |
| Capture A Item | Hold the1MiB compact snapshot owner; at most one heavy schema/profile reservation4MiB; compact compile output≤64KiB within that owner; sorter chunks/reservations within remaining budget (up to22MiB only if raw row/projection/codec/index reservations also fit32MiB). | Seal sorted source; close/drain capture; drop whole receipt/schema/raw profile before opening another dependency. |
| Capture A Sparse | Same single-heavy-profile rule, plus only compact A Item metadata. | Seal source; close/drop its heavy metadata. |
| Join A | No live heavy source schema profiles; two bounded reader heads/pages plus output/codec/index under shared32MiB. Compact A profiles stay inside the held1MiB owner. | Seal candidate joined A; delete consumed A Item/Sparse source runs only after successor seal. |
| Capture B Item, then B Sparse | Repeat single-heavy-profile capture; retained A candidate and all old/new files consume disk, not unbounded memory. Retain at most four compact64KiB profiles total. | Drop each heavy capture before the next; independently admit each compact source pair when B counterpart is available. |
| Join B | Same bounded merge with held1MiB metadata owner; A candidate remains on disk. | Seal B; delete consumed B source runs after seal. Finalize the paired field mask; no rows are called semantic changes before this gate. |
| Compare candidates | Use the Items core memory plan below: two12MiB input leases,3.5MiB codec,0.5MiB index plus the held1MiB source-snapshot metadata reservation =29MiB. Both candidates/findings share512MiB disk. | Release exhausted pages before replacements; retain metadata through paging; drain core/inputs before releasing metadata on close. |

This schedule **never retains four4MiB profiles beside a24MiB sorter**. Compiled
SourceProfile must not retain an indirect reference back to a receipt/schema/
CapturedRows handle. Do not open both heavy source receipts before the Items22MiB capture
sort, or retain all four receipts until final pair admission. A paired-source
alternative is allowed only with smaller reserved sort buffers and the same total
cap; it is not permission to assume free metadata.

Before source-sort startup, reserve and validate the heavy comparison metadata
profile within4MiB. If it cannot fit, publish a typed LIMITED source capability
and close/drop that capture; do not start the Items sorter beside uncapped retained
metadata. Native parser/reader metadata allocations retain their separately
stated limits; this reservation is not a whole-source/whole-process heap claim.
Compact profiles expose a conservative retained-size estimate for caller budget
charging, and family/source shape admission is complete before candidate projection.

### Retained metadata and the Items core memory plan

The current core's default scan plan consumes32MiB (two14MiB input leases,
3.5MiB codec and0.5MiB index); its result reader reserves31.5MiB alongside the
0.5MiB index. **That default is not admissible with retained Items metadata.**
The implementation prerequisite is an explicitly validated Items memory-plan
overload, not an assumption that small profiles are free or TempBudget will
somehow accommodate both. Existing no-metadata inventory/raw callers retain the
default behavior; Items composition must select the plan below.

One `SourceSnapshotCompactMetadata` owner reserves a fixed **1MiB** from the
pair's existing TempBudget before creating compact source profiles/dictionaries.
It owns all four≤64KiB profiles (≤256KiB combined), the at-most-four stamp entries,
the≤256selected binding dictionary entries, paired masks/omissions/descriptors
and their conservative container/string overhead. These share the same1MiB pool;
they are not extra uncharged allocations or a fresh pool per endpoint. The compact
metadata estimate must fit before admission/growth; excess is typed LIMITED,
not silently reduced provenance. Heavy source schemas/receipts are separately
charged during capture and dropped after sealing.

The snapshot metadata reservation stays live through both comparison **and every
result-page read**, including after input adapters have exhausted. The comparison
coordinator owns it; neither an adapter close nor core input-drain may release it
while the report/details can still use it. On session close, cancel/drain core,
close inputs and release their references, clear result-view references, then
release snapshot metadata and the pair budget. No ownership transfers to a new
persistent store. Already returned immutable pages are caller-owned under the
existing one-displayed-page UI policy; this remains a component buffer policy,
not a whole-JVM memory claim.

Proposed `ComparisonService.MemoryPlan.itemsWithSnapshotMetadata()` has fixed,
validated bounds (a future implementation may expose a carefully validated value
object rather than this exact factory spelling):

| Reservation / validation | Items policy |
| --- | --- |
| Input A and B leases, including adapter read/projection ownership |12MiB each; page canonical-size checks and aggregate retained-input checks use this same plan, never the default14/28MiB constants. Source pages may shorten and LIMIT rather than overrun a lease. |
| Scan codec workspace |3.5MiB, as current reviewed framing policy. |
| Sparse result index |0.5MiB, retained through result paging. |
| Retained source-snapshot metadata |1MiB, owned externally by the coordinator but charged to this exact shared TempBudget. |
| Scan reservation total |12+12+3.5+0.5+1 = **29MiB**; remaining3MiB is headroom, not permission for untracked buffers. |
| Result-page workspace |30MiB, replacing the default31.5MiB reservation for this plan. Input/scan codec leases have drained before paging. |
| Result-page retained-result estimate limit |12MiB, replacing default16MiB for this plan; continuation may shorten a requested100-row page. |
| Paging reservation total |30+0.5+1 = **31.5MiB**, leaving0.5MiB headroom. Metadata remains charged; no release/reacquire gap to evade accounting. |

Within the30MiB result workspace, plan at most12MiB retained result/delta estimates,
up to12MiB for the next decoded/classified candidate,≤1MiB framed bytes and5MiB
for decoder/container/normalization scratch. These are subdivisions of the same
reservation, not additional reservations. Before implementation approval, retain
the existing≤1MiB frame/field-count validation and prove its conservative decoded
candidate+delta bound fits12MiB; reject a candidate with typed LIMIT if the bound
cannot be met. Incremental decode/estimation must not allocate an unbounded pair
then discover the limit afterward. Result accounting includes FieldDelta/list/map
wrappers, not only serialized cell strings. First-row acceptance cannot bypass
this bound. Paging must use plan-derived limits throughout; merely changing the
reservation amount while leaving old decoded/retained limits is insufficient.

Composition passes the same TempBudget to source staging, snapshot metadata and
the core's explicit Items MemoryPlan overload. Validate plan arithmetic with
checked/subtractive operations; require the active1MiB metadata reservation to
belong to that budget and be owned by the composed session. A naked claimed
“external allowance” without the actual reservation is insufficient. Failed
admission transfers no inputs/metadata ownership. Existing core APIs need not
gain item DTOs; metadata lifetime remains the coordinator's responsibility.

Capture is adjusted consistently: the Items-only sorter chunk ceiling is22MiB,
not the raw default24MiB. One4MiB heavy profile +1MiB metadata +22MiB sort chunk
leaves5MiB for incoming raw row, projected source row, codec and indexes. Suggested
sub-reservations are≤1MiB each for the first three and≤0.5MiB index/bookkeeping
(30.5MiB planned total); every actual allocation still obtains its reservation,
and the sorter reduces its chunk or returns LIMITED if a source needs more.
There is never a four-profile16MiB hold alongside that sorter. Source parsing/
RecordFile decompression retain their separately documented source limits.

Mandatory integrated acceptance fixture: construct four compact profiles plus
stamp/binding/mask/descriptor dictionaries and hold the charged1MiB metadata owner;
run the real ItemUnionJoin over authored Item/Sparse source spools for both endpoints,
then the real ComparisonService with the Items plan and shared32MiB TempBudget.
Verify complete scanning of admitted identities, a sparse known change and partial
joined facts, and successful result paging **while metadata remains reserved**.
Exercise enough/larger findings for byte-shortened paging and multiple pages,
assert metadata is still1MiB after input close, and assert budget peaks≤32MiB
through scan and paging. Include quota/oversized-candidate refusal, exhausted-page
turnover, canceled delivery and final reservations/files returning to zero. A
standalone core test without metadata, a reservation-arithmetic-only test, or a
fixture that frees metadata before paging does not satisfy this gate. Existing
no-metadata core fixtures must still pass under the default plan.

A-before-B candidate sealing is safe only because it stores typed source facts
under a candidate selection, not an asserted final comparable schema. Compact B
admission may later remove a globally incompatible source family from both final
inputs; this filtering is recorded in descriptor scope. IDs remain available only
under their separately qualified identity policy. No source value is reinterpreted
or recaptured silently after admission.

All sealed dependency inputs, candidate outputs, source-sort runs, merge successors,
findings frames, file headers/footers and old-but-not-yet-deleted runs share the
same512MiB live disk budget. Reserve before writing; keep old/new bytes charged
until deletion succeeds. If a joined successor cannot fit alongside its inputs,
return LIMITED and clean up rather than exceed quota, discard the sole usable
input or create another per-stage512MiB budget. Streaming join output does not
make its retained input files free. Evidence is compact/source-qualified, not
removed to hide quota use.

Initial source records/ItemViews/results remain≤1MiB, named field text≤8192chars,
source heads/pages≤4MiB and observed union identities≤5million. The source parser,
record decompressor and prepared-key bootstrap retain their separately documented
limits; none of this claims a32MiB whole-process cap. Cancellation or failed delivery
keeps private ownership until captured reads, sorter/join workers and abandoned
adapters drain. The coordinator then closes borrowed endpoint sessions, without
closing the browsed build/shared library. No new persistent reports or generic
cross-layout cache identity is introduced.

### Exact-contract acceptance fixtures

Specific independent approval must cover the DTO/state/family masks and the above
phase/budget ownership, then code must prove:

- Legacy projector compatibility plus richer sparse-only/invalid-half preservation;
  explicit source-qualified disagreement, array slot ordering and no value winner.
- Unsigned max ItemID under signed32 DBD ID metadata, mismatched ID-cell rejection,
  and signed non-ID `-1` sentinel preservation without unsigned coercion.
- Incompatible Sparse fields with compatible Item values/IDs; globally excluded
  field scope versus a particular missing joined row; no-assessable-fields capability.
- Unknown/limited issue channels, exact row protection only when evidenced, known
  empty names versus unknown placeholders and cell-level LIMITED overrides.
- Sequential metadata turnover proving one4MiB heavy profile at a time, the held
 1MiB compact snapshot reservation and explicit Items core memory plan through
 scan AND real paging, shared source-sort/join/core reservations, all old/new file
 bytes charged, and quota failure without an unlimited fallback map/index.
- Forbidden ordinary item/table APIs and persistent publications; private cleanup
  after canceled capture/join/delivery; stable captured-source identity and honest
  UNKNOWN union membership. A readonly native paired proof follows authored tests.

These contracts remain **design only** until independent exact-contract approval
is recorded. Effects/use-text increment B needs its own later concrete design.


Manifest identity evidence for the exact initial source pair is the repository's
[definition manifest](../../formats-tables/src/main/resources/com/vibeminer/formats/definitions/manifest.json).
This is profile evidence, not permission to fetch definitions or client bytes.
