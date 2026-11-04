# G2 private global asset: API and allocation checkpoint

Design only, based on integrated G1 `2d12c79`, approved `world-global-asset-factory-next-slice.md`, and its synchronous decoder correction. This requests a private source-owned mesh/image slice. No standalone codec, shader, native/global camera owner, public facade or GUI enablement follows from it.

## Measured numeric profile

A new read-only, local-only diagnostic used the actual Map249/WDT declaration operation, parsed the actual root GFID list and read all eight guarded group position/UV streams. It retained only scalar extrema. Original/prior/image stores were read-only and generated preparation used fresh scratch. Private proof: `/home/aircan/.cache/vimi-tmp/global-asset-actual/bounds.log` and `GlobalAssetBoundsDiagnostic.java`.

| Actual operand | Minimum | Maximum |
| --- | ---: | ---: |
| source X | -63.21816635131836 | 218.80517578125 |
| source Y | -26.084177017211914 | 286.4366149902344 |
| source Z | -96.4473876953125 | 49.5633430480957 |
| U | -5.027403831481934 | 9.663012504577637 |
| V | -6.996004104614258 | 5.115926742553711 |

Proposed explicit initial guards: every source position finite and absolute value <=4096; UV finite and absolute value <=16. These are named narrow capability limits, not guessed source-format validity rules. Reject outside values before copying. Keep G1's actual root/group bounding-box checks and all index/material/span checks. Do not clamp, normalize, wrap in CPU, choose alternate UVs or adjust the recorded model. Repeat sampling remains a later renderer policy; UVs outside0..1 are intentional and must survive unchanged.

Only the reviewed zero global placement is admitted: actual WDT flags8, position/rotation zero, doodad/name sets0, raw scale1024, scale application disabled. Native position is exactly `(-x,-y,z)`, world origin `(0d,0d,0d)`. Widening binary32 to binary64, negating and subtracting zero then narrowing is exact for admitted finite operands; no general rotation, ADT C-offset or unnecessary approximate rebase is introduced. Preserve triangle winding because determinant+1. Preserve each UV float's literal bits. For position zeros, explicitly canonicalize all three output zero coordinates to positive zero; other values must match exact sign-change float bits. The numeric error bound is zero, not an arbitrary tolerance. Asymmetric fixtures establish the axis signs and winding independently.

## Closed ownership API

Proposed package-internal API, with no public positive DTO:

```java
WorldGlobalAssetFactory(GlobalScopes borrowedScopes);
OwnedAsset createOwned(GlobalScope owned, Selection comparison, Cancellation external);
// OwnedAsset has a PRIVATE constructor and no array or NIO-buffer getters.
Manifest facts(); // detached descriptive G1 facts only
<T> T withBorrow(AssetRead<T> trustedRead); // same-thread guarded spans, no source IO
CompletableFuture<Void> closeAsync(); // copied future of actual payload/pin retirement
```

`OwnedAsset` is the one returned R of the existing source transaction. It is not observable until actual G1 ancestry/material qualification, derived final stats, fresh Map/strict-EOF+WDT checks, cancellation callback drain and the final source owner/health/scope gate all succeed. On any failure the source operation closes the unreturned asset inside its active guard. There is no method taking an old `Manifest`, root/group/image ID, matrix, array, public object or previous receipt to create an asset. G2 reconsumes the complete actual chain inside its own operation.

The fixed implementation shares G1's private qualification walk through an internal sink selected only by the fixed factory, not a caller-supplied ancestry provider. Metadata inspection uses a no-payload sink. The G2 sink copies checked group positions/UV/indices directly while the actual raw group borrow is active, and adopts one checked decoded RGBA array per distinct actual image after alpha facts/opaque recipe are established. No second parse, image refetch, deferred read, generic positive sink constructor or metadata-based shortcut is allowed. Refactor tests must prove existing G1 behavior unchanged.

The asset owns one `float[32768*5]` interleaved XYZUV array and one `int[98304]` index array. Valid prefix counts are detached metadata; no final trim-copy. Each group stores its vertex/index base and original raw triangle count. Copy *all* recorded indices with checked base offset; draw metadata references only the actual disjoint MOBA spans. At most64 spans reference qualified material ordinals, material ordinals reference at most18 image slots in first recorded material-use order. Shared image references share one array. Preserve all raw-vs-drawn counts and omitted3039 triangles in the actual example. Do not synthesize spans for unbatched indices.

Images own exactly one adopted RGBA array per distinct source image, aggregate <=1,179,648 pixels. The existing synchronous decoder's `Image.rgba()` getter is invoked once. G2 adopts that copy after original-alpha digest/stat collection and in-place alpha255 transformation; it neither retains the decoder Image nor clones the array again. There is no normal/color array, image atlas, mip pyramid, alpha-stripped duplicate, vertex trim, mesh concatenation duplicate or public ownership-taking constructor. Validate MONR/MOCV and digest omitted bodies as in G1, but do not retain/upload them.

## Two actual payload owners, shared by the source

The two-credit registry belongs to `WorldGlobalScopes`, so constructing another factory over the same source cannot create extra credits. A credit is reserved under the source's short metadata monitor BEFORE initial build IO or payload allocation. The third build receives a new finite `GLOBAL_ASSET_CAPACITY` RETRY_LATER site before any Map/WDT/root/group/image work or arrays. This new site requires explicit review. Do not evict a live owner or raise any cap.

A build credit remains charged through construction, writer-independent source final checks, the returned asset's lifetime, and every active guarded read. Owner close marks it unavailable immediately but clears its arrays only after active reads finish. Registry removal and future completion occur only after those arrays are actually detached; logical cancellation, CF cancellation, scope replacement or caller dropping a reference does not release credit. All close/borrow futures exposed externally are copies. A failed build releases its reservation only after raw input/decode/local builder owners and unreturned payload have retired. Snapshot and callback pins are aliases of those same two payloads, not extra arrays or a third construction credit.

