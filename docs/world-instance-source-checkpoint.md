# Recorded instance sources: selected-build checkpoint

Status: read-only-original/private-scratch investigation, not parser, material, transform or renderer approval. Shared WMO city work remains the first visible-exterior track. This evidence supports the separate World Explorer instances architecture.

## Discovery is not complete

Selected build is wowf1.60.1.70755 enUS. The inspected saved Map table FileDataID1349477, CKf8fc790a503d856e64fbb552aba2897f, layoutD43AFAC3 is PARTIAL:73readable of81declared rows,3excluded sections/8records. AreaTable is also PARTIAL. These results establish specific readable rows, not an exhaustive instance list or absence of other maps. No private key or game setting was used or modified.

Actual Map row fields and source receipts:

| Map row | Recorded name / directory | InstanceType | WDT FileDataID / CK |
| --- | --- | ---: | --- |
|34|Stormwind Stockade / StormwindJail|1|791060 /ef73d5b735d90b98504c36ac6cd9749d|
|249|Onyxia's Lair / OnyxiaLairInstance|2|789922 /f0f56c70a2d122b12e925530e58a406c|
|389|Ragefire Chasm / OrgrimmarInstance|1|789981 /9a430840a3bfbce513ff5a529b0de3b2|

These three WDT bodies are163,980bytes each. Each records MPHD0x241, a MAIN inventory with zero active ADT tiles, MAID, and one explicit global MODF. The global declarations directly reference roots108631/109474/109483 respectively, flags8, unsigned uniqueID0xffffffff, raw position(0,0,0), rotation(0,0,0), raw scale1024. Flags8 select direct FileDataID but do **not** enable scale; do not reinterpret the scale field as ordinary city flags0xC.

Counterexamples from other readable instance rows prove that InstanceType cannot choose the source format:

| Map | Actual WDT | MPHD | Active MAIN tiles | Global MODF |
| --- | --- | --- | ---: | --- |
|33 Shadowfang Keep|790796 /a2a001404d08118adb57409f050a8e60|0x248|25|none recorded|
|36 Deadmines|780605 /7ff13af0d3bd9d66b46314ec156e3050|0x248|36|none recorded|
|47 Razorfen Kraul|790640 /4d0f0bb4bb49b3247ae27c7574e6e982|0x248|6|none recorded|

Those ADT-based cases may contain ordinary WMO placements; obj0 contents were not inspected for this checkpoint. Classify them as terrain-present/global-absent, not complete terrain-only appearance. A mixed global/terrain mode remains an explicit possible inventory state, but this sample does not establish an actual mixed-global WDT.

## Smallest promising complete root inventory: Onyxia

The actual Map249→WDT789922→global MODF references root109474. Its complete body is6,408bytes, CK7d98c803c26039b616c1d33910380099. WMO version17, root flags5,8groups,7portals, LOD count0, exactly8GFID IDs. Root-local bounds are(-63.245945,-26.111956,-96.47517)..(218.83295,286.4644,49.59112). Portal payloads are336MOPV/140MOPT/112MOPR bytes. This is recorded portal data, not an implemented traversal graph or floor inventory.

All eight exact GFID group bodies were acquired and inspected, with complete/nonprotected streams, unchanged pre/post receipts and independently checked decoded MD5=CK. Root plus groups total1,327,017bytes. No group-name-derived paths were used.

| Group ordinal / ID | Bytes | CK | Raw flags | Vertices / triangles / batches |
| --- | ---: | --- | --- | --- |
|0 /109475|39,616|2a2aa2cd58c39120b250eb334323923d|0xA09|793 /860 /6|
|1 /109476|215,168|3f6353fb2e122c23812bafb101c99ab6|0x3805|3,313 /5,574 /12|
|2 /109477|172,402|a7f7a96ed565f34501847d5bda4eb02e|0x3805|2,636 /4,397 /9|
|3 /109478|19,662|7fa4fef2ce11ad0156d35b74eeab175e|0x2A05|311 /482 /6|
|4 /109479|130,474|b325d1a01b4c0d6f5f8a2aeb58976a0e|0x2A05|2,061 /3,227 /6|
|5 /109480|128,553|e3541b22cf66ebdf6bc0538d0cd57bbd|0x3805|1,913 /3,370 /7|
|6 /109481|5,830|bc2e89e7a5e2b981441d36ff8853c082|0x2005|108 /129 /3|
|7 /109482|608,904|5a94cb3e2173a574361e19468f07fde9|0x2805|11,941 /13,180 /10|

