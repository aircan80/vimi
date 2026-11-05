# Handoff: Windows D3D11 GPU terrain renders only the sky

Branch: `debug/windows-d3d11-terrain` (from main `cfffb8ba`, 2026-11-04).
Work on this branch from native Windows PowerShell (not WSL). A Linux agent may also
push diagnostics here, so run `git pull --rebase` before you start and before you push.

## Symptom

On Windows, the World Explorer's inline GPU renderer uses Direct3D 11 by default.
With **GPU terrain** on, the viewport shows only the sky gradient, with no terrain at
any camera position. With GPU terrain off (CPU path), terrain renders correctly.

## Root cause (found by Linux code and shader-binary inspection)

**The instanced terrain shaders used the wrong D3D11 instance-data semantics.**

- The pinned bgfx (`780f7bf`, LWJGL 3.4.3) binds per-instance data `i_dataN` to
  `TEXCOORD(31-N)` on D3D11 (`BGFX_CONFIG_INSTANCE_DATA_FIRST_TEXCOORD = 31` in
  `src/config.h`; `setInputLayout` in `src/renderer_d3d11.cpp`). Upstream's
  `examples/05-instancing/varying.def.sc` at that commit uses `TEXCOORD31/30/29`.
- Our `rendering-bgfx/src/main/shaders/terrain/varying.def.sc` and
  `.../world-stream/varying.def.sc` (and both test spikes) declared the older bgfx
  convention `i_data0 : TEXCOORD7`, `i_data1 : TEXCOORD6`, `i_data2 : TEXCOORD5`.
- Parsing the shipped `world-stream/dx11/vs_model.bin` shows a DXBC input signature of
  `POSITION0, TEXCOORD7, TEXCOORD6, TEXCOORD5` and a bgfx attribute list that marks
  TexCoord5-7 as *per-vertex* attributes. The D3D11 runtime therefore feeds those three
  inputs from the 12-byte template vertex stream (offset 0) and puts the real 48-byte
  instance buffer on TEXCOORD31/30/29, which the shader never reads. Every triangle
  collapses to garbage near the camera origin and is culled or degenerate. No D3D error
  is raised.
- The sky shader only reads `POSITION`, so it is unaffected, which is why only the sky
  shows. GLSL, SPIR-V and Metal bind instance data by name or location, so Linux and
  macOS were never affected. Recompiling those profiles with the fixed semantics
  produces byte-identical binaries.

### Ruled out

| Candidate | Evidence |
| --- | --- |
| Missing or unpackaged DX11 shaders | `terrain/dx11`, `terrain/sky/dx11` and `world-stream/dx11` binaries exist, are `s_5_0`, and are listed in their manifests |
| GL-vs-D3D depth range | `BgfxTerrainRenderer.requireCapabilities` rejects `homogeneousDepth`; `TerrainCamera.reversedProjection` writes depth `near/d` in 0..1 with clear 0 and `DEPTH_TEST_GREATER`, valid on D3D11 and Vulkan |
| Matrix order | Same column-major data and `mul(M, v)` as the Vulkan path; bgfx keeps `mul` consistent across backends |
| Cull or winding | No `BGFX_STATE_CULL_*` flag is set, so nothing is culled |
| Texture formats | RGBA8, BGRA8 RT and D32F RT, all checked with `bgfx_is_texture_valid` |
| 32-bit indices | Terrain is non-indexed instancing |

## Fix on this branch

1. `varying.def.sc` for terrain, world-stream and both spikes now declares
   `TEXCOORD31/30/29`.
2. `scripts/rendering/compile_bgfx_shaders.py` rejects any `i_dataN` that is not
   `TEXCOORD(31-N)` in both compile and `--verify`, with Python tests.
3. `Dx11InstanceInputSignatureTest` (rendering-bgfx JUnit) parses each packaged DX11
   instanced vertex shader and asserts its input signature is exactly
   `POSITION0, TEXCOORD31, TEXCOORD30, TEXCOORD29`.
4. The Windows shader CI job now also compiles the production world-stream DX11
   shaders, which it did not do before. The DX11 binaries on this branch come from that job.
5. Diagnostic override for the GUI: `-PgpuBackend=vulkan|d3d11|metal|default`
   (Gradle), `-Dvimi.gpu.backend=...` or `$env:VIMI_GPU_BACKEND`.

## Setup

Follow [continue-windows.md](continue-windows.md) for the clone, JDK 25, `sqlite3.dll`
and .NET setup. Then:

```powershell
git fetch origin
git switch debug/windows-d3d11-terrain
git pull --rebase
$sqliteLibrary = (Resolve-Path .local\sqlite\sqlite3.dll).Path
```

## Verify the fix on Windows

1. Offline checks (no GPU needed):

   ```powershell
   .\gradlew.bat :rendering-bgfx:test --tests "*Dx11InstanceInputSignatureTest*" "-PsqliteLibrary=$sqliteLibrary"
   python -m unittest discover -s scripts/rendering -p "*_test.py"
   ```

2. Default D3D11 run. Open a prepared build in the World Explorer with **GPU terrain**
   on and move the camera around:

   ```powershell
   .\gradlew.bat :gui:run "-PsqliteLibrary=$sqliteLibrary"
   ```

   Expected: terrain shows the same as the CPU path (shading can differ slightly), and the
   status line ends with `Inline GPU renderer ready · Direct3D 11`.

3. Optional A/B, which also exercises the new override:

   ```powershell
   .\gradlew.bat :gui:run "-PsqliteLibrary=$sqliteLibrary" -PgpuBackend=vulkan
   ```

   Expected: the status line shows `Vulkan` and terrain renders the same as on D3D11.

