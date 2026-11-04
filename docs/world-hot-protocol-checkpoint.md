# World hot-stream protocol checkpoint

Material identity terminology is amended by [the explicit draft amendment](world-stream-material-identity-amendment.md); a source composition cache key remains genuinely nullable.

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

## Implementation checkpoint: pure metadata models

`WorldStreamState` is a package-private owner-thread model, not a running session.
It models ten slot generations, one issued credit/incoming pair, ordered commits,
source-reference credits, established and unused cancellation, optional rejection
and close drain. `WorldStreamFrameGate` models the independent presentation
revision/policy barriers without allocating pixels. Seven authored JUnit fixtures
exercise240 commits/reuse, held decoder/reference ownership, replay/ABA, unoffered
writer cancellation, same-tile replacement, moving and stationary presentation,
ACK/frame reordering and close. File codecs, actual mapped-memory fences, bounded
pipe queues/watchdog, worker integration and native renderer remain later work;
passing these models does not establish their implementation or performance.

## Implementation checkpoint: fixed metadata envelopes

`WorldStreamEnvelope` now encodes/decodes only the exact256-byte session and
commit envelopes, with whole-file SHA-256, mode/version/caps, reserved-zero,
nonce, count, duplicate-slot and integer checks. It owns no paths or source
receipts and cannot create a positive material candidate. Nonce byte encoding is
the high64-bit word followed by the low64-bit word, both little-endian; control
nonce hex must represent those same16 bytes. Scope digest remains its raw32 bytes.

Additional state fixtures cover a commit excluding STAGED, a commit dropping an
active member during COMMITTED_DRAIN, reference underflow/stale callbacks and
transaction exhaustion without wrap. The last positive signed transaction value
is reserved for termination: the host closes before emitting it, envelope creation
rejects it, and a direct state invocation drains/closes instead of granting more
credit. Four envelope fixtures cover nine-member explicit order, all reserved
padding, corrupt checksums, unsafe counts, truncation/trailing bytes, incompatible
modes, wrong nonce and immutable member ownership. Tile/source packet codec,
file publication/IO lifecycle, mapped-frame fences and pipe/watchdog implementation
remain separate unimplemented checkpoints.

A presentation policy also pins its minimum camera revision: the first camera
sent under the new epoch. Frames below that floor are rejected even if their
claimed epoch/dimensions match. This independently prevents relabeling old-policy
pixels; newer desired cameras within the same policy do not raise that floor or
starve continuous presentation. The authored regression fails when only this
floor check is removed, then passes with the barrier restored.

## Next source-factory API checkpoint (proposed, not implemented)

Keep dependencies `concepts-world -> rendering-world` and
`rendering-bgfx -> rendering-world`; do not make either depend on the other.
The GUI coordinator/adaptor already depends on both and can bridge owned source
output into a structural packet writer. None of the pure envelope/state classes
above creates source authority.

Current `WorldService.Inventory` and `LoadedTile` are public descriptive DTOs;
they do not retain checked WDT/root content-key evidence. They cannot mint a
streaming session scope or positive/terminal source-prepared tile. Existing
`WorldTerrainTextureSource.Receipt` and `BatchResult` retain private positive
source construction, but unavailable rows intentionally have no receipt. Preserve
those boundaries instead of adding a public receipt constructor or pretending an
unsupported row proves source identity.

Proposed optional `BuildWorld` capability:

```java
// Defaults keep custom/legacy providers on the existing cold path.
CompletionStage<Optional<WorldStreamScope>> streamingScope(MapInfo map, Cancellation token);
CompletionStage<WorldPreparedTile.Outcome> prepareStreamingTile(
    WorldStreamScope scope, WorldFormats.Tile recordedTile,
    Cancellation token, Consumer<WorldStreamProgress> progress);
```

These are source/preparation operations, never FX/native-owner calls.
`WorldStreamScope` is a public final immutable proof value in `concepts-world`
with private constructor, produced only by the actual source service after
`WorldFileBytes`-style bounded read/CK revalidation of the selected WDT and a
checked recorded inventory. It binds BuildRef product/buildConfig/cdnConfig/region,
locale, map ID, WDT ID/CK and canonical MAID references; exposes bounded metadata
and the canonical digest, not file bytes. Missing checked numeric WDT identity,
unsupported inventory or unavailable source means no hot capability, not a fake
scope. The digest encoding is versioned and length-prefixed before it is pinned
in source tests; no concatenated ambiguous strings. Source changes invalidate the
scope/session rather than silently changing its WDT CK.

