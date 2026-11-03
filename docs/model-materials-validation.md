# Static mount material acceptance plan

Companion to the [material proposal](model-materials-proposal.md), aligned with
approved root/domain integration `f770e62`, including the texture-off contract
(proposal `30d769d`, following `6496b4c`). This companion
records planned acceptance, not completed tests or implementation approval. Keep
dependent work aligned to that approved shared API/math. Keep game assets and live-library payloads out of Git; committed
fixtures must be authored geometry and textures.

Target nested `MeshAppearance` types are `Coordinate`, `Combiner`, `Lighting`,
`Blend`, `TextureStage`, `Material`, `Surface(sectionIndex, Material)`, `Vec3` and
`RigidBillboard(boneId, modelPivot, vertexIndices)`. Preserve the existing five-arg
`Surface` adapter and legacy accessors. Test every legal enum/stage combination
and reject combinations outside the proposal rather than passing raw M2 flags to
the renderer.

## Independent numerical oracles

CPU/GPU agreement is necessary but insufficient: two implementations can share a
coordinate, reflection, alpha or ordering error. Tests must calculate expected
values from independent analytical examples and the reviewed equations, without
calling production camera, environment-coordinate or blending helpers.

| Behavior | Authored input and required oracle |
| --- | --- |
| ENV coordinates | A known-color asymmetric texture with distinct quadrants, labeled corners and an off-center marker, plus a sphere/selected planar normals. Assert center and signed axis samples from the reviewed sphere-map equation, including reflection-vector normalization and the vertical convention. Test several camera yaw/pitch values, camera translation, front/back normals, a pole and the denominator singularity. A convention change must fail the expected-color assertions, even if both backends change identically. |
| ENV view dependence | Keep a mesh and its UV0 unchanged while rotating the camera. Check analytically selected sample locations/colors; an ordinary UV0 control remains attached to the mesh. Separate the world-to-view normal transform, reflection direction and sphere-map stages so a reversed camera vector is detectable. No guessed longitude/latitude or UV0 substitute is acceptable. |
| `Opaque_Mod2xNA` | For scalar inputs at light 1, A=(.2,.4,.6,.1), B=(.3,.5,.8,.2), assert RGB=(.12,.4,.96) and opaque output alpha under the reviewed `2*A.rgb*B.rgb` rule. Vary both texture alphas, including zero: the opaque combiner must not discard the surface or turn transparent. Then apply a known lighting factor once, after combination. Assert `.8*.8*2*.5=.64`, not the incorrectly early-clamped .5. This scalar isolation does not admit an UNLIT ENV material. |
| Float/byte boundaries | A byte-texture oracle A.rgb=(64,128,192), B.rgb=(32,64,128) predicts approximately (16,64,193) after modulation. Freeze rounding/tolerance explicitly; permit at most the justified final conversion error, not an image-wide tolerance that hides channel/combiner mistakes. Include unequal channels and a nonwhite second texture. |
| Unlit | A nonwhite texture keeps its RGB when the light direction, normal and camera change. A neighboring lit control changes according to the existing lighting rule. Opaque unlit alpha remains opaque; unlit does not imply disabled depth testing. |
| Blend4 | With opaque destination D=(.1,.2,.3), source S=(.8,.4,.2), source alpha=.25, assert SRC_ALPHA/ONE RGB=(.3,.3,.35). Alpha 0 contributes no color; alpha 1 contributes the full source, with final saturation. Preserve fractional alpha until blending; forcing shader alpha 1 must fail. The presented frame remains opaque. |

