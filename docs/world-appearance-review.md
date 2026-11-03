# World appearance continuation review

This is an in-progress foundation for visible terrain textures, scenery and water,
not a delivered rendering feature. Alpha.16 is published separately at46ad728.
The user's current policy permits grouping ready parallel features; world imagery
and scenery take priority over packaged GPU diagnostics.

## Reviewed checkpoints

| Checkpoint | Scope and evidence |
| --- | --- |
| Placement parser3b5c589 | Root/domain source approval; bounded raw ADT18 MDDF/MODF facts and all eight recorded MAID references; authored limits/unsigned/finite/cancellation tests and three private actual obj0 sources |
| Placement source21d972f + ce30e94 | Root implementation review; bounded borrowed-source service, content-key/ID receipts, nine-entry/16,384-record cache and off-caller cancellation. Close waits for all helpers before reporting an error. Authored lifecycle tests and actual cold/cache/fresh facts pass. No placement transforms or drawing |
| Handmade decoderdbcb658 | Root/domain source approval; explicit exact flag2 BC2 complete-chain profile, unchanged strict defaults/persistence/C# behavior. Authored negatives, all13 private original images and an independent block oracle pass |
| Distance designd3e1153 | Independently reviewed source-free stitched/hole-preserving geometry spike, unchanged aggregate geometry bounds; no production distance scene |
| Error reference517979e | Root source review and module checks; bounded exact-rational TEST-ONLY overlap/coverage/error oracle. No runtime budget/performance/fidelity acceptance |
| Texture design879dd6c / API2960bcb | Source-backed pre-code design. Root approved staged root-grid/mask-reader then pure compositor checkpoints. The source/pool/persistence and render integration still require implementation review |
| SD/HD investigation throughb279d03 | Root reviewed exact-build client UI/API, known-table and sampled ROOT evidence. Local pairing remains unproven; no inert toggle or game setting change |

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
