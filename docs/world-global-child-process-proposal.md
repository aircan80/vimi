# Explicit global child protocol and sole application GPU owner

Design-only from0bd6416; depends on reviewed fixed Source→canonical file bridge, renderer241/404/179/2cb and frame-owner4825/5cb review. No CLI/transport/application implementation or Source-positive DTO is introduced. Keep global separate from MODEL, COLD_TERRAIN and STREAMING; no fake terrain tile, model scene or v2 world transaction.

## Exact child launch and control

Dispatch global mode before existing legacy option/render selection. Required distinct pairs:

```
--global-scene <canonical absolute global.wgs>
--global-nonce <32 lowercase hex>
--global-scope <64 lowercase hex>
--global-sha256 <64 lowercase hex>
--global-length <canonical decimal 1..6291456>
--frames <canonical absolute frames.vgpu>
--global-frame-nonce <16 lowercase hex, nonzero>
--parent-pid <canonical positive decimal long>
```

Optional `--backend vulkan|metal|d3d11` only. Reject duplicates, unknown keys, odd argv, mixed --scene/--terrain-scene/--terrain-textures/--wmo-annex/streaming/headless modes and wrong filename/path/field lengths before IO. Paths are normalized absolute default-filesystem paths, maximum4096 UTF-16 units, no NUL; data files are opened NOFOLLOW. Scene nonce/scope/SHA/length must come from owned ExpectedFile. Check exact800×500 mapping header/extent and actual `GpuFrameFile.sessionNonce()` against the distinct16hex mapping nonce BEFORE owner initialization; never infer identity from a reused path. Reuse existing hardware-only backend selection and parent watchdog; no software fallback or second context.

Dedicated ASCII/LF controls, at most256B INCLUDING LF; bound before split/numeric parsing:

```
GLOBAL_CAM <nonce32> <revision> <width> <height> <x> <y> <z> <yaw> <pitch>
GLOBAL_CLOSE <nonce32>
```

CAM has exactly10 fields; CLOSE2. Nonce must match the launched scene. Revision is canonical positive decimal long, strictly increasing; dimensions1..800/1..500. Float tokens are at most24ASCII chars, parse finite and require exact `Double.toString(parsed)` canonical spelling; XYZabs<=8192, pitchabs<=1.5, yaw bounded identically to GlobalWmoCamera. Signed zero camera values are allowed, separately from canonical native geometry+0. Reject stale/nonpositive revisions, revision exhaustion, unknown prefix, extra/empty fields, controls/Unicode, overflow and partial EOF lines. Reject WORLD/VIEW/WS2 commands. Command encoding uses the same bounded grammar. Child holds exactly one latest CAM, no history; CLOSE/EOF/parent death closes admission immediately.

`GlobalWmoFrameRequest(revision,camera,width,height)` remains the fixed-owner internal record. Owner initialization NEVER autosubmits; only an explicit parent CAM can cause a first draw. The child coalesces newer cameras and advances once before a second submit, retaining issued slots through actual readiness. Fixed scene identity never changes during this process.

The parent needs an initial known request. Extend the closed bridge narrowly to capture a bounded six-scalar native XYZ AABB while its ACTUAL G2 writer borrow is active (scan<=32768 vertices, no arrays/extra IO/Source service). Keep those descriptive scalars/initial camera in Prepared only after Source final gates succeed; no caller-supplied bounds qualify Source. Use the same bounded origin0 GlobalWmoCamera inspection-framing policy (share a small public scalar camera value/pure framing utility where necessary, not a TerrainScene). Parent sends revision1 with its actual dimensions. No child-selected unreported camera, invented spawn or unknown-initial-revision pixel acceptance. Bounds clamping may fail to fit an extreme valid box and remains explicitly inspection-only.

## Child lifetime and statuses

Decode via actual `GpuGlobalWmoSceneFile.read` using expected Identity+length; it verifies whole SHA on the same handle before positive decode transfer. Only AFTER that read method returns and the sole file handle closes emit existing `GLOBAL_INPUT_CONSUMED nonce32 fileSHA64` (120B). Cancellation before successful decode emits no consumed acknowledgment; retain input until actual process drain instead. Register the zero-native owner shell, initialize adopting Decoded, and preserve its two-phase shutdown API. Readback destinations survive timeout/unknown failure until enclosing actual bgfx_shutdown succeeds; if shutdown fails, no positive drain and no freeing unknown-live destinations before process termination.

Keep existing strict `WorldGlobalStatus.Ready` exactly as frozen (208B, identity/counts/profile1/omissions511). Emit once only after `advance()` reports the first actual owned mapping write. Initialization/upload/submission is NOT READY. Add a separately reviewed pure status `GLOBAL_CLOSED nonce32 scope64 fileSHA64` (177B inclLF) only after owner retirement, successful actual shutdown/deferred direct release and child mapping close; no unknown failure may produce this positive terminal status. A child which created no context may report all of its actual owners retired without claiming GPU initialization. Parent still requires actual successful process exit and local worker/mapping/file retirement; CLOSED alone never releases application arbitration.

Use a bounded reliable off-native stdout writer (at most4×256B records; consumed/ready/closed or consumed/unavailable/closed are finite), with actual blocked-write deadline and no callback/IO under owner monitors. Reuse/extract reviewed generic writer/deadline primitives; do not wrap global bytes in WS2 or put println on native thread. Unknown stdout records, duplicates or mismatched identities are fatal. Drain stderr separately with bounded lines/retained diagnostic bytes and existing safe user-message policy; do not surface raw paths/URLs/provider Throwable messages.