4. Optional regression proof: `git checkout 317015ff -- rendering-bgfx/src/main/resources`
   followed by step 2 should show only the sky again. Run `git checkout HEAD -- rendering-bgfx/src/main/resources` afterwards.

### What to send back

Keep it privacy-safe: no game install paths, account names or hostnames.

- GPU model and driver version (from `dxdiag` or Device Manager) and the tested commit
  (`git rev-parse --short HEAD`).
- Pass or fail for step 1.
- For steps 2 and 3: the GUI status line text (`Inline GPU renderer ready · ...`) and a
  screenshot of the World Explorer viewport with GPU terrain on, cropped to the viewport.
- If terrain is still missing on D3D11 but present on Vulkan: a RenderDoc capture
  (RenderDoc supports D3D11) of one frame, or at least the terrain draw's IA input
  layout (semantics and input slots) and VS output positions from the Pipeline State
  view.

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

Windows 11 Pro, 2026-11-05. Tested commit `5b1c8315`.
GPU: NVIDIA GeForce RTX 5090, driver 32.0.16.2114 (an Intel Graphics adapter, driver
32.0.101.8860, is also present; which adapter bgfx picked was not confirmed).

| Step | Result |
| --- | --- |
| 1. Offline checks | **Pass.** `Dx11InstanceInputSignatureTest` passed; `scripts/rendering` Python tests: 15 OK |
| 2. Default D3D11 run | **Pass.** Zephras Isle (map 2991) renders terrain, river and cliffs with GPU terrain on, 9 / 9 tiles textured. The sky-only bug is fixed. The `Inline GPU renderer ready · Direct3D 11` status text was not visible anywhere in the World view, so the backend was not confirmed from the UI (no override was set, so D3D11 is the default). This run was short. |
| 3. Vulkan A/B (`-PgpuBackend=vulkan`) | **Fail, but not a Vulkan bug.** Terrain renders, then after 23 s to about 2 min of walking the viewport freezes on its last frame: "World viewer failed", 0 / 9 tiles. Reproduced 4 times at different places (Shrine of Akir tile 26,31; Shen'dar Highlands tile 26,27). |
| 4. Regression proof | Not run. |

### New bug: a routine tile cancellation kills the World viewer

The renderer child is not crashing. It exits cleanly (code 0) because the host closes it:
the host's source side raises `IllegalStateException("Source outcome accompanied by an
unexpected failure")` from `WorldSourceRefusals.fatalAuxiliary`. The wrapped original
failure, captured with temporary local logging (not committed), was:

```text
thread=world-v2-parent-producer-files
java.util.concurrent.CompletionException: World source await failed
    at com.vibeminer.concepts.world.WorldSourceFutures.lambda$await$0(WorldSourceFutures.java:25)
    at com.vibeminer.files.Io$Bounded.lambda$run$0(Io.java:59)
Caused by: java.util.concurrent.CancellationException: join
    at java.util.concurrent.CompletableFuture.join
    at com.vibeminer.files.BuildFilesHandle.lambda$stat$0(BuildFilesHandle.java:310)
Caused by: java.util.concurrent.CancellationException: join
    at java.util.concurrent.CompletableFuture.join
    at com.vibeminer.files.names.ListfileStore.lambda$nameOf$0(ListfileStore.java:275)
Caused by: java.util.concurrent.CancellationException
    at com.vibeminer.store.Cancellation.check(Cancellation.java:70)
    at com.vibeminer.store.ObjectStore.readCore(ObjectStore.java:990)

fatalAuxiliary called from:
    WorldSourceRefusals.boundary(WorldSourceRefusals.java:108)
    WorldTerrainLiquids.inspectAndWrite(WorldTerrainLiquids.java:534)
    WorldWaterPreview.prepareAndWrite(WorldWaterPreview.java:391)
    com.vimi.gui.WorldStreamSourceBridge$Producer.prepareWater(WorldStreamSourceBridge.java:212)
    WorldStreamTransport$Session.produceFile(WorldStreamTransport.java:969)
```

Cause: water inspection for a tile is cancelled through `admission.cancellation()` as the
camera moves on, which is expected. Each `CompletableFuture.join()` in the files layer
(`ListfileStore.nameOf`, then `BuildFilesHandle.stat`) wraps it in a new
`CancellationException("join")` whose cause is the previous one.
`WorldSourceRefusals.boundary` accepts only a cause-free `CancellationException`, so it
classifies this chain as fatal, the stream protocol fails, and `GpuPreviewService` closes
the persistent renderer. This lives in the source/water path, not the GPU backend, so D3D11
is very likely affected too; the D3D11 run was probably just too short to hit it.

Supporting observation: `WorldStreamTransport.drainErrors` discards the renderer's stderr
and the host failure is only shown as "World viewer failed", which hid this.

### Next step (Linux)

1. Decide the fix. Proposed: in `WorldSourceRefusals.boundary`, also accept a bounded chain
   in which every link is exactly `CancellationException.class` (not a subclass) with no
   suppressed exceptions, ending in a cause-free one. A chain made only of cancellations
   cannot hide an auxiliary failure, so the existing
   `cancellationSubclassCannotHideAuxiliaryFailure` and accessor tests still hold.
   The alternative is to stop the files layer (`ListfileStore`, `BuildFilesHandle`) from
   re-wrapping cancellation on `join()`.
2. Add a regression test that reproduces the exact chain above through `boundary()`.
3. Consider an opt-in way to surface the host failure and renderer stderr for debugging.
4. Back on Windows: walk Zephras Isle for several minutes on both D3D11 and Vulkan.
