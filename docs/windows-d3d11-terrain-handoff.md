# Handoff: Windows D3D11 GPU terrain renders only the sky

Branch: `debug/windows-d3d11-terrain` (from main `cfffb8ba`, 2026-11-04).
Work on this branch from native Windows PowerShell (not WSL). A Linux agent may also
push diagnostics here, so run `git pull --rebase` before you start and before you push.

## Symptom

On Windows, the World Explorer's inline GPU renderer uses Direct3D 11 by default.
With **GPU terrain** on, the viewport shows only the sky gradient, with no terrain at
any camera position. With GPU terrain off (CPU path), terrain renders correctly.

## What is already known (from Linux code reading)

- Backend selection: `rendering-bgfx/src/main/java/com/vibeminer/rendering/bgfx/GpuViewerMain.java`,
  `backend()` (line about 278). A `--backend vulkan|metal|d3d11` child argument exists,
  but there is no OpenGL option. Without it, WINDOWS maps to D3D11.
- The GUI always passes the default backend:
  `gui/src/main/java/com/vimi/gui/WorldStreamGpuOwner.java:20`
  (`WorldStreamTransport.Backend.DEFAULT`). **There is currently no way to pick another
  renderer from the GUI.** Adding a debug override (for example a `vimi.gpu.backend` system
  property read there) is the first useful change, so Vulkan vs D3D11 can be compared on
  the same machine.
- DX11 shader binaries **are** shipped for terrain, sky and world-stream
  (`rendering-bgfx/src/main/resources/com/vibeminer/rendering/bgfx/shaders/{terrain,world-stream}/dx11/`),
  so "missing shader" is unlikely. A loading failure would also raise
  `Terrain shader unavailable`.
- `BgfxTerrainRenderer.requireCapabilities` rejects `homogeneousDepth` (it expects
  0..1 depth) and requires D32F and instancing. D3D11 meets this, so a plain GL depth-range
  mismatch is unlikely too, unless the world-stream path builds its own projection.

## Remaining candidates (in rough order)

1. The inline World Explorer probably draws through the **world-stream** renderer,
   not `BgfxTerrainRenderer`. Check its projection, depth clear value and compare
   function (reverse-Z?), and cull winding under D3D11.
2. Vertex layout vs HLSL input semantics, or attribute types (normalized or integer
   attributes behave differently on D3D11).
3. Matrix upload order (row or column major `mul` in the compiled HLSL) and uniform
   type or array sizes that D3D11 packs differently.
4. Texture formats or samplers (heightmap or array textures) that D3D11 doesn't support,
   leaving the vertex shader displacing to NaN or zero.
5. Instance data stride or offset, and 32-bit index flags.

## Setup

Follow [continue-windows.md](continue-windows.md) for the clone, JDK 25, `sqlite3.dll`
and .NET setup. Then:

```powershell
git fetch origin
git switch debug/windows-d3d11-terrain
git pull --rebase
$sqliteLibrary = (Resolve-Path .local\sqlite\sqlite3.dll).Path
.\gradlew.bat :gui:run "-PsqliteLibrary=$sqliteLibrary"
```

Open a prepared build in the World Explorer with GPU terrain on and reproduce the bug.

## Experiments that separate the candidates

1. **Backend A/B:** after adding the override, run with Vulkan instead of D3D11. If terrain
   appears on Vulkan, the bug is in a D3D11-specific path (shader compile, layout, state).
2. **Log once per session:** renderer name, `caps.homogeneousDepth`, `originBottomLeft`,
   loaded shader resource paths, draw/primitive counts from `bgfx_get_stats` for the terrain
   view, and the first terrain vertex's clip-space position computed on the CPU.
3. **bgfx debug:** enable `BGFX_DEBUG_TEXT | BGFX_DEBUG_STATS` (and wireframe) to see whether
   terrain draw calls are submitted and whether any primitives reach the rasterizer.
4. **Graphics debugger:** if available, capture a frame with RenderDoc (it supports D3D11)
   and inspect the terrain draw: vertex shader output (positions), depth test result and
   cull state.

Primitives submitted but nothing visible points to projection, depth or cull state. No
primitives submitted points to data upload or the CPU-side visibility path.

## Paste into an agent on Windows

> Read AGENTS.md, docs/continue-windows.md and docs/windows-d3d11-terrain-handoff.md.
> We're on branch debug/windows-d3d11-terrain on native Windows to fix the World
> Explorer's D3D11 GPU terrain rendering only the sky. Run `git pull --rebase` first.
> Add the backend override, run the experiments in the handoff, find the root cause and
> fix it with a regression test where possible. Keep logs privacy-safe (no game install
> paths, account names or hostnames). Commit with author and committer aircan80
> <329744803+aircan80@users.noreply.github.com>, push to this branch and open a PR to main.
> Before finishing, update this handoff with the tested commit, GPU/driver, results and
> the next step.

## Returning to Linux

Update the "Results" section below, commit and push. Then tell the Linux session:
"I'm back from Windows. Read docs/windows-d3d11-terrain-handoff.md on
debug/windows-d3d11-terrain and continue."

## Results

_(Windows: fill in GPU and driver version, tested commit, experiment results, and the cause.)_
