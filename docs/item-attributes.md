# Item attributes: Layer 6 source and base calculation contract

The Items game-concepts layer owns stat identification, pairing, amounts, scaling
and provenance. Layer 7 renders supplied text/style; it must not interpret stat
allocations, apply item budgets or join gameplay tables itself.

## Implemented direct base projection

`ItemDetail.attributes()` returns immutable `ItemAttributes`: a state, attribute
lines and issues. Each attribute has its original array slot, stat ID, English
label, exact integer amount, display text and source cells. For example, a direct
slot containing type 7 and amount 20 produces `+20 Stamina`.

Slots are paired by explicit array index, table and row identity, not by positions
in two potentially reordered lists. Unknown types, malformed text, missing partners
and ambiguous slots remain explicit. Zero and unused slots do not create lines.
No allocation input is interpreted as a flat amount. Raw facts remain unchanged.

These are **direct base source values**, not a promise of the character's final
in-game tooltip. Labels are currently English and reviewed stat IDs are bounded.
Character context, bonus lists, enchantments, gems, scaling and derived armor or
weapon damage are outside this first projection.

## Base allocation calculation

`BuildItems.inspect(id, priority, token)` is the separate selected-item call. It
returns the unchanged source `ItemResult`, resolved attributes and an explicit
calculation scope. The GUI renders those lines; it contains no budget formula.
`getData` retains its table-only compatibility contract.

The versioned `base-item-budget-v1` policy reads the base item level, quality and
inventory slot, joins the corresponding `RandPropPoints` float budget, aligns
`StatPercentEditor` and socket shares by original array slot, and reads the build's
numeric `GameTables/*.txt` inputs through Files. Stamina and combat ratings use
their category multipliers. Socket penalties use nearest-even rounding; final
supported positive amounts use nearest-integer rounding. Exact input cells remain
attached to each result. Parsed GameTable inputs persist in generated storage.
`prepareAttributes` acquires these small gameplay inputs during preparation; icons,
models and textures remain on demand.

The supported profile is **base source attributes**, excluding bonus lists,
enchantments, gems and player-level scaling. Nonzero scaling/squish/offset context,
unknown stat types, unsupported schemas, missing/protected budgets and missing
multiplier rows remain explicit. Shields and holdables with multiplier-dependent
stats remain unresolved because the reviewed reference implementations disagree
on their category. Direct Classic values continue to use their existing path.
This is not a certified reconstruction of every WoW build's final in-game tooltip;
in particular, live WoW Forever 1.60.1.70707 numeric parity still needs validation.

Tests cover reordered slots, semantic budget joins, socket penalties, stamina and
rating multipliers, missing inputs and refusal to invent scaling values. The strict
bounded GameTables reader rejects ambiguous columns, duplicate IDs, invalid UTF-8,
non-finite numbers and malformed row shapes.

Reference evidence:

- The pinned C# `contracts/item-facts-v1.md` distinguishes direct Classic amounts
  from allocation inputs. Java retains that raw-fact contract.
- [TrinityCore item logic](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Entities/Item/Item.cpp)
  and [budget slot selection](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Entities/Item/ItemEnchantmentMgr.cpp)
  describe item-level budgets and effective-level context.
- [SimulationCraft allocation calculation](https://github.com/simulationcraft/simc/blob/midnight/engine/dbc/sc_item_data.cpp)
  supplies the reviewed socket rounding and multiplier sequence for this bounded
  base profile. It is research evidence, not proof of parity for every client.
