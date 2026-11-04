# One-asset BASIC_WMO_PREVIEW checkpoint

Status: proposed narrow material/ownership contract, awaiting review and implementation proof. Supersedes the general32MiB city envelope **only for this one-asset vertical slice**. It does not approve a general city, an instance, multiple root/group assets, or a whole-process memory ceiling.

## Useful first visible behavior

Place actual root106905/group106906 at the recorded MODF173696 in the existing terrain world child. Use its recorded mesh, UV0 and six diffuse textures. Name the result BASIC_WMO_PREVIEW: source geometry and diffuse preview with a disclosed simple static light/fog recipe. Full client lighting parity is not required for this useful slice. Preserve nonzero ambient/color/groupType declarations as source facts and explicitly disclose which client lighting operands the preview approximates. Never call the asset fully supported merely because it is visible.

The reviewed-profile proposal admits version17, exact root flags0, one recorded GFID group, group flags0x9, one UV stream/no MOCV, six-or-fewer flags0/shader0/blend0 batches, one ordinary texture1 per material and no extra texture stages. All indices/vertices/normals/UV/batch spans require validation. The demonstrated source is424vertices/378triangles/sixbatches with six strict BC1 opaque textures. Do not broaden to cutout, multi-stage, other root/group flags, MOCV/interior groups, doodad sets, portals or liquids through this profile. Source discovery can preserve them as unsupported.

Use the proposed heading-only0xC MODF transform only after independent source/transform review; the source checkpoint's0.000567root-bound residual is evidence, not full transform approval. Root/group bounds, no-ground-snap semantics, proper normals/winding and same-camera native terrain alignment remain explicit tests. Source receipts and private source-qualified factory ownership precede the renderable object DTO; neither a public model ID nor an IPC checksum authorizes an appearance claim.

## Separate hard caps

| Boundary | One-asset ceiling |
| --- | ---: |
| Root / groups / placements |1 /1 /1|
| Vertices / triangles / draw batches |512 /512 /6|
| Vertex stride / CPU index / GPU index |32 /4 /2bytes|
| Texture handles |6|
| Selected texture side / aggregate pixels |256 /393,216(6×256²)|
| Root source / group source / one texture source |1KiB /32KiB /64KiB|

The exact source fits: root712bytes, group19,404bytes, largest texture44,876bytes,335,872selected pixels. Refuse sources over these slice limits; do not silently claim generic WMO support from this asset. A high-resolution original source that exceeds64KiB is unsupported here even if a small mip could fit; relaxing source admission is a later bounded profile change.

No selected-image upscaling is required. Decode one source at a time on the parent worker using the existing strict BLP decoder. Source validation, legacy key/BLTE/network caches, prepared placement metadata and original library ownership remain separate named subsystems, not hidden inside the child ledger below.

## Additional child payload ledger

All values use the caps above, not only the smaller actual sample. This conservative sum includes transient rows even when some phases cannot peak together:

| Owner | Bytes |
| --- | ---: |
| Retained CPU mesh512×32 +512×3×4 |22,528|
| Resident GPU mesh512×32 +512×3×2 |19,456|
| Queued bgfx mesh copies |19,456|
| One mesh upload staging owner |19,456|
| Retained CPU RGBA pixels |1,572,864|
| Resident GPU RGBA textures |1,572,864|
| Queued bgfx texture copies |1,572,864|
| One texture native upload staging |262,144|
| One texture packet read buffer |262,144|
| One texture immutable-constructor extra copy |262,144|
| One texture codec/defensive extra copy |262,144|
| Bounded geometry codec scratch |65,536|
| Material/instance/control metadata and shader payload reserve |262,144|
| **Additional named payload total** |**6,175,744(5.8896484375MiB)**|

The implementation must map every actual allocation to an owner and refuse unlisted copies. In particular, no whole multi-image packet allocation, expanded per-placement geometry, retained source BLP buffers, separate building readback target or second CPU atlas is allowed. Source BLP decoding stays on the parent; the child receives already qualified RGBA and does not run another decoder. Load texture records sequentially so constructor/codec/staging extras are each bounded by one256²image, while retained CPU/GPU/queued image totals are charged globally. GPU retirement and queued bgfx copies remain charged until their real barriers.

Combining this proposed additional payload with the existing immutable textured-terrain ledger120,844,580bytes gives **127,020,324bytes**, leaving **7,197,404bytes** below128MiB. This arithmetic demonstrates a viable design envelope; it is **not** runtime budget approval, an RSS/driver-memory claim or automatic admission into the newer hot-world ledger. Actual codec, native buffers, shader resources and construction/retirement peaks must be independently checked against every row. Any additional hidden owner consumes the remaining reserve or lowers caps; no silent ceiling increase.

Parent asset preparation has a separate budget: retained current/new CPU pixel+mesh snapshots, one bounded root/group/texture source, BLP input/output/defensive-copy scratch, parsed facts, source provenance, cancellation retirement and packet staging. Disk packet bytes are distinct from heap buffers. Existing `WorldPlacementSource` cache/raw record representation needs explicit accounting before wiring; its16,384record cache limit is not a free byte allowance. This document does not claim the whole parent fits an8MiB or64MiB budget.

## Cold immutable scene integration is sufficient for this slice

Hot streaming is not a prerequisite. Add a separately versioned, bounded **optional immutable object annex** to the typed world launch request, preserving old terrain-file/control compatibility. Existing terrain and this object share the same arbiter-owned child, origin/camera, framebuffer, reversed GREATER depth and native render thread. No separate model-preview owner or per-building process is created. The cold parent may prepare one replacement world snapshot; the old child must drain before its successor allocates GPU owners, as in the existing singleton lifecycle.

Keep camera-only updates on the same scene/child without rebuilding or re-uploading the asset. Source preparation remains off FX and leaves old terrain visible with object-phase activity. Optional object refusal leaves terrain available with a partial/unsupported reason. This slice inherits the existing cold terrain-neighborhood handoff limitation; release notes must say so. It does not claim stutter-free streaming. A future hot asset annex can replace the transport after its separate generation/credit/retirement review.

The annex codec must validate counts/source recipe/transform and source-bound material facts before allocation, stream bounded texture records, and preserve source-qualified parent claims separately from descriptive wire metadata. Child close destroys every additional handle exactly once on the native owner thread. Failure after any individual upload must retire partial owners and preserve the existing error/fallback policy.

## Proof required before visible release

1. Independent review of BASIC_WMO_PREVIEW's consumed source fields, deliberately approximate lighting, heading transform and strict limits; no full client-lighting-parity gate.
2. Authored binary/DTO negatives and independent analytic same-depth/foreground/background/winding/normal/UV/fog/near-clip fixtures, including unsupported adjacency. Actual Vulkan frames must compose the placed mesh with terrain rather than merely compare CPU/GPU implementations sharing one mistake.
3. Allocation instrumentation for CPU arrays/direct buffers/bgfx copy bytes/GPU textures and live handle counts, sequential texture construction/upload, failure at each allocation/upload, exact retirement and no old/new child overlap. Prove the6,175,744byte additional ledger, or revise it before approval.
4. Private actual-source alignment/pixels for the recorded placement, camera-only owner stability, cold/fresh cache restoration and explicit BASIC/partial/loading UI. Metal/D3D remain untested runtime until exercised. The sample's real city identity and broader city coverage remain unclaimed.