Use the proposal's explicit eye basis (X right, Y up, looking down negative Z):
`n=normalize(normal)`, `u=normalize(p)`, `f=u-2*n*dot(n,u)`,
`q=f.x²+f.y²+(f.z+1)²`, `m=2*sqrt(q)`, `ENV=(.5+f.x/m,.5+f.y/m)`.
Compute this at each **vertex**, then perspective-correctly interpolate the
resulting ENV UVs. Never recompute the reflection from interpolated position and
normal in the fragment stage. For u=(0,0,-1), n=(0,0,1) samples center(.5,.5);
n=(sqrt(.5),0,sqrt(.5)) samples(.853553,.5), the negative-X counterpart(.146447,.5), and the positive-Y
counterpart(.5,.853553). With an authored 8×8 cell-color texture these select
cells (4,4), (6,4), (1,4), (4,6) under the existing nearest sampling convention.
At the singular pole assert center. The pure helper returns center for nonfinite
inputs or when dot(p,p), dot(normal,normal), or q is at most 1e-12. Test the threshold
on both sides. Source admission rejects unusable normals consumed by ENV; zero
normals in unrelated sections do not discard an otherwise readable mount. Name
each vector's coordinate space and direction.
Expected values come from the proposal's cited Khronos equation, not production
helpers; “looks like the CPU result” is not an oracle.

A second oracle must detect the wrong rasterization stage. For eye vertices
(-1,-1,-2), (1,-1,-4), (0,1,-3), and normals (0,0,1),
(sqrt(.5),0,sqrt(.5)), (0,sqrt(.5),sqrt(.5)), the vertex ENV UVs are
(.392906752351,.392906752351), (.881283013675,.404679246581),
(.5,.905621092588). Screen barycentrics (1/3,1/3,1/3) give perspective weights
(6,3,4)/13 and UV (.538560735010,.553381740323). Incorrect fragment recomputation
would give (.572483221905,.589599499442). An authored 256×256 coordinate gradient
selects cells (137,141) versus (146,150); use an admitted material and independently
computed light/combiner to verify distinguishable final pixels. Do not round the
UVs before interpolation or use the wrong fragment result as the expected image.

## Drawing, visibility and material ordering

Use small overlapping triangles/cards with known center pixels and unequal depths.
Run each fixture through the CPU renderer and the actual native backend as well as
the independent scalar oracle.

- Render opaque and cutout geometry before additive passes, regardless of input
  section order. A front opaque triangle hides an additive card behind it; an
  additive card in front contributes to the existing opaque color.
- Keep depth testing enabled and depth writing disabled for the admitted additive
  profile. Two cards at different depths in the admitted additive draw must both contribute when visible; the
  first card must not occlude the second through an accidental depth write.
- Keep cutout alpha-reference behavior unchanged at byte values 127/128. Test
  texture wrapping and nearest sampling at both sides of a UV boundary.
- Preserve the reviewed combiner, coordinate, texture-stage, unlit, two-sided,
  derived depth state and blend fields independently. Check coplanar LEQUAL
  for additive and the existing LESS/write state for opaque/cutout. Unsupported combinations remain explicit
  refusals, not silently rewritten into this admitted profile.
- Distinguish surface/section indices from geoset IDs. Include three sections all
  with geoset ID 0 and independent material bindings, plus an independently
  selectable nonzero geoset. Selecting ID 0 must retain all its qualified sections;
  raw/all-recorded mode must not quietly promote an unsupported material.

## Texture-off diagnostics

Test every admitted material with textures disabled, including additive source
alpha 0 versus 1 and an unlit material under changing light. All become the legacy
neutral, opaque, LESS/depth-write diagnostic: no texture-alpha discard, additive
blend or unlit override. Preserve the qualified section mask and billboard
transforms; this switch must not expand unsupported material coverage.

Use overlapping ADD cards with alpha 0/1 to verify both draw as opaque geometry,
not glow or missing geometry. Check card-face flat lighting against the legacy
headlight transformed into eye space through `PreviewCamera.lightingEye`; do not
invent a new billboard normal policy. Ordinary legacy texture-off pixels remain
unchanged. Include the separate edge diagnostic with the same qualification and
geometry transformations.

## Rigid billboard groups in a static bind pose

Author two independent four-vertex cards around different finite pivots, with an
asymmetric four-corner texture. Include ordinary geometry in the same mesh.

