# Item effects (layer 6)

`com.vibeminer.concepts.effects` reads item effects through layer 5 Tables and the shared `concepts-core` relation index. It ports the pinned C# `VibeMiner.Concepts/ItemEffects` service, reviewed profiles, cursor rules and public outcomes. References remain at C# `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`; current reference HEAD `1d6632e` has no changes to these contracts.

Retail links use ItemXItemEffect.ItemID/ItemEffectID; Classic links use the resolved ItemEffect.ParentItemID relation. Every link occurrence stays in link-table row order, including copies and zero keys. Inputs retain declared-width or physical raw evidence; candidate foreign keys are hints and are never looked up. SpellName text preserves whitespace and distinguishes empty, invalid, oversized, protected, absent and unknown scope. Profiles remain exact; no trigger meaning or effect labels are invented.

Issue #94 is addressed by additive per-role `DefinitionProvenance`: exact `BUILD` or `LAYOUT` matching, the observed and declared layout hashes, and structured warnings. A layout match emits `ITEM_EFFECTS_DEFINITION_MATCHED_BY_LAYOUT`; the note survives later profile/row refusal. This describes how a definition was selected and does not change the authority of game facts.

`BuildItemEffects.closeAsync` rejects new calls and drains accepted calls before closing Tables. Outward future cancellation cannot skip that drain. Calls use fail-fast bounded virtual operations (1,024 active; `ITEM_EFFECTS_OPERATION_LIMIT`) and a per-handle relation-entry budget (default 4,000,000). The independent consumer fixture `MemoryItemEffects` reads no tables; the same public contract tests run against it and the real service over the independent Tables fixture.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/vimi-gradle-home ./gradlew :concepts-effects:test :concepts-effects:check :concepts-effects:assemble
```

This initial commit contains the complete service surface and profile/contract tests. Lifecycle adversarial tests, stale-source tests and independent C# golden validation are still being completed; no full-reference-parity claim is made by this incremental commit.
