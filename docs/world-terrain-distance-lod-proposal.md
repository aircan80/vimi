# Bounded terrain distance LOD

Status: design and source-free geometry spike only. Independent design review is required before production implementation. This does not claim native rendering, real-source compatibility, preparation latency or measured memory improvement.

## First deliverable

Offer an explicit farther-terrain mode with **up to 7×7 source tiles**, while retaining the current aggregate geometry, IPC, framebuffer and native memory limits. Keep the current 3×3 path and its old scene decoding unchanged. Correct native-axis traversal separately; distance must not conceal that bug.

Filename first index follows native X and second follows native Y. Native vertex coordinates decrease as source row/column increase. The World overview separately uses screen column=filename Y, row=filename X. Planning, source keys and camera traversal must not inherit that screen transposition.

## Existing limits and proposed representation

`rendering-world/TerrainScene` currently represents at most nine real ADT tiles, each at most 37,120 vertices and 196,608 indices. Its total limit is 334,080 vertices, 1,769,472 indices or 589,824 triangles. `GpuTerrainSceneFile` is bounded to 16MiB and validates real tile descriptors; its tile count must not be raised or its descriptors relabeled as arbitrary batches. `TerrainCamera` retains near=.5 and whole-triangle far=2200.

Introduce a separately reviewed `DistanceTerrainScene`: at most 49 source descriptors and nine geometry batches, each batch bounded by the existing per-tile vertex/index capacities. Batches are explicitly upload partitions, not ADTs. Each source descriptor binds filename coordinates, native origin, file/content identity and selected coverage. Aggregate geometry remains bounded by the existing limits. A new versioned scene codec must bind the source descriptors, policy and geometry in its integrity digest and retain old decoding. It must validate counts and byte arithmetic before allocating. No production API or codec change is included here.

Maximum logical position/index payload remains 11,086,848 bytes. Persistent GPU triangle instances remain 48 bytes each, at most 27MiB. Existing 16MiB scene-file, child memory, 800×500 output and three-slot frame-bridge bounds remain unchanged. Passing aggregate bounds does not prove a particular batch partition fits: production must preflight both dimensions and refuse or reduce farther coverage if nine batches cannot hold it.

## Geometry policy

Each source MCNK begins with its existing 8×8 cells and 145 height samples. Desired dyadic cell spacing is 1, 2, 4 and 8 in Chebyshev source-tile rings 0, 1, 2 and 3 respectively. The central tile remains exact. A node containing a hole refines until each missing cell is represented exactly; no fan crosses a hole and holes are neither filled nor expanded. Height-error refinement may require finer nodes elsewhere.

Balance adjacent leaves to a maximum 2:1 spacing ratio before mesh allocation. A coarse edge adjacent to finer leaves includes its existing source midpoint. Fan corners, edge midpoints and centers all reference original MCNK samples; no invented heights or skirts. Adjacent nodes therefore use identical source samples and edge segments. The test-only spike demonstrates matching authored boundaries; it does not certify that independent real ADTs have identical seam samples. Existing source-coordinate discrepancies must be measured and retained or rejected explicitly, not silently welded.

Source topology needs explicit provenance. The current generic `WorldFormats.Terrain` accepts geometry without certifying the original 145-sample grid. Production must obtain validated MCNK origin, height-grid and hole metadata from the shared ADT walker, bound to the source identity. Reconstructing that grid by guessing positions or triangle patterns is excluded.

The uniform no-hole 7×7 policy starts at 286,720 triangles and 205,056 vertices before transition stitching. The implemented spike produces **287,680 triangles and 206,016 vertices**, with 903,168 bytes of logical planning arrays. Its counts fit the existing geometry limits. These are authored geometry counts, not an actual-source or frame-time benchmark.

## Height fidelity gate

