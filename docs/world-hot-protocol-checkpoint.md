# World hot-stream protocol checkpoint

Status: proposed contract for independent review, based on approved design
`5547ef1`. This checkpoint adds no production renderer or GUI wiring. Immutable
model/terrain v1 files, control and frames remain unchanged. New codecs and pure
state tests follow approval. The existing authored `GpuTerrainSpike.Harness`
provides camera/depth arithmetic oracles; it is not a streaming-resource API.

## Mode, identities and parent API

Launch a distinct `--world-session-v2` child through the existing single GPU
arbiter. One immutable `WorldSessionSpec` fixes a cryptographically random
128-bit nonce, SHA-256 scope digest (canonical build product/config identity,
locale, map ID, WDT ID and content key), profile 1, geometry encoding revision,
material recipe revision and initial presentation policy epoch. Never resume a
nonce, wrap a counter or reinterpret another mode's file. Positive signed 64-bit
counters close the session before overflow. No native source authentication is
implied by checksummed/descriptive IPC.

Parent-owned proposed interface:

```java
interface WorldSessionHandle {
    void updateCamera(WorldCamera camera); // replaces one metadata-only latest value
    OfferResult offer(PreparedWorldTile owned); // consumes on every outcome
    CommitResult commit(WorldDrawTable desired); // metadata only, expected+next revision
    void cancel(long offerTransaction); // idempotent reference cancellation; handle supplies lease
    CompletionStage<Void> closeAsync(); // completes after child+packet owners drain
}
```

`offer` returns ACCEPTED or NO_CREDIT/CLOSED/SCOPE_MISMATCH and always retires the
candidate it receives, transferring its contents only on ACCEPTED. It never queues
another full tile or atlas behind a consumed credit. The coordinator may retain
at most three separately charged speculative prepared neighbors from the approved
parent budget; passing one to offer moves ownership, not copies it. A rejected
candidate is not silently parked inside the handle. A source metadata descriptor
retains IDs/revisions only. No source reads, geometry packing or file operations
run on FX, control-reader or native owner threads.

`PreparedWorldTile` is an owned package-private final type constructed only by a
source-qualified factory, with close-once state and no public positive DTO factory.
It owns one immutable tile and, for qualified material, one pool lease. Terminal
outcomes are READY_BASIC (256 rectangles), PARTIAL_BASIC (1..255 rectangles), or
HEIGHT_ONLY_SOURCE_UNAVAILABLE (0 rectangles, explicit bounded reason enum).
Pending/loading and cancellation cannot manufacture a terminal fallback. The
source receipt must match exact tile geometry, scope, cache key, recipe and source
bindings; no native/IPC DTO authorizes a positive source claim. Packet writing
borrows the lease synchronously, releases borrow+lease after atomic publication,
and retains only the bounded packet owner until child consumption. Cancellation
of a writer keeps that credit occupied until the writer closes its channel and
retires its candidate; a stale completed writer never advertises its file.

`WorldTileKey` is immutable metadata: scope digest, tile X/Y, geometry SHA-256,
qualified cache-key digest, recipe revision and terminal material outcome. Active
resident metadata retains no geometry arrays, source pixel views or pool borrows.
The parent retains the approved current atlas claims for CPU fallback separately;
these do not belong to IPC packet lifetime.

Resident identity is `(session nonce, slot, slot generation)`, not filename, tile
coordinate or parent-selected slot. The child grants one `SlotLease(slot[0..9],
generation>0, creditSerial>0)`. The first FREE slot is deterministic, but slot order
never defines draw order. A generation increments before each new lease, including
canceled/rejected offers. Child-generated generations prevent ABA. Parent offer
transaction IDs increase once per accepted credit, and all messages bind nonce,
transaction and lease. OFFER/COMMIT use a shared strictly increasing new-command
high-water mark. CANCEL references an established offer transaction and does not
advance or compare against that mark. The parent reserves an offer transaction
before writing and never lets a later OFFER/COMMIT overtake that unpublished
writer; at most one latest commit metadata descriptor may wait. Camera/CLOSE
remain independent. This preserves publish order without buffering another tile. Credit serials increase globally; repeated/stale credit
messages cannot grant another owner. At most one credit is issued/consumed at a
time, even when several of the ten fixed slots are FREE.

The private bootstrap `session.wss` is exactly256 bytes, SHA-256 bound by launch
arguments: magic8=`VIMIWSS2`, version uint32 at8, length uint32 at12, nonce16 at16,
scope32 at32, profile uint32=1 at64, geometry revision uint32 at68, material recipe
uint32 at72, pixel format uint32=1 at76, initial policy uint64 at80, width cap
uint32=800 at88, height cap uint32=500 at92, fixed slots uint32=10 at96, active cap
uint32=9 at100, packet cap uint32=2284544 at104, and148 zero bytes at108. The
bootstrap carries no source payload. Unsupported profiles fail before GPU resource
allocation; caps are fixed assertions, not caller-configurable increases.

