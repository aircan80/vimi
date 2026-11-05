# Talents browser

Open a prepared Classic-style build and select **Talents** after **Creatures**.
Choose a class on the left; the main area displays its ordered trees. Partial
client tables show all readable classes, trees and talents with an incomplete
data warning; protected records do not hide the readable records. A missing
prerequisite keeps its source ID in details and omits the unresolved connector.
Select a talent to inspect rank spell IDs, prerequisites and any readable spell
text in a fixed right-hand WoW-style tooltip. Its rank label previews the first
recorded rank's spell text; it does not represent learned character points.
Unreadable descriptions and prerequisite names stay explicitly unavailable.
Technical source details remain collapsed outside the tooltip. Arrow keys move
focus to the nearest node along a row or column; Enter selects it. The tree strip
scrolls horizontally when the detail rail leaves less room.

Forever builds that record TraitTree talents read them from `SkillLineXTraitTree`,
`TraitNode`, `TraitNodeEntry`, `TraitDefinition`, node groups and `TraitEdge`, not
from the legacy Talent rows that keep the Classic 1.12 layout. Talents recorded
outside the tree grid are listed under their tree with the reason. Trait edges
record no rank requirement, so prerequisites show only the required talent. See
the [TraitTree checkpoint](talents-traittree-checkpoint.md) for the evidence and
limits. Other Classic products keep the legacy reader.

Class names, trees, positions and ranks come from the selected build's verified
client tables. Rank badges read "Max N": the recorded maximum rank, not points
spent. The browser does not allocate points or load character builds. Legacy
Talent rows show the Classic tier gate ("Requires N points in <Tree> Talents")
and their prerequisites with the recorded points ("Requires N points in
<Talent>"); TraitTree tier gates (`TraitCond`) are not interpreted, and trait
edges record no rank, so they show "Requires <Talent>". A legacy talent with
several rank spells also shows its next rank's description. The hover tooltip
carries the same facts as the detail rail. The header and Source details name the
source build. It does not replace unavailable data with another game's or build's
talents.

Icons load on demand through the build's existing file/image services. Missing
icons use neutral artwork, never a glyph that reads as data; the reported cause
(no SpellMisc icon, different icons for one spell, an unreadable or over-bound
SpellMisc table, an image or file failure) is in the icon's tooltip and in Source
details. Unresolved background art uses a plain dark tree surface.

Optional spell names, descriptions, icon references and legacy tree names are
read again on the next request after a failure, so Reload recovers from a
transient read or schema failure. Reload with unchanged source data reloads the
selected class. Unsupported models (including Retail and Mists) explain that their
layout is not implemented, instead of showing fabricated Classic trees.

Component contract and checks: [concepts-talents](../concepts-talents/README.md).

## Layout preview

This screenshot uses authored fixture nodes and placeholder icons to review the
layout. It does not represent a real build's talent data or a character's points.

![Authored three-tree talent layout](images/talents-tooltip-authored.png)

## Spell value previews

The selected first rank resolves reviewed `$s1`–`$s5` fixed percentage amounts
through same-build SpellEffect rows. A tokenizer keeps all readable prose: each
value it cannot resolve shows `?` in place, with its cause in Source details.
Tokens are case-insensitive (`$S1`, `$D`). Colour, link and newline markup is
rendered as text. `$lsingular:plural;` follows the preceding resolved amount and
shows both forms otherwise; `$gmale:female;` always shows both. `$/N;s1` and
`$*N;s1` scale a resolved amount (at most two decimals). `$<spell>s1` and
`$<spell>d` read up to eight other spells' rows. Expressions (`${...}`),
conditionals (`$?...[...]`), `$@` references and named variables are never
evaluated and show `?`. Raw templates and exact substitution provenance remain
in collapsed Source details, not the styled tooltip. SpellEffect and SpellMisc
reverse indexes have separate budgets.

The original numeric profile is deliberately limited to Forever 1.60, layout
`5362E3D4`, APPLY_AURA effects with percentage modifier/critical chance aura IDs
108/290. It uses integral `EffectBasePointsF` directly, with no legacy `+1`;
random, level, resource and scaling coefficients must be zero, and the reviewed
bonus/group/PvP defaults must equal one. The unverified field name at raw integer
column 24 has no assigned semantics; the reviewed layout conservatively requires
its value to be zero. Other profiles and formulas remain unresolved.

Read-only verification against the cached 1.60.1.70707 build matched spell 16942,
EffectIndex 0, SpellEffect row 693970 and base amount 2, yielding `2%`. Spells
16858 and 16998 also matched base previews 8%/3% and 20%. No game data payloads or
local cache paths are included in the repository.

Relevant primary implementations are the
[WoWTools effect formatter](https://github.com/Marlamin/WoWTools.SpellDescParser/blob/master/WoWTools.SpellDescParser/Nodes/Property.cs),
[SpellEffect definitions](https://github.com/wowdev/WoWDBDefs/blob/master/definitions/SpellEffect.dbd),
and [aura constants](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Spells/Auras/SpellAuraDefines.h).

![Authored resolved spell preview](images/talents-spell-text-authored.png)

This image uses authored text/pixels to test numeric preview presentation.

The shared resolver also supports the separately reviewed static percentage and
fixed-duration forms described in [item spell previews](item-spell-text-proposal.md).
This does not add general client expression, character scaling or script evaluation.

## Missing image and description explanations

The selected talent's collapsed **Source details** section includes separate image
and description status. Image results retain the actual decoder/file outcome,
reported code/message, FileDataID and bounded missing-key identifiers. Unsupported
formats, required downloads, protected bytes and validation failures remain
distinct. Expanding this section uses already requested evidence; it does not
start an additional source query.

A missing icon reference or null description does not establish why it is missing.
These cases explicitly say the cause was not reported. Class-level source notices
are labelled as context rather than attributed to every selected talent. Raw
client text and spell substitution sources remain available in the same section.