Existing `Unavailable` INPUT/CAPABILITY codes remain explicit-origin outcomes, never a broad catch. First integration may conservatively treat every file decode/filesystem IOException as fatal: current reader does not yet distinguish its structural checks from unknown provider/OS IO. Reserve INPUT emission until a reviewed private structural-negative marker exists. Similarly introduce typed closed capability negatives only at known init/cap/format/profile/invalid-handle observations; arbitrary IOException/RuntimeException/Error is fatal. No fabricated ADT, blank successful frame or fallback scene. Preserve actual original failures and distinct cleanup failures; only exact clean standard cancellation is healthy cancellation.

## Parent process/mapping boundary

Introduce a source-neutral `WorldGlobalTransport` session, borrowing the actual private-constructed MovedInput metadata/capability for launch; it does NOT close that parent file owner or claim Source authority. Application code alone supplies this from private WorldGlobalSourceBridge.OwnedInput. No neutral caller facts/path are accepted as qualified Source by the GUI bridge. One child, one immutable input, one mapping and one latest camera. Transport's request directory for `frames.vgpu` MUST be separate from the bridge's input-only `global-source-*/global.wgs` directory; otherwise early input deletion cannot retire its directory while mapping exists.

Session methods are bounded metadata-only camera submission, future copies for validated input-consumed/READY/failure/actual close, and bounded frame polling. Standard CompletableFuture consumers must post small metadata/offload IO; no hostile-consumer notifier threads. Control writes and output reads use reviewed finite lanes and blocked-pipe deadlines. Cooperative CLOSE allows the owner's5s readback retirement; total parent stop deadline must explicitly cover that plus output drain (propose10s), then actual termination/wait. Unexpected exit, missing/mismatched CLOSED, unknown cleanup or process-wait timeout stays failed; never turn process-kill intent into a successful native drain. Distinguish a queued request which NEVER started a child from an actual child terminal receipt.

Before detached pixel allocation require matched READY Identity/counts against owned ExpectedFile and a current request/source/view generation. Existing GpuFrameFile.readLatest allocates pixels before an external caller can filter metadata, so add a narrow package-internal fixed-scalar overload selecting revision interval and exact dimensions BEFORE payload allocation (no arbitrary predicate under its monitor). Header session nonce/extent checks and stable slot snapshot remain unchanged. Revision interval starts at the current resize floor/last accepted revision and ends at the latest sent revision; camera-only lag remains eligible so continuous movement does not starve. After copy and again before FX upload/swap, recheck generation, current dimensions, resize floor and source validity. At most one borrowed pending presentation, same staged two-bitmap upload/accept pattern; no background decoder graph per frame.

Validated InputConsumed OR actual confirmed process drain permits the application existing cleanup worker to close OwnedInput (file+input-only directory). Keep the actual cleanup future in the owner aggregate; deletion failure is unknown/fatal and sticky, not optional availability. Closing Prepared after MOVE separately retires G2 Source payload/credit; it cannot delete the moved file. Parent mapping and transport directory retire only after actual child/IO workers and borrowed frame users drain.

## Same GpuPreviewService owner/run/gate

Add explicit RequestKind.GLOBAL to the EXISTING service and its same run/pending/owner gate. Add an explicit global request payload containing the private Prepared holder, exact ExpectedFile, initial camera metadata and callbacks; never reuse MODEL mesh/appearance or COLD_TERRAIN parameters. `openGlobal` performs metadata enqueue only; preparation, MOVE, Source close, process launch, mapping IO and cleanup happen off FX/outside the service monitor. No parallel global service, executor, arbiter or renderer process.

The existing sole-owner worker waits the old model/cold/streaming/global physical owner drain before launch. A queued global canceled/replaced before launch still retires its Prepared file/payload on the existing cleanup lane; no fake Session/CLOSED is completed for no child. The active worker moves only inside Prepared's real Source pin, closes Prepared separately, then owns OwnedInput through consumed/process drain. Keep exactly one pending global request/file claim at the application service, including construction/cleanup; Source's two payload credits are NOT a new arbitrary pending-file allowance. New global requests replace/cancel bounded pending metadata, never silently drop an owned holder.

Use a private GlobalGpuOwner adapter paralleling the reviewed sticky WorldStreamGpuOwner: session/startup outcome separate from original request lifetime and actual close; repeated/canceled future views do not cancel physical work or turn prior failure into a positive empty owner lookup. No listener/future completion under service gate; observational listener RuntimeException/Error cannot escape ownership. Unknown physical retirement fails the service closed instead of launching a successor. Existing model/cold/streaming APIs/semantics stay unchanged.

## Concrete implementation partitions and gates

1. Pure global argv/control/Closed codec, mapping-nonce checks and fixed-scalar pre-allocation selection; deterministic malformed/mixed/overflow/partial-line/exhaustion tests. Root review before runtime parser wiring.
2. Global child dispatcher around reviewed frame owner and lifetime/deadline writer; actual Vulkan firstCAM/resize/continuousCAM/samePID/CLOSE/EOF/parentdeath/blockedpipe/partialinit/timeouts, actual shutdown-before-free proof. No GUI route yet; full eight real shader blobs required before default distribution activation.
3. Source-neutral single-child parent transport plus explicit same-service GLOBAL request/adapter, with held startup/source/MOVE/file-delete/reader/process-drain causal tests and queued cross-kind successor gating. Private Source bridge remains fixed writer, never generic provider promotion.
4. Actual configured Source→fixed file→MOVE→one child→matched mapping→accepted FX pixels, current generation/resize/close gate; then a separately reviewed visible instance route. Compiler success alone is not backend hardware, frame acceptance or client fidelity.
