# Standalone global child and fixed frame owner

Design checkpoint from `6b6a749`. This extends the approved private global renderer and structural file contract. It authorizes no implementation, source-positive constructor, application route, or hardware profile. The immutable global scene is a separate mode: never an ADT, terrain annex, model scene, or persistent terrain session.

## Concrete internal boundary

Introduce package-private `GlobalWmoFrameOwner implements AutoCloseable`, constructed on the native owner thread with the actual private `GpuGlobalWmoSceneFile.Decoded`, the already opened 800×500 `GpuFrameFile`, and a cancellation predicate. Construction adopts Decoded on every path. It creates no context, process, source service or worker. Its enclosing child owns context initialization and shutdown.

The owner exposes only `boolean submit(GlobalWmoFrameRequest)`, `boolean advance()`, `boolean pending()`, and metadata-only allocation/ticket statistics. `GlobalWmoFrameRequest` is a separate immutable internal record of positive monotonic camera revision, `GlobalWmoCamera`, width1..800 and height1..500. The shared immutable file identity belongs to the session, not a caller-selected scene per request. Requests use the corrected native north/west/up camera at origin0; positions are bounded±8192, pitch±1.5, finite yaw as already checked by GlobalWmoCamera. No extra D transform, entrance or floor inference.

The child keeps one latest request, coalescing camera changes, and no request history or unbounded queue. A frame slot captures the exact request at draw issuance; later camera, dimension or latest-pointer changes cannot relabel that readback. Reject mixed model/terrain/global controls and stale/nonpositive revisions. Camera syntax and launch options must receive their own bounded codec tests before wiring; this checkpoint does not silently broaden existing model/terrain parsers.

The child has explicit global dispatch before existing legacy render selection, with exactly one scene kind and its exact expected nonce/scope/fileSHA supplied by the private parent launch. Decode the actual expected file before native upload; emit GLOBAL_INPUT_CONSUMED only after its sole read handle closes. Source receipt authority remains in the closed writer and final source/MOVE handoff, not in this structural child.

## Fixed allocation and publication

Allocate all targets once at800×500. Resizing changes viewport/projection and ticket dimensions only; it never recreates targets. Set view0 SEQUENTIAL, opaque clear, depth0, D32F strictGREATER and the approved global pass. A submit performs one draw and one blit/read into one free slot. Never issue a second draw into the same bgfx frame; advance before another submit. Slots remain occupied until actual GPU readiness even when their camera is obsolete.

The exact frame-owner payload is:

| Owner | Maximum bytes |
| --- | ---: |
| Fixed color+depth targets, 2×800×500×4 | 3,200,000 |
| Three readback textures | 4,800,000 |
| Three independent direct readback destinations | 4,800,000 |
| One reusable vertical-flip staging destination | 1,600,000 |
| Existing exact frame mapping | 4,800,256 |
| **Frame owner total** | **19,200,256** |

There is no second mapping, per-frame pixel array, resize overlap, fourth readback, or image mirror. BGFX framebuffer handles do not own duplicate pixel payloads. The approved complete standalone logical envelope remains **41,154,816B**, including this frame owner, adopted CPU mesh/RGBA, GPU mesh/RGBA, conservative queued copies, lexical direct staging, codec/scratch and resource reserves. Source16MiB and parent pixel owners remain separate named components, not implicitly included here. JVM/driver baseline is not a logical-payload claim.

Blit only the requested rectangle. Fixed full-size GPU readback texture destinations use physical row stride800×4 even for smaller rectangles. Publication must pack exactly requested width×height rows into the single reusable flip buffer, selecting the correct physical rows for `originBottomLeft`; never treat fixed800-stride storage as tightly packed width-stride pixels. Write exactly that rectangle into GpuFrameFile, with the slot's actual camera revision and dimensions. This explicit stride rule requires an asymmetric resize/orientation oracle on the qualified backend.

`advance()` advances BGFX once, considers only actually ready slots, selects the newest ready revision beyond the last published revision, writes the owned mapping synchronously, then releases every actually ready slot. Readiness uses bounded modular arithmetic `currentFrame - readyFrame >= 0` on signed32 subtraction, under the documented <2^31 outstanding-frame latency; unsigned ordering is incorrect at wrap. Check wrap fixtures at fffffffe/ffffffff/0/1. Sequence/revision exhaustion fails closed instead of wrapping positive protocol counters.