- Verify every nonzero influence: exactly one weight 255, sum 255, valid model bone
  index, and SKIN property/palette mapping to the same bone. Reject mixed weights,
  palette disagreements, out-of-range references and triangles crossing groups.
  Require unique bone IDs and disjoint sorted membership. Every triangle touching
  a group must use that group exclusively and belong only to its UNLIT ADDITIVE
  draw; reject sharing with ordinary/lit/ENV/opaque/unresolved geometry.
- Admit only the reviewed leaf flag/profile. Reject cylindrical/other billboard
  variants, extra flags, child bones, cycles, unresolved dependencies and any own
  TRS form outside the explicitly supported contract. Test the exact accepted
  empty-track descriptors as well as nonempty, global-sequence and external cases.
- Test `delta=restPosition-modelPivot` and
  `pEye=view(modelPivot)+(delta.y,delta.z,-delta.x)` directly: the pivot is unchanged;
  card offsets follow the eye plane, lengths and handedness are preserved. A
  nonzero delta.x must change eye depth, not be flattened away. With eye pivot
  (3,5,-7) and delta (.002,.25,-.5), expect (3.25,4.5,-7.002).
  Check each corner against independently computed coordinates at several yaw/
  pitch angles, including a pole and opposite-facing views. Keep original UVs
  attached to their corners, with no mirroring or inter-group pivot averaging.
- Verify ordinary geometry/normals are unchanged and ordinary ENV sampling receives
  the intended eye-space normal. Billboard materials are UNLIT: reject lit/ENV
  billboard combinations rather than inventing a lit-card normal transform.
- Move a rich-material card/triangle across the near plane: retain correct
  clipping/interpolation with finite positions and UVs; no NaN, exploding
  triangles or disappearing unrelated groups. Preserve the legacy ordinary
  whole-triangle-skip behavior where the approved compatibility path requires it.
  Exercise `PreviewCamera` degeneracy handling, including its .001 radius floor
  for point bounds; do not turn a valid point mesh into an arbitrary rejection.
- Bounds must include the rotated cards at every admitted camera, including a
  large off-center pivot. Camera fit/culling and any accelerated screen bounds
  must not use a smaller unrotated rectangle. Exercise offscreen and edge pixels.

The actual 79/103 parents contain animation tracks. Empty leaf tracks do **not**
prove static ancestors. Under the proposed static bind-rest policy, ordinary
ancestor animated TRS is deliberately not evaluated: the already bound model
vertices and absolute pivots are the reference. Validate the permitted ancestor
flags/dependencies and identity of the chosen bind-rest convention; do not label
those tracks static or substitute their first key. Reject leaf own tracks and
unreviewed inheritance. This is not an animation or client-pose acceptance test.

## Data contracts, codecs and bounds

Keep the existing finite ceilings unless a separately reviewed change replaces
them: 65,536 vertices, 1,500,000 indices, 256 parts, 16 textures, 2,048 pixels per
texture dimension, 8,388,608 total texels and a 40 MiB appearance-codec envelope.
For new sparse groups, test 128 groups, 8 members/group and 1,024 total members,
plus 8 referenced triangles/group and 1,024 total triangles. Exercise cap-1/cap/
cap+1 rejection and bounded traversal of overlapping part ranges; validation
must not become proportional to arbitrary repeated input ranges. Source admission
is narrower: exactly 4 vertices/2 triangles per group. Retained raw bone facts stay
within the existing format-reader limits. GPU derives 48 bytes/vertex independently
of encoded sparse data; verify checked `48*vertexCount` upload accounting and
failure/close cleanup. Do not serialize expanded GPU vertices into the codec.

- Keep authored v2 bytes as decode-compatibility fixtures; decode them through the
  legacy one-stage adapter with empty card groups. New encoding uses the new
  version; re-encoding need not reproduce the old wire bytes. Unknown/previously unsupported magic
  remains rejected. Round-trip new coordinate/combiner/blend/unlit/derived-depth/
  group facts without changing old rendering or inventing missing material facts.
  Mutating constructor input or an accessor's returned membership array cannot
  change subsequent rendering or encoded output.
