# Smooth bounded 3×3 terrain loading

Status: source/lifecycle investigation and proposed first fix, not production implementation. Baseline main15706d0/alpha16. Farther 7×7 LOD remains separate and must not be simulated by increasing full-detail tile or GPU caps.

## Finding and authored evidence

`WorldView.loadNext` eagerly calls `trimLoaded` and `rebuildScene` before fetching missing neighbors. Each successful `loadTile` trims again and prepares/publishes a new scene before invoking `loadNext`. For a complete 3×3 neighborhood moving one filename tile along one axis, this can publish **9→6→7→8→9** tile scenes. `TerrainGpuPreview.request` treats every new scene identity as a new `openTerrain`; `GpuPreviewService.enqueue` retires the active request. The service correctly drains the old child before starting its successor, but the first-frame/upload/startup cycle is repeated four times for one boundary.

Camera-only requests on the same scene use WORLD controls and do not restart the child. Source reads, scene preparation, serialization, native startup and bridge polling run off FX. The evidence does **not** establish an FX-thread stall, an asset-network latency value or native frame-rate loss. Blue uncovered terrain while a genuinely missing source loads is also distinct from repeated child replacement.

A private authored probe compiled the current main `GpuPreviewService`, `TerrainGpuPreview` and `ModelPreviewView` sources into an isolated directory. Fake child processes and detached tiny terrain fixtures exercise actual component/service lifecycle, with accepted mapped frames and twenty camera updates. The eager scene sequence launched five children total; initial+one coalesced replacement launched two. Both had zero camera-only restarts, zero concurrent live children and zero failures. Fake-process wall time is not a native startup benchmark. Probe source/logs are private under `/tmp/vimi-world-smooth-proof`; no game assets or production edits are included.

## Proposed first fix

Keep the active immutable terrain scene and its GPU child readable while loading a new anchored neighborhood. Remove the eager trim-only publication and per-arrival scene publication. A source wave computes the at-most-nine wanted filename keys, requests missing tiles nearest-first on the existing bounded worker, and publishes one scene when the wave's missing requests have reached terminal outcomes. Failure produces an honest partial wave, not an endless spinner or invented empty terrain.

The mutable preparation map stays at most nine tiles: on each successful arrival, replace one least-needed outgoing entry with the incoming entry. The single incoming result is transient, matching existing load completion ownership; do not add a three-tile raw staging cache. The active immutable scene already owns its readable geometry independently, so removing an outgoing map entry must not retire the active child. Keep useful previous geometry when a requested replacement is unavailable, reporting the actual retained keys; a prepared scene need not pretend every retained source is in the exact newly wanted square.

Separate source-wave loading from scene-preparation activity. Source progress must continue without waiting for a new scene after every tile. Every handoff checks selection, source handle, revision and wave identity. Wave completion after retirement cannot replace a newer scene or clear newer loading state. A blocked or canceled source must remain bounded under the existing provider/cancellation contract.

A camera move during a wave changes one latest wanted anchor. Do not build a scene for each intermediate anchor. Either finish the still-useful bounded request or cancel it safely, then recompute from the latest target. Keep at most one active wave/preparation and one latest pending target. No unbounded list of camera positions, futures or prepared scenes.

Publish a replacement only after the active native scene has accepted its first frame or reached terminal failure, so rapid source arrivals cannot repeatedly starve the first frame. Keep WORLD updates flowing to the active child until replacement is actually committed. Preserve the last displayed image across the unavoidable stop/start handoff. The shared service still admits only one live native child; no old/new GPU context overlap or second full-size readback bridge.

## Bounds and later prefetch

Keep current nine-tile geometry/scene-file limits, existing source-cache allowance, child128MiB and frame dimensions. Parent ownership may contain one active immutable scene plus one latest pending immutable scene as already supported; release superseded pending references before allocating a successor. The mutable map, incoming terrain, scene clones, serialization and texture atlas leases need explicit accounting in the implementation review. Texture-pool eighteen-payload bound is global, not multiplied by old/current/pending owners.

First ship/test coalescing without introducing velocity prefetch. Then separately review anchor hysteresis and early strip loading against measured boundary behavior. Actual tile coordinates and UI position labels always follow native camera X/Y, independently of a retained load anchor. Prefetch can use the existing source cache only within its unchanged bounds; it cannot silently retain twelve full meshes in a nine-tile scene or launch unbounded parallel acquisitions. A short debounce alone is insufficient: slow sequential arrivals can exceed it and still trigger one restart each.

Fast travel beyond all retained geometry can still expose unavailable coverage; explain loading and retain the last readable image where appropriate. Coalescing does not promise instantaneous network content or a larger horizon.

## Required proof before release

- Authored WorldView source wave: full9→one-axis boundary with three controlled arrivals publishes one replacement, including delayed/error cases; no eager six-tile scene. Both native axes and diagonal/reversal paths remain correct.
- During held source reads, camera controls reach the same active child and accepted frames continue; no assertion that an unchanged screenshot alone proves smooth rendering.
- Rapid anchor changes and late generations cannot publish stale scenes, revive retired sources or strand activity. Close drains all owned work; borrowed providers remain open.
- Active-first-frame gating plus one latest pending snapshot prevents starvation, concurrent native children and pending allocation growth. Test failure/timeout/fallback and retry.
- Real private-source/native proof records actual child PIDs, starts/stops, request/accepted-frame timestamps, maximum replacement gap and tile/source timing separately. Measure FX responsiveness independently before making a claim about UI stalls or FPS.
- Keep existing geometry/hole/depth/native-axis acceptance, source/cache/atlas resource limits and software fallback. Larger distance remains the separately reviewed source-grid/error/LOD work.
