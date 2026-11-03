# Item effects (layer 6)

`com.vibeminer.concepts.effects` reads item effects through layer 5 Tables and the shared `concepts-core` relation index. It ports the pinned C# `VibeMiner.Concepts/ItemEffects` service, reviewed profiles, cursor rules and public outcomes. References remain at C# `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`; current reference HEAD `1d6632e` has no changes to these contracts.

Retail links use ItemXItemEffect.ItemID/ItemEffectID; Classic links use the resolved ItemEffect.ParentItemID relation. Every link occurrence stays in link-table row order, including copies and zero keys. Inputs retain declared-width or physical raw evidence; candidate foreign keys are hints and are never looked up. SpellName text preserves whitespace and distinguishes empty, invalid, oversized, protected, absent and unknown scope. Profiles remain exact; no trigger meaning or effect labels are invented.

Issue #94 is addressed by additive per-role `DefinitionProvenance`: exact `BUILD` or `LAYOUT` matching, the observed and declared layout hashes, and structured warnings. A layout match emits `ITEM_EFFECTS_DEFINITION_MATCHED_BY_LAYOUT`; the note survives later profile/row refusal. This describes how a definition was selected and does not change the authority of game facts.

`BuildItemEffects.closeAsync` rejects new calls and drains accepted calls before closing Tables. Outward future cancellation cannot skip that drain. Calls use fail-fast bounded virtual operations (1,024 active; `ITEM_EFFECTS_OPERATION_LIMIT`) and a per-handle relation-entry budget (default 4,000,000). The independent consumer fixture `MemoryItemEffects` reads no tables; the same public contract tests run against it and the real service over the independent Tables fixture.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/vimi-gradle-home ./gradlew :concepts-effects:test :concepts-effects:check :concepts-effects:assemble
```

Validation: 81 deterministic unit/contract cases and one optional C# regeneration case pass, with zero failures or skips. The contract runs against the independent layer 6 fake and the real service over independent layer 5 tables. Further tests cover canonical widths and raw evidence, actual pinned bundled schemas, partial/protected/empty scope, copies, Unicode UTF-16 text limits, exact cursors, stale identities/link choices, relation-budget refusal, bounded admission, late cancellation, reentrant close, canceled outward futures and late-open ownership.

The C# harness runs the original Release `ItemEffectsService` and linked original `InMemoryTables.cs`, with the original bundled definitions. Six synthetic service scenarios establish the same link/effect/name facts and raw evidence; separate binding/cursor goldens are byte exact. It uses no fabricated production service or contract stubs. Build the reference Concepts Release project first, then run:

```sh
./gradlew :concepts-effects:interopTest -PreferenceRoot=/path/to/vibeminer -Pdotnet=/path/to/dotnet
```

The optional harness writes fresh outputs only under `build/`. Checked-in synthetic goldens contain no game content. Text fixtures are pinned to LF to keep Windows checkout conventions out of canonical cursor comparisons. The facts projection deliberately excludes Java's new definition provenance, which fills the C# backend gap and has dedicated Java tests.

All four pinned ItemEffects source files are accounted for in [PARITY.md](PARITY.md). This establishes supported behavior against synthetic and original-code fixtures; it does not certify every live build or equal physical memory/throughput. Native Windows machine testing remains the repository's deferred platform task.

## Preparation and on-demand media

Concept handles expose `warmup(priority, token)` with immutable per-lookup results.
Preparation builds only reviewed, bounded table indexes and reports unsupported,
partial or resource-limited inputs explicitly. Retain the same warmed handles in
the application context; closing and reopening them discards in-memory indexes.
Warmup performs no icon/model/texture I/O and does not claim universal table readiness.

## Bounded spell text previews

`SpellTexts` borrows immutable build/locale tables and resolves selected spell
text asynchronously, with at most four accepted operations, 128 cached previews,
200,000 relation-index entries, 16 KiB text and 256 substitutions. Close cancels
and drains its own accepted work without closing borrowed tables or cancelling
caller tokens. Plain descriptions do not build a SpellEffect index.

The first numeric profile supports reviewed Forever fixed percentage aura
amounts. Other tokens/formulas remain typed unresolved values; raw templates and
source cells are retained separately from display text. See the profile and
read-only validation details in [Talents](../docs/talents.md#spell-value-previews).
