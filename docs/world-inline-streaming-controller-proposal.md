# Inline persistent World controller integration

Design for the remaining GUI boundary, after the reviewed child, source ownership,
parent transport direction and frame/file helpers. This does not switch WorldView or
claim the incomplete public transport/factory has shipped. Existing model/terrain UI,
controls, minimap and source reasons remain usable while integration is validated.

## One application GPU owner

Extend GpuPreviewService's existing Request/gate/lifecycle lane with a distinct
streaming request variant. Do not use terrain!=null as the model/stream discriminator,
create a second semaphore, or start the transport outside this application arbiter.
The streaming variant carries small launch/control ownership, not a duplicate scene
or texture snapshot. Existing model and cold-terrain paths stay separately tested.

Its off-caller perform path creates the fresh prepared session, then calls the
reviewed irreversible launch(Starter) API using the existing matched Java/classpath
launcher. Cancellation may arrive during preparation/start; the old preparation
handle cannot delete transferred files. A failed initialized/close future is never
permission for a successor. Actual transport DrainReceipt releases this arbiter only
after child exit and all source/file/worker owners drain. Unknown termination retains
the permit and files; late actual drain can release the same ownership without a
new close attempt. CLOSE/status success alone is insufficient.

## Controller and bounded demand

Keep FX-owned camera/viewport/widgets; use one off-FX controller lane for source,
packet/commit orchestration and payload retirement. FX calls replace metadata-only
latest camera/demand. Source progress, status and frame delivery use coalesced latest
mailboxes, never one queued FX task per source event/frame. No source IO, parser,
pixel preparation, file close or cancellation callback executes under metadata locks
or on FX. Completion callbacks post small tokens and discard expired resources off FX.

One validated Map scope/session persists across neighborhood changes. Native camera
revision/policy counters remain monotonic; resize has a new policy and actual ACK.
Map/workspace change creates a new view generation and drains the prior GPU owner
before another context starts. Source scope generation/owner must match at every
handoff and FX pixel acceptance. Captured handles borrow workspace tables/files;
the controller never closes those borrowed services itself.

Initial delivery prepares the closest actual available tile and presents its fully
prepared texture outcome, then streams recorded neighbors incrementally. Do not wait
for all nine neighbors before first useful pixels. Current bounded resident table
stays at most nine actual filename XY tiles; one incoming tenth native slot is used
for replacement. Greater distance/49-source planning is a separate profile, not an
implicit slot/cache cap increase. Missing tiles have a recorded reason, not endless
activity or invented geometry. Camera seeding/bounds/area facts are computed inside
actual checked geometry borrows off FX, without another Terrain/Scene array copy.

## Exactly one parent payload owner per resident

A controller holder owns one WrittenTile: its one base geometry owner, optional one
atlas owner, descriptive source/material/area metadata and initially unadvertised R.
Pending/current/presented ordered tables contain aliases to this SAME holder, never
nine retained geometry/atlas leases per commit. Fixed membership metadata determines
when a holder has no users. Twelve total issued/construction outputs remain the
source-service cap; at most nine active plus incoming/retiring holders are expected.
No unbounded transaction history, discarded-neighborhood cache or GPU-frame history.
Stalled FX presentation may grow the union of presented/current holders: backpressure
BEFORE the twelve-output/construction cap, wait for actual presentation/borrow drain,
and never reinterpret that pressure as a terminal material fallback. Accept genuine
current receipt frames incrementally; do not wait for all requested neighbors before
presentation can release outgoing holders and unblock preparation.

Packet publication needs an explicit one-shot file move. Inside actual source
admission, a local owner writes the private packet and returns R; the engine performs
all final positive/negative observed-binding and Map checks before transfer. Only then
may the closed application bridge atomically move the file capability from R into a
source-neutral TransportOwnedPacket. R becomes an inert no-delete shell. Closing the
source output cannot delete a file still used by the child; transport alone deletes
after INPUT_CONSUMED or actual process/IO drain. Before that move, failure/cancellation
closes the unadvertised source-owned file. The transport packet retains no source,
geometry, atlas or WrittenTile handle. The controller independently owns the payload.

One private pending producer holder coordinates that split handoff: late cancellation
must close an unreturned payload and any unmoved file. MOVE also creates a local
OwnedPacket before the producer returns: if handoff then throws/cancels, local finally
must close that MOVED-but-unreturned packet because source R is already inert. A
successfully returned transport file stays transport-owned until drain. If source
close wins, no file move or positive holder handoff succeeds. If file move wins, source close retires its base owners but cannot
reclaim transport's file. Test both orders with real held writer/read cleanup. No
public DTO/success boolean/checksum or arbitrary generic R mints source qualification.
An unsupported custom provider/strict Map capability keeps the honest legacy route or
an explicit preparation/unavailable state; never silently decode/repair captured tables.

## Commit and actual presentation

Build a commit table from exact STAGED opaque transport residents and retained current
members, preserving deterministic order and unique XY. Keep the previous displayed
image/source holder table while preparation, upload, commit or the next frame is
pending. Do not clear the image, restart the child, or temporarily publish height-only
geometry for a texture job that is still running. Height fallback requires a genuine
terminal material result; pressure, unavailable configured capability, cancellation,
source change and program/provider failures are distinct from supported fallback.

COMMITTED establishes the next content receipt but does not end loading or retire the
last presented holders. The parent requires actual matching CAMERA_APPLIED and content
ACK before detached pixel allocation. A frame carries its exact immutable receipt,
policy/dimensions and camera interval. FX rechecks view generation, terminal state,
source-holder validity and frame/receipt order before bulk image upload. Only accepted
pixels mark presentation ready and retire outgoing last-presented holders. Continuous
drag accepts monotonic lagging completed camera revisions to avoid starving display.
A map change retains an explicitly pending/last-view image until new accepted pixels;
it must not relabel old imagery as already rendered from the new map.

## Fallback, visibility and feedback

CPU fallback consumes the same checked resident payload/material borrows synchronously
through the separately reviewed renderBorrowed helper; only detached pixels escape.
It must not construct another nine-mesh Scene payload set while hot GPU owners exist.
Do not run CPU and GPU source-acquisition pipelines simultaneously. GPU-only failure
can use existing valid payloads; workspace/source invalidation requires fresh source
qualification and cannot reuse a closed holder. Toggle/hide/close use the same source
and process lifetime rules; a canceled future never proves work has stopped.

Activity spans scope qualification, terrain, texture/material work, packet writing,
upload/commit and accepted pixels. Show meaningful pending/terminal/partial reasons
and counts in the existing viewport/status areas. Texture completion alone is not
presentation completion; exposed geometry alone is not texture completion. Preserve
world controls, area/subzone/minimap behavior for terrain mode; global instances need
their separately qualified scene mode and cannot receive fake ADT minimap/area facts.

## Acceptance before enabling this route

Authored controller tests hold writer finally, source-close, packet move, child read,
COMMITTED and FX upload independently. Prove no early file deletion, successor GPU,
source-credit reuse, repeated nine-base retains, transient texture downgrade, stale
pixel success or lost final frame. Cover superseded plans, hide/resume, software toggle,
model/world arbiter replacement and failure before queued FX delivery. Actual proof
then traverses several real Map0 tiles in the same child with textures, camera/resize,
activity through accepted pixels, stable owned-resource bounds and readonly original
library. Measure representative movement/upload/presentation latency and report its
limits; authored constant-color proofs alone are not smooth real-world performance.
