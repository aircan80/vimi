# World explorer minimap

The explorer gets a top-right circular, north-up minimap. The player remains at the center; a facing arrow points up at yaw zero and left at yaw π/2. The displayed world diameter is one terrain tile. Only recorded `WorldFormats.Tile.minimapId` references are read; there is no inferred path, area-based artwork lookup, or whole-map acquisition.

## Source and lifetime

`BuildWorld.minimap(Tile, Cancellation)` is optional with an unsupported default for existing providers. `WorldService` delegates to a dedicated single-worker bounded source helper, borrowing `BuildFiles` without closing it. Complete, nonprotected BLP2 sources are decoded at a declared mip no larger than 512 pixels, then explicitly nearest-resampled at pixel centers to no larger than 256 pixels off FX, with explicit source-byte, declared-dimension and decoded-allocation bounds. RGBA conversion happens off the FX thread. The generic BLP decoder remains strict and never invents or resizes a mip. Sources with only larger-than-512 mips remain unavailable. The decode allocation limits are 512² pixels and 1 MiB each for mip/RGBA working bytes, with declared dimensions at most 4096 and source bytes at most 16 MiB. A decoded RGBA array plus its defensive access copy retain at most 2 MiB during conversion; final ARGB output is at most 256 KiB (plus its bounded defensive construction copy). The nine-entry source image cache and nine FX images each retain at most 2.25 MiB of pixel payload; an active batch can retain at most nine output results (another 2.25 MiB), excluding decoder internals/source staging and toolkit/native image storage. Results contain detached, read-only ARGB pixels and an explicit unavailable reason. The helper retains at most nine decoded images, bound to recorded file ID and current source content key. Close cancels requests and drains the helper; custom source handles remain borrowed.

## Coordinate handoff

The component receives the current inventory, origin filename tile `(x,y)`, that tile's native maximum `(originX,originY)`, and native camera position/yaw. A tile's native maximum is:

```
maxX = originX - (tile.x - origin.x) * TILE_SIZE
maxY = originY - (tile.y - origin.y) * TILE_SIZE
```

Screen horizontal increases toward negative native Y; screen vertical increases toward negative native X. Source image U increases toward decreasing native Y and V toward decreasing native X. The circular viewport has a world diameter no larger than `TILE_SIZE`, so only the surrounding 3×3 recorded tiles can intersect. The circle clips all image artwork. A stationary center marker and separately rotated facing arrow remain legible over missing artwork.

## Async presentation

A separate component owns image loading, local animated activity and an explicit missing-artwork fallback. It admits one active request batch plus one replaceable pending view, never an unbounded request per camera update. A batch contains at most nine recorded image requests and checks cancellation between them. Camera motion redraws already available images immediately without spinner flicker; only genuinely pending artwork shows activity. Generation and source identity checks reject old results after map/workspace changes, hiding or close. FX image upload uses bulk pixels, never per-pixel loops. WorldView integration is limited to mounting the component, supplying the handoff and retiring it at existing explorer/workspace lifetime boundaries.

## Acceptance

Independent asymmetric image fixtures check tile artwork orientation, neighboring placement, player centering and yaw arrows. Service fixtures check only recorded IDs, missing/protected/oversized/malformed sources, detached pixels, cancellation, cache identity and borrowed-source lifetime. Delayed-provider FX fixtures check loading, latest-view coalescing, map retirement and close. Real source images and private screenshots remain outside Git. No assertion of universal artwork availability or a rotating player-up map is made.