## Bounded control and status

All commands/status lines are ASCII, at most 256 bytes including LF, with fixed
arities, lowercase hex digests/nonces and decimal nonnegative integers. Reject
noncanonical forms, extra tokens, CR/control characters, overflow and unknown
versions. No arbitrary path/token text or unbounded error strings. Basenames are
child-predictable `tile-<transaction>.wst` and `commit-<transaction>.wsc`, each
under 64 ASCII bytes; no path separator, dot segments or symlink is permitted.

Commands (each begins `WS2 <nonce32>`):

- `CAM <cameraRev> <policyEpoch> <width> <height> <x> <y> <z> <yaw> <pitch>`
- `OFFER <tx> <creditSerial> <slot> <slotGen> <basename> <length> <sha256>`
- `COMMIT <tx> <basename> <sha256>`
- `CANCEL <offerTx> <slot> <slotGen>`
- `CANCEL_UNUSED <freshTx> <creditSerial> <slot> <slotGen>`
- `CLOSE`

Camera revisions strictly increase. Policy epochs never regress; a newer resize
policy discards queued older-policy camera metadata and establishes a presentation
barrier. CAMERA_APPLIED confirms that epoch/dimensions; an ACK never publishes
old-policy pixels. Camera values obey existing finite coordinate/yaw/pitch bounds; dimensions stay
within 800×500. `CAM` uses bounded canonical numeric formatting; worst-case writer
checks the entire 256-byte limit before publication. The control-reader parses
only bounded lines, sends camera into one coalesced atomic metadata slot, sets a
separate close flag, and queues at most four transaction descriptors. It never
opens a file or waits for decoder/owner work. Queue overflow is a protocol failure
and orderly session termination; camera or CLOSE is never blocked behind it.

Typed statuses are SESSION(profile), CREDIT(serial,slot,generation),
CAMERA_APPLIED(policy,width,height), INPUT_CONSUMED(tx,kind), STAGED(tx,lease,materialOutcome,coverage),
COMMITTED(tx,expected,next), REJECTED(tx,code), CANCELED(tx,lease), and CLOSED.
Every carries nonce and bounded fixed fields. INPUT_CONSUMED means a file handle
is closed and the input may be deleted, not that upload credit returned. STAGED
means validated geometry and its terminal material pair finished upload; it says
nothing about displayed pixels. COMMITTED is state acceptance, not presentation.
Terminal rejection does not itself return credit; a separate CREDIT follows safe
drain. A duplicate/nonincreasing new OFFER/COMMIT transaction receives a bounded
STALE_TRANSACTION rejection, never a second commit or credit. CANCEL instead
matches the current in-flight offerTx and exact lease, even if a later rejected
COMMIT has advanced the new-command high-water mark. Repeating that CANCEL while
cancel-drain runs is a no-op; repeating it after retirement reports bounded STALE
and cannot change generation or grant credit again. Pure fixtures process all
replays without allocating payloads.

CANCEL_UNUSED is specifically for a granted credit whose parent writer retires
before advertising OFFER. The parent first cancels and drains its writer, closes
its channel, releases candidate/borrows and removes any unpublished temp/final
file; only then sends its reserved fresh transaction and exact lease/credit serial.
The child requires CREDIT_ISSUED with no established OFFER, freshTx above the
new-command high-water mark, and the current nonce/lease/credit serial. It binds
and retires that unused grant without opening or claiming consumption of a file;
there is no INPUT_CONSUMED for this case. It emits CANCELED and, after its empty
drain, one next-generation CREDIT. It advances the new-command high-water mark.
A racing already-advertised OFFER makes CANCEL_UNUSED invalid: use established
CANCEL instead. A stale/replayed unused cancellation cannot consume a newer lease.
Parent close may simply drain the writer then close the session without requesting
another credit.

The native owner must not write to a potentially blocked pipe. One status writer
owns a bounded 16-entry mailbox: metadata-only progress is coalesced, credit and
terminal statuses are reliable until orderly close. Stop granting credit before
mailbox capacity is exhausted. A live parent that stops draining stdout is not detected by the existing parent-PID
watchdog. Add a dedicated bounded status-progress deadline: when reliable status
is queued/in flight, five seconds without writer completion flags session failure
from an independent monotonic watchdog. The owner stops admission and initiates
close without joining the blocked writer. If process drain has not completed
within a further five seconds, that watchdog terminates the child; OS process exit
reclaims its blocked pipe/native owners. Parent deletion/arbiter release still
waits for confirmed process termination. Normal parent death/EOF/CLOSE uses the
existing early watchdog plus explicit drain. No unbounded queue or native-owner
pipe write is permitted. CLOSED may be superseded by confirmed process termination
during failure. These new status deadline mechanics require authored tests and
must not be described as an already implemented watchdog capability.

