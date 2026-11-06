# World terrain splat layers (#174 step 2b-1)

Shader-side splatting inputs are carried and uploaded next to the baked atlas. They are off by default; `-Dvimi.world.splat=true` turns them on in the app. The shader that samples them is step 2b-2. Until then the atlas is still what you see on screen.

## Layer format (owner decision, 2026-11-06)

Every layer diffuse is one `TerrainLayerBlocks` chain: 256 x 256 BC1 with every level down to 1 x 1, 43,704 bytes.

- **DXT diffuses of side 256 or more** that record their levels from 256 down to 4 x 4 keep their own blocks.
  - BC1 blocks are copied as they are.
  - BC2 and BC3 keep their colour half. When a colour block's endpoints are not ordered `c0 > c1`, they are swapped and its indices remapped, so the block decodes to the same colours as BC1.
  - Terrain layers are opaque (the atlas composer blends colour only), so no alpha is lost.
  - A missing 2 x 2 or 1 x 1 level becomes one block of the 4 x 4 level's mean colour.
- **Every other diffuse** (paletted or BGRA, smaller than 256, or with missing mips) is decoded at its largest mip within 256. It is then enlarged with wrapping bilinear filtering, box-mipped and encoded with a range-fit BC1 encoder, which is lossy.

The parent caches prepared layers by file id and content key: up to 256 entries, about 10.7 MiB.

## Packet trailer, version 2

The splat trailer (`VIMIWSP1`, version 2) carries:
- the 16 x 16 chunk table of GPU layer indices;
- the 512 x 512 RGBA8 mask map;
- up to 16 new layers, each an int layer index followed by one 43,704-byte chain.

The parent's `WorldStreamDiffuseArray` decides which diffuse holds which layer. A tile that is not staged forgets the layers it never uploaded.

## Child array

The hot child creates one shared 2D array of 256 layers on the first packet that has a splat trailer.

| Backend samples BC1 arrays | Array | Size |
|---|---|---|
| yes (D3D11, Vulkan on this machine) | 256 x 256 BC1, 9 levels | about 10.7 MiB |
| no, or `-Dvimi.world.splat.rgba8=true` | 128 x 128 RGBA8 (the chain's levels 1 to 8, decoded) | about 21.3 MiB |

- The child reports the chosen format once on stderr (`World splat layers: …`).
- BC1 layers are uploaded in block rows with the object texture planner. Each layer is one bounded step.

`nativeGpuWorldStreamSplatUploadProof -PsplatProofBackend=vulkan|d3d11 [-PsplatProofRgba8=true]` checks the array format, the bounded steps and that the tile still renders. It cannot check layer contents until 2b-2 samples the array. The BC upload layout itself is proven on both backends by the object layout ladders (#420).
