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
