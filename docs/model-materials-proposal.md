# Reviewed mount material profiles: proposed contract

Status: design for independent domain and architecture review. This document does
not authorize a production implementation or claim animated/client-equivalent
mount rendering. The existing qualified-material preview and raw-surface diagnostic
remain the fallback while individual profiles are added.

## Goal and observed cases

Support the complete **reviewed static material profiles** observed on build
1.60.1.70755 mounts 79 and 103: two-texture reflective armor, unlit surfaces,
additive glow, and rigid camera-facing cards. Continue declining unknown shaders,
blend states, coordinate transforms, opacity tracks and bone behavior. This is not
a general M2 renderer or animation evaluator.

Read-only/scratch proofs established that CPU and Vulkan already reproduce the
same neutral artifacts from the same immutable input. The remaining failures are
material/geometry admission, not a GPU camera or projection regression.

| Case | Required addition |
| --- | --- |
| Mount 79, section 2 | Raw shader 14, two textures, UV0 + ENV, **Opaque_Mod2xNA**, lit opaque armor. This is not the existing **Opaque_Mod2xNA_Alpha** combiner. |
| Mount 79, section 4 | MOD texture, raw blend 4, material flags 17: unlit additive, depth test, no depth write. Eight vertices form two separate rigid cards on leaf bones 36/37. |
| Mount 103, section 2 | Single-texture unlit opaque material, ordinary bone 12; no billboard transform. |
| Mount 103, section 3 | Unlit additive cards on rigid leaf bones 17/18, four vertices per bone. Preserve their small nonzero local X offset. |

Each card vertex has one weight of exactly 255. Skin palette resolution agrees
with the global vertex bone index; each card has two triangles and no triangle
crosses leaf groups. The leaf bones have exactly flag 0x8, no children and absent
own TRS tracks. Their ordinary ancestors **have animated tracks**. The preview
must not describe those ancestors as static or evaluate a chosen animation frame.

## Source admission matrix

Keep existing simple profiles and the reviewed v272 duplicate-section extension.
Add only these observed profiles; this is not a claim that unknown flags are
universally inert:

| Profile | Exact initial source conditions beyond shared guards |
| --- | --- |
| Reflective armor | M2 v274 with second raw field 0 and material flags 0, or v272 with second raw field equal to section index and material flags exactly 0 or 4; shader 14; batch flags 0x90; two textures; blend 0; empty coordinate lookup with the shader's UV0/ENV convention; both transform lookups identity. Unknown 0x80 combinations stay declined. |
| Unlit opaque | M2 v272; shader 0; batch flags 0x10; second raw field equals the section index; one UV0 texture; material flags 1, blend 0. |
| Unlit additive card | M2 v274/raw-second 0/material flags 0x11, or v272/raw-second equals section/material flags 0x15; shader 16, batch flags 0x10, one UV0 texture, raw blend 4; admitted rigid-card evidence. |

Shared guards retain one batch per section, priority 0, color index -1, material
layer 0, verified full static mesh opacity, supported texture flags and verified
file bindings. Validate every consumed stage's coordinate/transform lookup; do
not silently reuse the first stage's transform for ENV. Missing texture/fact data
declines the affected section and remains an explicit partial result. Source
admission belongs to DisplayModelService's reviewed rendering policy; do not widen
the existing strict raw-format/C# compatibility contract as a side effect.

## Immutable rendering data API

Extend `MeshAppearance`, rather than passing raw M2 shader/flag integers into the
renderers. Keep the existing five-argument `Surface` constructor and its accessors
as source/binary adapters to the current one-texture material. Existing callers,
especially Races, retain their current behavior.

Proposed types, with explicit stable wire codes rather than enum ordinals:

```java
enum Coordinate { UV0, ENV_SPHERE }
enum Combiner { DIFFUSE, OPAQUE_MOD2X_NA }
enum Lighting { LIT, UNLIT }
enum Blend { OPAQUE, ALPHA_KEY, ADDITIVE }
record TextureStage(int textureIndex, Coordinate coordinate,
                    boolean wrapU, boolean wrapV) {}
record Material(Combiner combiner, List<TextureStage> stages,
                Lighting lighting, Blend blend) {}
record Surface(int sectionIndex, Material material) {}
record Vec3(float x, float y, float z) {}
record RigidBillboard(int boneId, Vec3 modelPivot, int[] vertexIndices) {}
```

