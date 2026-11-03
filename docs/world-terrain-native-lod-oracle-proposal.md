# Native-float terrain LOD certification checkpoint

Status: test-reference extension plus proposed production math/API for review. No production planner, renderer, source loader, scene codec or distance setting is added. The0/1/4/16 ring tolerances remain proposed fidelity policy. Preserve all existing geometry/native memory caps and limited-coverage fallback.

## Actual native coordinates are the reference

The old normalized twice-cell reference remains mathematically correct for its explicit input domain. It cannot certify a real ADT by assuming its normalized grid is transformed affinely: `WorldFormats` computes sample XY in double arithmetic and stores each result as a float. Different sample positions round independently. The new `compareNative` mode converts **actual binary float XY and heights** exactly into bounded rationals. A common exact rational origin subtraction reduces intermediate magnitudes without moving a sample. It retains existing exact clipping, partition/nonoverlap/coverage validation,1024-bit preflight,50million maximum operation allowance and outward final conversion.

Native inputs are capped512triangles/partition, finite absolute coordinates≤1million, combined XY span≤64 native units. Degenerate triangles and mismatched domains fail closed. The old normalized API/tests are unchanged and remain useful independently.

New authored evidence uses exactly the legacy staggered sample arithmetic. A normalized plane has zero normalized error, yet its corresponding quantized native samples at origin1million produce an exact outward vertical error **0.015037593984962407**. Treating that native surface as an affine normalized plane would falsely certify zero. Conversely, a plane defined on the actual native X values remains exactly zero-error at origins0,10000 and1million. Existing source-edge differences (`-33.333332f` versus `-33.333984f`) fail same-domain coverage rather than being welded or silently tolerated. These tests are not actual-source performance measurements.

## Proposed conservative packed first oracle

A production first path need not reproduce exact polygon clipping. Assume a checked planner establishes that the original fine fan partition and final stitched candidate fan partition cover the **same per-chunk leaf domain**, with no overlap or hole bridging. For every candidate fan triangle plane P and every original fine vertex v inside that leaf, evaluate a conservative upper bound for `abs(v.z - P(v.xy))`. The maximum across **all** candidate planes and fine vertices is an upper bound for the true vertical error.

Proof: on the intersection of a fine triangle F and a candidate triangle C, the difference of the fine plane and C's plane is affine. The absolute value at every point of F is bounded by the maximum absolute difference at F's three vertices, even where C's plane is extrapolated outside C. Taking every candidate plane includes the one actually used at each point. The extrapolation can overestimate error; it cannot understate it. This trades efficiency/detail acceptance for a simpler conservative certificate. Overestimates cause refinement or explicit limited farther coverage, never a relaxed tolerance. Original one-cell fans can use an exact-geometry bypass instead of a floating zero-error test.

Proposed evaluation uses packed primitive arrays and outward double interval operations for rebased coordinates, plane determinants, barycentric numerators and height differences. Float inputs are exactly representable as double constants. Each arithmetic operation encloses its result using outward rounding; nonfinite intervals or determinant intervals containing zero return UNRESOLVED. No arbitrary epsilon, approximate equality or unchecked sign branch grants a certificate. Do not assume double subtraction preserves every float difference near zero; enclose it like any other operation.

This is a design, not an implemented interval algorithm. Its operation-level proof, underflow/overflow handling, conservative comparison to the exact native reference and measured work must precede production admission. A small returned bound certifies only the final candidate fan that was checked; neighbor stitching or refinement invalidates older certificates.

## Proposed API/ownership boundary

`TerrainGrid.Chunk` provides validated source ordinal, native origin, original145samples/holes and exact legacy `position(row,column)` values. A checked dyadic leaf plan references **source sample indices**, never reconstructed normalized heights. Candidate triangles use those exact sample positions. No public arbitrary triangle array should be mistaken for a certified partition.

Proposed pure rendering-world interface for later review:

```java
ErrorResult evaluate(TerrainGrid.Chunk source, CheckedLeafPlan finalPlan,
                     ErrorWork work, BooleanSupplier canceled);
// Result: EXACT_GEOMETRY, BOUNDED(upperBound), or UNRESOLVED(reason).
// CheckedLeafPlan: generated/validated topology and source-index references.
// ErrorWork: one shared remaining plane-evaluation/arithmetic budget.
```

Plan construction validates leaf alignment, nonoverlap, exact fine-cell coverage excluding source holes, center/perimeter sample membership and balanced stitched edge subdivision. Holes refine to original cells. Certificates cannot survive a plan change. Bounds are checked before arrays/copies; no per-node object graph or per-frame certification. Packed scratch can hold at most145fine samples and eight candidate fan planes per leaf. The exact rational reference remains test-only, not an implicit slow production fallback.

A work budget must charge validation and all coefficient/vertex evaluations, and have explicit cancellation granularity. Its numeric value remains unapproved until measured across full MCNKs, holes, worst-case refinement and neighborhood replacement. Budget exhaustion gives limited coverage/refusal. Do not infer that nine or49full-resolution source meshes can be retained while checking candidates.

## Separate seam and distance gates

Within one source chunk, matched leaf boundaries use identical source-index vertices and matching subdivision. Across chunks/tiles the original files can already have small coordinate/height gaps. Preserve each side's exact original edge samples; do not assert bit equality, weld origins or fill an unknown source crack. Logical edge correspondence, equal LOD subdivision and comparison of pre-existing versus newly introduced gaps need a separate actual-source seam policy. Any bridge, displacement or tolerance is an explicit approximation requiring review. If seam safety cannot be established within caps, retain finer edges or omit farther coverage honestly; do not silently ship a crack under an error threshold.

The new shared grid source API closes provenance, not these native-math/seam/error-policy/packing gates. Before a visible distance mode: compare packed upper bounds against this exact native reference for analytic planes, saddles, isolated peaks, stitched boundaries, quantized XY and near-degenerate refusals; verify policy thresholds; preflight unchanged vertex/index/batch caps; measure source/preparation/upload time and replacement overlap; then run independent CPU/native frames. No49-tile coverage, latency or full-client terrain claim is made here.