## File layouts

Use explicit little-endian versioned layouts and exact lengths. All reserved
bytes must be zero. Hashes cover the complete published file; the command carries
the expected SHA-256. The writer creates a private fresh temp file with
CREATE_NEW/NOFOLLOW_LINKS, closes it, then ATOMIC_MOVE without replacement inside
the private session directory. Never fall back to non-atomic publication. Reader
opens only the expected basename, uses NOFOLLOW_LINKS and fstat-equivalent channel
size validation, accepts regular private files only, streams exactly that size,
and verifies digest from the same bytes it decodes. Scope/nonce/lease/tx must match
before large allocation. A bad optional input leaves active drawing untouched.
Do not reopen a verified path to decode different bytes.

Tile file `VIMIWST2`: fixed 4,096-byte header plus one native tile payload and
optional exactly 1,048,576-byte 512² RGBA8 atlas. Bound native vertices≤37,120,
indices≤196,608 (multiples of three), chunks1..256, indices in range and finite
absolute native float coordinates≤1,000,000. Body order is float XYZ vertices,
uint32 indices, RGBA atlas; geometry maximum 1,231,872 bytes. Exact cap is
**2,284,544 bytes**, no full-neighborhood packet or general archive.

The first1,024 header bytes use these exact offsets (all integer fields unsigned
unless positive signed counter semantics were specified above):

| Offset | Bytes | Field |
| --- | ---: | --- |
| 0 | 8 | magic |
| 8 | 4 | version=2 |
| 12 | 4 | headerBytes=4096 |
| 16 | 8 | totalBytes |
| 24 | 16 | nonce |
| 40 | 32 | scope digest |
| 72 | 8 | transaction |
| 80 | 8 | credit serial |
| 88 | 4 | slot |
| 92 | 4 | tile X |
| 96 | 4 | tile Y |
| 100 | 4 | chunk count |
| 104 | 8 | slot generation |
| 112 | 4 | vertex count |
| 116 | 4 | index count |
| 120 | 4 | geometry bytes |
| 124 | 4 | atlas bytes |
| 128 | 32 | geometry SHA-256 |
| 160 | 32 | qualified cache-key digest, zero for height fallback |
| 192 | 4 | recipe revision |
| 196 | 4 | geometry encoding revision |
| 200 | 4 | terminal outcome enum |
| 204 | 4 | explicit fallback reason enum |
| 208 | 32 | four coverage uint64 words |
| 240 | 4 | native atlas maxX float32 |
| 244 | 4 | native atlas maxY float32 |
| 248 | 8 | max source UV error float64 |
| 256 | 4 | diffuse tuple count0..64 |
| 260 | 252 | zero |
| 512 | 144 | three48-byte source tuples: WDT/root/tex0 |
| 656 | 368 | zero |

The remaining 3,072 bytes contain
up to64 diffuse-source tuples of48 bytes; unused tuples and reserved fields zero.
A tuple is fileID uint32, CKey16, consumedLength uint32, declaredLength uint32
(0xffffffff if absent), sourceLimit uint32, originalSide uint16, mipLevel uint8,
mipFlags uint8, encoding uint8, alphaDepth uint8, alphaEncoding uint8, reserved9.
Structural WDT/root/tex0 tuples use zero mip fields. The canonical supported
encoding table is pinned to the current source factory's formats, not a string.
The child checks descriptive sources and metadata consistency; it does not repeat
BLP decoding or treat these tuples as authentication. Height fallback has no
atlas/body/mip tuples and zero coverage/anchor/material digest, while still
binding geometry and available WDT/root source identity plus explicit reason.
Textured input validates geometry hash, coverage/pixel alpha and UV envelope using
existing admission rules before packing. It never substitutes guessed textures.

