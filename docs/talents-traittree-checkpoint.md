# Forever TraitTree talents checkpoint

Issue #164 reported that Forever talents were read from legacy `Talent`/`TalentTab`
rows that still have the Classic 1.12 layout. This checkpoint records what was
checked against actual client tables, what the reader now does, and what is
still unverified.

## Evidence

Read-only probes opened the locally prepared `wowf` 1.60.1.70707 (`us`) and
1.60.1.70755 (`us`) builds through the normal `TablesService`. They read the base
DB2 tables only: no client hotfix cache was applied, and no game payloads, cache
bodies or keys were copied into the repository. Results for both builds:

- `SkillLineXTraitTree` has 9 rows. Each one maps a class skill line (from
  `SkillRaceClassInfo.ClassMask`, a single class bit) to one TraitTree. For example,
  Druid SkillLine 574 maps to TraitTree 1089.
- Each class TraitTree has 50–53 `TraitNode` rows. All of them are Type 0 with exactly
  one `TraitNodeXTraitNodeEntry` link. The multi-entry and tiered nodes found in the
  data belong to unmapped trees (1083, 1187–1189).
- In each class tree, the `TraitNodeGroup` rows whose member sets are not contained
  in another group are exactly three disjoint sets that cover every node. These
  are the three Classic trees; the smaller groups are the tier gates.
- Every legacy `TalentTab` of a class matches exactly one of those sets by the
  largest number of shared spells (7–16 shared, with at most one shared spell
  with any other set). All 27 tabs match one to one, left to right.
- Node positions use a 600-unit grid. The three trees start at X 1020, 5020 and
  9080, and tiers start at Y 2130. Exceptions: some nodes are nudged by 10 units
  (Paladin 5030 in 70707, Warlock 2120/3320), and three nodes are parked far
  outside the grid: Hunter Lightning Reflexes (102800, 5740), Hunter Improved
  Serpent Sting (6820, 39300) and Priest Holy Specialization (9280, 21300).
- TraitEdge types in class trees: 2 (71–72 edges), 3 (1–2) and 0 (1). Hunter
  Intimidation and Bestial Wrath are linked by type-2 edges in both directions.
- The Druid trees confirm the report. Overgrowth (spell 17245) is in tier 5, not
  tier 1. Genesis, Nature's Majesty, Nature's Splendor, Eclipse, Living Spirit,
  Wild Growth and the Gift of the Earthmother nodes are present. Nature's Grasp,
  Natural Weapons, Improved Thorns and Omen of Clarity are not in TraitTree 1089.
  Sharpened Claws → Blood Frenzy has one edge.
- 70707 and 70755 differ: for example, the lower Feral tiers (Lacerate, Predatory
  Discipline, Shifting Power placement) and the Paladin Protection nodes. The
  reader takes everything from the build that is open; nothing is copied from
  one build to another.

The talentsforever.com 70753 comparison and any 70707 client hotfix effects were
not verified. Build 70753 is not prepared locally, and no 70707 hotfix cache was
available.

## Reader

`TalentsService` selects the source per build:

- Forever products (`wowf`, `wow_classic_beta`, `wow_cn_beta` at 1.60.x) that record
  `SkillLineXTraitTree` use the TraitTree reader. Other Classic products, and Forever
  builds without that table, keep the legacy reader.
- An unreadable `SkillLineXTraitTree` (encrypted, missing definition, and so on) is
  an explicit `TALENTS_TRAIT_TABLE_*` failure. Legacy rows are never used in its
  place.
- WoWDBDefs marks most trait field names as unverified. They are accepted only for
  the physical layouts listed in `TraitTrees.REVIEWED_LAYOUTS`, which are the layouts
  in both probed builds. Any other layout fails with `TALENTS_TRAIT_LAYOUT_UNREVIEWED`.
- Trees come from the maximal node-group partition. Overlapping groups, or fewer
  than three groups in complete tables, fail with `TALENTS_TRAIT_PARTITION_UNSUPPORTED`.
- Placement picks the grid anchor that places the most nodes, then refines it to
  the most common exact anchor. The tolerance is ±60 units on the 600-unit grid,
  with 4 columns and up to 11 tiers. Nodes outside the grid, or that lose a shared
  cell, stay readable in the tree's unplaced list with the reason.
- `TraitDefinition.SpellID` is the talent spell for every rank, and
  `TraitNodeEntry.MaxRanks` is the rank count. Override names, descriptions and
  icons are used when they are present.
- Edge types 2 and 3 are prerequisites. They record no rank requirement, so none
  is shown. Type 0 (visual only) is not drawn. For a pair linked in both
  directions, only the link from the higher node to the lower one is kept, and an
  issue says so.
- Tree names and backgrounds come from legacy `TalentTab` only when the shared-spell
  match is one to one. Otherwise the trees are named "Talent tree N" and an issue
  explains why.
- Choice or tiered nodes, and nodes whose entry or definition rows are unreadable,
  are counted in an issue and are not shown.

## Not done here

Point requirements from `TraitCond` tier gates, `TraitNode.Flags` meanings and
`VisibleSpellID` are not interpreted. Issue #165 (stale-row policy, description
tokens, rank UI and retry behaviour) is separate work.
