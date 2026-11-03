# Build Comparison: business requirements

Status: proposed for independent peer review. This file is the business-analyst
contribution requested in #117; it does not authorize implementation before the
consolidated design has been reviewed and approved.

## User outcome

Choose two observed builds and understand what changed in every supported browser
area, with enough source evidence to tell a real data change from a gap in our
knowledge. The comparison must remain useful when only part of either build is
available. It must not download an entire build merely to show a comparison page.

The principal workflow is: select baseline and target, inspect compatibility and
prepared-data coverage, run an explicit comparison, scan area summaries, filter
changes, and inspect before/after facts with provenance. Preserve a chosen pair
and readable completed results across application restarts when their captured
inputs and interpretation policy are still valid.

## Required endpoint context

An endpoint identifies product, exact build/configuration identity and locale;
a version label alone is insufficient. Default to the same enabled product and
locale. Respect Settings → Products in new selectors. Explain incomplete metadata
or unavailable cached inputs before starting, without hiding an otherwise useful
partial comparison.

Cross-product and cross-locale comparison need explicit modes and area-specific
compatibility. Do not silently treat identical numeric IDs across unrelated
products as equivalent gameplay entities. A locale-only text difference must be
recognizable as localization. Reversing baseline/target reverses directional
changes without changing the captured source facts.

Do not block every area because one area is unavailable. Each area carries its
own readable coverage, membership certainty and supported interpretation. The
meaning of a missing result is part of the result, not an implicit zero.

## Every current area has an honest entry

The area inventory is Overview/context, Files, Tables, Items, Creatures, NPCs,
Quests, Talents, Races, Dungeons, Mounts, Professions/Recipes and World. Spells can
appear through supported talent/item-effect/table facts; do not advertise a full
standalone spell-browser comparison that the application cannot supply.

Every area must either present supported changes or explicitly state what cannot
yet be compared. A planned adapter is not a zero-change result. Generic table or
file differences can be useful fallback evidence, but cannot stand in for an
implemented semantic adapter while claiming that area's gameplay facts are equal.

The first implementation may be delivered in reviewed stages, provided the
screen continuously discloses area capabilities and release notes name the exact
implemented scope. The complete epic requires the agreed adapters for all current
areas; an initial slice alone must not close #117.

## What counts as a change

- Added/removed membership needs proof that the opposite endpoint's relevant
  membership is complete. A readable new row against a complete old index can be
  a confirmed addition even if the new table is partial; the symmetric rule
  applies to removals. Otherwise use an uncertainty/readability state.
- Modified facts require comparable, known values at both endpoints. Unknown,
  unavailable, protected/encrypted, absent and known empty/null are distinct.
- A row may contain confirmed field changes and additional unresolved fields.
  Show both; do not erase a known change because another field is unreadable.
- Newly readable or newly prepared data is a knowledge/availability change until
  evidence supports a client-data change. Parser/schema-policy changes likewise
  need their own attribution.
- CKey/EKey/hash differences establish byte/content-identity facts only. They do
  not, by themselves, establish a balance change, new model, new quest behavior
  or other gameplay interpretation.
- Separate asset/appearance evidence from metadata. Static rendered samples do
  not establish animation, runtime customization or actual player appearance.

For NPCs and Quests, preserve the existing field priority Blizzard → Questie →
Polestar → EG Link. Capture the winning value and all relevant source observations.
A provider snapshot or user mapping change is a source-context change, not
inherently a Blizzard build change. Equal resolved values with different winning
provenance are not a fabricated value modification. Retain both dimensions when
client and provider facts actually changed.

## Acquisition, freshness and persistence

Merely opening the area or choosing endpoints must not start CDN/source refresh.
The default run uses prepared/cached data. Preparing missing inputs is a separate
explicit action with its normal progress/cancellation and existing source policy.
It must not prevent a cached partial comparison.

Pin input build/content identities, locale, provider snapshots/mappings,
coverage/readability evidence and parser/projection policy for a run. Source
refresh or subsequent downloads must not silently rewrite a displayed completed
result. Show that newer inputs are available and offer recomputation. Reuse a
stored result only for an exact valid captured context.

Work must be bounded, asynchronous and cancellable. Display area-level progress
and trustworthy processed/unknown counts. Never mark a cancelled/failed run as a
complete zero-change result. Durable resume is optional until the architecture
can prove identical inputs and safe checkpoint boundaries; cancellation plus
bounded restart is an acceptable initial contract if labelled honestly.

Comparison-derived payloads follow the existing unified catalogue/object-store
boundary and build ownership rules. GUI code must not acquire direct SQL access.
Closing a visible build must not free a table/file handle still borrowed by an
active comparison; the architecture must define ownership/leases before coding.

## Size/accounting context

Separate published content differences from local download/storage changes.
Stored bytes, shared-object accounting and derived-cache growth can change without
any client-data change. Preserve the existing original/shared/derived meanings;
do not count SQLite overhead or imply that all published encodings equal an
installed game footprint. If size evidence is incomplete, label its scope and
unknown portion rather than displaying an exact unsupported total.

## Acceptance and review gate

Before implementation, consolidate the WoW domain, mining/contracts, UI and BA
contributions; resolve any contradictory classifications or scope promises. An
independent reviewer who did not write those contributions must review the
consolidated design. Record findings, fixes, approval and the exact reviewed
revision in the repository and #117. Peer approval is the requested gate; the
user has asked us to continue autonomously and retain nonblocking questions.

Implementation acceptance includes deterministic completeness/unknown/provenance
fixtures, bounded work and lifecycle/cancellation tests, persisted-context cache
validation, truthful unsupported-area presentation and authored UI review. Use
focused component checks during development, then the established native CI and
prerelease workflow for the integrated major feature. No game payloads, real-data
screenshots, credentials or local configuration belong in committed fixtures.

## Nonblocking follow-ups

Export formats, automatic background recomparison, exact visual/animation diffs,
and signing/notarization are optional later scope. Native Windows installation
and filesystem acceptance remain tracked separately. None should block a useful,
truthful metadata comparison or require an invented product decision now.