Totals are23,076vertices,31,219triangles,59batches. Every group has one UV stream. Seven groups have MOCV; groups1/2/5 also contain MLIQ. These are source-inventory counts, not full material-span/normal/winding validation or visible-surface approval. Liquids remain separate. This geometry fits the proposed general WMO element counts, but source counts do not establish the combined allocation budget.

All19MOMT declarations record flags0/shader0/blend0 and one positive texture1 with texture2/3zero; they reference18distinct texture IDs. Texture bodies and their aggregate selected-pixel budget have **not** been qualified for this instance. The city's eight-image handle cap would not admit the whole root; neither a silent handle increase nor ignoring materials is approved. A reviewed higher handle count with unchanged aggregate pixel accounting, or explicit partial batch admission, is required.

Onyxia is therefore a better first **instance candidate** than Stockade, but the current no-MOCV exterior profile does not qualify its seven interior/color groups. An explicit BASIC_INTERIOR preview recipe can avoid full client-lighting parity, while still qualifying consumed source operands and disclosing its approximation.

## Other actual roots establish negative-profile coverage

Stockade root108631, CK30f935a4d2f60abaaae98e245ddf2297,35,288bytes, flags0x1F:27groups,3LODs,81GFID IDs and26portals. Its13materials include shaders5/13/4/0, extra texture stages, one blend1 and shader0 flags200. First LOD0 groups108632/108633 contain MOCV, multiple UV/color streams and local-light declarations. No complete Stockade appearance is claimed.

Ragefire root109483, CK18c9698976d1d0935ceb10f1f42674dd,22,280bytes, root flags0:12groups,11portals,32materials. Sampled groups109484/109485 have flags0x3805, MOCV and one UV stream. This checkpoint does not assert all32materials or all12groups are qualified.

## Global coordinate and camera gate remains open

Zero global position is not proof that the ordinary ADT conversion with constant51200/3 applies. The traced TrinityCore global extraction path explicitly adds the map-base offset before internal/native conversion; the inspected wow.export `load_global_wmo` visibly calls its ordinary matrix/camera-base path. Upstream producer normalization still needs tracing before treating those as agreeing or disagreeing end-to-end. Do not choose a convention by which looks plausible.

Preserve raw global placement, root/group bounds and source mode. Independently pin global source→native/inspection basis, scale-flag handling, normals/winding and non-origin asymmetric vertices. A separately named local inspection coordinate frame is possible only through explicit design review; it must not masquerade as client/map coordinates. Initialize a clearly labeled free-camera bounds view if no source-backed start exists; do not invent a walkable entrance, terrain height, collision or floor from an AABB center. Same-child reversed-depth rendering, near clipping and camera-inside/interior surfaces need actual native tests before release.

## Maps, minimap and floors

The previous selected-build Maps investigation found no UiMapAssignment world Map IDs34/249/389 and no positive Group/GroupMember floor rows. This does not prove no instance image exists anywhere. The first Maps facade deliberately leaves floor capability unsupported until those schemas/joins are qualified. WMO groups and portal indices are not floors, and height sorting must not manufacture a floor selector. The existing World minimap consumes MAID per-tile references; global-WMO entry cannot invent a tile merely to activate it. A qualified global minimap-art source is a separate task.

## Ownership and next checkpoint

All new objects, CDN observations and temporary generated output stayed in private `/tmp` stores; the real library was opened read-only. The normal verified source pipeline was reused. Private logs/bootstrap files are under `/tmp/vimi-world-city-private` (`instance-selected-source-probe.log`, `instance-wmo-selected-probe.log`, `onyxia-source-probe.log`); no assets/images are committed.

Next: review source/profile facts; implement the shared bounded raw WMO root/group parser only after that review. Qualify Onyxia's MOCV/ambient/simple diffuse recipe, global transform and texture/pixel ledger separately from the city's first small no-MOCV preview. No production renderer/parser code was changed by this checkpoint.
