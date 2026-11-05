# MH2O rectangle to recorded native grid

Formats-only, source-neutral coordinate helper and proof. `WorldLiquidGrid.rectangle(grid,header,x,y,width,height)` copies one bounded matching MCNK's recorded native XY anchor and the rectangle scalars. It retains no root, TerrainGrid, chunk height/color arrays or Source handle. `vertex(row,column,actualHeight)` returns finite native XYZ; success is not provenance, source qualification, a scene/file/native owner or a drawing grant. A caller-made grid/rectangle/height can produce only descriptive positions. Existing Source owners remain unchanged on the frozen source branch.

## Primary mapping rule

Pinned TrinityCore `ec72c1914da121c07620dd5fe8ad084458dad9d1`: [the MH2O header array/accessor](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/adt.h#L172-L180) and [liquid grid traversal](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/System.cpp#L603-L660) associate the first header dimension with extracted terrain rows, the second with columns. [MCNK terrain traversal](https://github.com/TrinityCore/TrinityCore/blob/ec72c1914da121c07620dd5fe8ad084458dad9d1/src/tools/map_extractor/System.cpp#L493-L534) identifies those rows with MCNK iy and columns with ix. Thus flat header h selects recorded MCNK `(ix=h%16,iy=h/16)`, independent of parse order. Local liquid X advances column/east; local Y advances row/south. MH2O heights are stored directly into the liquid height map, unlike terrain heights which add the chunk height base.

In the application's unchanged raw native axes (+X north,+Y west,+Z up), use the actual recorded chunk anchor:

```
sourceRow = effectiveOffsetY + row
sourceColumn = effectiveOffsetX + column
nativeX = originX - (2 * sourceRow) * (TILE_SIZE / 256)
nativeY = originY - sourceColumn * (TILE_SIZE / 128)
nativeZ = actual stored/qualified liquid height
```

XY arithmetic matches even/V9 `TerrainGrid.Chunk.position` samples exactly. No filename-derived anchor, camera handedness, parse-order indexing, min/max fill or MCNK base-Z addition participates. This stage provides vertex positions only, no triangle diagonal or water optics. Future Source use must pass a rectangle/mask/height qualified from the same actual root and bind its private recorded anchor/root receipt; this helper cannot perform that qualification.

## Authored oracle

A deliberately shuffled grid first contains decoy MCNK(11,4), then actual(4,11), with unrelated asymmetric native anchors and nonzero terrain height base. Header180 selects(4,11) by recorded keys. Rectangle offset(1,2), width2,height3 and asymmetric cell bits0/5 have explicit independent expected native positions: local column changes only nativeY negatively, row changes only nativeX negatively, supplied17.25 remains Z17.25 instead of217.25. Every corner also matches the corresponding unmodified native terrain V9 sample's XY bits. Separate adjacent-chunk fixtures vary terrain base heights while checking shared native edges. Missing/transposed chunk key, invalid header/rectangle/vertex and nonfinite heights refuse rather than guess.

Full formats-world check passes:81 tests, including the new3 mapping fixtures and existing parser/grid/geometry gates. No Source-public or rendering API changed.

## Actual recorded terrain/adjacency overlay

Private proof reads actual configured local-only Source from original/prior stores and the existing world-stream acquisition cache opened READONLY; fresh scratch owns generated native tables/output. Every ROOT's decoded MD5 equals the actual numeric file content key. Actual native LiquidType/Material rows independently qualify LVF0 for indirect source layers; only layers with exactly constant actually used heights enter the coordinate proof. No standalone diagnostic bytes are substituted as Source inputs.

| Actual ROOT / inventory tile | Recorded native north range | Recorded native west range |
| --- | --- | --- |
| 777947 / (32,32) | −533.3333..0 | −533.3333..0 |
| 777952 / (33,32) | −1066.6674..−533.334 | −533.3333..0 |
| 778147 / (32,33) | −533.3333..0 | −1066.6674..−533.334 |

The helper maps5268 corners of1317 constant-layer cells across the two liquid roots. Every XY bit equals the corresponding **existing ROOT terrain mesh** vertex, selected through recorded MCNK x/y and its actual parse ordinal, not through header/list-index coincidence. Z bits equal the actual source height. Root777947 supplies the adjacent dry terrain tile; it does not acquire fabricated water. Seven nonplanar layers remain excluded under the source profile.

Across all adjacent chunk edges in the three roots and both actual neighboring tile edges,13248 paired samples pass. Maximum native XY difference is0.001312255859375 source units, within the explicit0.002 diagnostic tolerance and far below one4.1667-unit cell. These tiny differences come from recorded float anchors and are **preserved**, not snapped or repaired by invented coordinates.

The private top-down overlay plots terrain V9 height samples plus admitted liquid footprint quads, north up and east right(−nativeY). Its asymmetric shoreline inset shows header104→MCNK(8,6), with exact grid-corner alignment. This is a diagnostic XY overlay, **not** an in-engine water screenshot, visibility/depth/alpha proof or source-promoted scene. Private artifacts/harness: `/home/aircan/.cache/vimi-tmp/liquid-native-mapping-proof/{LiquidMappingActualProof.java,proof-final.log,run-final/terrain.csv,run-final/water.csv,run-final/native-liquid-overlay.svg,run-final/native-liquid-overlay.png}`. No game assets or derived images enter Git.

Resource change: each mapping retains only eight scalar fields (charged128B); no grid/payload copy, worker or cache is added. Returned Vec3 values are caller-owned. Future scene128-layer aggregate, source constructor/receipt join, world geometry and native/CPU draw/depth policies remain separate implementation reviews.
