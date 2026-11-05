# Visible global instance interior viewer (partitions 2–4)

This checkpoint implements partitions 2–4 of
[world-global-child-process-proposal.md](world-global-child-process-proposal.md) on top of the
reviewed partition 1 codecs (see
[world-global-partition1-checkpoint.md](world-global-partition1-checkpoint.md)). It is the first
user-visible dungeon/raid interior (#170).

## What is visible

Dungeons → choose an instance → **View interior (GPU)** opens a separate window. The window
renders that map's single global WMO with its qualified textures, through the application's sole
GPU owner, and offers free flight:

- W/A/S/D to move, Q/E for down/up, Shift to move faster;
- arrows or mouse drag to look.

The initial camera only frames the native bounds for inspection. It is not a client entrance,
spawn point or floor. Instances outside the qualified profile show a bounded reason instead of a
picture. The profile is:

- one direct global MODF placement with zero placement transform;
- at most 8 groups, 64 batches, 24 materials, 18 BC1/BC3 images and 32768 vertices.

Not drawn yet: doodads, liquids, portals/culling, lighting/vertex colors, NPCs, collision or
navigation. #184 covers floor, group and entrance navigation; its first slice is below.

## Group navigation (#184, first slice)

The interior window has a side panel listing the WMO's recorded groups in file order:

- **Name.** Read from the root MOGN block at the group's recorded MOGP name offset. The name must
  be NUL-terminated printable ASCII of at most 255 bytes within the block. Otherwise the group says
  "name not recorded"; a recorded empty name says "unnamed". Names are never derived from paths or
  ordinals. `WmoFormats` retains MOGN only when it is at most 16 KiB and fits the root fact budget,
  so a root that qualified before names were read still qualifies. The MOGN budget is claimed after
  every other root declaration.
- **Flags.** The raw MOGP flags in hex, with the commonly documented bits named (BSP, vertex
  colors, outdoor, exterior lit, lights, doodads, liquid, indoor). Other bits stay raw.
- **Jump.** Selecting a group (click or Enter) puts the camera at the centre of the group's
  recorded MOGP box, mirrored into the mesh's native space and clipped to the drawn scene bounds.
  Qualification checks every group vertex against the MOGP box (within 0.001), and the scene box is
  the bounding box of all drawn vertices. "Overview" restores the
  initial framing. A jump only replaces the view's latest camera, so the transport protocol is
  unchanged: one coalesced latest CAM.
- **Floors.** Linked from group-scoped UiMap assignments; see the second slice below. Floors are
  never inferred from height or group order.
- **Entrance.** Reported as unknown, because no entrance source is read for the interior yet.
- **Failures.** After a frame-poll failure, failure or close, the list is disabled so a jump
  cannot hide the status message. An unreadable outline says so instead of listing nothing.

The outline is built on the service lifecycle lane from the detached `WorldGlobalScenes.Facts`,
before MOVE, and handed to the view in `GlobalOpened`. An unreadable outline says so in the list
and never blocks the render. Tests:

- `WmoFormatsTest`: MOGN offsets, termination, limits and budget order.
- `WorldGlobalAssetFactoryTest`: a recorded name is read at the name offset, absent names stay
  null, and native bounds are mirrored.
- `:gui:globalInteriorOutlineTest`: labels, flags, clipping, the centre camera, entry targets,
  the unreadable fallback, and unknown entrance and floors.

## Floor / map-layer navigation (#184, second slice)

Below the group list, the panel lists **floors / map layers**: UiMaps linked to recorded WMO groups
by exact keys only.

- **Source.** `BuildLocationMaps.wmoGroupAssignments(mapId)` returns every readable
  UiMapAssignment row of the instance map with `WMOGroupID != 0`, as recorded: the UiMap (name,
  ParentUiMapID, Type), the WMO group ID, WMODoodadPlacementID and the cell sources, plus the
  recorded parents' names. `LocationMapsService` reads it from the same cached tables and MapID
  index as `resolve`. `resolve` keeps its unscoped-only filter (`WMODoodadPlacementID == 0 &&
  WMOGroupID == 0`), so existing location results are unchanged (tested).
- **Match.** `GlobalInteriorFloors.link` (pure, off FX) links a row when its placement ID is 0, its
  UiMap record is readable and exactly one group's MOGP header records the same group ID
  (`GroupHeader.groupId`, kept as `GlobalInteriorOutline.Group.recordedGroupId`). Groups with
  group ID 0 never match. Matching assumes the row belongs to the one drawn WMO, which holds only for these
  single-global-WMO maps; IDs shared by two groups are reported as ambiguous, never picked.
- **Not linked.** Every other row is counted, with up to 8 example group IDs, under one reason:
  no recorded group has the ID; more than one group has it (ambiguous); the row also names a WMO
  placement (not matched yet); the UiMap record is unavailable; or the row's fields could not be
  read; or the row is beyond the 1024-row per-map list limit.
- **Hierarchy.** Each linked UiMap is one entry with its recorded type and parent. A UiMap whose
  ParentUiMapID is another listed floor is nested under it; floors on a parent cycle are listed
  flat. Floors are ordered by UiMap ID within each level, not by height.
- **Select.** A floor entry lists its groups in file order. Selecting it frames the union of
  those groups' usable boxes with the same inspection framing as the overview; it replaces only the
  latest camera, so the transport protocol is unchanged. Floors whose groups cannot frame a camera
  say so and do not move it.
- **Threading.** The lookup runs on a virtual thread after the interior opens (the table read
  itself on the location-maps worker); only the result is published on FX. Closing the window
  cancels it off FX.
- **Entrance.** Still unknown.

Tests: `LocationMapsServiceTest` (group rows per map, placement and missing UiMap kept as recorded,
unreadable rows counted, `resolve` identical before and after the query, default unsupported) and
`:gui:globalInteriorFloorsTest` (exact links, hierarchy, dedup, union framing, every unlinked
reason and cap, nothing inferred, unsupported and unreadable sources, parent cycles).

Not proven yet: no actual-data run checks how many real assignments link, or whether real
dungeon rows use WMODoodadPlacementID.

## Components

- **`GlobalWmoMain` (child).** Dispatched before every legacy mode in `GpuViewerMain`.
  - **Startup.** It parses the reviewed argv and checks that the parent is alive. It then opens the
    fixed 800×500 mapping and checks its session nonce before any input IO.
  - **Input.** `GpuGlobalWmoSceneFile.read` verifies the scene on the same file handle.
    `GLOBAL_INPUT_CONSUMED` is emitted only after that read returns.
  - **Rendering.** It runs hardware-only bgfx and adopts the decoded scene into the reviewed fixed
    frame owner. It never autosubmits; one latest parent CAM is coalesced.
  - **Ready.** `GLOBAL_READY` is emitted only after the first actual mapping write.
  - **Shutdown.** CLOSE, stdin EOF or parent death stops admission. Then `owner.close()` runs,
    followed by real `bgfx_shutdown()`, `afterContextShutdown()` and mapping close, and only then
    `GLOBAL_CLOSED`.
  - **Cancellation and failures.** Standard cancellation after a stop request is healthy. A
    malformed control line or any unknown failure exits non-zero, with no `CLOSED`.
  - **Status and deadlines.** Status records are written off the native thread through a bounded
    four-record writer with a 2 s blocked-pipe deadline. A 9 s halt deadline starts on stop.
- **`WorldGlobalTransport` (parent, `rendering-bgfx`).** It needs package access to the selection
  overload.
  - **Launch.** It creates a request-only `global-frames-*` directory separate from the input
    directory, plus the mapping. The argv comes from `GlobalWmoLaunch.arguments()`.
  - **Controls.** One latest camera, written by a lifecycle lane.
  - **Statuses.** They are validated strictly: consumed, then ready matching the expected identity
    and counts, then closed. Anything else is fatal and kills the child.
  - **Termination.** `terminated()` completes only after actual exit, mapping close and request
    directory deletion. A healthy result also requires a parent-sent CLOSE, exit 0 and
    `GLOBAL_CLOSED`.
  - **Stop deadline.** 10 s cooperative stop, then forced termination. A blocked control pipe cannot
    postpone it.
  - **Frame polling.** `poll(floor, w, h)` uses `readLatestGlobal`, so revision interval and exact
    dimensions are filtered before pixel allocation.
  - **Input ownership.** The transport never closes the moved input.
- **`GpuPreviewService` `RequestKind.GLOBAL`.** Same gate, run lane and pending replacement as the
  existing request kinds. Model, cold-terrain and streaming behavior is unchanged.
  - **Order of work.** On the lifecycle lane it runs Source preparation (bridge `prepare`), then
    MOVE, then Prepared close, then launch.
  - **Cancellation.** The view's stop maps onto the actual Source `Cancellation`.
  - **Input deletion.** `OwnedInput` closes after validated consumed, or after confirmed process
    exit. When exit cannot be confirmed, the service fails closed and keeps the files.
  - **Messages.** User text is bounded: Refusal reason/site, explicit `GlobalUnavailable`, or an
    exception simple name. Raw paths and provider messages are never shown.
- **`WorldGlobalSourceBridge`.** It captures a six-scalar native AABB while the actual writer
  borrow is live (at most 32768 vertices, no arrays or IO), for the inspection camera.
- **`GlobalInteriorView`.** An FX window, sending at most 800×500 control dimensions.
  - **Polling.** Frame polling runs off FX and delivers latest-only.
  - **Presentation.** It requires the current generation and exact current dimensions, at or above
    the resize floor.
  - **Close.** Closing the window retires the owner.
- **Shaders.** The eight digest-verified global candidate blobs (glsl/spirv/metal/dx11) are copied
  into the main runtime resources by `processResources`. Sources stay where PR161 and the reviewed
  checkpoints put them, and the runtime digest check is unchanged.

## Verification

- `:rendering-bgfx:check` passes: argv/control/selection pure tests plus all existing tests.
- `nativeGpuGlobalChildProof` passes on actual Vulkan, with authored data, a real child process
  and the public transport:
  - first CAM, resize 800×500 → 317×163 → 160×80, then 40 coalesced continuous cameras;
  - 16 independent ray/UV/bilinear pixel oracles;
  - cooperative CLOSE drain, with input deleted after consumed and the frames directory removed;
  - close before the first CAM: drain without READY;
  - mismatched mapping nonce: the child refuses before consumed, and the transport fails
    unhealthy.
- `:gui:gpuPreviewGlobalTest` covers:
  - explicit unavailable preparation failing with its bounded message and no launch, with the owner
    reused;
  - stop during preparation canceling the actual Source token, with no launch;
  - a held global preparation blocking its successor on the sole owner;
  - messages that never contain raw causes.
- A private actual proof covers the whole route: configured local-only Source on
  wowf 1.60.1.70755 enUS, then the GUI `GlobalInteriorView`, then the real child (Vulkan).
  - **Data.** The original library was opened READONLY; scratch output is private, outside Git.
  - **Map.** Onyxia's Lair, map 249: 8 groups, 18 images.
  - **Frames.** Frames rendered with textures, and synthetic Shift+W key events flew the camera into
    the lair.
  - **Drain.** Closing the window drained with no request directories left.
  - **Location.** Helper, log and screenshots are in
    `/home/aircan/.cache/vimi-tmp/global-interior-actual-proof/`.

## Limits

- Hardware proof is Linux Vulkan only. The Metal and D3D11 blobs are compiled and
  digest-verified, but are not hardware-qualified here.
- Only Onyxia is actually proven. Other instances depend on the existing Source profile and
  otherwise report a refusal.
- Raw MOCV and lighting are deliberately omitted, so interiors are lit uniformly. The renderer
  has no face culling. Gaps seen from some interior angles are most likely the 3,039 Onyxia raw
  triangles that no MOBA batch covers, which are preserved but deliberately not drawn (see the
  instance source proposal). This has not been confirmed per pixel.
- `GLOBAL_UNAVAILABLE INPUT/CAPABILITY` is not emitted. Every child failure is treated as fatal,
  as partition 2 allows for a first integration.

## Independent review follow-up

An independent reviewer read the child, transport, service, bridge and view code against the
proposal and found no blockers. The should-fix items are resolved:

- **Health after full drain.** Transport health is decided only after status-reader EOF, with a
  2 s bound, so a record arriving after CLOSED (even after the actual exit) is fatal. This is a
  causal test: removing the wait makes the trailing-record test fail.
- **Retirement tracking.**
  - Mapping/frames-directory retirement is tracked separately (`retired()`).
  - The service fails closed when exit cannot be confirmed or when that retirement fails.
  - An in-loop input deletion failure is sticky and fails the service closed.
- **Launch after start.** If launch fails after the child started, the transport destroys the
  child and waits for its exit. When exit cannot be confirmed it throws `UnconfirmedExit` and
  retains the mapping and input, and the service fails closed.
- **Service loop.** Lifecycle futures are hoisted out of the poll loop. The `opened` callback is
  observational and skipped once stopping. Cancellation is recognized only as exact standard
  `CancellationException`, unwrapped from completion/execution wrappers.
- **Build.** `processResources` no longer depends on the Python verifier; verification stays under
  `check`.
- **Child.**
  - Lost-wakeup re-check after publishing the waiter.
  - `Decoded` closed if owner allocation throws.
  - Exact cancellation class.
  - GLOBAL dispatch only at flag positions.
- **View.** Held keys clear on focus loss, and there is one window per map.
- **Tests.** `WorldGlobalTransportTest` adds 6 scripted fake-child cases:
  - canonical CAM/CLOSE bytes and mapping retirement;
  - a trailing record after exit;
  - exit without parent CLOSE;
  - wrong counts and READY before consumed;
  - missing CLOSED and nonzero exit;
  - coalescing and no admission after close.

Deferred: stderr bytes are drained but not retained, and the view writes into the displayed bitmap
rather than staging two. Post-launch service behavior has no deterministic fake-Source test: a
`Prepared` needs an actual Source. The actual Onyxia proof covers that path.
