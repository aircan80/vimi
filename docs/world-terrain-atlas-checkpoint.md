# Pure terrain atlas checkpoint

This checkpoint implements the pure composition portion of the reviewed texture
proposal (`879dd6c`, `2960bcb`), following formats checkpoints `ca99757` and
`f3e1819`. It contains no source service, cache, file format, UI, native renderer
or child-process changes. Independent production approval is pending.

## Contract and ownership

`TerrainAtlasComposer.compose(tile, grid, textures, diffuse, canceled)` consumes a
complete unique 16×16 checked source grid and 256 source-ordinal texture records.
It does not authenticate those inputs. The later concepts owner must positively
qualify WDT flags/source receipts and all recorded root/tex0/diffuse identities.
The bounded diffuse map contains at most 64 immutable declared square 4×4 RGBA
mips, with descriptive file ID/content key/profile/original-side/mip-level data.
Constructors validate shape before copying their fixed 64-byte pixel input.

`TerrainTileAtlas` retains exactly 512² RGBA bytes, four coverage words, the tile
record and 256 immutable source mappings. Its package-private constructor takes
sole ownership of arrays freshly allocated by the composer, avoiding a second
1 MiB output copy. Public views are read-only and expose no backing arrays.
Coverage is keyed by source ordinal; header X selects atlas columns (decreasing
native Y), header Y selects rows (decreasing native X). Each source ordinal's
exact native float anchor is preserved independently of its header coordinates.
The conservative retained-byte estimate is 1,065,248 bytes per complete atlas.

Composition retains one 28,672-byte scratch buffer, at most eight borrowed mip
references for the current chunk, 256 bounded diagnostics, four coverage words
and source mappings, plus the single 1 MiB output. It borrows the bounded grid,
raw texture owner and immutable diffuse values and never closes or retains them.
It creates no full-tile decoded alpha-mask allocation. Existing source buffers
and DTO copies remain covered by their own format/decoder ledger. This pure
checkpoint does not yet prove the complete future 48 MiB service/pool envelope.

## Explicit preview mathematics

Recipe `basic-coverage-preview-512-v1` is BASIC_COVERAGE_PREVIEW: direct checked
MCAL byte weights, not sequential source-over or the client's nonlinear
height-weight sharpening. Actual WDT `0x3ca` includes `0x80`; zero MHID/no MTXP
is not evidence that the upstream client's nonlinear path is disabled.

For chunk-local atlas pixel `(px,py)` in 0..31, sample MCAL at
`(2px+1,2py+1)` in its 64² row-major map. Base weight is 255 minus all overlay
weights. The reader scans every source mask sample, including samples absent
from the atlas lattice, and declines sums above 255. A missing diffuse or failed
mask declines the whole chunk rather than dropping or renormalizing layers.

Eight repeats per chunk and a declared 4² diffuse mip produce nearest periodic
texel `(px mod 4, py mod 4)`. Texture alpha does not supply terrain coverage.
MCCV must be absent (neutral multiplier one), or exactly 145 BGRA entries with
all alpha bytes 255. Unsupported shape/alpha declines the chunk.

MCCV interpolation is explicitly a **normalized local grid approximation** for
this coarse preview. Atlas centers map to fine-cell coordinates
`((px+.5)/4,(py+.5)/4)` on the original 8×8 staggered fan. Interpolate RGB in the
fan triangles `a-m-b`, `b-m-e`, `e-m-d`, `d-m-a`, multiply blended diffuse RGB by
`2 * interpolatedMCCV / 255`, then round and clamp once into RGBA8. Native float
positions are not welded into an assumed perfectly affine grid. This does not
claim exact native-space pixel identity or approve a single-anchor GPU UV map;
those precision/seam checks remain a renderer admission gate. Holes remain
geometry facts and do not erase source texture samples.

Unsupported chunk rectangles remain all-zero RGBA; admitted chunks have alpha
255. A later renderer may choose the existing height-shading fallback for alpha
zero. This checkpoint does not implement fog, terrain lighting or that selection.

## Validation

`./gradlew :rendering-world:spotlessApply :rendering-world:check` passes, including
new authored cases for:

- Direct RGB `(63,128,64)` instead of sequential blend; all eight layers and
  whole-mask rejection of excessive sums outside the chosen atlas lattice.
- Missing texture without renormalization; unsupported MCCV shape/alpha leaves
  a transparent chunk and a typed local reason.
- Asymmetric repeat orientation/pixel centers, BGRA swizzle, all four fan regions
  and boundaries, final-only rounding/clamping, independent geometry holes.
- Large-coordinate exact retained source anchors, noncanonical source/header
  ordering, immutable inputs/output views, bounded map/grid/mip shape and
  cancellation followed by reuse of the borrowed texture owner.

Private read-only evidence is under `/tmp/vimi-terrain-texture-proof`; no assets
are committed. `NativeAtlasProof.java` composes the previously verified source
pairs and the 13 original production-decoded declared 4² diffuse mips. Every
source produces 256/256 admitted chunks and opaque atlas pixels:

| Root / tex0 | Geometry holes | RGBA SHA-256 |
| --- | ---: | --- |
| 777947 / 777950 | 12 | `1233b96277378c5e1fdfa0168f24d3f7eadcdcb21ef05510bc4d459eec85a2b0` |
| 777952 / 777955 | 0 | `f48c056a064399001a0b64d6d24651a52bbf63686e60c6fd7a5b88412fb6cb79` |
| 778147 / 778150 | 12 | `46e9ca887631ce66b5f1d78126bd2d5e324bd2f8b68c257ef403bd827f3e8fb9` |

Private PNGs are `atlas-<root>-basic-v1.png` and
`atlas-three-sources-basic-v1.png`. The inspected images show source paths,
rock and grass coverage with deliberately coarse 4-texel repeats. They are a
visual data/composition proof, not a World Explorer runtime or fidelity claim.
`native-atlas-proof.log` records each result. Observed 24–60 ms composition times
are diagnostic observations, not a performance guarantee.

A separate standard-library Python oracle, `compare-atlas-samples.py`, parses the
original root/tex0, independently expands masks and uses generic barycentric
coordinates instead of the production region-specific formulas. It checks
12,288 exact RGBA samples across all 768 source chunks; all pass. Log:
`independent-atlas-samples.log`. Existing full-mask SHA-256 agreement and the
111,360 unchanged native-vertex proof remain recorded in the formats checkpoint.

## Remaining gates

Source authentication/optional-failure semantics, shared 18-atlas pool,
persistence, source/copy/cache/replacement lifetime ledger, native-anchor UV
precision/seams, atlas annex integrity, CPU/GPU composition and real inline
frames remain separate checkpoints. No runtime backend qualification or GPU
texture upload behavior is implied here.