`Surface(sectionIndex, textureIndex, wrapU, wrapV, alphaKey)` builds the legacy
DIFFUSE/LIT material. Legacy `textureIndex`, wrapping and `alphaKey` accessors derive
from the first stage/material; there is no duplicated mutable material state.
`surfaces()` still identifies admitted **section ordinals**, independently of
recorded part/geoset IDs. There remains one admitted material draw per section;
multiple batches for a section stay unresolved in this increment.

These are nested rendering-model data types, with no formats-models dependency.
Array-bearing groups must copy inputs and return defensive/readonly membership
views; the abbreviated declarations above do not waive immutable ownership.

A new appearance constructor accepts the rich surfaces plus immutable rigid-card
groups. Old constructors provide an empty group list. Constructors validate:

- One or two stages, texture indices and finite UVs/normals; reject unrecognized
  wire codes and arbitrary blend/depth booleans. The complete material matrix is:
  DIFFUSE + one UV0 stage + LIT + OPAQUE/ALPHA_KEY; DIFFUSE + one UV0 stage +
  UNLIT + OPAQUE/ADDITIVE; or OPAQUE_MOD2X_NA + exactly UV0 then ENV_SPHERE + LIT + OPAQUE.
  Every other combination is invalid, even if its individual enum values exist.
- At most 256 surfaces, 16 textures, 8,388,608 total texture pixels and the existing
  mesh vertex/index bounds.
- At most 128 card groups, at most eight members each (1,024 total), unique source
  bone IDs, no duplicate membership, sorted in-range vertex indices and finite
  bounded model pivots. Bound referenced card triangles as well (at most eight per
  group / 1,024 total); validation must not introduce unbounded overlapping-range
  traversal. Initial M2 admission is narrower: exactly four vertices and two
  triangles per group.
- Every triangle touching a card member must have all three members in that same
  group and belong only to an admitted UNLIT ADDITIVE surface. Reject cross-group
  triangles or sharing with lit, ENV, opaque or unresolved draws.
- ENV requires finite, nonzero normals on the vertices actually consumed by its
  section. Zero normals elsewhere do not invalidate unrelated surfaces. Source
  admission declines the affected ENV section before appearance construction;
  it must not discard an otherwise readable mount. Codec/data validation rejects
  a rich ENV material naming unusable consumed normals.
- Billboard groups are admitted only for the reviewed unlit additive card passes;
  lit/ENV billboard combinations remain unsupported.

Material state is derived, not accepted as arbitrary flags: opaque and cutout use
legacy LESS depth test with depth write; additive uses LEQUAL and no depth write.
All supported profiles remain two-sided, matching the current renderer. No new
sRGB conversion, mip filtering or UV transform is introduced.

## Material math and draw phases

Both renderers use normalized straight RGBA texture samples with the existing
nearest-neighbor and wrap/clamp rules. The output frame remains opaque BGRA/ARGB
with alpha 255; texture alpha is a material input, not the frame's transparency.

1. DIFFUSE preserves the current texture-times-light behavior. UNLIT uses light 1.
   OPAQUE ignores texture alpha for coverage; ALPHA_KEY retains the existing
   alpha < 128/255 discard rule.
2. OPAQUE_MOD2X_NA computes `rgb = 2 * t0.rgb * t1.rgb * light`, alpha 1. Both
   texture alphas are ignored. Keep intermediate values in floating point and
   clamp only at final UNORM output. Do not use the existing `_ALPHA` mix formula.
3. ADDITIVE computes straight `src.rgb = texture.rgb`, `src.a = texture.a` for the
   reviewed UNLIT/full-opacity profile, then
   `dst.rgb = clamp(src.rgb * src.a + dst.rgb)` and preserves destination alpha.
   Alpha zero contributes nothing; there is no cutout threshold.

Render opaque/cutout first in the existing `PreviewMesh.parts()` **section ordinal
order**, independent of material-list insertion order. This deliberately preserves
legacy deterministic first-wins behavior for equal depth; it is not a promise to
reproduce raw M2 batch-array order. Render admitted additive passes afterward in
section ordinal order with LEQUAL/no-Z-write. Their RGB operation is commutative for
nonnegative UNORM colors, so retain that deterministic section order rather than
inventing a transparency sorter. Actual target proof must confirm ENV/body/cutout
coverage; if coplanar overlaps require raw batch order, stop and review an explicit
order field instead of claiming full profile support from material counts. This contract does not admit general source-over alpha,
subtractive blending, arbitrary priority planes or layered/translucent materials.
The first source admission permits the observed single additive pass per model;
new blended combinations require separate fixtures/review.

