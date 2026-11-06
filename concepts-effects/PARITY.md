# Item-effects source inventory and qualifications

C# compatibility is not a goal (owner decision, 2026-11-05). This file records how the Java port relates to the original C# implementation as background; differences are product choices, not defects.

Pinned C# reference: `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`; root verified the current `1d6632e` has no Concepts/contracts differences.

| Pinned C# source | Java implementation |
| --- | --- |
| ItemEffects/IItemEffects.cs | ItemEffects.java, BuildItemEffects.java, EffectsTypes.java |
| ItemEffects/ItemEffectsService.cs | ItemEffectsService.java, EffectsTypes.Options |
| ItemEffects/ItemEffectProfiles.cs | ItemEffectProfiles.java |
| ItemEffects/ItemEffectQueries.cs | ItemEffectQueries.java; shared framing from concepts-core.ConceptQueries |
| Concepts.Testing/InMemoryItemEffects.cs | testFixtures/MemoryItemEffects.java |

Shared relation indexes remain in `concepts-core.RelationIndexes`, owned and tested separately; this module contains no duplicate relation implementation. All table access goes through public layer 5 `Tables`/`BuildTables`. The effects service imports no lower-layer I/O implementation, generated-store implementation, asset decoder or frontend type.

The complete reviewed profiles are WDC5 ItemXItemEffect (3177687, layout 96F083AD), Retail ItemEffect (969941, 4CA77678), Classic ItemEffect (969941, 1BF9CF3A) and SpellName (1990283, 782EE721). File IDs, layout, definition fields, physical header columns, ID/relation storage, names, signed widths and optional raw evidence must all agree. A new or malformed profile is Unsupported, never inferred. Inputs retain original values and candidate hints; no trigger/effect meaning is invented.

Issue #94's additive Java source provenance records exact BUILD/LAYOUT selection and both observed/declared layouts. Only LAYOUT emits `ITEM_EFFECTS_DEFINITION_MATCHED_BY_LAYOUT`. The warning survives downstream profile or row failure and does not promote layout-based selection into exact-build authority.

Source states retain checked table entries and distinguish unavailable/retryable, not-in-build, not-in-locale, protected, unsupported, corrupt and resource-limit outcomes. Definition failure state belongs only to applicable Unavailable/Unsupported problems. Partial misses remain UnknownOutsideReadableScope; keyed rows retain their key names. Names preserve whitespace, invalid-text issue codes, copies and UTF-16 unit bounds.

Cursors use the original policy/binding and canonical web-JSON/base64url framing. The real service stores consumed match count and exact relation identity; stale cursors are never rebased. The independent fake shares validation and uses its own opaque identity, with consumed-count continuation semantics consistent with the public contract.

Java lifecycle hardening: accepted calls drain before Tables closes; canceling an outward future cannot bypass the drain. Explicit cancellation is checked before work and again before publication, including a lower provider that returns a terminal reply while cancellation arrives. Failed/abandoned opens dispose acquired table handles, and malformed provider outcomes fail their future rather than leaving it pending. Virtual work has fail-fast admission at 1,024 operations, without nested permit waits. The relation-entry budget is per handle, default 4,000,000.

Validation includes one shared real/fake contract, authored boundary/adversarial cases and original C# service/cursor goldens. No production C# functionality is silently omitted; the Java provenance warning intentionally fills an existing backend gap. Fixtures establish supported behavior, not universal live-build coverage or matching physical memory/performance.
