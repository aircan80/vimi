# Instance Map/WDT source compatibility notes

Read-only compatibility checkpoint for the instance/dungeon/raid team. No parser,
source factory, terrain packet or renderer behavior is changed here.

Current repository behavior:

- `WorldFormats.wdt` reads MVER18, MPHD's global-model bit, MAIN and all eight MAID
  references. It ignores global object/name declarations and requires MAIN even
  when the global-model bit is set. A global-model source without MAIN therefore
  cannot currently reach a successful inventory. This is an implementation limit,
  not evidence that the map has no scene.
- `WorldStreamScopes` retains only the qualified Map→numeric WDT binding, checked
  WDT CK and packed MAIN/MAID facts. It does not retain global scene kind or a WMO
  reference. Empty MAID cannot authorize an invented terrain tile or WMO asset.
- `WorldView.emptyInventoryMessage` explicitly reports that recorded global-world-
  model previews are unavailable. Instance metadata can be readable while no
  terrain starting tile is available.
- `WorldPlacements.readObj0` is an ADT18 profile, and WorldPlacementSource binds
  its input to a terrain tile's obj0 reference. Its MODF parser distinguishes
  direct FileDataID via0x8 from a name-table index, but that ADT profile alone does
  not prove the same interpretation/resolution for a WDT. It ignores legacy name
  blocks, so it cannot resolve MWMO references by itself.

Reuse the new private qualified Map-row/source identity and fresh Map-stamp
revalidation. Do not infer geometry kind from caller MapInfo.instanceType/name,
zone data, a guessed directory or the absence of MAID. Factor checked WDT input
identity/actual length away from the terrain-only readMaid projection before
adding global scene facts. A new separately reviewed WDT scene parser can then
expose typed declarations from those same bounded actual bytes:

- TERRAIN: recorded terrain references with no supported global declaration.
- GLOBAL_WMO: a positively parsed/resolved global declaration, with no terrain.
- MIXED: both recorded terrain and a supported global declaration, if the admitted
  source profile permits that combination.
- DECLARED_UNSUPPORTED/EMPTY: explicit global flag/declaration whose reference
  profile cannot be resolved, or a source with no supported scene declarations.

These are proposed result categories, not claims about all WoW format variants.
The actual instance-WDT probe must pin whether MAIN is optional for the supported
WMO-only variant, exact MPHD/placement flags, placement cardinality, transforms,
doodad/name sets, scale and name-reference semantics. A declared global bit alone
cannot manufacture a readable WMO reference; unknown chunks/flags remain explicit
unsupported facts. Preserve ordinal/source order and original coordinate units.

For a direct reference, derive the numeric WMO FileDataID only from the admitted
record's reference-kind rule, then stat/read it through the same build/locale
BuildFiles and retain actual CKey/consumed-length evidence. For a name reference,
resolve ONLY an in-range recorded name/offset from the same checked WDT name
payload, under a bounded, terminated-string profile. Preserve that original
recorded virtual name and bind the resolved numeric file/CKey. Do not guess
world/maps paths, reinterpret every integer as an ID, search similarly named
assets, or attach a WMO proof to a synthetic Tile(0,0).

The private registry should retain an explicit scene-facts variant: packed MAID
for terrain and a separately bounded global-WMO declaration/proof for global
scenes. External scope values remain detached small tokens; current+replacement
heavy proof count remains two. No extra map→scene cache. A WMO-only scope should
not need a fake64×64 grid. Any additional declaration metadata must receive a
named bound inside the reviewed source ledger; resource-heavy WMO root/group
geometry/material owners need their own accounting.

Keep PreparedGeometry/PreparedTile narrowly terrain-specific. A WMO/global-scene
factory requires its own actual source proof and typed payload. The first global
scene may use a separate reviewed renderer delivery while terrain hot streaming
continues; don't widen the terrain packet's vertices/triangles/atlas limits or
reinterpret its tile lease identity to smuggle whole WMO groups. Mixed scenes
need a coordinator that represents global geometry once and terrain by its real
recorded tiles, with independent pending/partial activity. Loading unsupported
instance geometry must not make the selected map disappear into an empty tile UI.

Coordinate with the WMO agent on a private actual-source probe. Record only facts
and digests in repository notes; keep original WDT/assets outside Git. Required
source fixtures/probes are global-without-MAIN, global+empty MAIN, recorded mixed
source (if supported), direct and recorded-name references, malformed/ambiguous
names/placement count, changed WDT or WMO CK, missing source and cancellation.
All must derive Map→WDT from the owned qualified row. Scene selection should expose
honest availability and preserve the last accepted image while object roots,
groups and materials prepare; CPU/native draw threads do no source IO.
