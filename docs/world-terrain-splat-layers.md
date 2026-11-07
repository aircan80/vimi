# World terrain splat layers (#174 steps 2b-1 and 2b-2)

Shader-side splatting inputs are carried and uploaded next to the baked atlas, and the terrain shader blends them on near ground. They are off by default; `-Dvimi.world.splat=true` turns them on in the app. Without them, or for a chunk that has none, the atlas is what you see on screen.

## Shader blend (step 2b-2)

`world-stream/splat.sh` is included by the terrain fragment shader. It binds stage 1 (the layer array: repeat, anisotropic), stage 2 (the slot's mask map: clamp, trilinear), stage 3 (the chunk table: point) and `u_worldSplat` (x: on for this tile, y: mask side).

- **Weights:** the same as the atlas composer's. The base layer takes `max(0, 1 - (a1 + a2 + a3))`, and overlays summing past one are normalized, never saturated (#397). The MCCV brightness in mask A multiplies the result, with 128 = 1.0.
- **Tiling:** eight repeats per chunk (`atlasUV * 128`), continuous across chunk borders, so mips and anisotropy stay smooth.
- **Mask lookups** stay inside the pixel's chunk. A neighbour chunk's channels weigh other layers, so filtering never crosses a chunk border.
- **Fade to the atlas:** `smoothstep(0.5, 1.0, atlas texels per pixel)`. The splat shows where the 512 atlas is magnified at least two times, and the atlas shows from one texel per pixel on. It's resolution-driven, so it follows FOV and window size.
- **Missing layers:** a chunk keeps the atlas when its table says 255 for layer 0. The parent writes all four entries as 255 when any layer the chunk uses has no array layer (past the per-packet cap, or a failed decode), so a chunk is never blended with a layer missing.
- **No discard and no gradients in divergent branches.** The splat samples are taken under uniform branches only, and terrain keeps early depth testing.
- **Stand-ins:** a tile without splat inputs still binds every stage (the shared array, or a 2-layer stand-in, plus its atlas), so no backend sees an unbound sampler.

## Layer format (owner decision, 2026-11-06)

Every layer diffuse is one `TerrainLayerBlocks` chain: 256 x 256 BC1 with every level down to 1 x 1, 43,704 bytes.

- **DXT diffuses of side 256 or more** that record their levels from 256 down to 4 x 4 keep their own blocks.
  - BC1 blocks are copied as they are.
  - BC2 and BC3 keep their colour half. When a colour block's endpoints are not ordered `c0 > c1`, they are swapped and its indices remapped, so the block decodes to the same colours as BC1.
  - Terrain layers are opaque (the atlas composer blends colour only), so no alpha is lost.
  - A missing 2 x 2 or 1 x 1 level becomes one block of the 4 x 4 level's mean colour.
- **Every other diffuse** (paletted or BGRA, smaller than 256, or with missing mips) is decoded at its largest mip within 256. It is then enlarged with wrapping bilinear filtering, box-mipped and encoded as BC1 (endpoints are each block's two most distant texels), which is lossy.

The parent caches prepared layers by file id and content key: up to 256 entries, about 10.7 MiB.

## Packet trailer, version 3

The splat trailer (`VIMIWSP1`, version 3) carries:
- a head with the version, the count of new layers and the mask side;
- the 16 x 16 chunk table of GPU layer indices;
- the RGBA8 mask map: R, G, B are layers 1 to 3, A is the MCCV brightness;
- up to 16 new layers, each an int layer index followed by one 43,704-byte chain.

**Mask side by texture quality tier (#174 step 2b-2):**

| tier | mask map | per chunk | mask chain per slot (measured) |
|---|---|---|---|
| STANDARD | 512 x 512 | every other alpha-map texel (32) | 1,398,100 bytes (1.33 MiB) |
| HIGH | 1024 x 1024 | the native 64 x 64 alpha map | 5,592,404 bytes (5.33 MiB) |

- HIGH masks are used only with the NEAR view distance; FAR uses STANDARD masks on any tier (`TerrainTileSplat.maskSide(high, far)`). A tile's side is fixed when it is composed, and slots are reused as the camera moves, so the rule is per session, which lets #276 charge masks per slot exactly: NEAR + HIGH 10 x 5.33 MiB, FAR 26 x 1.33 MiB.
- The tier is the World view's texture quality setting (the object textures' tier). It applies to tiles composed after it changes. Each trailer states its own side, and the child recreates a slot's mask texture when the side changes.
- The mask keeps its full mip chain: bgfx cannot create a partial chain, and levels below 2 are only about 1.5% of it.
- Splat steps upload in slices of at most 64 KiB. One step call may issue several of them while staying within the 256 KiB step bound.

## Layer lifetime

- **Ownership:** the parent's `WorldStreamDiffuseArray` decides which diffuse holds which layer.
- **Who holds a layer:** a slot holds its layers from bind until its resident retires. Credit only goes to slots outside the active table, so a slot is never rebound while its old generation is drawn.
- **Release on commit:** each acknowledged commit releases every bound slot that the new table no longer draws and that is not the tile still in flight. Released layers stay cached and evictable.
- **Slots without splat:** a slot staged without splat layers releases its previous binding.
- **Failed tiles:** a tile that is not staged forgets the layers it never uploaded.

## Child array

The hot child creates one shared 2D array of 255 layers (255 is the table's "no layer" marker) on the first packet that has a splat trailer.

| Backend samples BC1 arrays | Array | Size |
|---|---|---|
| yes (D3D11, Vulkan on this machine) | 256 x 256 BC1, 9 levels | about 10.6 MiB |
| no, or `-Dvimi.world.splat.rgba8=true` | 128 x 128 RGBA8 (the chain's levels 1 to 8, decoded) | about 21.3 MiB |

- The child reports the chosen format once on stderr (`World splat layers: …`).
- BC1 layers are uploaded in block rows with the object texture planner. Each layer is one bounded step.

`nativeGpuWorldStreamSplatUploadProof -PsplatProofBackend=vulkan|d3d11 [-PsplatProofRgba8=true] [-PsplatProofMask=high]` checks the array format, the bounded steps and the per-slot mask bytes. It also checks three actual pixels:
- near ground shows the composer's blend of four solid layers under the mask, with its MCCV applied;
- a chunk without layers keeps the atlas;
- ground the atlas no longer magnifies fades to the atlas.

The BC upload layout itself is proven on both backends by the object layout ladders (#420).
