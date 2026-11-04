# Packed planner test-only emission checkpoint

This follows the independent gap assessment in
[world-terrain-packed-planner-review.md](world-terrain-packed-planner-review.md).
Production planner/source/math files, geometry caps, codecs, renderer and UI are unchanged.
The emitter is test-only and its partitions must not be passed off as real ADTs.

## Authored coverage

Four deterministic acceptance tests construct compact sources from synthetic canonical native
height grids. Caller source lists are deliberately reversed. Source association always uses
`ScopedPlan.descriptor(ordinal)` and filename XY, never the caller list's ordinal.

- A full49-source flat window at asymmetric filename anchor7,19 emits206016 vertices and
  287680 triangles in six bounded partitions. These independently match the existing preflight
  counts. Every original fine cell is covered once, every final neighboring spacing is at most
  2:1, all four split-mask directions occur, and all required edge midpoints are present.
- The emitter deduplicates only within each original MCNK, preserving original source sample
  identity and every emitted float coordinate bit. Triangle winding/nonzero area and exact
  twice-area are checked in the source's integer staggered domain, both per leaf and per chunk.
  Checkerboard holes have exactly their original missing area; no fan fills or expands a hole.
- Full-window checkerboard holes cause explicit GEOMETRY_CAP limitation. The nearest nine
  sources survive; farthest rings are omitted first. Actual emitted counts/partition sizes fit
  unchanged aggregate334080-vertex/1769472-index/nine-partition limits. Extra already-certified
  midpoints after omission remain permissible; required retained-neighbor midpoints cannot vanish.
- Source-domain seams are checked across every MCNK boundary and all1344 neighboring source-tile
  edge pairs in the7×7 window. Both sides choose the same logical seam samples. Existing native
  float XY gaps remain observable, and positions match each side's full-resolution source bits.
  This deliberately does not weld files or claim universally watertight real-source seams.
- A map corner (4×4sources) and an asymmetric clipped edge (4×7sources) retain canonical source
  identity, coverage and emitted bounds, guarding against accidental screen-axis transposition.
- One100-unit original cell-center peak triggers finer leaves. Every final fan in that affected
  MCNK is independently compared with the exact native rational clipping reference against the
  original fine fans; each meets the explicit ring1 tolerance. Other authored chunks are constant
  height, so their exact height error is analytically zero. The test does not claim exhaustive
  rational comparison of every possible49-source nonlinear surface.
- Arithmetic preallocation refusal, plane-budget exhaustion, pass-limit refusal during peak
  refinement and mid-operation cancellation publish no unchecked plan. Scoped closure remains
  exercised by all successful cases.

The full49 flat test charges81975682 arithmetic groups and8242048 plane events, including source
copies, as previously measured. Tests do not raise these hard ceilings. The test emitter, source
fixtures and reference oracle allocate independent validation arrays/objects; their heap is not a
production planner memory ledger or evidence of a shipping frame-time budget.

## Check and remaining gates

```sh
./gradlew :rendering-world:check
```

The checkpoint needs independent root review before integrating tests or treating them as planner
acceptance evidence. No production bug was found in these cases. Real source receipts/grids,
source seam policy, rough-source acceptance/performance, selected work/fidelity policy, production
mesh/batch format, integrity codec, near-textured/far-material policy, native frames and continuous
replacement remain separate gates. A49-source batch is not compatible with the existing single-ADT
hot packet merely because its geometry counts fit.