The header's geometry SHA is exactly `TerrainGeometryFingerprint.REVISION`
`native-terrain-geometry-v1`, not SHA-256 of the bare payload. Hash ASCII domain
bytes `VIMI_TERRAIN_GEOMETRY_V1` followed by a single NUL, then five32-bit
little-endian integers in order: tileX, tileY, totalChunks, vertexCount, indexCount.
Append every XYZ float's raw IEEE754 bits as little-endian32-bit words in source
vertex order, then every index as a little-endian32-bit word in source triangle
order. All these values exist in this packet; no per-chunk record is required.
Compute from the same decoded bytes that packing consumes and compare against
both packet declaration and qualified source receipt before parent publication.
The child recomputes the same fingerprint for structural binding; it does not
thereby authenticate the receipt. The independent full-file digest also covers
header/source metadata and atlas, so it remains a separate identity.

Commit file `VIMIWSC2`, exactly256 bytes:

| Offset | Bytes | Field |
| --- | ---: | --- |
| 0 | 8 | magic |
| 8 | 4 | version=2 |
| 12 | 4 | length=256 |
| 16 | 16 | nonce |
| 32 | 8 | transaction |
| 40 | 8 | expected content revision |
| 48 | 8 | next content revision=expected+1 |
| 56 | 8 | presentation policy epoch |
| 64 | 4 | member count0..9 |
| 68 | 4 | zero |
| 72 | 144 | nine16-byte members: slot uint8, zero7, slotGen uint64 |
| 216 | 40 | zero |

Scope/profile are immutable session bindings. Members must be distinct active
leases or the single STAGED lease; duplicated coordinate identities are rejected
unless a same-tile replacement removes the previous resident in this transaction.
No unknown/unready/stale slot is admissible. Empty table is allowed explicitly.
Expected revision must equal committed revision; no skipping revisions/reordering
by slot. COMMIT has its own increasing transaction counter in the same parent
sequence; STAGED association comes from its member lease, not tx equality. Stayed
member order and incoming insertion order are explicitly the parent's draw order,
matching CPU fallback's ordered source list. At most one unconsumed commit file
exists; stale expected-revision rejection closes/deletes it through acknowledgement
and preserves the current table. A commit without camera movement still marks
render dirty.

## Child ownership and upload state

One native owner mutates ten fixed slots; nine ACTIVE maximum, one incoming
inactive pair maximum. One decode/pack worker owns the sole incoming input. No
whole immutable scene or second atlas is retained in the child. The owner tracks
only metadata and fixed handles after upload. Logical resource ceiling and upload
budgets remain those of approved5547ef1; this protocol adds no handle allocation
or transient full-scene copy. Only the authored follow-up spike may establish
native feasibility; this checkpoint makes no driver-memory guarantee.

```
CREDIT_ISSUED -> WRITING(parent) -> READING -> VALIDATED -> UPLOADING -> STAGED
CREDIT_ISSUED/WRITING(parent) + CANCEL_UNUSED(after writer drain) -> FREE
STAGED + valid COMMIT -> ACTIVE (whole ordered table exchanged between frames)
READING/VALIDATED/UPLOADING/STAGED + CANCEL -> CANCEL_DRAIN
bad packet -> REJECT_DRAIN
CANCEL_DRAIN/REJECT_DRAIN -> FREE -> fresh generation/CREDIT
```

The decoder exposes one result mailbox; owner takes it and clears worker aliases
before another input is admitted. Canceled decode closes file, clears raw and
prepared buffers and reports consumed even when validation fails. An in-progress
read cannot grant new credit merely because cancellation was requested. Clear
completed futures/mailboxes/queued callbacks that could retain old payloads;
weak identity is allowed only for diagnostics. An inactive slot is never drawn.

Owner tick priority is close/watchdog, latest camera, render/readback progress,
transaction metadata, then bounded upload slice (≤256KiB and measured2ms between
commands). Geometry slices≤98,304 bytes and atlas slices≤65,536 bytes, without
copying the whole tile per slice. The sole immutable packed3MiB and atlas1MiB
direct owners remain valid while make_ref_release callbacks reference them. A
bounded callback credit counter and cancellation epoch signal completion; the
callback may run on any thread and never mutates slots/calls bgfx. Always advance
bgfx when pending updates/readbacks need progress even if camera is stationary.

CANCEL stops future enqueues, clears worker output after join-by-future (never
blocking owner), and waits until every submitted source reference is consumed.
Only owner then disposes incoming CPU buffers, invalidates slot generation and
returns CREDIT. A successful commit can grant the next inactive-slot credit only
after incoming CPU buffer references drain and the draw-table swap is enqueued.
This does not pretend the release callback is a GPU fence: resource reuse depends
on validated bgfx ordering, covered by repeated-overwrite/cancel/commit probes.
Old readbacks already submitted may finish; their captured revisions identify
their provenance. Rejected commits preserve STAGED ownership, allowing recompute
or explicit CANCEL; they never leak credit or silently evict active data.

