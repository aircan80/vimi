# Finalized packet file ownership MOVE

Private source-neutral helper only. `WorldStreamPacketFile.finalized` accepts the
actual writer's Expected, exact normalized absolute default-filesystem ticket path,
and a fresh literal Object identity token (no Session/source/closure payload).
It checks no-follow regular-file/length metadata. The trusted caller MUST already
own the newly published file through its own CREATE_NEW writer; metadata, pathname
or a matching digest never grants ownership of a preexisting file. On finalizer
failure the caller still owns its local cleanup. No whole-file reread or payload
copy occurs here.

MOVE is an atomic metadata transition into a separately private-constructed Moved.
The source's old shell drops file/destination metadata and closes inertly. Moved
retains only bounded Expected/path/token metadata, never source/geometry/atlas
handles. It exposes those fields package-privately for the future transport's
OwnedPacket wrapper. The closed application bridge must MOVE inside its independently
pinned source resource callback AFTER final source success and BEFORE producer
return. Parent transport cannot move an unpinned source R later. A MOVED-but-unreturned
packet is independently cleaned in that bridge's local finally.

Only one close path can win against MOVE. A close decision under its private monitor
removes the owned file before releasing the monitor; filesystem work and executor
calls occur outside it. `closeAsync(existingFileLane)` schedules deletion on the
existing bounded file lane and returns a copied completion. No per-file thread,
watcher, new IO lane or executor is created. Synchronous AutoCloseable.close performs
and waits actual cleanup only on explicitly off-FX source/controller/file lanes.
Repeated close waits the same actual completion; cancelling an exported copy cannot
cancel cleanup or fabricate retirement. A rejected lane or delete failure is sticky
failure and retains the file, never a positive retirement receipt.

The helper does not know INPUT_CONSUMED or process state. Transport must only request
moved-file deletion after exact input consumption or confirmed actual child exit and
worker drain. It marks its file-retired metadata only after successful actual delete
completion. CLOSED/EOF/failed close are not deletion authority. This helper is not a
source qualifier, packet writer, GPU owner, public transport or GUI switch.

Six authored real-file tests pass: actual codec output and full read after source
shell closes; duplicate MOVE/idempotent close; blocked existing file lane with copied
future cancellation; unmoved close winning before MOVE; null/rejected executor and
metadata/identity mismatches without deleting unrelated files;32 concurrent close/MOVE
trials proving exactly one deleting owner. Full rendering-bgfx checks pass40tasks in
`/tmp/vimi-world-packet-move-root-check.log`. Existing tile/source/native resource caps
and wire bytes stay unchanged. This adds only one tiny metadata owner per already
bounded pending/moved input, charged to the existing parent transport metadata reserve.
