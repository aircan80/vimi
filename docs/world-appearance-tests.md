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
| `objects` | Three exterior object quads over the authored terrain (`WorldAppearanceProof.objects`): a fully transparent cutout (discarded), an opaque quad at full light, and an alpha-blended quad. The scene is frozen in the appearance proof, so a change to another proof's scene (the material proof's grew mip ladders in #420) cannot move this reference. |

## Running

The proof needs a GPU, so it is a `nativeGpu*` task, not a unit test:

```sh
./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof --max-workers=2
./gradlew :rendering-bgfx:nativeGpuWorldAppearanceProof -PappearanceBackend=D3D11 --max-workers=2
```

`-PappearanceBackend` selects `VULKAN` (the default) or `D3D11`. Each backend has its own
references under
`rendering-bgfx/src/test/resources/com/vibeminer/rendering/bgfx/appearance/<backend>/`, because a
backend's frame read-back is compared as the raw 32-bit values it returns. The references are
recorded with the World's render target multisampled (MSAA x4) and anisotropic texture filtering
(#389); the proof prints `WORLD_APPEARANCE_SAMPLES <n>` for the mode the helper ran. A device that
cannot multisample (`WORLD_APPEARANCE_SAMPLES 1`) draws harder silhouettes and differs from the
references at edges.

A passing run prints the renderer and sample count, one line per scene, then a pass line:

```
WORLD_APPEARANCE_RENDERER Vulkan
WORLD_APPEARANCE_SAMPLES 4
WORLD_APPEARANCE terrain mean 0.000, outliers 0.0000%, max 0; within a pixel mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE water mean 0.000, outliers 0.0000%, max 0; within a pixel mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE fog mean 0.000, outliers 0.0000%, max 0; within a pixel mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE objects mean 0.000, outliers 0.0000%, max 0; within a pixel mean 0.000, outliers 0.0000%, max 0
WORLD_APPEARANCE_PASS backend=VULKAN scenes=[terrain, water, fog, objects]
```

The helper must also close cleanly after the last frame: a drain failure fails the proof.

## Tolerance

A frame matches its reference when it matches within a pixel, both ways
(`AppearanceImages.matches`, `Tolerance.NEAR`, `compareNearBothWays`): each pixel of the frame is
compared with the reference pixel within one pixel of its place (its 3 x 3 neighbourhood) that it
matches best, and each reference pixel with the frame the same way. Over the worse of the two
directions, the mean absolute channel difference must be at most 1.5, and at most 0.05 % of pixels
(about 70 of a 480 x 300 frame) may differ by more than 8 in any channel.

Another GPU or driver that places MSAA samples or rasterizes an edge a pixel apart moves edges, so
it passes even when many edges move. A whole feature that appears or disappears does not, down to a
line one pixel wide across the frame: comparing one way only misses a thin feature that disappears,
because every remaining pixel still finds a match beside it, which is why both directions are
checked. A lost texture, a wrong light and a changed sky or fog gradient fail as well. What this
check cannot see is a change smaller than its limits: a feature of a few dozen pixels, or one
that moves by a single pixel.

The proof also prints the plain pixel-by-pixel measurement (`compare`, with the limits of
`Tolerance.STANDARD`: mean 1.5 and 0.5 % of pixels) for every scene, next to the within-a-pixel
one; it does not decide the result. The comparison itself is unit-tested
(`AppearanceImagesTest`) and needs no GPU.

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

## History of the references (#527)

The Vulkan references were re-recorded on Linux (Vulkan, MSAA x4) at the main of #527, where
two runs are byte-identical. The earlier ones (#464) did not match any current helper: their sky
is the legacy screen-row gradient (91,141,177 at the top row to 170,200,213 at the bottom), which
the sky shader draws only with `u_terrainMode.w` 0, and the World renderer has set it to 1 (the
horizon gradient) since #407. Rendering the #464 commit itself on Linux gives the horizon gradient,
so the old references were not portable output of that code but frames of an older effective sky,
and no tolerance could have accepted them. The terrain and object frames also changed since #464 (on the same
machine, the #464 commit and current main differ in terrain shading and the objects scene), so
the references are re-recorded from current main.

The Direct3D 11 references still date from #464 and must be re-recorded on Windows with
`-PappearanceBackend=D3D11 -PappearanceUpdate=true` before that backend is checked again.

## Not covered yet

- A frame-rate and camera-to-pixel latency check on a fixed real route. It builds on the
  read-only measurement probe (#353, #423) and will fail on a deliberate regression such as a
  30 fps cap.
- Cross-tile objects drawn once (with #380).
