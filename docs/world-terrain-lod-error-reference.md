# Exact terrain LOD error reference

Status: test-only independent reference, not a production oracle or renderer change. Builds on the reviewed distance proposal and source-free geometry spike. The ring0/1/2/3 error candidates0/1/4/16 remain proposed policy, not shipped fidelity guarantees.

## Contract and exact arithmetic

`TerrainErrorReference` compares two bounded triangle partitions in one normalized MCNK. XY coordinates are integer twice-cell coordinates0..16; fine-cell centers and candidate coarse corners/centers/stitched edge midpoints are therefore exact existing grid locations. Heights are finite binary floats, converted exactly to rational numbers from their IEEE binary representation. This contract deliberately does not guess a grid from arbitrary native geometry.

For each triangle pair, Sutherland–Hodgman clipping computes their convex XY intersection. Both height fields are planes on this intersection. Their difference is linear, so its absolute maximum occurs at an intersection-polygon vertex. Evaluating only original source vertices would miss extrema where edges cross: an authored saddle fixture has error2 at its diagonal intersection while both surfaces agree at every input vertex. A second independent fixture has exact maximum1/3 at a non-binary intersection.

The reference uses exact rational orientation, intersections, areas, barycentric height evaluation and comparisons. It normalizes winding and rejects zero-area triangles. It separately rejects positive-area overlap within **each** partition, then requires exact overlap-area coverage for every fine and every candidate triangle. Thus a candidate hole cannot be canceled by an equally sized overlap elsewhere, and a candidate cannot fill an original hole. Coincident boundary lines contribute zero area. This is a single-valued height-field comparison, not arbitrary 3D mesh distance.

Only the final exact maximum is converted to a double. Conversion is compared back to the exact rational result; if below it, the returned value advances by one representable double toward positive infinity. The comparator therefore returns an upper bound without an arbitrary geometry epsilon. Exact zero stays zero. An unsupported numeric range or arithmetic budget is a refusal, not a certificate. Ring0 should retain exact source geometry directly rather than invoke approximation certification.

## Resource and work guards

Inputs admit at most512 triangles per partition; lists are checked before internal arrays allocate. Clipping uses two fixed eight-point buffers. Per-triangle coverage arrays and prepared plane inputs are bounded by the triangle cap. No intersection polygon list or all-pairs result matrix is retained.

Rational numerator and denominator payloads are capped at1024 bits. Cross-products preflight the sum of operand bit lengths **before multiplication**, and sums preflight the maximum operand length plus one **before addition**. GCD/division and final decimal-to-double conversion therefore operate only on bounded inputs. The conservative guards may refuse arithmetic that could simplify to a smaller result; refusal is intentional. The final decimal conversion has fixed80-digit precision and is followed by exact binary comparison.

Every primitive rational operation, triangle pair and clip edge/vertex charges the same explicit operation budget, including partition nonoverlap validation. Caller allowance is positive and at most50,000,000 operations. Cancellation is checked at each charge. This bounds admitted work count and integer operand size; it is **not** a measured latency or heap guarantee. Exact rational objects are appropriate for a test reference, not the promised packed production implementation. A future production oracle must use independently reviewed interval/error treatment or another conservative method and compare against this reference.

## Authored evidence

Six tests cover:

- Original four-triangle-per-cell fans against a stitched coarse fan on a plane, exactly zero error.
- An isolated original inner sample and a boundary ridge, with independent maximum3 and2 oracles.
- Alternate saddle triangulations whose maximum lies at an edge intersection; reversed comparison and an invertible XY reflection/translation plus exact altitude offset retain the bound.
- Exact1/3 intersection maximum rounds outward rather than below the mathematical error.
- Uncovered domains, filled holes, and equal-area hole/overlap cancellation fail in either partition direction.
- Degenerate/nonfinite/out-of-grid inputs, exhausted work and cancellation fail closed.

Run `./gradlew :rendering-world:check --offline`. No actual game geometry, performance, source parser, renderer, codec, GPU or shipping distance changes are claimed.

## Next production gate

Validated ADT grid provenance must establish the same fine topology, source holes, sample identities and native-to-normalized affine mapping. Affine re-expression in XY preserves this vertical-difference problem only when the same invertible map applies to both partitions; deriving and validating that map is outside this reference. Separate sample-height offsets that round differently as floats are different inputs, not an invariance promise.

Production planning must bound all error checks and neighbor rebalancing, refuse ill-conditioned/nonfinite geometry, apply error acceptance to the exact final stitched candidate fan, and repeat after any refinement changes that fan. Geometry limits still apply before allocation. A near-threshold or unproven result causes refinement/limited farther coverage; it must never be silently accepted. Benchmarking full MCNK/tile/neighborhood work and cancellation latency remains required before selecting production budgets or promising farther coverage.
