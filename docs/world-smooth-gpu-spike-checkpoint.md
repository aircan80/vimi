# Persistent World authored feasibility checkpoint

This checkpoint is test-only. It does not change the production renderer, child protocol,
model previews, or running application. The approved design remains
[the streaming proposal](world-smooth-gpu-streaming-proposal.md).

Run `./gradlew :rendering-bgfx:nativeGpuWorldStreamSpike` on qualified Vulkan hardware.
`verifyWorldStreamSpikeShaders` checks separate test assets; production assets are unchanged.
The initial Linux checkpoint contains GLSL, SPIR-V, and Metal offline blobs. DX11 must be
compiled by the pinned Windows workflow before all-profile verification passes. Offline
Metal/GLSL/DX11 compilation does not qualify those backends at runtime.

## Actual Linux Vulkan evidence

The allocation probe starts with three underlying vertex buffers, allocates ten maximum
65,536-triangle, 48-byte instance buffers, and observes thirteen underlying buffers. Ten
additional near-1-MiB probes fit their spare regions without adding backing buffers; one
additional 96-byte padded request spills to a fourteenth backing. Combined with the pinned
allocator's size and alignment rules, this verifies ten 4-MiB backing allocations. Probe
handles are then destroyed before the resident-scene experiment.

The resident experiment maintains nine active geometry/atlas pairs and one staging pair.
It performs 104 replacement cycles, including cancellation with native references outstanding,
slot reuse and stale-generation rejection. A maximum tile upload uses 4,194,304 bytes of
immutable native producer storage. Upload steps enqueue at most 262,144 bytes. All 1,999
release callbacks drain; final outstanding references and producer bytes are zero. Resource
counts remain ten dynamic vertex buffers and fourteen textures (ten atlases and four frame
resources). Five camera positions produce distinct images while the maximum tile uploads.
An unchanged-camera commit advances the returned world-content revision. This is a local
Java render ticket, not evidence for the future mapped-frame v2 protocol.

Observed maximum enqueue-step duration was approximately 4.3 ms in this run, despite checking
a 2-ms budget between native calls. A native call is not preemptible. This result is not an
FPS, frame-time percentile, or JavaFX responsiveness claim. The test performs synchronous
readback and does not implement the production asynchronous readback loop.

## Logical payload versus driver heap

The existing 128-MiB contract bounds named logical payloads, not total driver/JVM/context
memory. In this run bgfx's Vulkan heap-usage statistic was 130,233,600 bytes before the ten
maximum buffers and 172,176,640 bytes afterward: an observed 41,943,040-byte increase, exactly
ten 4-MiB allocations. Temporary spill probes increased that statistic to 175,322,368 bytes.
The resident rendering context reported 183,787,812 bytes with its frame resources. These
are backend heap-usage observations, not a portable allocation-accounting API or RSS limit.

The pinned Vulkan implementation allocates per-frame scratch staging with a default
32-MiB size and a default maximum frame latency of three, plus uniform and transient context
resources. It is therefore incorrect to describe the logical 81,563,944-byte proposed ledger
as total GPU/process memory. Production approval needs combined accounting and hardware
headroom evidence; this checkpoint does not raise any active, source, pool, or logical cap.

Pinned implementation references:

- [Dynamic buffer allocation](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/bgfx_p.h)
- [Vulkan context and heap statistics](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/renderer_vk.cpp)
- [Default staging and frame-latency configuration](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/src/config.h)
- [Reference-release lifetime contract](https://github.com/LWJGL-CI/bgfx/blob/780f7bf00a29573473b90dcfab9f92a7d8687702/include/bgfx/c99/bgfx.h)

## Runtime frame-latency experiment

The pinned public `Resolution.maxFrameLatency` setting is runtime configurable. The Vulkan
implementation uses zero as the compiled default (three here); the scratch buffer size itself
is compile-time. A test-only opt-in leaves the default experiment unchanged:

```sh
JAVA_TOOL_OPTIONS=-Dvimi.worldStreamSpike.frameLatency=1 ./gradlew :rendering-bgfx:nativeGpuWorldStreamSpike
```

The same 104-cycle/nine-active/camera-progress/callback experiment passes at latency one.
Its baseline heap statistic was 54,736,128 bytes, increasing to 96,679,168 bytes for ten maximum
backings (the same exact 40-MiB delta). Resident rendering reported 108,290,340 bytes. The
75,497,472-byte reduction versus default is observed context overhead, consistent with fewer
scratch/uniform resources; it does not change any logical payload cap. Maximum enqueue step
remained approximately 4.2 ms. This test proves neither lower asynchronous display latency nor
higher frame rate. Production configuration requires independent review and real readback-ring
qualification; no production initialization setting is changed here.

## Remaining gates

Independent authored native acceptance must verify UV, common-rebase, coplanar/source-order,
shared-edge and near/far-depth behavior at ordinary and approximately ±999,000 coordinates.
The Windows workflow must supply matching DX11 blobs. Callback shutdown/failure paths need
independent review. No packet parser, credit protocol, mapped-frame v2 implementation, or
production renderer is approved by this checkpoint. Keep `WorldStreamSpikeAcceptance.java`
reserved for independently authored acceptance.