A caller's MapInfo is a requested map ID, never authority for its WDT or directory.
Unlike the legacy inventory API, streamingScope must rederive that ID from the
service-owned current qualified Map table row/source receipt. Reject unknown or
duplicate IDs, missing/unqualified numeric WDT field and ambiguous rows. Compare
or ignore all other caller fields; never independently open its supplied WDT ID
or path. Bind the exact qualified Map-row identity (table/schema/source revision,
row identity and consumed WDT field) into the scope evidence/digest. A changed
Map-row receipt invalidates the scope even if caller MapInfo or WDT bytes appear
unchanged. The verified numeric binding then authorizes the fresh bounded WDT
CK/read/MAID check above. Retain only the selected qualified Map tuple after a
bounded page scan, never a new whole-Map index. A catalog DTO alone cannot mint
this relationship.

Bound one scope to at most4096 filename coordinates (64×64), each with exactly
its recorded fixed source-reference tuple; reject duplicates/out-of-range MAID
entries before retaining the proof. Retain one fixed32-byte tuple per coordinate
plus a4096-bit presence bitmap (131,584 bytes), never parsed WDT arrays/bytes or
duplicate object lists behind it. Charge at most2KiB scalar scope/Map-row metadata,
so each retained scope has a133,632-byte named logical allowance. The canonical
Map-row receipt is detached/bounded metadata inside that allowance, not a retained
schema/table/page object. At most one current scope and one replacement scope
under construction may be live; charge267,264 bytes total scope metadata in the
parent ledger (separate from geometry/texture payloads), with no map→scope cache.
The next replacement is a latest metadata descriptor only, at most one request
ID/map ID/owner generation/cancellation tuple with≤256-byte allowance; it retains
no scope, row/WDT bytes or source buffers. The267,264-byte scope allowance plus
256-byte latest descriptor are **new** metadata charges, not silently assigned to
the old3MiB receipt metadata reserve. Combined with the accepted43,328,576-byte
texture-component named total this is43,596,096 bytes, still below48MiB
(50,331,648 bytes), leaving6,735,552 bytes. Geometry source/cache/file/frame owners
remain separate; this is not an application/JVM/RSS cap. Source work still has one active job
and one latest descriptor globally, not per scope/map. Issuing a newer replacement
cancels the outgoing replacement and waits for its source worker/mailbox owners
to drain before constructing another proof. Current scope survives only while
its selected visible session remains current; hide/map replacement/service close
invalidates that owner generation and retires the scope according to coordinator
ownership, without adding a background proof cache.

Scope values retain only immutable detached evidence and an opaque owner identity
plus generation value. They hold no BuildFiles, BuildWorld, service, executor or
closure reference. The service's separate bounded current/replacement registry
checks that identity/generation, active lifecycle and fresh source revision on
every prepare operation; service close invalidates it before releasing workers.
An externally retained old scope cannot keep source handles alive or authorize
new work. No global registry of stale scopes is required.

An internal private-constructed `WorldPreparedGeometry` binds that same scope,
recorded tile/root reference, checked root CK/consumed source identity, immutable
native tile and existing geometry fingerprint. Produce it during the existing
bounded source read/cache path; never accept a caller's `LoadedTile` as proof or
add an independent cache. Raw and immutable cache worst-distinct owners remain
separately charged as already approved; the evidence adds metadata, not geometry
copies. Its static/private constructors remain inside source code. Before reading geometry, prepareStreamingTile looks up the supplied filename XY
in that scope's proven MAID table and compares **all** recorded references (root,
obj0/obj1/LOD/texture and every remaining tuple field) against the caller's tile.
Reject absent coordinates or any mismatch; never treat a caller-selected root ID
at an otherwise valid XY as independently authorized. The factory reads only the
scope-derived exact tuple, and binds the resulting root CK/fingerprint to it.
It permits
terminal height fallback to bind real geometry even when no material receipt was
available, without inventing a positive material receipt.

`WorldPreparedTile` is public final and AutoCloseable in `concepts-world`, with
private construction and one close-once resource owner. The service factory takes
its own checked geometry, actual material BatchResult and cancellation state,
consumes/closes that batch on every path, and transfers at most one retained pool
claim. For READY/PARTIAL it compares receipt build/locale/map/WDT/root/tex0, exact
recorded references, geometry fingerprint, cache key and source revision against
scope/geometry; existing `TerrainTextureBinding.qualify` validates the retained
atlas mapping/pixels/coverage. Binding failure is explicit unavailable, never a
positive packet. A receipt from another map/build/geometry cannot be transplanted.
No caller-supplied DTO, wire enum, scope digest or pool entry alone grants this
factory's authority.