Closing freezes admission, cancels writer/decoder/remaining upload, drains source
references/readbacks, then destroys resources and joins status/file owners. Child
failure/timeout requires confirmed process termination before parent deletes
possibly open inputs or returns arbiter ownership. Retain last UI image and offer
CPU fallback; no restart retry loop. Source errors never require killing an
otherwise healthy session.

## Frame version and presentation barriers

Introduce a separate `WorldFrameFileV2`/mode; do not reinterpret v1 reserved bytes.
Retain3 readbacks, 800×500 and owned detached pixels. Use a64-byte immutable
file header: magic8=`VIMIWSF2`, version uint32=2, headerBytes uint32=64, nonce16,
scopeDigest32. Version2 fixes three slots and maximum800×500; no caller-expanded
mapping. Each slot is64 bytes of descriptor followed by1,600,000 bytes of opaque
BGRA8 pixels; total mapping stays **4,800,256 bytes**, exactly the existing bridge
charge. Descriptor offsets: stamp uint64 at0, nonce16 at8, cameraRev uint64 at24,
contentRev uint64 at32, policyEpoch uint64 at40, width uint32 at48, height uint32
at52, CRC32C uint32 at56, payloadBytes uint32 at60. Frame sequence is stamp>>>1;
zero is empty, odd is writing, even is complete. Sequence is at most
`Long.MAX_VALUE >>> 1`; close before it could overflow. Retain the existing
GpuFrameFile seqlock's two-sided fences explicitly: writer release-publishes odd,
executes `VarHandle.fullFence()` **before** any mutable descriptor/pixel write,
writes metadata+pixels+CRC, then release-publishes even. Reader acquire-loads the
first even stamp, copies descriptor+pixels and checks CRC, executes
`VarHandle.fullFence()` **after** that copy/check and **before** its second
acquire-load of stamp, and accepts only the same even value. Release/acquire
alone does not replace either fence. CRC covers descriptor bytes8..55 and60..63 plus exact payload;
never trust dimensions/length before bounds checks. Torn-write fixtures must pin
ordering before wiring. Readback submission captures these values with the
immutable active table; later commit never relabels old pixels. No detached frame
pool is added by this checkpoint; its owners would require separate accounting.

Parent rejects wrong session/policy/dimensions before pixel allocation. Within the
current barrier it accepts increasing frame sequence and nondecreasing camera
AND content revision, bounded by sent camera/acknowledged content revisions. A
frame arriving before its COMMITTED status may be held only as one detached latest
frame until that ACK arrives (or dropped and rerender requested); no unbounded ACK
history is needed. Prefer drop+rerequest for first implementation. It must not
require equality to latest desired camera/pending content: moving keeps current
terrain responsive rather than starving every frame. Once a newer content frame
presents, an older-content readback is rejected. Once a newer camera presents,
older-camera frames are rejected independently. Future policy epochs are rejected,
not adopted from frame data. A resize/policy barrier retires old-policy frames and
requires matching CAMERA_APPLIED dimensions before presentation resumes.

Accepted FX pixel upload, not COMMITTED/STAGED, publishes displayed tile counts
and ends that content's loading activity. Source-prefetch metadata never becomes
current zone/subzone/minimap provenance. A later content commit does not clear a
newer pending activity. The last image remains readable through optional rejection
and close/fallback.

## Tests after checkpoint approval

Pure codec fixtures cover exact boundary sizes, all outcome classes, source tuple
count64, float/index/UV/coverage validation, reserved bytes, checksum/truncation,
extra bytes, cross-scope/nonce/profile, unsafe basename/symlink, failed atomic
publication, cancellation at every IO boundary and candidate close-once.

A deterministic host/child model drives >100 lease reuse cycles, stale CREDIT,
replayed OFFER/COMMIT/CANCEL, slot ABA, commit order/coplanar priority, expected
revision mismatch, established CANCEL after a later rejected COMMIT, repeated
CANCEL during/after drain, canceled WRITING with no OFFER ever advertised,
CANCEL_UNUSED replay against a reused lease, stale writer completion, canceled
decode completion, held release callbacks, blocked status writer with a live
parent and independently advanced monotonic timeout, mailbox
capacity, EOF and close. Assert exactly one incoming buffer owner, no ACTIVE
partial pair,≤9 active, ten slots, input consumption versus credit distinction,
continued camera revisions while decode/uploads are held, no owner waits, and
complete terminal cleanup. Frame fixtures cover ACK/frame reordering, old readback
completion after commit, continuous moving-camera acceptance, stationary commit,
policy/resize barriers, closed delivery and no v1 compatibility changes. Authored
native feasibility and actual continuous-PID boundary traces remain later gates.
