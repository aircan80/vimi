# Terrain texture application integration

Implementation plan; this document does not claim textured terrain is delivered.
The source/pool and native annex proposals remain authoritative for admission,
ownership and memory limits.

## Application ownership

`NativePreparationContexts` owns one explicitly injected `TerrainAtlasPool` for
the application. Each `NativePreparationWork` passes that same pool to its
`WorldService` instances. Existing constructors retain explicit unsupported
texture behavior rather than silently constructing another pool. Custom
`BuildWorld` implementations get an optional unsupported texture method.

`BuildApplication.closeAll` already closes preparation and comparison owners
before native contexts. Work retirement drains every workspace's World service
before closing its file handle and invoking its context lifecycle callback.
Close the shared pool during native-context cleanup, including failure paths;
do not close it when retiring one build. Preserve error aggregation so an earlier
source failure cannot skip pool cleanup. Delivered batch handles belong to the
viewport, which must release them before backend shutdown can finish.

## Viewport publication

Geometry remains independently presentable. Request one bounded texture batch
for the prepared neighborhood; replace obsolete pending work and cancel active
source work explicitly. Keep the previous image and camera controls responsive
while reading or composing. Track build revision, selection, scene identity and
texture generation so an old completion cannot texture a new scene.

The viewport owns the current batch. Each asynchronous CPU render and native
annex writer first retains its own leases. Borrow RGBA only inside the
synchronous render/write callback, then release retained leases in `finally`.
Never enqueue a borrowed buffer into a future or retain it in a geometry scene.
Replacing an unstarted latest render must close that render's retained leases;
clearing an atomic reference alone is insufficient. Cancellation, hide, revision
change, rejected execution and close must release both pending and owned handles.

The first native integration permits one coalesced texture replacement after
the geometry wave, not one child restart per atlas. Keep activity visible through
source preparation, upload and the first accepted textured frame. READY/PARTIAL
acknowledgements determine presentation text; honest unavailable/unsupported
fallback ends activity with a reason. Height fallback must remain usable.

## CPU fallback

Keep the existing render entry point and its height-only pixels unchanged. A new
overload consumes scoped `TerrainTextureBinding` views matching scene ordinals.
At a depth-accepted pixel, reconstruct native XY using double reciprocal depth
and the inverse camera transform, then use the shared uniform point/clamp
mapping. Keep the existing float depth comparison and first-wins rule. Alpha zero
uses the original color; covered atlas RGB receives only the existing fog based
on the original triangle's maximum eye depth. Do not apply height/slope lighting
again. Near-plane clipping therefore requires no new interpolated attributes.

## Acceptance gates

- Legacy height-only frame equality, including near clipping and overlapping tiles.
- Authored asymmetric atlas orientation, point sampling, partial coverage and fog.
- Delayed source and upload: visible activity until the target frame is accepted.
- Selection/revision changes reject stale batches and acknowledgements.
- Replaced pending render, active cancellation and shutdown release all leases;
  source close for one build preserves another build's cache entries.
- CPU and real Vulkan comparisons use the same qualified geometry and atlas;
  actual game assets and resulting images stay outside Git.
- Existing frame transport and native memory bounds remain in force. No hot
  texture update or simultaneous textured scenery claim is implied.