`Outcome` distinguishes PREPARED(owned complete tile pair), RETRYABLE_NO_CREDIT
(POOL_BUSY/backpressure), and UNSUPPORTED_CAPABILITY. Cancellation stays exceptional
and releases all owners. A PREPARED terminal height outcome is produced only after
an actual source job concludes unavailable/unsupported/failure with a bounded
reason and checked scope+geometry. Pending/slow work, pool pressure, canceled work
and mismatched caller metadata cannot become height fallback. Custom provider
unsupported defaults do not manufacture this proof; the current cold/legacy view
remains available.

Prepared output owns its immutable geometry reference and optional single lease.
It exposes detached immutable metadata and a synchronous scoped payload callback
for packet writing; no pixel/texture binding escapes that callback. Before that
callback begins, atomically capture the immutable geometry reference and acquire
an independent retained lease/borrow pin. Concurrent close invalidates admission
and retires the object's base owner, but cannot release the callback's pin or
clear its local geometry reference. Callback finally releases both even on error;
no waiting runs on FX/native owner threads. Retained alias references do not copy
geometry/atlas payload and are explicitly retired rather than relying on GC. Parent may
retain a separately charged current-display lease through an explicit retain
operation, subject to CPU/GPU retirement barriers. `close` retires ownership even
if callback/write fails. The GUI packet adapter consumes an offered tile on every
outcome, checks session scope/generation before writing, borrows the atlas during
streaming write, and closes prepared ownership after atomic publication. Writer
credit stays occupied until scoped payload callbacks and the file channel have
both drained; concurrent prepared close cannot grant CANCEL_UNUSED/new credit
early or free callback memory. The
packet file then owns disk bytes until INPUT_CONSUMED/confirmed child death; it
does not retain source arrays/leases or reserve a second incoming payload.

The lower `rendering-bgfx` tile codec accepts/describes wire fields and validates
bounds, digests, geometry and material consistency. Decoded data is explicitly
structural IPC output, not `WorldPreparedTile` or a source receipt. Its writer does
not authorize source results; only the owning adapter may advertise packets from
the checked source factory to the production session. Authored codec tests may
construct descriptive wire bytes, but positive source-factory tests must run the
real factory over authored WDT/root/tex0/BLP BuildFiles, matching the existing
source-proof fixtures; no reflection or fake-positive factory.

Before any source/packet integration, test forged known map ID with another WDT,
unknown/duplicate/missing Map rows/fields, changed qualified Map-row identity,
sameXY with wrong root/tex0/obj/LOD/other references, scope cap/cancel-drain and
service-close/stale-generation invalidation, wrong-scope and changed CK, mismatched
geometry, unavailable WDT, unsupported/partial material, POOL_BUSY versus terminal
failure, held/canceled source work, callback/write failure, rejected offer and
complete lease drain. Source work stays one active plus latest metadata and does
not raise the18-entry shared atlas pool or12-entry geometry source cache.

Runtime status integration must emit INPUT_CONSUMED for the old transaction/lease
from its decoder completion before advertising fresh CREDIT. `inputConsumed()` in
the pure model describes the current incoming transaction, not an event history;
a rejection/cancel may immediately grant a new lease and reset that getter.

The two-heavy-scope bound applies despite externally retained stale capability
values: the service-owned bounded registry exclusively owns MAID tuple arrays and
Map-row evidence. Public WorldStreamScope values contain only a detached small
nonce/owner/generation/digest/header token, not the131,584-byte MAID payload.
Invalidation removes registry ownership after active callbacks drain; retaining
arbitrary old public tokens cannot retain heavy proofs or source handles. Every
operation resolves its token through the current/replacement registry and rejects
unknown/stale generations before source access.

## Implementation checkpoint: isolated source scope authority

`WorldStreamScopes` is package-private and unwired. It resolves requested Map ID
through an actual prepared-local native capture, validates matching source/schema
and native issue identity, scans one ordinal row at a time, and retains only a
selected Map digest. There is no generic page fallback. Unsupported/unknown native
capture cannot create a hot scope. It revalidates Map binding plus fresh numeric
WDT source, packs fixed MAID tuples into its two-proof registry, and exposes only
private-constructed small tokens. All eight recorded references are checked before
source use; changed Map/CK invalidates the registry entry. Source operations use a
failfast one-active CAS; source IO/capture callbacks run outside lifecycle locks.
Close invalidates immediately and returns an asynchronous active-operation drain.