**Textures OFF is a geometry diagnostic**, not a different source-material
qualification. Override every material, including rich ENV/unlit/additive ones,
to the existing lit-flat neutral opaque draw with LESS and depth write: no
texture sampling/alpha cutout, blending or unlit override. Keep the same section
qualification and all admitted card transforms. Shade transformed card face
normals against the legacy headlight expressed in eye space; ordinary legacy
geometry retains its established result. `PreviewCamera.lightingEye` provides the
same operation to the CPU. This avoids opaque-white additive rectangles while
preserving the old texture-off mode. All/raw visibility is an independent control.

GPU additive state uses separate RGB/alpha factors: SRC_ALPHA, ONE for RGB and
ZERO, ONE for alpha, or disables alpha writes while retaining the cleared opaque
alpha. CPU uses the same equations. Neither path may let additive geometry write
depth or occlude later glow. Edges are a separate diagnostic pass; qualification
and geometry transforms must apply to that pass too.

## Explicit eye-space ENV convention

Use a standard eye basis: X right, Y up, camera looking down negative Z. Expose a
shared pure camera transform in rendering-models; `GpuCamera` consumes it rather
than independently re-deriving another convention. Preserve the existing preview
center, yaw/pitch orientation and focal framing for ordinary meshes.

Generate ENV coordinates **per vertex**, before rasterization, from that vertex's
fully transformed eye-space position `p` and normal. Let `n = normalize(normal)`,
`u = normalize(p)`, `f = u - 2*n*dot(n,u)`, and
`q = f.x*f.x + f.y*f.y + (f.z+1)*(f.z+1)`; `m = 2*sqrt(q)`.
The vertex ENV coordinate is `(0.5 + f.x/m, 0.5 + f.y/m)`. The CPU rasterizer and
GPU varying then perspective-correctly interpolate **these UVs**, just like UV0.
The fragment shader samples the ENV stage at that varying. Recomputing reflection
from interpolated position/normal in the fragment shader is a different contract
and is not admitted here.

Define finite center (.5,.5) fallback when `dot(p,p) <= 1e-12`,
`dot(normal,normal) <= 1e-12`, or `q <= 1e-12`; the pure helper also returns center
for nonfinite inputs. Source admission rejects nonfinite/near-zero consumed ENV
normals, so the helper's zero-normal fallback is defensive, not wider admission.
Use the same thresholds and stage placement on both backends. Do not add a UV flip
to compensate for a different viewer's convention. Stage wrapping remains explicit.

This adopts Khronos GL_SPHERE_MAP in the explicit basis above, also used by the
reviewed WMV implementation. A different primary viewer uses a negative-UV
convention; that convention is not silently mixed into this contract. Analytic
and asymmetric-texture oracles are required in addition to CPU/GPU agreement.

## Bind/rest camera-facing cards

This remains an **unanimated bind/rest preview**. Ordinary bone transforms are
identity by this declared mode, not because their animation tracks are constant.
No TRS interpolation, animation selection, IK, skeletal attachment or general
skinning implementation is added.

Admit only source-proven rigid groups with an acyclic, in-range parent chain,
ordinary ancestors (the observed chains use 0x200), no billboard/inheritance flags
on ancestors, one 255-weight
bone per vertex, matching skin/global bone resolution, exactly flag 0x8 on a
leaf, and absent own TRS tracks. External skeleton/bone sources and other billboard
modes are excluded initially. Obtain the own-track absence evidence from a bounded
reader fact tied to the same parsed M2/source, rather than assuming it from flags.

The primary screen-aligned transform for a source vertex is:

```text
delta = restPosition - absoluteModelPivot
cardEyePosition = view(absoluteModelPivot) + (delta.y, delta.z, -delta.x)
```

This aligns the card with the camera plane. It is not a perspective point-facing
look-at operation for each pivot. Preserve all three offset components, including
Mount 103's approximately 0.002 local X displacement. Existing mesh indices and
UVs remain unchanged. The selected unlit profile does not invent a lit-billboard
normal policy.

CPU computes this eye position during its worker's vertex transform. GPU derives
one extra per-vertex `vec4(pivot.xyz, enabled)` upload from sparse immutable group
metadata and evaluates the same transform in the vertex shader. Ordinary vertices
use the shared model-to-eye transform. Eye-space projection and ENV inputs then
share the same convention. Camera updates change uniforms only: no scene
serialization, geometry upload or host frame IPC is added per frame.

