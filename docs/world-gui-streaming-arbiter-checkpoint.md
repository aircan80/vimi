# GUI persistent World lifecycle in the application GPU arbiter

Adds a private STREAMING request kind to the existing GpuPreviewService queue/gate/run
owner. It uses the concrete public WorldStreamTransport preparation/launch/drain API;
there is no second process arbiter or semaphore. The dedicated perform branch never
uses the model/cold-terrain null-scene discriminator. Models and cold terrain retain
existing paths. All native starters share the same matched Java/classpath and macOS
first-thread prefix.

openStreaming carries only bounded Spec and an application controller callback, never
a duplicate terrain scene/texture snapshot. Preparation, waiting and cleanup run on
the existing off-FX lifecycle lane. The opened callback is delivered only after actual
protocol initialization and must post small metadata; it cannot perform source IO or
wait on the session. This is not a first-frame/readiness notification. No World READY
is published by this checkpoint: actual tile/commit/frame/FX acceptance remains a
later controller step.

Replacement and owner cancellation mark metadata only. A held Starter keeps its
mapping and the single application permit; no successor begins until the public
actual DrainReceipt succeeds. Bounded protocol failure is distinct from actual drain.
The last operation can fail while cleanup still owns the renderer. Unconfirmed failed
actual drain closes this application GPU service conservatively and rejects queued
successors; it never reinterprets failure as a free native context. Source output and
CPU fallback ownership are not added by this lifecycle checkpoint.

Listener/future completion in closed enqueue and fail-closed rejection is outside the
service gate; the legacy unkillable-process path uses that same rejection helper.
Preparing a queued view explains that the prior GPU renderer is stopping. Existing
status/owner isolation and callback contracts remain in force.

Validation: full GUI checks pass126tasks in
`/tmp/vimi-world-gui-stream-lifecycle-check.log`. The new authored lifecycle fixture
uses real prepared stream files and a held no-child Starter: model successor remains
queued, an unrelated owner cannot close streaming work, source mapping survives stop,
and exactly one model successor starts only after old directory/ownership drain.
It also ensures failed launch/protocol initialization never claims a displayed World
frame. Existing model/cold-terrain replacement/cancel/fallback tests pass unchanged.

The underlying public lifecycle independently passes40bgfx tasks plus an actual
Vulkan child/100camera submissions/samePID/CLOSED/exit0/files-gone proof in
`/tmp/vimi-world-public-lifecycle-review.log`; content frames are NOT_TESTED.
No WorldView route is switched, no movement/FPS or visible streaming/buildings claim
is made. The next steps remain concrete tile producer/commit/frame transport, fixed
source packet bridge and actual inline controller presentation/cadence validation.

Independent review reproduced a status-listener Error escaping the STOPPING delivery
in streaming cleanup and allowing a successor before a held Starter drained. The
isolated followup makes observational status delivery contain RuntimeException AND
Error; controller opened callbacks remain fatal inside the protected lifecycle path.
A causal held-Starter/error-listener fixture verifies no model successor starts during
that hold. Full GUI checks still pass126tasks in
`/tmp/vimi-world-gui-stream-listener-followup-check.log`. Idle service-close and run-finally
future completion also move outside the service monitor. Standard CompletableFuture
consumers must still post small metadata/offload blocking work; synchronous consumer
continuations are not a separate bounded notification executor. Legacy reader join
behavior is unchanged and is not newly claimed as full local-worker drain here.