Seven scope tests include a genuine native positive Map7→WDT1 fixture:
Db2Fixture→Db2Reader→GeneratedTableContent.nativeDb2→GeneratedTables→
ClientBuildTables.capturePreparedLocal. No positive source/certificate constructor
is faked. Negative wrappers mutate that actual source only to prove rejection.
Tests cover forged caller WDT, all eight MAID mismatches, unsupported/unknown or
partial capture, unknown/duplicate Map rows, changed Map/CK, retained stale tokens,
protected/mismatched WDT sources, canceled work, held source IO and nonblocking
close/drain. Existing source/material tests remain unchanged except sharing their
authored file fixture; no WorldService/BuildWorld/GUI entry point calls the engine.

**Lower-reader correction adopted:** scope scans now use the reviewed optional
CapturedRows strict profile (compressed256KiB, decoded128KiB, record64KiB,
128 values/issues, cell64KiB), which rejects before block/record/row allocations.
Missing bounded capability and LIMIT_EXCEEDED mean unsupported hot scope, never
missing terrain or terminal height material. There is no generic/legacy fallback.
Eight scope fixtures now exercise this path, including policy refusal followed by
successful recovery through the same genuine native graph. Legacy APIs/defaults
and persisted identities are unchanged.

The corrected helper ledger explicitly adds retained scope metadata and a4MiB
post-open read transient allowance. WDT read/parse is a separate sequential phase:
one≤1MiB input owner,≤384KiB MAID parser arrays,16KiB MAIN,≤1MiB immutable tile/list
metadata and≤256KiB buffer/checksum/control allowance fit a conservative3MiB phase.
The packed scope tuples are already charged in the two-proof retained allowance.
A private readMaid helper closes/clears its raw input owner and returns only packed
metadata before opening the next Map capture. Its parsed world/list never escapes.
Thus Map4MiB and WDT3MiB do not add simultaneously; one-active source admission
charges their maximum4MiB. Counts are logical owners, not a JVM/RSS guarantee.

| Named helper term | Bytes |
| --- | ---: |
| Existing accepted texture-component total | 43,328,576 |
| Two scopes + latest metadata descriptor | 267,520 |
| Maximum additional sequential phase | 4,194,304 |
| Combined named helper total | **47,790,400** |
| Margin below48MiB | **2,541,248** |

Opening a capture creates a NEW prepared reader; it does not merely borrow an
already allocated one. Manifest bodies (existing64MiB ceiling),16MiB metadata JSON,
parsed schema/graph and certificate owners belong to the separate prepared-table
subsystem ledger and are not included in the48MiB helper envelope. Exactly one
capture is held per scope source operation and is closed/drained before the next
phase. File-store and prepared-table owners must remain named separately before
integration; this is emphatically **not** a whole-pipeline/process48MiB claim.

Source geometry/PreparedWorldTile positive ownership and callback-close tests
remain next work. Native/renderer/frame/GUI wiring remains absent.

Scope source reads compare consumed identity (FileDataID, CKey and consistent known
length), not the entire BuildFile display/storage snapshot. Download may enrich
unknown length, path, detected format and NOT_STORED→STORED while the same source
remains valid. An authored enrichment regression passes; restoring whole-object
equality fails it. Changed CK/FileDataID/known consumed length still rejects.
Repeated close calls share the existing active-operation drain and do not dispatch
additional cancellation workers; the held-IO fixture repeats close32 times.

The initial uniqueness proof requires contiguous ordinal consumption: exactly
min(1,remaining) rows and exactly next=first+count until the receipt's rowCount,
then null. A nonadvancing, skipped or empty-jumped middle page cannot certify a
unique requested Map ID. The real native fixture supports multiple rows; crafted
skip/empty-middle pages reject before WDT IO, while complete traversal succeeds.
Restoring merely increasing nextOrdinal checks makes the regression fail.

Map metadata is checked before identity encodings/hashes: at most128 actual/schema
columns,32 layout hashes, bounded names/kinds/interpretation warnings/comments and
source tuple scalars, and a conservative64KiB aggregate input profile. UTF-8
scratch uses a four-bytes-per-UTF16-unit upper bound without encoding oversized
inputs first. Build/locale scalar caps precede scope digest serialization. Hash
workspace stays within the existing4MiB Map phase; oversized prepared metadata
remains an unsupported hot source, not a helper-budget loophole. Eleven negative
metadata cases refuse before ordinal/source IO; removing this prehash guard makes
the regression fail instead of merely rejecting after allocation/hash mismatch.