- Reject unknown enum/version tags, wrong optional-array lengths, duplicate or
  conflicting section/group mappings, invalid texture-stage indices, nonfinite
  pivots/normals/offsets and invalid numeric bone IDs. Sparse codec groups have no
  raw bone hierarchy: cycle/parent-index tests belong to source facts/admission,
  while codec/data constructors check bone-ID uniqueness and member ownership.
- Check count/byte arithmetic before allocation or upload. Exercise multiplication
  overflow, truncated payloads, trailing data and an envelope larger than the cap.
  Individual maxima need not all fit simultaneously: test a payload valid per
  field that still exceeds the aggregate 40 MiB envelope, and reject it before allocation.
  Validate constructor inputs, codec decode and native-scene boundaries, not only
  the producer that normally creates a valid object.
- Bump the source material/profile and derivative identities. An old cached neutral
  surface cannot be restored as evidence that the new material was admitted.
  A fresh component must restore the exact new facts and qualifications.

## Native/platform and UI gates

Compile and verify matching pinned GLSL, SPIR-V, Metal and DX11 vertex/fragment
blobs; bind the new sources/uniform/attribute contract in the shader manifest.
The GPU-free runtime probe must load native entry points and verify all shader
resources on supported operating systems. Five-target packaged smoke tests must
use the bundled Java executable, classpath and natives.

These are availability checks, not Metal/D3D hardware qualification. Run actual
material/billboard/depth fixtures on each available hardware backend, recording
backend/device, shader profile, frame size and evidence. A backend without real
hardware evidence remains explicitly unqualified. Linux Vulkan evidence must not
be relabeled as universal platform support or speedup.

Use authored delayed scene preparation and camera updates to assert bounded
workers, current-owner/generation delivery and responsive FX callbacks. Camera/
ENV/billboard changes update the existing scene/child rather than restarting a
process, redecoding game assets or uploading immutable textures repeatedly.
Exercise rapid camera changes, resize, hidden/reattached views, cancellation,
terminal failure and close; verify no stale ready frame, child overlap, restart
loop or retained readback resources.

## Private selected-build acceptance after implementation

Re-run a read-only source proof against the selected wowf 1.60.1.70755/enUS inputs;
all downloads, generated derivatives and PNGs stay in isolated scratch storage.
Preserve original qualification and issue messages alongside each image.

| Case | Source facts to retain and required proof |
| --- | --- |
| Mount 28, Skeletal Horse | Link 23 → display 5228 → model 491/File 126304. Three sections share ID 0. The already corrected legacy 272 alias profile must retain 3 bindings, 2 textures and 2 cutouts, unchanged geometry and cold/new-component restoration. This is a regression control, not the new ENV/additive case. |
| Mount 79, Black War Raptor | Link 74 → display 14388 → model 1916/File 125649 (v274). Five sections, all ID 0; previous support covered 0/1/3. ENV section 2 has 797 vertices/698 triangles and mixed bone influences; shader 14 has two stages. Additive section 4 has 8 vertices/4 triangles split into two rigid leaf groups, bones 36/37. Verify the newly admitted ENV/additive facts individually and complete selected-profile coverage before claiming all 5 materials. |
| Mount 103, Great Brown Kodo | Link 174 → display 14578 → model 1914/File 124696 (v272). Four sections, all ID 0; prior support covered 0/1. Unlit section 2 has 8 vertices/6 triangles, all rigid bone 12. Additive section 3 has 8 vertices/4 triangles, leaf groups 17/18. Preserve the alias guard while qualifying unlit and camera-facing additive behavior. |

For each newly supported case, prove a cold result under the new policy while old
neutral-cache entries still exist, then close/reopen the component and compare
restored bindings, material fields, group maps, qualified masks and output. Compare
CPU and actual GPU images at multiple cameras, with independently checked sample
pixels/group positions as well as image parity. Report remaining refusals instead
of asserting full coverage from a plausible screenshot. No game assets or private
payloads become committed regression fixtures.
