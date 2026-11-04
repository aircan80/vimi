# Prepared terrain and texture bundles

Status: immediate presentation design for review. This removes the geometry-first
material pop and one redundant cold replacement. It does not claim uninterrupted
GPU streaming; the existing child still performs one cold handoff per published
neighborhood. Persistent tile updates are a separate design.

## Publication unit

Replace the geometry-only `PendingPrepared` record with a small owned candidate.
It contains the existing immutable CPU/native geometry snapshots, recorded tile
references, selection/scene generations, camera-window anchor, callback and one
optional `WorldTextureSnapshot`. It owns no RGBA copy. Its material outcome is
pending, qualified/partial, or terminal fallback with an explanation.

A candidate remains unpublished until material preparation reaches a terminal
outcome. A qualified snapshot must be bound to the candidate's exact native scene
identity; zero-atlas and source failures become explicit height fallback. At
publication, take the owned snapshot from the candidate and transfer it together
with both geometry references. The native service therefore receives one complete
scene plus optional annex in its first request, rather than a geometry child and
a second texture child. The old accepted image, scene and camera continue while
source preparation runs.

`WorldTextureLoading` will report a typed completion containing the requested
native scene identity, an owned optional snapshot and optional failure message.
Every current noncanceled request gets a terminal callback, including failures.
Canceled/stale completions retain the current close-before-discard behavior. The
consumer owns a delivered snapshot on successful callback return. Progress remains
latest-only metadata, never pixels.

## Initial load and current view

On a cold native-capable opening, the first checked geometry preparation primes
only camera/origin. It does not launch a child. Load the complete available local
3×3 neighborhood using the existing sequential/capped geometry source wave, then
prepare its candidate and materials. The initial activity indicator remains
visible until the combined target frame is accepted. Initial flight input does
not move an invisible camera before the first scene is published.

CPU-only legacy/custom World views retain their initial height-only path when
there is no native scene to qualify. Application software fallback still uses
qualified native geometry and can present a prepared texture bundle.

Once a scene exists, movement and camera requests continue targeting that scene
throughout preparation. Zone/subzone/minimap behavior remains independent.

## Admission and memory

Current and pending candidates each own at most nine atlas entries from the same
18-slot pool. The source actor remains one active preparation and one latest
pending descriptor. There is no 27-slot assumption and no increased cache,
geometry, atlas, input or native memory limit.

Before starting a pending candidate's material source, require an accepted frame
for the current scene AND current texture generation. For GPU presentation this
also requires `canReplaceScene`; the serial native service has then retired the
previous child before starting the accepted current one. For CPU presentation,
the single CPU worker's accepted current frame proves older CPU work has drained,
while an explicit GPU-owner retirement future must also be complete after toggles
or GPU fallback. A current CPU render may still borrow the same current atlases;
that adds claims, not a third physical set.

Proposed narrow controller API addition: `TerrainGpuPreview.retirement()` returns
a copy of the last owner-retirement future. `hide` and failure record the existing
`service.closeOwner` future; no waiting runs on FX. This lets the source admission
barrier cover CPU/GPU transitions without polling pool internals or relying on
GC. Initial no-current-scene preparation can reserve one pending set alongside
one retiring old owner; subsequent candidates wait for current-frame admission.

Geometry/current-source arrays retain the existing current+pending bounded
lifetimes; source service still caches twelve raw tiles. No additional full-window
cache or pixel copy is introduced. If admission cannot proceed, keep the readable
old scene and show local pending activity.

## Movement, coalescing and cancellation

A pending candidate whose recorded sources still cover the camera's current tile
remains useful and may finish, avoiding cancellation starvation near boundaries.
If the camera leaves its source window, cancel its source job, close any delivered
candidate snapshot, invalidate its generation and gather the latest neighborhood.
Keep at most one candidate and one latest desired anchor descriptor. Map, build,
locale or preparation revision changes always invalidate candidate ownership.

Source arrivals during newer geometry preparation do not publish an outgoing
scene. On stable completion, only the newest eligible candidate may transfer its
snapshot. Every replace, hide, deactivate, map change, failed callback, rejected
work submission and close explicitly closes candidate ownership. A completed
future or queued FX callback must not retain a live lease after retirement.

On restore, reconstruct/requalify the desired bundle if needed without requiring
camera movement. A desired-neighborhood dirty flag survives canceled preparation;
this avoids falsely treating newer loaded geometry as already presented.

## Activity and fallback

Keep the outgoing textured frame while geometry and textures load. Report source
phases separately from GPU handoff/presentation. A source failure or unsupported
chunk is a terminal fallback, not a permanently spinning candidate. Qualified
partial coverage is explicitly partial. Native upload acknowledgement does not
end activity: only acceptance of the published combined target frame does.

Current-map explicit texture retry may requalify the current geometry, but it
retains the last accepted image until replacement or explicit fallback is ready.
No stale acknowledgement may clear another candidate's activity.

## Acceptance gates

- Held material source: no geometry-only child starts; initial open launches one
  combined child after material completion.
- Existing textured scene: camera controls and accepted old frames continue while
  the next neighborhood's source is held; publish one combined replacement.
- Current/pending atlas ownership remains at most18 unique entries across GPU,
  CPU toggle, old-worker retirement and rapid candidate supersession.
- Warm source, partial coverage, zero atlas, failed source and explicit retry all
  reach honest terminal states without an intermediate textured-to-height flash.
- Useful boundary candidates may finish; leaving their source window preserves
  latest-only work and does not starve indefinitely on small oscillations.
- Hide/restore, deactivate/activate, iconify, map/revision changes and close release
  every pending claim, then resume while stationary.
- Existing World navigation, current area, minimap and model-preview regressions
  pass. Authored source fixtures use the real qualification path; private actual
  source/Vulkan proof remains outside Git.

Cold child startup can still pause frame delivery. Measure that remaining gap
separately and do not describe this immediate change as a hot-streaming engine.
