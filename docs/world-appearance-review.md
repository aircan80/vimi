# World appearance continuation review

This is an in-progress foundation for visible terrain textures, scenery and water,
not a delivered rendering feature. Alpha.17 is published separately atc70e88d.
The user's current policy permits grouping ready parallel features; world imagery
and scenery take priority over packaged GPU diagnostics.

## Reviewed checkpoints

| Checkpoint | Scope and evidence |
| --- | --- |
| Placement parser3b5c589 | Root/domain source approval; bounded raw ADT18 MDDF/MODF facts and all eight recorded MAID references; authored limits/unsigned/finite/cancellation tests and three private actual obj0 sources |
| Placement source21d972f + ce30e94 | Implementation review; bounded borrowed-source service, content-key/ID receipts, nine-entry/16,384-record cache and off-caller cancellation. Close waits for all helpers before reporting an error. Authored lifecycle tests and actual cold/cache/fresh facts pass. No placement transforms or drawing |
| Handmade decoderdbcb658 | Root/domain source approval; explicit exact flag2 BC2 complete-chain profile, unchanged strict defaults/persistence/C# behavior. Authored negatives, all13 private original images and an independent block oracle pass |
| Distance designd3e1153 | Independently reviewed source-free stitched/hole-preserving geometry spike, unchanged aggregate geometry bounds; no production distance scene |
| Error reference517979e | Root source review and module checks; bounded exact-rational TEST-ONLY overlap/coverage/error oracle. No runtime budget/performance/fidelity acceptance |
| Texture design879dd6c / API2960bcb | Source-backed pre-code design. Review approved staged root-grid/mask-reader then pure compositor checkpoints. The source/pool/persistence and render integration still require implementation review |
| Texture formatsca99757 + f3e1819 | Root/domain source approval after public DTO cap-before-copy correction; shared native grid and lazy split-texture masks, unchanged old geometry. Three source pairs match111,360 original vertex positions and2,024 independent mask hashes. No atlas/render claim |
| Atlas compositore1a0fd2 | Root/domain approval; basic coverage/tint recipe supports768/768 chunks across three private sources, with12,288 independent RGBA samples. No client nonlinear sharpening/height-blend fidelity claim |
| Shared poola6d6e62 +59d7a8a | Root/domain final approval;18 reserved/live slots, scoped borrow pins and asynchronous drain. A refused nineteenth reservation preserves pinned cache keys; only cache-only entries are evicted. Integrated concepts/rendering checks pass |
| Native-XY reference3f8add3 and production math0030477 +2cf897c | Reviewed exact float-coordinate reference and conservative interval math. Opt-in source-free49-tile schedule completes8,175,616 plane events/63,338,496 scalar groups within explicit16M/100M hard ceilings. No shipping work budget, distance control or latency acceptance |
| Shared texture binding1e28350 | Root/domain approval; detached qualification metadata, exact original geometry fingerprint, all-source-vertex UV guard, header-rectangle coverage and synchronous readonly pixel borrow. Source authenticity and lease ownership remain caller responsibilities |
| CPU fallbackbce81d0 | Root/domain source approval; optional synchronous binding consumption with inverse-camera point sampling, alpha-zero height fallback and original triangle fog. Analytical ray/plane, clipping, coverage, fog and coplanar checks pass. Not connected to GUI yet |
| Water source1789221 +96d0969 | Source/parser API design approved; one bounded immutable MH2O owner with explicit low-level layout parsing and separate concepts qualification. No production liquid reader or rendering |
| SD/HD investigation throughb279d03 | Reviewed exact-build client UI/API, known-table and sampled ROOT evidence. Local pairing remains unproven; no inert toggle or game setting change |

Integrated format/world/render/GUI checks pass. The explicit handmade decoder also
passes Java/C# image interop with the pinned sibling reference. Rebase onto the
publication handoff changes documentation only; reviewed source scope is retained.
Private source assets and screenshots remain outside Git.

## Remaining gates

Texture work must validate the shared original root grid, ordered split tex0
metadata, exact raw/RLE masks, finite colors and source receipts. The first512²
atlas is a basic diffuse coverage preview with baked source tint and fog. It does
not implement client height blending or nonlinear layer sharpening; zero MHID
entries do not prove those client effects are disabled. Approximation/detail
policy belongs in the recipe, UI feedback and release notes.

Application ownership and frame-publication gates are recorded in
[the integration plan](world-terrain-texture-application-plan.md). Memory-only
batch source/scheduling, native bound geometry/annex codec and GPU shading remain
active implementation checkpoints. No terrain texture release is allocated yet.

Do not merge visible-render claims from parser/source success alone. CPU/native
UV, fog, depth, unsupported-chunk fallback and old height-only compatibility need
analytic tests and real Vulkan frames; Metal/D3D remain outstanding. Source/cache,
copy/upload and old/pending snapshot ownership must fit the reviewed ledger.

Scenery needs a separately reviewed native transform, winding/normals, static M2
material profile and shared world rendering pass. WMO buildings require new strict
root/group readers. Water needs qualified vertex format, source masks and height
interpretation; its transparency/depth and combined resource policy are separate
gates. Greater view distance still needs source-grid integration, a conservative
production error oracle, packing/codec, streaming and native performance evidence.
