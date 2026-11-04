# Preserved packed distance planner: independent gap assessment

Scope: `f0892ec` + `0ef211b`, read-only production review. This is not renderer,
source-authentication, scene-codec or visible distance-mode approval. No production
code changed during review. `:rendering-world:check` passes.

The packed levels, mask-specific certificates, 2:1 refinement, source-index positions,
hole refinement, bounded count traversal and farthest-source omission are coherent
on inspection. Refusals return no unchecked plan. Scoped closure drops source/packed
references. Public construction remains descriptive rather than source authority.

## Additional local diagnostics

A private source-free runner constructed49 canonical source grids around tile32,32,
with exact legacy native sample arithmetic, zero base altitude, declared ring tolerances
1/4/16 and maximum existing work/geometry/batch limits. Source copies share the same
ErrorWork allowance as planning. These single-run timings include no game IO, emitted
mesh, upload, rendering or UI and are not performance qualification.

| Input | Outcome | Retained / omitted | Vertices / triangles / batches | Work | Local plan time |
| --- | --- | --- | --- | --- | --- |
| Flat, no holes | READY |49 /0 |206016 /287680 /6 |81975682 arithmetic,8242048 planes |2412ms |
| Every chunk holes `0xaa55aa55aa55aa55` | LIMITED / GEOMETRY_CAP |11 /38 |312576 /360448 /9 |23389442 arithmetic,0 planes |213ms |
| Every original cell center height100, outer samples0 | UNAVAILABLE / WORK_BUDGET |No plan |No output |100000000 arithmetic,9686950 planes |2783ms |

The flat result independently confirms the earlier authored geometry count. Its work
already uses about82% of the hard arithmetic ceiling before source IO or mesh output.
Rough-source refusal is honest, but no useful49-tile acceptance rate is established.
Do not raise work or geometry caps to disguise this limitation. Camera rendering must
remain independent of certification; reuse/optimization requires measured bounded design.

## Recommended next narrow milestone

Complete pure49-source planner acceptance with a **test-only geometry emitter**. Keep
source service, renderer, native protocol and UI unchanged. Verify:

- Full fine-cell coverage exactly once except original holes; no invented positions.
- All four directions of chunk/tile boundaries, 2:1 ratios, final split-mask agreement,
  peaks/ridges/saddles, unknown neighbors, clipped map-edge windows and cancellation.
- Final fan errors against independent native rational references on bounded adversarial
  leaves, with analytic full-window cases. Stitch changes must invalidate old certificates.
- Counted vertices/indices/batches equal emitted output; farthest omission preserves
  already-certified midpoints and nearest coverage; refusal precedes output allocation.
- Actual native-float seams are compared with full-resolution source seams. Existing gaps
  must not be silently welded or described as newly watertight.
- Explicit work/time/allocation diagnostics for flat, holes and refinement-heavy inputs;
  fail-closed behavior at both arithmetic/plane ceilings and pass limits.

Existing tests currently establish central exact counts, position-copy bits, closure,
absence versus unknown, budget preflight and immediate cancellation. They do not yet
establish these complete-neighborhood properties.

## Consumer boundaries before visible integration

`TerrainSourceWindow.sources()` returns the original input order, while planner visitation
and `ScopedPlan.descriptor(ordinal)` use canonical window order. Future output must use
the plan's descriptor API or explicitly normalize the public source ordering; it must
not silently associate input-list indices with canonical source ordinals.

The current TerrainScene and World hot-v2 tile slots each describe one real ADT, with
one tile key and atlas anchor. A packed geometry batch may span several of49 sources.
It must not be relabeled as an ADT or sent through an unchanged single-tile packet.
A separately reviewed DistanceTerrainScene/source-to-batch codec and material-partition
contract is required. Nine atlas owners cannot automatically texture49 sources; near
textured plus explicitly height-shaded far terrain is a possible policy requiring review.

Validated source receipts/grid extraction, source/cache and old/new-window memory ledger,
selected fidelity/work policy, CPU/native frames and continuous replacement remain later
acceptance gates. This checkpoint can progress alongside persistent GPU streaming without
changing that protocol or claiming a larger visible distance today.