Compute conservative view-independent bounds from ordinary rest geometry and
`pivot +/- max(length(memberPosition-pivot))` for each group. Both CPU framing and
GPU framing use those same derived bounds; never trust serialized bounds. This
prevents card rotation from changing framing or clipping against a rest-only AABB.

## Codec, caches and native lifetime

Introduce a new appearance magic/revision with explicit material/stage and sparse
card counts. Decode existing v2 appearances through a legacy adapter; reject
unknown versions/codes and validate counts, byte arithmetic and remaining lengths
before allocation. Encode rich appearances in the new format. Keep the outer
`GpuSceneFile` checksum/session envelope and validated immutable ownership.

Retain the **40 MiB encoded appearance bound**, 16-texture/8-million-pixel bounds
and existing scene envelope limit. Encode sparse groups/membership, not expanded
48-byte GPU vertices. Check the exact aggregate encoded size before allocation;
not every individual maximum is simultaneously encodable. GPU's derived 48-byte
vertex upload is independently accounted/bounded and freed on failure/close.

Bump `MeshAppearanceCodec.REVISION` and DisplayModelService's source policy/cache
identity. Old incomplete material derivatives must not bypass new admission via a
cache hit. Include source model/SKIN/texture receipts, material-profile revision
and bind-card evidence revision. No bulk deletion of user caches is required.
Legacy callers and v2 decode retain their old materials and empty card list.

GPU changes include a second sampler, typed material uniforms, shared eye/projection
matrices and card attributes; rebuild all four offline shader profiles with the
pinned compiler/manifests. Do not ship a backend with old shader bytes. Single
scene upload, bounded offscreen readback, mapped-frame CRC, latest-frame barriers,
parent watchdog and software fallback stay in effect. Native/backend refusal
falls back to CPU using the same rich immutable appearance, not a simpler new
approximation.

## Stages and ownership after contract approval

| Stage | Owner / boundary | Acceptance |
| --- | --- | --- |
| 1. Material/geometry data and math | Rendering-models: types, legacy adapters, codec, shared camera/ENV/bind-card math and CPU renderer. No GUI/native ownership. | Legacy output unchanged; malformed codec rejection; analytic equations, alpha/depth and card-bound fixtures. |
| 2. Source evidence and admission | Display model service: DisplayModelService plus narrowly required bounded reader facts. No global raw-format reinterpretation. | Exact raw profiles, source/weight/coordinate/own-track/bone checks; unknowns remain partial; policy/cache cold and restored parity. |
| 3. GPU implementation | GPU renderer: GpuModelScene/BgfxModelRenderer/camera/shaders/offscreen integration. | Upload ownership/bounds, fill/edge parity, new native analytic and image fixtures; all offline shader profiles verified. |
| 4. Independent target proofs | Acceptance: private live 79/103 payloads, CPU/Vulkan comparisons, cache roundtrip and authored FX checks. Do not commit game assets. | Eye cards remain camera-facing over yaw/pitch; ENV changes with view; glow blends/occludes correctly; fresh/restored payloads agree. |
| 5. Integration and review | Integration; each reviewer excludes their own authored slice. | Full module/GUI checks, platform packaging/native probes, scoped independent review, accurate partial/rest-mode UX. |

Stages 1–3 start only after the shared API/math contract is approved. Stage 1's
committed API precedes dependent implementation. Do not race edits to shared
renderer files or use live application data as generated-test output.

## Required independent oracles and remaining limits

- ENV center and tilted normals: with `u=(0,0,-1)`, `n=(0,0,1)` gives (.5,.5);
  `n=(sqrt(.5),0,sqrt(.5))` gives approximately (.853553,.5); negative X gives
  (.146447,.5), positive Y gives V .853553. Use asymmetric textures and pole tests.
- Interior-triangle ENV stage oracle: eye vertices (-1,-1,-2), (1,-1,-4),
  (0,1,-3), with normals (0,0,1), (sqrt(.5),0,sqrt(.5)),
  (0,sqrt(.5),sqrt(.5)). Their vertex UVs are approximately
  (.392906752351,.392906752351), (.881283013675,.404679246581),
  (.5,.905621092588). At screen barycentrics (1/3 each), perspective weights are
  (6,3,4)/13 and the ENV UV must be (.538560735010,.553381740323).
  Incorrect per-fragment recomputation from interpolated position/normal gives
  (.572483221905,.589599499442). An asymmetric gradient must distinguish these
  outcomes on CPU and GPU; agreement with each other alone is insufficient.
