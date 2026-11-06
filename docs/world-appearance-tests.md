# World appearance tests

The World explorer's unit tests check accounting: counts, budgets, ownership and protocol safety.
The appearance proof checks what the view **looks like**. It renders small authored scenes through
the real World helper on the native GPU and compares every frame with a stored reference image
(#379). All scenes are authored in test code; no game data is used or committed.

## Scenes

| Scene | What it covers |
|---|---|
| `terrain` | The authored tile 7,19, flat, with all 256 chunks triangulated as the client does (four triangles per cell around its inner vertex). Seen from above: terrain atlas sampling, shading and the sky around it. |
| `water` | A strip of authored water (16 x 64 cells, 2.5 above the terrain, depths rising across each cell) on the same tile, seen from above: the water pass over terrain. |
| `fog` | The tile from 400 beyond its edge, looking back and down: the fog rising across the tile toward its far edge. |
| `objects` | The exterior object material quads over the authored terrain (`WorldStreamObjectsMaterialProof.quads`): a fully transparent cutout (discarded), an opaque quad at full light, and an alpha-blended quad. The tile is filled to the placement cap behind the camera, so culling and both object passes run at the cap. |

## Running

The proof needs a GPU, so it is a `nativeGpu*` task, not a unit test:

```sh
./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof --max-workers=2
./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof -PappearanceBackend=D3D11 --max-workers=2
```

`-PappearanceBackend` selects `VULKAN` (the default) or `D3D11`. Each backend has its own
references under
`rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/appearance/<backend>/`, because a
backend's frame read-back is compared as the raw 32-bit values it returns.

A passing run prints one line per scene, then a pass line:

```
WORLD_APPEARANCE terrain mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE water mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE fog mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE objects mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE_PASS backend=VULKAN scenes=[terrain, water, fog, objects]
```

The helper must also close cleanly after the last frame: a drain failure fails the proof.

## Tolerance

A frame matches its reference when both of these hold (`AppearanceImages.Tolerance.STANDARD`):

- the mean absolute channel difference over all pixels is at most 1.5;
- at most 0.5 % of pixels differ by more than 8 in any channel.

That absorbs driver rounding and edge rasterization differences. It fails a lost texture, a wrong
light, a missing or extra surface, or a region of the frame drawn differently. The comparison
itself is unit-tested (`AppearanceImagesTest`) and needs no GPU.

On a mismatch, the proof writes `<scene>-actual.png` and `<scene>-diff.png` (differences scaled up
8x) to `rendering-bgfx/build/native-world-appearance/`, and fails with the measured difference.

## Refreshing the references

When a change is **meant** to alter the image (a new shading model, fog, a material fix):

1. Run the proof without the update flag and look at `<scene>-actual.png` and `<scene>-diff.png`
   in `rendering-bgfx/build/native-world-appearance/`. Confirm the difference is the intended one.
2. Rewrite the references for each backend you can run:

   ```sh
   ./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof -PappearanceUpdate=true --max-workers=2
   ./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof -PappearanceBackend=D3D11 -PappearanceUpdate=true --max-workers=2
   ```

3. Run both again without the flag (they must pass), and commit the new PNGs with the change.
   Say in the PR which scenes changed and why.

The proof checks that the helper really used the requested backend: the helper reports the
renderer bgfx created on stderr (`World v2 renderer: <name>`), the proof prints it as
`WORLD_APPEARANCE_RENDERER <name>` and fails if it is not the requested one ("Vulkan",
"Direct3D 11"). On the Windows development machine both backends report their own renderer and
still produce byte-identical frames for these flat, untextured-light scenes.

## Not covered yet

- A frame-rate and camera-to-pixel latency check on a fixed real route. It builds on the
  read-only measurement probe (#353, #423) and will fail on a deliberate regression such as a
  30 fps cap.
- Cross-tile objects drawn once (with #380).