GLOBAL_READY is emitted once only after the first successful draw/readback/mapping write, using exact immutable decoded counts/identity. Upload, submit, initialization or native capability alone is not READY. This remains distinct from parent mapping read and JavaFX image acceptance. Parent must require exact status identity/counts, matching requested revision/dimensions and its current source/view generation before allocating/accepting pixels. Mapping has one immutable scene per process, so content revision is not repurposed as a terrain world transaction.

## Shutdown and construction failure

All native operations stay on the creating thread. Close stops admission immediately. During normal shutdown, poll/advance outstanding readbacks for a bounded5s using monotonic time and wrap-safe readiness, without publishing new positive frames. After successful retirement, destroy scene/targets on the owner thread and return from actual context shutdown before the enclosing process advertises complete native retirement.

If readiness times out, a native operation throws, or constructor fails after issuing native work, retain every potentially referenced direct destination. Attempt all scene/target destructions, then call actual `bgfx_shutdown` in the enclosing owner's finally. Only after shutdown successfully returns may unresolved destinations be freed. If shutdown itself fails, there is no positive drain receipt; retain unknown-live destinations until process termination. Ordinary resource destruction and cancellation are not context-drain proof. A failed setup cannot lose an adopted Decoded, an allocated direct block or partially created handle: register each immediately, clear handles before destruction, and attempt all cleanup while preserving the original failure and distinct cleanup failures.

Use an explicit two-phase internal API if needed: `close()` requests owner-thread retirement and destroys handles, while `afterContextShutdown()` releases deferred direct destinations only after the enclosing child has actual shutdown success. The latter is never callable merely because a timeout elapsed. The final implementation must pin the exact API and exercise both normal and exceptional paths; this document does not bless plain AutoCloseable as proof of successful shutdown.

The existing preview child lifetime machinery still monitors parent death, CLOSE and EOF. No arbitrary source callback runs on native thread. Bounded status output and blocked-output termination must use the reviewed child lifetime/deadline approach, not block the owner indefinitely on println. Unknown failure remains fatal; it cannot become optional scene absence or a successful CLOSED/drain.

## Application arbitration and acceptance gates

The parent must launch through the same GpuPreviewService owner/run/gate as model, cold terrain and persistent terrain. Add an explicit GLOBAL request kind. Before starting global, old native process actual exit, workers, borrowed source callbacks, readback ownership and registered files must genuinely drain; cancellation of a view future is insufficient. Conversely, no model/terrain successor starts while global drain is unknown or exceptional. The rendering module receives a provided starter/permit contract and never creates a competing application arbiter. Parent source-owned file capability moves exactly once after final source checks; source close cannot delete an advertised child input.

Required implementation acceptance before enablement:

- Pure request limits and exact kind rejection; monotonic revision/sequence, modular ready-frame wrap, no ready-early release; fixed accounting and no resize allocation.
- Authored Vulkan same PID across repeated camera/resize updates, three outstanding tickets with different cameras/dimensions, physically fixed-stride asymmetric rectangles, correct orientation and opaque pixels. First mapped frame precedes GLOBAL_READY; stale ready tickets cannot masquerade as latest camera.
- Cancellation/construction failure before/after each allocation/upload/readback; actual handle baseline; held readback forces bounded timeout but never early destination free; shutdown failure never yields positive retirement.
- Real child CLOSE, EOF, parent death and blocked status output terminate under the reviewed deadlines, with actual process/worker/file drain. Application arbiter tests show no simultaneous context/successor on unknown exit.
- Complete independently verified shader distribution and actual backend proof before advertising each profile. Current six test-only blobs remain candidate resources; old forty production blobs stay byte-identical. DX11 compilation alone is not Windows hardware rendering qualification.
- Closed actual Source→G2 writer→final ALL/Map/WDT checks→pinned MOVE→child decode/upload→mapped frame→parent acceptance, with source changes and stale UI generations rejected. Neutral structural fixtures alone cannot establish source authority.

This milestone deliberately excludes global navigation/floors/collision, client lighting/MOCV, portals, liquids, doodads, terrain aggregation, hot global mesh replacement and universal performance claims.
