# Native bgfx rendering support

LWJGL **3.4.3**, bgfx **API156**, core bindings are pinned together. This module
uses Java25 and depends on the immutable `rendering-models` scene contracts. The
isolated offscreen helper entry is `com.vibeminer.rendering.bgfx.GpuViewerMain`.

## Inline presentation contract

The user's selected behavior is automatic rendering inside the existing JavaFX
model viewport. `GpuPreviewService` owns an isolated **offscreen** bgfx child; it
creates no preview window and needs no GLFW runtime. The previous separate-window
prototype is superseded.

The child uploads the immutable scene, draws into an offscreen target, and keeps
three bounded asynchronous texture-readback slots alive until their ready frames.
Completed top-down BGRA pixels are copied into the bounded mapped-frame bridge.
The parent validates framing/checksums and copies the newest applicable frame off
the FX thread; JavaFX performs a bulk image upload into the existing viewport.
Request generations reject stale delivery; hidden/closed views drain their owner,
and a failed native path can return to software without a respawn loop. Native
readback and shutdown waits remain outside the FX thread. These are ownership and
work-placement rules, not a guarantee that all FX callbacks are inexpensive.

This is **not zero-copy**: GPU readback, shared-memory publication, parent pixel
copy and FX image upload remain real costs. A GPU backend does not imply a speedup
for every mesh, driver or view size. The software renderer remains a fallback.

## Evidence and limits at the 2026-11-03 checkpoint

Actual hardware evidence is **Linux Vulkan only**, using authored geometry and
materials rather than game/user data. `GpuMaterialProof` covers 16 material cases;
`GpuInlineProof` exercises the real offscreen child and frame transport;
`GpuInlineUiProof` exercises JavaFX presentation, camera changes, hide/restart and
failure fallback. The fresh native run recorded cold 324.7 ms, warm
request-to-copied-frame p50 30.65 ms / p95 39.09 ms, sampled 31.17 fps and copy 1.82 ms.
These local fixture observations are not live-build or cross-platform benchmarks.

At `93da682`, independent GUI review still has two delivery races under correction;
final implementation approval and fresh full/hosted checks remain pending. Pinned
Metal/DX11 resources, runtime classifiers and a successful GPU-free native load
probe do **not** constitute actual Metal/D3D hardware rendering acceptance.

Supported runtime targets and native classifiers:

| Target | Classifier |
| --- | --- |
| Linux x64 | `natives-linux` |
| Linux ARM64 | `natives-linux-arm64` |
| macOS Intel | `natives-macos` |
| macOS Apple Silicon | `natives-macos-arm64` |
| Windows x64 | `natives-windows` |

Gradle selects the matching host by default. Use `-PbgfxPlatform=linux-arm64`
(or another table target) to resolve a cross-platform distribution explicitly.
`gradle.lockfile` pins the common versions; `lwjgl-runtime.json` records all Java
and native JAR URLs/SHA-256 values. Native JARs remain on the packaged classpath
for LWJGL's own extraction/loading. No manual game-data or cache transfer is needed.
Existing jpackage runtime modules include `jdk.unsupported`, required by LWJGL.
Explicit LWJGL/bgfx licenses are bundled in this module's resource JAR.

## Reproducible offline shaders

Shader source uses immutable native position/normal/UV0 plus sparse-card-derived
pivot/marker attributes (48-byte GPU stride). Shared `PreviewCamera` supplies
model-to-eye, projection and native/eye-space headlight uniforms. A second sampler
supports the reviewed per-vertex sphere-map ENV coordinate; material uniforms carry
explicit combiner, lighting and blend codes. Opaque/cutout sections retain legacy
section order, followed by unlit additive draws with separate RGB/alpha blending,
LEQUAL and no depth write. Camera-facing cards are the reviewed rigid bind/rest
profile; this does not implement animation or general skinning. Texture-off mode
remains neutral opaque geometry, with qualification and card transforms intact.

The matching official release compiler is **shaderc1.19.156**. Every platform's
library provenance records bgfx source
`780f7bf00a29573473b90dcfab9f92a7d8687702`; its `bgfx_shader.sh` is fetched by exact
commit and SHA-256. Build-only compiler URLs are versioned under
`https://build.lwjgl.org/release/3.4.3/`, with checksum pins in
`scripts/rendering/compile_bgfx_shaders.py`. No `stable` or `nightly` assets are used.

The compiler supports GLSL330, SPIR-V, Metal and DX11 shader-model5.0 targets.
It rejects GLSL150; the OpenGL backend consequently needs at least OpenGL3.3.
macOS uses Metal. Shader binaries are independent of CPU architecture.

```sh
# Linux x64/macOS: regenerate GLSL330, SPIR-V and Metal resources.
python scripts/rendering/compile_bgfx_shaders.py
# Windows x64: regenerate DX11 using its system D3DCompiler.
python scripts/rendering/compile_bgfx_shaders.py --profile dx11
# Normal verification needs no compiler download/network access.
python scripts/rendering/compile_bgfx_shaders.py --verify
python -m unittest discover -s scripts/rendering -p '*_test.py'
```

The optional compiler cache defaults to ignored `.local/toolchains/bgfx-3.4.3`;
`--tool-directory` selects an explicit build-machine cache. Linux ARM64 has the
runtime natives but no official matching shaderc executable: consume committed
shader resources, or regenerate them on Linux x64/macOS/Windows. DX11 generation
on Linux would need unbundled Wine/D3D4Linux; the retained Windows workflow handles
that target instead. The workflow uploads `bgfx-shaders-with-dx11`, containing the
complete resource directory and updated manifest, for review/commit.

The checked-in manifest binds every binary to the exact authored sources,
upstream include, compiler identity, native API revision and target profile.
`processResources` and `check` run offline verification and reject missing/stale
profiles. The explicit `compileShaders` Gradle task regenerates host-supported
profiles; application launch never downloads or invokes shaderc. Drivers can still
perform their normal API-level shader/pipeline compilation when creating programs.

All four profiles are committed. The DX11 binaries were generated and verified by
the pinned Windows workflow; the other three profiles were generated by the pinned
Linux x64 compiler. Regeneration requires both build hosts when shader inputs change.
Full verification intentionally rejects a partial or stale resource set.

## Native library availability without GPU hardware

`./gradlew :rendering-bgfx:nativeRuntimeProbe` loads the actual host LWJGL core and
bgfx native libraries, calls renderer enumeration and the native init-structure
constructor, and verifies all eight packaged shader blobs against their manifest.
It never initializes bgfx, creates a window, or opens a GPU device. `check` runs
this probe on its host target, including the Linux/macOS/Windows unit CI matrix.
Cross-target builds skip execution; they retain their pinned target native JARs.
This proves native loading and compiled backend availability, not hardware support
or successful Metal/D3D rendering. Hardware rendering remains an explicit smoke.
The macOS first-thread option applies to the isolated helper, not JavaFX.