Spacing alone does not certify terrain fidelity: peaks can lie between coarse vertices. Before production, implement and independently review a geometric error oracle. A concrete candidate clips original fine triangles against candidate coarse fan triangles in native XY. The difference between their height planes is linear on each overlap polygon, so its extrema occur at overlap vertices. This bounds vertical error over the whole surface, including unsampled intersections. Validate planar, ridge, saddle and isolated-peak cases against independent analytic expectations.

Candidate maximum vertical deviations are 0, 1, 4 and 16 native units for rings 0–3. These numbers and the bounded oracle work allowance require design acceptance before use. Exceeding the error/work/geometry allowance refines or omits farther coverage; it must not quietly relax the error bound. The current spike does not implement this oracle or claim a silhouette-error bound.

Checkerboard holes defeat reduction. Preserve nearest usable coverage first, then omit farthest tiles with an explicit limited-coverage status. Never allocate 49 full meshes or raise caps to satisfy a nominal radius. Full 7×7 coverage is conditional, not promised. Its rectangle also does not cover the entire 2200-unit far-distance circle from every camera position.

## Ownership and streaming

Plan with packed per-cell levels and holes, not one Java object per node. A 896×896-cell window requires 802,816 level bytes and 100,352 hole bits in bytes; source metadata and object overhead must be separately bounded. Decode and reduce sources sequentially with one bounded source/preparation worker. Do not retain 49 full `Terrain` instances or increase the existing full-tile cache allowance.

Retain one readable scene while preparing one replacement; logical old+pending geometry is at most twice 11,086,848 bytes. A proposed 32MiB geometry-pipeline envelope includes those arrays plus bounded planning/metadata/working geometry, but **does not** claim the parser buffers, library cache or whole application fit 32MiB. Their existing independent limits must be accounted in implementation review. Dispose transient source references before decoding the next source.

Nearest-first loading must preserve current geometry until a replacement is ready. A one-tile move can have 56 tiles in the union of old and new 7×7 windows; each active snapshot still admits at most 49 source descriptors. Do not turn that union into a hidden active-source cap increase. Source, selection and request-generation guards remain mandatory.

Current scene identity changes can restart the terrain child. Production needs one active replacement and one latest pending snapshot, rather than restarting for every arriving tile. Allow an accepted first frame or terminal result before committing the pending scene; retain camera updates and the last readable image. No concurrent native children. Define anchor hysteresis and cancellation so crossing a boundary cannot repeatedly starve first-frame delivery.

## Visual semantics and acceptance

Retain current near clipping, reversed-depth capability gate, whole-triangle far rejection, sky, source-axis orientation and ordered depth semantics. Fog and flat shading applied to generated coarse triangles are an explicit farther-terrain approximation; do not claim pixel identity to the full-resolution surface. CPU and GPU must render the same generated distance geometry and material rules.

The test-only `TerrainLodSpike` is deliberately not a source loader or renderer API. Five deterministic tests cover a stitched mixed-detail boundary with nonlinear shared heights, exact hole area and cell coverage, the complete 7×7 bounded mesh, worst-case refusal before geometry allocation, native sample/axis mapping and cancellation. The logical planner uses packed arrays; it does not establish production heap or timing bounds.

Production gates still required:

- Exact error-oracle tests and bounded-work refusal, all four seam directions, missing neighbors, peaks and hole edges.
- Validated source-grid metadata and malformed/duplicate/nonfinite/source-mismatch rejection; actual-source boundary-height measurements.
- Batch-packing preflight, codec corruption/count bounds, old-format compatibility and cancellation at every ownership transfer.
- Streaming tests for tile-boundary reversal, retained geometry, old/new windows, limited farther coverage and bounded child replacements.
- Independent CPU/native GPU frames for generated geometry, depth/near/far/fog/holes and source axes; actual hardware proof rather than CPU/GPU agreement alone.
- Measured cold preparation, source/cache retention, upload time, replacement gap, steady camera latency and logical/native allocation peaks. No performance acceptance is claimed by this spike.

Run the current authored spike with `./gradlew :rendering-world:test --tests '*TerrainLodSpikeTest' --offline`; run module checks with `./gradlew :rendering-world:check --offline`.