- NA: base RGB (100,80,60), ENV (128,64,255) gives approximately (100,40,120) before
  lighting, regardless of both alphas being zero. Test deferred clamping:
  `.8*.8*2*.5 = .64`, not .5 from prematurely clamping the product.
- Additive: background (10,20,30) plus source (100,80,60), alpha 128/255 yields
  approximately (60,60,60), output alpha 255. Test zero alpha, opaque occlusion,
  coplanar LEQUAL, no depth write and section-ordinal phase order. Reverse material
  insertion order in an equal-depth opaque fixture and require the first mesh
  section to win. Do not use the raw M2 batch sequence as the oracle.
- Cards: nonorigin pivots, asymmetric UVs, several yaw/pitch values and nonzero X
  offset; verify the analytic eye positions, stable pivot, depth-scaled size and
  conservative bounds at several yaw/pitch values for card-only and mixed meshes,
  near clipping and depth preservation. Reject multiple weights, palette disagreement, nonleaf,
  own TRS, external bones and unreviewed billboard/inheritance modes.
- Textures OFF: ENV, unlit and additive examples all become neutral opaque
  LESS/depth-writing diagnostic geometry; alpha zero no longer punches a hole or
  contributes an additive blend in this diagnostic. Qualification and card
  transforms still apply, with eye-space flat-light checks for rotated cards.
- Confirm decoded old v2 output and existing Races/qualified/raw diagnostic modes
  are unchanged. CPU/GPU pixel agreement is necessary but is not the only oracle.

Actual Metal/D3D hardware remains a separate qualification gap. Packaging a shader
or passing a headless native-load probe does not prove its driver output. No
animation, live-client pose, particles/ribbons, arbitrary M2 materials or universal
rendering/performance claim follows from these targeted static profiles.

## Primary evidence

- [Khronos GL_SPHERE_MAP equations](https://registry.khronos.org/OpenGL-Refpages/gl2.1/xhtml/glTexGen.xml).
- [Pinned WMV use of GL_SPHERE_MAP](https://github.com/wowmodelviewer/wowmodelviewer/blob/ab6060333415dbcc31328447896ea33c2a36a4f6/Source/games/wow/ModelRenderPass.cpp#L610-L617).
- [Pinned per-vertex ENV generation](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/shaders/m2.vertex.shader#L41-L45).
- [Pinned Opaque_Mod2xNA material equation](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/shaders/m2.fragment.shader#L107-L109).
- [Pinned separate RGB/alpha additive factors](https://github.com/Deamon87/WebWowViewerCpp/blob/1a8cccbeffc46231c6497e6b3f5bfbf3507d8071/wowViewerLib/src/gapi/ogl3.3/GDeviceGL33.cpp#L30-L35).
- [Pinned primary viewer material/state implementation](https://github.com/Deamon87/WebWowViewerCpp/blob/1a8cccbeffc46231c6497e6b3f5bfbf3507d8071/wowViewerLib/src/engine/objects/m2/m2Object.cpp).
- [Pinned primary flags-8 bind-card implementation](https://github.com/Deamon87/WebWowViewerCpp/blob/1a8cccbeffc46231c6497e6b3f5bfbf3507d8071/wowViewerLib/src/engine/managers/animationManager.cpp#L498-L558).
- Private authored/read-only target evidence is recorded in the task's scratch
  material/bone logs and CPU/Vulkan PNGs; those files and game assets are not
  repository test fixtures. New committed tests must be independently authored.

## Legacy reflective armor qualification

The reviewed v272 ENV extension is evidenced by wowf 1.60.1.70755 Swift Timber Wolf (Mount105/display14575/record176, M2 123410, SKIN473965) and Swift Brown Ram (Mount94/display14347/record89, M2 125522, SKIN479324). Both reflective section ordinals are1 with raw second field1; the wolf material flags are4 and the ram flags0. Both have shader14, batch flags0x90, two recorded texture sources, empty coordinate lookup, proven full opacity and two identity transforms. The wolf ENV texture is249237 and ram144337. Material flag4 disables back-face culling in the pinned primary viewer, matching the existing two-sided contract; no shader math is added. Composition policy v4 prevents restoration of old partial appearances. The additive card section remains independently unsupported in each model; this extension does not claim full material coverage.
