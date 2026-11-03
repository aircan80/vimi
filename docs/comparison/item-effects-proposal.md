# Recorded item effects comparison: increment B

Status: **design for independent review**, based on `405ed5e`. This document
permits no production implementation, acquisition or persistent comparison
publication. It makes the remaining Effects increment in
[Items](items-proposal.md#effects-and-item-use-text-increment-b) concrete. A later
reviewed implementation is released as its own GitHub prerelease; that release
boundary does not authorize durable reports or comparison caches.

## First deliverable and exclusions

Add an explicitly selected **Item effects** comparison mode alongside recorded
Items. Compare observed item-to-effect links and individually admitted recorded
numeric inputs from already prepared local tables. Results identify the parent
ItemID and the original link row, with source-qualified fields and availability.
The first mode is a separate comparison session, not a combined Items-plus-effects
run. Existing Items comparison, its two source profiles and its ItemID inventory
remain unchanged.

Use same-product-lineage and same-requested-locale endpoints, as the existing
local comparison. Start with like-for-like reviewed bridge or ParentItemID
profiles below. No cross-profile bridge/direct identity mapping is claimed.
No Item/ItemSparse row is required to preserve an observed relation: an orphaned
but valid parent reference remains an observed ItemID reference, not proof that
an Item record is readable or exists in the complete game data.

Do not capture SpellName, Spell, SpellEffect or difficulty/context dependencies.
SpellID is a recorded input; the first deliverable does not resolve spell names,
descriptions, use/equip tooltip text, trigger labels, condition meanings or
formulas. Do not compute cooldowns, charges remaining, specialization eligibility,
character-effective effects or balance. Models, icons, acquisition/loot, external
providers, all-spell semantics, exports, resume, persistent reports and provider
or definition downloads are excluded. “No effects” and confirmed removed links
are not supported conclusions in this increment.

## Existing code and reuse boundary

[ItemEffectProfiles](../../concepts-effects/src/main/java/com/vibeminer/concepts/effects/ItemEffectProfiles.java)
provides the reviewed physical profiles, field widths, relation storage and
numeric/evidence policy. Reuse/factor that pure policy in `concepts-effects`;
comparison dependencies must not enter that module.

[ItemEffectsService](../../concepts-effects/src/main/java/com/vibeminer/concepts/effects/ItemEffectsService.java)
is not a prepared-local executor. It checks Item readability, invokes ordinary
schema/get and relation indexes, and can resolve names/text. Its all-fields-at-once
`fields` helper also returns null when any one field fails and treats null
`Row.textIssues` as no issue. Neither behavior satisfies captured partial-cell
projection. Do not wrap the service in a purported local flag or use its page
exhaustion/absence outcomes as capture completeness.

The implementation should expose a small pure prepared-effect compiler/projector
analogous to
[PreparedItemProjection](../../concepts-items/src/main/java/com/vibeminer/concepts/items/PreparedItemProjection.java).
It accepts captured schema/actual columns, source stamp, issue-channel evidence
and independently typed rows. It returns immutable source-qualified facts and
omissions, preserving unrelated cells when alignment remains unambiguous. Factor
shared qualification/range rules and retain existing effects API compatibility;
do not reproduce profiles by field-name guessing in GUI code.

Capture, external sorting, joins and result paging belong in comparison/app
composition. Useful reusable infrastructure is
[PreparedItemRowsSource](../../concepts-comparison/src/main/java/com/vibeminer/concepts/comparison/inputs/PreparedItemRowsSource.java),
[SortedComparisonInput](../../concepts-comparison/src/main/java/com/vibeminer/concepts/comparison/SortedComparisonInput.java),
[BudgetedTempFile](../../concepts-comparison/src/main/java/com/vibeminer/concepts/comparison/BudgetedTempFile.java)
and
[SealedCandidateInput](../../concepts-comparison/src/main/java/com/vibeminer/concepts/comparison/SealedCandidateInput.java).
The existing `StreamingItemJoin` is a one-to-one ordered union; it must **not** be
used unchanged for the many-links-to-one-effect join described below.

## Source profiles and paired admission

| Relation mode | Captured sources per endpoint | Exact reviewed profile |
| --- | --- | --- |
| Bridge | ItemXItemEffect and ItemEffect | WDC5, file3177687/layout96F083AD and file969941/layout4CA77678 |
| Direct | ItemEffect only | WDC5, file969941/layout1BF9CF3A, qualified noninline ParentItemID relation |

The exact schema width, signedness, scalar shape, verified-name status, ID/relation
storage and actual column kind/order must satisfy `ItemEffectProfiles`, including
its declared physical-interpretation checks. Match the profile through captured
metadata only, never an ordinary `schema()` call. A definition selected by layout
remains a warning/provenance fact; it does not broaden semantic authority.

Select from positively qualified captured profiles. In particular,
`ItemEffectProfiles.choose` is a browsing convenience with missing-schema defaults;
its default must not invent a comparison relation mode when capture is missing or
unsupported. Capture ItemEffect first. If its direct profile positively qualifies, do not
capture an artificial bridge. Otherwise attempt one prepared-local bridge capture
and admit bridge mode only if that bridge positively qualifies; a missing effect
source can then leave bridge references useful with effect inputs unassessed. An
unsupported readable effect layout is not silently treated as the retail target
profile. Close heavy compilation before compiling the next source. Register
explicit unavailable/unsupported dependency outcomes.

Compare semantic inputs only when each dependency has the matching reviewed
profile on both endpoints, with exact schema/definition/interpretation admission.
Different CKeys/build identities are expected and must not prevent assessment;
different reader/definition/key provenance is retained as evidence. A changed
reader or evidence producer alone is not a per-link gameplay finding.

Bridge/direct or other unsupported layout combinations are an unsupported
capability with explicit scope explanation, not an empty successful comparison.
If both bridge profiles qualify but an effect dependency is unavailable, observed
bridge references remain useful; effect-input families are globally unassessed.
A missing effect row within a readable PARTIAL source remains per-link UNKNOWN.
Do not manufacture repeated unknown fields for a globally unsupported family.

## Identity, joins and recorded fields

A relation record key is the tuple `(mode, parentItemID, ownLinkRowID)`, with a
versioned namespace and fixed-width unsigned decimal components for lexical
ordering. In bridge mode the own link row is ItemXItemEffect.ID; ItemEffectID is
a separately compared recorded reference. In direct mode the own link row is
ItemEffect.ID. Retain copy-row own IDs and copy provenance. Multiple rows sharing
an effect or SpellID remain distinct; duplicate logical keys are rejected as
invalid source rather than silently overwritten. A changed parent changes this
scope-qualified key: report two observed-only records, not an inferred reassignment
or confirmed addition/removal. Never match links by spell, ordinal or label.

Own row identity follows the existing prepared Items ID convention: source Row.id
is unsigned32, its ID cell must canonically agree, and noninline signed32 ID
metadata alone does not forbid positive IDs above INT_MAX. This identity rule does
not reinterpret signed foreign-key fields. For this first profile, parent ItemID
must be canonical positive signed32 under its declared signed source field;
negative/zero/out-of-range parents are retained as bounded source issues, not
attached to a fabricated unsigned item. Bridge ItemEffectID must parse in its
signed32 source range. Positive values can join; zero/negative recorded references
remain known numeric references with an unresolved target state. Do not bit-cast
negative references into unsigned keys. Referenced target identity may itself be
unsigned32, but signed32 bridge fields cannot identify its upper-half IDs.

| Field | First-deliverable meaning |
| --- | --- |
| Parent ItemID and own link ID | Qualified identity components, with original source cells/provenance retained |
| Bridge ItemEffectID | Recorded signed32 reference, distinct from effect-row readability |
| LegacySlotIndex | Recorded verified unsigned8 slot value, not current list order; zero is a value |
| TriggerType | Recorded verified unsigned8 input; no use/equip/gameplay label inference |
| Charges | Verified signed16 value only; preserve negative sentinels |
| CoolDownMSec / CategoryCoolDownMSec | Verified signed32 recorded inputs; preserve negative/zero values, no effective cooldown arithmetic |
| SpellCategoryID / ChrSpecializationID | Verified unsigned16 numeric references only |
| SpellID | Verified signed32 recorded reference; no lookup or signed-to-unsigned coercion |
| PlayerConditionID | Existing profile marks this field unverified: unassessed candidate evidence, not an eligibility fact |

The retail profile allows raw-physical interpretations on fields3/6/8 (Charges,
SpellCategoryID, ChrSpecializationID). Such cells are **not** admitted semantic
values. Preserve bounded raw value, interpretation policy and warning as unassessed
source evidence. If interpretation is absent and the exact verified candidate
schema qualifies, use the normal strict numeric rule. The direct profile has no
admitted raw-physical overrides. The source-family mask is paired: a field with
unqualified evidence on either side is unassessed as a semantic comparison on
both sides, with the reason retained. Raw evidence is never relabeled as a
cooldown/charge/specialization change.

Fields retain source table, file ID, row ID, column name/index, scalar shape,
width/signedness, copy provenance and policy revision. Compare a verified numeric
cell independently; an invalid Charges value must not erase a valid SpellID.
Unsupported fields and invalid values are typed unassessed/unavailable states,
not known zero. A change in target readability is availability, not a changed
SpellID or a changed effect input value.

## Capture, issues and honest membership

Only `BuildTables.capturePreparedLocal` may obtain dependencies, through dedicated
borrowed comparison endpoints. Do not call check/stat, schema/get/page/relation,
prepareKnown, normal item/effect warmup, local DB2 decode, repair or acquisition.
A prepared dependency that is absent remains a typed scope outcome. Capture each
required source once per endpoint. Never scan a normal table per link.

Retain captured source identity: build/product/locale, file/content key, layout,
definition SHA and resolved block, graph identity, reader/options/row encoding,
effective key fingerprints and capture invalidation policy. Native issue-channel
evidence state, policy, producer and source/block/options/actual-column bindings
must be retained using the same compact provenance discipline as
`IssueEvidenceMetadata`. Do not infer trustworthy empty issues from empty arrays,
READY, short pages, exhausted streams or a source generation number.

Carry page cell issues as affected-cell LIMITED overrides and row-level channel
limitation as UNKNOWN/LIMITED context. Invalid, protected and unavailable evidence
remain distinct. Null row textIssues is an unknown issue channel; explicit empty
issues are known clean only under the captured evidence contract. Unknown/limited
channels do not certify relation parent/ref/ID cells. Reuse prepared Items'
reviewed issue precedence and numeric admission behavior; do not silently treat
null as empty. When a required identity/join cell cannot be admitted, omit that
record from qualified relation inventory and record a bounded counted omission.
Do not derive a relation from a name or another readable row.

These inputs keep relation membership NOT_CERTIFIED. An absent joined target is
UNKNOWN unless exact row protection evidence says PROTECTED; table-level excluded
protected sections cannot identify an individual missing row. An observed relation
on just one endpoint is OBSERVED_ONLY, never ADDED/REMOVED. An empty readable
prefix is “no links observed in captured scope,” not “this item has no effects.”
An observed valid parent/link survives missing Item, effect or spell data.

Captured handles are BEST_EFFORT_INVALIDATING unless their actual receipt says
otherwise. Graph loss invalidates the source; do not silently reprepare/reopen or
switch to the browsed workspace. Sealed scratch owns run inputs. Stronger native
certificates may be recorded as evidence but do not upgrade relation-set absence
in this increment without separate reviewed proof of all relation/admission steps.

## Bounded execution and ownership

One active comparison, one shared32MiB buffer budget and512MiB live scratch budget
remain in force. No fresh quota per dependency, endpoint or sort phase. Maximum
source/candidate frames1MiB, named evidence text8192 characters, input pages256,
result pages100, admitted links5million and scanned dependency identities5million
per endpoint per source. Limits produce typed incomplete scope. Native parser,
prepared bootstrap and decompressor allocations remain separately documented; this
is not a32MiB whole-process promise.

For each bridge endpoint, stage links sorted by `(effectReference, linkKey)` and
stage effect rows by own EffectID. Merge with one current effect and one current
link, streaming all links for an effect; do not collect a fan-out group. Negative
or zero references bypass lookup with explicit unresolved state. Preserve each
link's parent/key and separately sourced target facts. Then externally sort
candidates by the final relation key. Direct mode projects each ItemEffect row
once without a self-join. No in-memory whole-table map, per-parent nested array,
SQL relation index or per-link normal get call is allowed.

Retain unreferenced effect rows only in their charged dependency spool until the
join drains; do not turn them into invented item links. A missing/unavailable
bridge cannot be reconstructed from unrelated effect rows. A missing effect
source can be represented by an explicit empty UNKNOWN-side join input, preserving
observed valid bridge rows without asserting missing targets are absent.

Reuse the actual existing comparison reservations, not arithmetic-only allowances:

- Hold one compact source/scope snapshot reservation of1MiB through final result
  paging. At most one heavy4MiB captured profile exists at a time.
- Source sorting keeps its existing24MiB total (12sort+8codec+4source workspace).
  With snapshot+heavy profile this is29MiB. Extra projector/index/bookkeeping
  allocations share the remaining3MiB and must reserve before allocating.
- A separately implemented many-to-one join may use at most two4MiB heads,
  8MiB codec and3MiB output/projector scratch, plus held1MiB metadata:20MiB.
  Any sort successor starts after these leases close; source spool files remain
  disk-charged until successfully deleted. Join heads must not each silently
  invoke a standalone14MiB reader.
- Final candidates use `SealedCandidateInput` and
  `ComparisonService.MemoryPlan.withSnapshotMetadata()`: two borrowed12MiB
  input/canonical allowances with held1MiB metadata and existing core codec/index
  reservations. Preserve the current combined read/decode/projector/canonical
  contract. A new adapter cannot pass12MiB to the old14MiB sorter reader.

Snapshot metadata has at most four captured source descriptors (two endpoints,
two bridge dependencies), bounded dictionaries/masks and reasons. Enforce its
actual1MiB cap before retaining entries. No extra Item/Spell source profiles fit
by assumption. All staged inputs, output successors, sort runs, frame headers and
failed-deletion files share the same512MiB disk ledger. Reserve before writes;
keep predecessors charged until deletion succeeds. Quota failure must not discard
the only usable input and restart through an unlimited fallback.

Explicit ownership transfer is required for every capture, profile, staged file,
join successor, final adapter and metadata owner. Constructor failure leaves
inputs with the caller unless documented otherwise. Cancellation drains readers,
sort/join workers and canceled deliveries before borrowed endpoints close. Close
never closes the browsed build/shared library. Final private scratch and held
snapshot reservation drain when the comparison session closes, including failed
capture, rejected capability and result-paging cancellation. No native row/cache
repair or comparison report is published.

## Implementation sequence and independent acceptance

1. Independently approve this exact source matrix, identity, issue precedence,
   many-to-one join and budget plan. No production implementation before approval.
2. Add pure source compilation/cell projection in concepts-effects, with a stable
   API checkpoint and old browsing-effects compatibility fixtures. Add compact
   immutable comparison snapshot/source adapter after API review.
3. Add the charged many-to-one spool join and final candidate composition. Prove
   actual same-budget reservations through final paging before GUI integration.
4. Add the explicit Item effects mode, activity, scope summary and source-qualified
   result details. Existing Items behavior remains unchanged. No link absence
   wording or tooltip resolver is introduced.
5. Run source-free authored tests plus an optional read-only prepared-native paired
   proof; report unavailable/partial dependencies honestly. Publish this increment
   as a separate prerelease only after implementation review and repository gates.

Required meaningful fixtures:

- Both exact profiles; unsupported layout/version/column order/ID/relation/kind;
  bridge/direct mismatch refuses assessment without invented crosswalks.
- Strict vs retail raw fields3/6/8 and unverified field9; known raw equality/change
  cannot become semantic gameplay equality/change. One bad cell preserves other
  verified inputs. Negative Charges/cooldown/SpellID and zero values retain signs.
- Copy own link IDs, duplicate links sharing SpellID/effectID, signed parent/ref
  sentinels, upper-half own ID versus foreign-key signedness, mismatched ID cell,
  duplicate key rejection and observed parent-change behavior.
- Missing target in PARTIAL capture vs exact protected target; absent Item row;
  unavailable effect source; empty prefix; no ADDED/REMOVED or “no effects” claim.
- Null/empty/limited/native-certified issue channels, affected-cell overrides and
  mismatched scoped evidence producer/source/options binding; no legacy empty
  issue channel promoted to known-clean. Evidence-only refresh must not emit
  one gameplay finding for every unchanged link.
- Many links to one effect with a bounded head count and no group accumulation;
  unreferenced effect rows; adversarial oversized frame/text,5million limit,
  quota exhaustion while predecessor+successor coexist and deletion failure.
- Real capture→sort→many-to-one join→candidate→ComparisonService→multi-page result
  integration with1MiB metadata still held, both12MiB allowance paths exercised,
  peak reservations≤32MiB and disk≤512MiB, all files/reservations zero after close.
  Cancellation must cover pending capture, sorting, fan-out merge and canceled
  final-page delivery, including borrowed endpoint lifetime ordering.
- Forbidden-source doubles fail if any ordinary table/item/effect, acquisition,
  definition fetch, SQLite publication or persistent comparison API is invoked.
  Session/source replacement rejects late results; native graph invalidation
  cannot silently rebind a completed partial run.

No live data, performance or release completion is claimed by this design.
