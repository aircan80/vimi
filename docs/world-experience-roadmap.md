# World experience and character roadmap

Latest verified release: [alpha.20](https://github.com/aircan80/vimi/releases/tag/v0.1.0-alpha.20), source590aa0e, all five platform packages and18 assets. Published tags and frozen launchers are immutable. Group ready parallel milestones into explicit GitHub prereleases after review and checks.

The user authorized all items below and autonomous continued work, including overnight work, agents, routine fixes and prereleases. Do not ask for repeated routine confirmation. Preserve truthful source provenance, bounded ownership, off-FX acquisition/preparation and visible activity through presentation.

| Work | Current state | Delivery gate |
| --- | --- | --- |
| Terrain textures/navigation/download feedback | Released alpha.18 | BASIC diffuse/tint/fog; larger material fidelity remains below |
| Continuous world GPU streaming | Active; persistent native owner and opt-in child actor independently pass actual Vulkan content/camera/resize/cancellation and real blocked-pipe shutdown; public source/Tile/Commit/Frame APIs and closed bridge reviewed; actual three textured Map0 tiles pass in one child, including partial coverage; inline connection integrated after independent lifecycle/recovery reviews; actual fast-movement proof passes with final9 textured tiles and clean drain; final local485-task, packaged smoke and one-child movement gates pass; hosted/publication gates in progress | Persistent same-child terrain changes, accepted content/camera provenance, cancellation/drain and real movement proof |
| Greater view distance | 49-source planner/test-emitter checkpoint; integration pending | Independent seam/error/cap review, multi-source geometry/material representation and bounded streaming |
| Terrain load allocation | Released alpha.19 | Primitive parser preserves exact geometry/area; cold neighborhood renderer handoff remains |
| Terrain visual quality | Planned | Higher-resolution GPU material sampling, filtering/mips and qualified blending; no claim of client parity |
| Cities/buildings/doodads | Private BASIC_WMO source, cold annex, native draw pass and optional cold-child pass reviewed; actual source-to-native and integrated Vulkan child proofs pass; WorldView wiring pending | Qualified transforms/materials, culling, same-world depth/camera and combined GPU ledger |
| Dungeons/raids | Raw WDT facts released; strict zero-global transform direction reviewed; actual group/texture diagnostics complete; narrow global declaration source reviewed and actual Map249 proof passed; WMO/image scene source remains next | Qualified global transforms/materials and scene mode integration; first actual instance, interior navigation and safe camera framing |
| Water | Raw MH2O parser independently approved on separate branch with actual20/26-layer proofs; source qualification design ready | Qualified source/layout and bounded visible rendering |
| NPC/monster placement | Authorized | Verified spawn coordinate domains, model/display mapping, height/floor/phase provenance, bounded instances; static placement first |
| Area music | Recorded AreaTable/ZoneMusic/SoundKit playback with fades, mute/volume, day/night slot and visible missing-source state ([checkpoint](world-area-audio-checkpoint.md)); WMO interiors, underwater and intro music not evaluated | Actual area/subzone music relationships, supported playback, transition fades, mute/volume and missing-source state |
| Sky/lighting/fog and time of day | Authorized | Qualified source tables/material rules and measurable bounded implementation |
| Ambient audio | Recorded AreaTable/SoundAmbience loops with separate toggle/volume and owned channel lifetime ([checkpoint](world-area-audio-checkpoint.md)); emitters, start/stop kits and mixing not evaluated | Recorded environmental sound relationships; separate music/effects volume and ownership |
| Walk/fly movement | Authorized | Ground following, sprint, smooth camera; collision separately qualified |
| Click-to-inspect | Authorized | Object/creature selection, names/IDs/source details without blocking FX |
| Search/travel/bookmarks | Authorized | Validated coordinates and map identity, saved camera bookmarks and safe jumps |
| Quest/vendor/trainer/flight overlays | Authorized | Source-specific spatial provenance, filters and completeness; no guessed map conversion |
| Idle animations | Authorized | Qualified animation/skinning and bounded many-model GPU performance; no AI required |
| Dungeon floor/group/entrance navigation | Authorized | Actual floor/group/entrance evidence; no inferred floors from height alone |
| Hierarchical Maps browser | Released alpha20: hierarchy/artwork UI, strict source and actual3-map proof approved | Allfive packages and18assets verified; floor/marker overlays remain future |
| Remaining character models + character-creator screen | Queued after current world milestones; audit started | Complete remaining race/sex rendering profiles, actual customization option catalog/choice vector, WoW-inspired clean creation-style layout |

Character controls should expose supported skin, face, hair style/color, facial hair and other recorded appearance choices, with clear loading/failure states and reset/randomize over valid choices. Do not add inert options or invent HD/SD pairings. This is an asset preview/customizer, not game-account character creation. Independent immersive extras must not indefinitely postpone this character milestone.

Placement sources must distinguish real world XYZ from zone percentages or interaction observations. Existing Polestar adapters can carry world-map XYZ; Questie spawn adapters carry area percentages and no height. Neither a creature definition nor a quest hint alone proves a precise world spawn. Instance classifications likewise do not determine whether a map uses terrain, global WMO or both.

Release readiness requires relevant component checks, authored UI/native proofs for changed paths, independent review and hosted/package validation. Document limitations; keep acquired game assets and private proof output outside Git. No gameplay interaction, NPC AI or complete game-server simulation is required by this roadmap.

## Tracked terrain texture bug

[Handmade BC3/DXT5 terrain textures](bugs/terrain-handmade-bc3.md): reported five
textures rejected as DIFFUSE_PROFILE. Narrow BC3 opt-in fix and synthetic256×256
full-mip regression independently reviewed and integrated into alpha.22 candidate
PR162; publication pending. This is separate from broader terrain material-quality work.