`withBorrow` admits only while the asset is open, the source is healthy/open, and its exact originating private scope is still known as current or replacement. Detached descriptive facts remain historical and readable after scope retirement; payload borrows do not. Admission takes the source metadata monitor FIRST, checks source/scope, then takes the private payload monitor to check/open and increment its pin; it releases both before invoking any callback. No path takes the source monitor while already holding the payload monitor. Retirement detaches owner references under the source monitor, then calls payload close outside it; payload expiry releases its own monitor before notifying the source registry or completing any future. A closing source therefore cannot race a new payload borrow into admission. The trusted callback executes outside every source/owner monitor; finally expires all guarded spans and releases the pin. Position/UV/index/byte accessors check same-thread+active and bounds; saved spans clear payload references on expiry. No Java NIO buffer, backing array or Image can escape. Detached scalar facts can escape but never mint authority. Unknown callback failure is fatal and never a retry; the callback owns any unrelated resource it allocates and throws before returning. The generic `withBorrow<T>` result is an ordinary trusted read result, never an unreturned writer-resource transfer, MOVE receipt, packet or source-positive output authority. It cannot certify a file written after its borrow or source/scope retirement. A future closed writer bridge must own and close unreturned R, perform its own post-borrow source/health gate, and transfer MOVE/output authority only under its separately reviewed contract. G2 itself introduces no arbitrary codec/writer callback or output file: those need the next independent gate.

Source close seals construction/borrow admission and requests the existing active cancellation lane before retiring both source-owned asset owners. Borrowed assets close and retire after actual reads return; actual source close waits for both payload retirements AND the existing active operation/cancellation callback drain. Array clearing and metadata happen inside private locks; callbacks, cancellation requests, resource close, future completion and joins occur outside source monitors. No new thread, notifier, cache, executor or IO lane is introduced. Existing declaration/G1 APIs retain their result/ownership semantics; only their source-close future gains the real additional owners that G2 introduces.

## Unchanged16MiB parent envelope

All numbers are maximum live logical byte owners, not JVM RSS claims. Array headers, registry entries, bounded descriptors/receipts, retained current+replacement Map proofs and callback spans are covered by262,144B metadata. The parser allowance includes all raw guarded-view/chunk/header descriptors; the raw body itself is separately charged. The 1MiB synchronous image allowance includes Image constructor/getter clone overlap, immediate byte-source clones/read fragments and decoder stack; the adopted output array is already part of the new payload and is not charged twice. No decoder worker survives a lexical phase.

| Phase | Maximum live owners | Bytes |
| --- | --- | ---: |
| One maximum asset | XYZUV655360 + indices393216 + RGBA4718592 | 5767168 |
| Initial Map/WDT qualification | old asset5767168 + Map4194304 + metadata262144; new payload not allocated | 10223616 |
| Group extraction | old asset5767168 + new mesh1048576 + raw group1048576 + parser1048576 + scratch262144 + metadata262144 | 9437184 |
| Image/build | two full assets11534336 + raw image65536 + synchronous decode1048576 + scratch/writer reservation1048576 + metadata262144 | 13959168 |
| Final derived stats, then native Map/EOF+WDT | two full assets11534336 + Map4194304 + metadata262144; all raw/parser/decode/scratch owners retired first | 15990784 |

The existing Map4194304B phase must explicitly include its captured-row/header/digest/strict-EOF work and current/replacement proof descriptors must stay in the separate262144B metadata charge. There are no MAIN/MAID arrays. The remaining786432B is unassigned; do not spend it implicitly. There is no new file writer in G2, so its reserved1MiB is headroom for the subsequent separately reviewed streaming writer, not permission for a full mesh/image/file copy. No memory admission is claimed until reviewers accept these exact owners/phase retirement.

## First implementation and acceptance scope

If approved, implement only the shared source credit/owner, private fixed builder/sink and guarded asset view. No native or GUI files. Required authored cases:

1. Actual-source asymmetric positions/UV/index winding and literal float bits; zero normalization, finite/magnitude guards, group-offset overflow, invalid spans/material/image ancestry. No unauthorized descendant IO and no output on refusal.
2. Maximum32768-vertex/98304-index prefixes with no trim/normal/color/atlas arrays; <=18 images and exact aggregate pixel budget. BC3 red/alpha0/217 oracle preserves RGB while forcing output255; transparent BC1 continues to refuse.
3. Hold assetA and assetB; third build refuses BEFORE all source IO/allocation. Logical close with held guarded read keeps its credit/actual close pending; after the read exits, a new build succeeds. Test two factories sharing the SAME source registry.
4. Cancel during raw input, synchronous decode and completed-builder-before-final-check phases; no resource escapes, actual stream/callback drains, original clean async cancellation preserved, dirty/foreign failures fatal. Change every consumed binding and final Map/WDT identity: unreturned asset closes and both payload credit and source active guard retire.
5. Source close with an old held payload read plus a new held raw build requests cancellation FIRST and remains pending until both actual owners retire. A callback throws after creating a local resource: its finally owns cleanup; G2 payload pin still retires.
6. Production actual Map249→28-source reconsumption into the private asset, compare exact G1 coverage/alpha facts and new position/UV/image digests through guarded reads, close and prove both payload credits0. Original/prior stores remain read-only; no user assets in Git.

A subsequent standalone global codec/native draw proposal must consume the private asset through a closed bridge, define unlit/no-outdoor-fog sampling, preserve actual source and private output ownership, and independently prove pixels and actual native shutdown. This checkpoint grants none of that later work.
