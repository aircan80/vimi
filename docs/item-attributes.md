# Item attributes: Layer 6 contract and remaining calculation work

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

## Allocation-based calculator remains to implement

C# and the original Java port expose `StatPercentEditor` and
`StatPercentageOfSocket`, not a completed Retail tooltip calculator. The new
projection returns `REQUIRES_CALCULATION` for that layout instead of fabricating
amounts. The GUI displays this issue beside source facts.

The backend follow-up needs an explicit item context (at least build, base/effective
item level, quality, inventory slot and relevant bonus/scaling context), versioned
policies and lookups for the applicable budget, socket cost and scaling data. Some
inputs may need additional format/table support. Missing/protected/unsupported
inputs must preserve typed outcomes; absence must not be treated as zero. Numeric
rounding and float precision require reviewed build-specific fixtures.

Reference evidence, reviewed 2026-11-01:

- The pinned C# `contracts/item-facts-v1.md` distinguishes direct Classic amounts
  from Retail allocation inputs. Java retains that raw-fact contract.
- [TrinityCore stat identifiers](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Entities/Item/ItemTemplate.h)
  document the stat ID vocabulary; IDs are data identifiers, not translated labels.
- [TrinityCore item logic](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Entities/Item/Item.cpp)
  demonstrates that allocated stats depend on effective item level, random-property
  budgets and socket costs, while effective level itself has squish/scaling rules.
  This is research evidence, not a universal algorithm certified for every build.

The calculator belongs in `concepts-items`, consuming lower-layer table/game-data
services. Its result should extend the attribute contract with calculation scope,
resolved inputs and unsupported reasons. GUI tooltip work does not certify that
calculator as finished.
