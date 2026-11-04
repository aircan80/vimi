# Next global WMO source and material slice

Design/API checkpoint only after the reviewed d8da89f declaration engine. No source,
decoder, mesh, packet, shader, renderer or GUI code changes follow from this document.
The existing exterior profile and terrain/hot-world limits remain unchanged. This
uses a genuine GLOBAL_WMO_ONLY scene; there is no fake ADT, MAID tile, terrain base
fingerprint or exterior f6 annex.

## First concrete implementation: complete consumed-source manifest

Implement **G1**, the source/ancestry/material qualification operation, before a
renderable or persistent pixel/mesh owner. It reuses WorldGlobalScopes' actual
Map/WDT transaction, one-active admission and final post-drain gate. It consumes
actual root/GFID/group/image bodies, qualifies the new named profile and returns
bounded descriptive coverage through a private factory. It can discard one decoded
image after recording its facts; G1 does not need two resident snapshots or a new
GPU owner. A source-only manifest is not accepted as input to a later renderable:
G2 must consume/qualify the actual chain again inside its own operation.

Proposed closed API:

```
WorldGlobalAssetFactory(WorldGlobalScopes scopes);
<R extends AutoCloseable> R inspectSources(
    GlobalScope owned, Selection comparison, Cancellation external,
    SourceManifestUse<R> trustedWriter);
// Later, separately allocation-reviewed:
<R extends AutoCloseable> R withAssetAndWrite(
    GlobalScope owned, Selection comparison, Cancellation external,
    AssetUse<R> trustedWriter);
```

The writer receives a private-constructed same-thread expiring borrow. G1 exposes
only detached facts/coverage, not a positive renderable or raw input. R remains an
unadvertised caller resource through final ALL-binding/Map/WDT checks, actual
cancellation drain and the final scope/health gate. Callback-local owners close
before R exists; any unreturned R closes inside the active guard. Existing
withRecordedDeclaration stays metadata-only and gains no generic file reader.

A separate package-internal fixed `GLOBAL_ASSET_INPUTS_V1` operation in the source
engine admits one actual root selected only from the private recorded MODF. The
factory alone parses that root into private CheckedRoot/CheckedMaterial ancestry.
It can then request candidate numeric group/image reads; candidate bytes and a
successful read do not mint positive ancestry. The fixed factory accepts only
actual GFID members and actual qualified MOMT.texture1 values from that CheckedRoot.
There is no public root/group/image ID, GlobalObject, matrix or source-positive DTO
input, filename lookup, alternative texture search or generated repair.

One raw owner is reserved BEFORE open/allocation and stays charged until actual
stream and parser/decode uses retire. Fixed ceilings: WDT1MiB, root64KiB, group1MiB,
image64KiB; one root, up to8 unique groups,18 unique images,28 consumed bindings
including WDT. Repeated materials share one actual image owner/receipt. No parallel
input/decode pipeline or callback-owned second read is admitted. Before-stat null
length remains null in that operation's receipt; later metadata enrichment is
allowed only with the same consumed ID/CK/actual length. Exact complete/no-hole/EOF
rules from d8 apply. Initial and final Map work runs after raw inputs retire.

The final read set includes every consumed body, fresh native Map/strict EOF and
private scope generation, not just root or WDT. Actual Map witness stat observation
is separate from the28 WMO-chain body receipts. Proposed additional finite sites
requiring review are `GLOBAL_ASSET_PROFILE` capability and `GLOBAL_ASSET_BINDING`
known identity change. A later two-snapshot admission needs its own finite retry
site. Malformed/contradictory data, arbitrary providers and dirty cleanup stay fatal;
no message/code-string inference converts decoder exceptions into expected policy.

## Concrete source profile and actual evidence

Profile `GLOBAL_UNLIT_OPAQUE_RGB_V1` intentionally omits client lighting and source
texture alpha under actual MOMT shader0/blend0. This is separate from strict BC1
interior/exterior profiles, which continue to refuse the observed nonopaque BC3.
Actual Map249/WDT789922/root109474 are examples/proofs, never allowlist positives.

Root: bounded WmoFormats.root with explicit narrowed Limits (64KiB root,8groups,
24materials,8GFIDs,64KiB facts), WMO17/flags5/noLOD, flat GFID count exactly group
count, positive unique group IDs, no MOTX. Closed observed root chunk kinds only;
unknown/duplicate chunks refuse. MOMT words0,1,2,6,9,11,12,13,14,15 must be zero;
word3 must be a positive numeric texture reference under this modern GFID/no-MOTX
shape. Every material is qualified, not only distinct images. Words4,5,7,8,10,
MOHD ambient/counts and omitted root chunks remain preserved/digested descriptions,
intentionally unused by this unlit recipe. No array is sized from omitted counts.

New read-only/local-only diagnostic checked ALL16 words of actual19materials:
`/home/aircan/.cache/vimi-tmp/global-declaration-actual/root-material-diagnostic.log`.
The required zero operands hold; word8 is10 and color words are nonzero. Do not
claim all operands are zero. Earlier actual root has declared208doodads versus76
physical40-byte MODD records and43 MODI IDs; preserve that semantic discrepancy,
not a complete embedded-object inventory.

Groups: use only approved WmoGroupSpans guarded spans, never WmoFormats.group's
normal/position copies. Require one UV0, valid finite source operands, actual
index domains and batch bounds, flags0 material8 batches and total declared batch
count matching the physical list. Preserve every raw index and draw ONLY exact,
disjoint recorded MOBA spans; unbatched triangles stay counted/undrawn. Initially
restrict group raw flags to the observed set0x0a09/0x3805/0x2a05/0x2005/0x2805,
with actual stream consistency, rawBatchPadding/tail0/tail1 zero, rawUnused
limited to0/1 and preserved/ignored, and bounded omitted chunks. This is a narrow raw shape, not interpretation of all group flags.
Closed optional kinds are MOCV/MLIQ/MOLR/MODR/MOBN/MOBR plus the required mesh
chunks. Unknown/extra UV streams refuse. Validate/digest MOCV count/raw bytes and
MONR operands without retaining/uploading normal or color arrays. MLIQ/BSP/light/
doodad/portal data are recorded and deliberately omitted, not parsed into floors,
water height or guessed room visibility. All groups draw conservatively.

Actual eight groups contain23076vertices/31219raw triangles/59spans drawing28180
triangles;3039raw triangles are unbatched. Seven MOCV and three MLIQ bodies remain
explicitly omitted. The new profile must admit these counts through actual guarded
reads while refusing overlap/invalid ranges, never inventing missing material spans.

Images: actual eighteen bodies are17BC1/depth0/alphaEncoding0 and oneBC3/depth8/
alphaEncoding7, mipFlags1, mip0, base side<=256. Aggregate1130496pixels, cap1179648.
The BC3 image128111 has16384nonopaque pixels, alpha0..217; it is reused by material18.
BC1 still requires decoded alpha255. For admitted BC3 retain original encoding and
alpha min/max/nonopaque count, then force alpha255 IN PLACE in the one adopted
RGBA output. Preserve RGB exactly. No second alpha-stripped image/atlas. Validate
bounded raw header/count/product BEFORE decode and cross-check actual decoder
result/mip directory afterwards. Unsupported raw profile guards are explicit;
malformed decoder failure remains fatal. No neutral/missing image substitution.

## Decoder ownership correction required before the tight final phase

Existing Blp2Decoder.decode uses AssetTasks.run. Its CompletableFuture completes
while the virtual worker still holds its supplier/AssetSource clone and Image
value; a preempted worker can retain them after join returns. Future completion
alone is NOT an actual worker-stack retirement receipt. Therefore the earlier
720896B transient estimate is usable only with explicit phase ownership; do not
silently carry eighteen possibly completed decoder task owners into final Map work.

Propose a small explicit synchronous/off-caller Blp2Decoder entry delegating the
SAME existing decodeCore, with the existing strict limits/profile. Async/default
APIs, decoding algorithm and persistence stay unchanged. The fixed factory calls
it only on its source worker using AssetSource.bytes and bounded immediate-memory
reads. A private decodeOne scope adopts Image.rgba() exactly once, records alpha
facts and returns only compact metadata plus that owned array. Image, AssetSource,
encoded payload, read fragments and decoder stack retire before the next phase.
No new async worker/drain abstraction or hidden decoder cache is required. This
new API needs its own small tests/review before G1 uses it.

## Later G2 private unlit asset and unchanged16MiB envelope

G2 adds private-owned maximum arrays:32768vertices*20B XYZ+UV and98304int indices,
1048576B total CPU mesh; RGBA cap4718592B. No TerrainScene.Tile, normals/colors,
concatenated atlas, final trim-copy or public ownership-taking constructor. Group
raw views copy only positions/UV/indices into that single bounded builder. Draw
metadata stays <=8groups/64spans/24materials/18images. Scalar/raw digests and all
28 receipts fit a fixed262144B metadata reservation including both current/pending
source proofs and array headers. A private two-owner credit precedes allocation;
current/pending/payload pins count until actual final release, not logical cancel.
A third request refuses before source payload allocation.

Use the approved strict zero-placement D=(-x,-y,z), determinant+1, no ADT translation
or guessed permutation. Preserve winding; derive global native bounds from actual
root/group positions, use double origin rebasing then a checked float error bound.
Exact magnitude/UV/rebase-error guards require the G2 API review, not guessed
wide limits. No exterior directional light/fog is reused. Future output is unlit
RGB1, two-sided, repeat U/V, linear min/mag, one selected mip0, alpha1, no sRGB
conversion or outdoor fog. Lighting, alpha, MOCV, liquids, doodads and portal
omissions remain visible coverage metadata.

| Phase | Owners charged | Maximum bytes |
| --- | --- | ---: |
| Group extraction | old snapshot5767168 + building mesh1048576 + raw group1048576 + parser allowance1048576 + scratch262144 + fixed metadata262144 |9437184|
| Image/build/write | two full snapshots11534336 + raw image65536 + synchronous decode1048576 + codec/writer scratch1048576 + metadata262144 |13959168|
| Final native Map/EOF/ALL stats | two full snapshots11534336 + Map4194304 + metadata262144; ALL raw/decode/writer owners retired first |15990784|

Final margin786432B remains unassigned. These rows are a proposed allocation
contract, not granted16MiB admission; every additional owner requires review.
G1 has no persistent new mesh/RGBA snapshot and can qualify the complete actual
chain before G2's snapshot/allocation gate. Source/source-copy/parser/decoder facts
must retire lexically, not be silently deducted merely because a future completed.

## Acceptance sequence

1. Review the fixed derived-input/source profile API and the synchronous decoder
   correction. Implement G1 only, authored ancestry/tamper/EOF/bounds/cancel/ALL-final
   gates, then actual read-only/private-scratch Map249→WDT→root→8groups→18images
   proof. Result is source/coverage evidence only, not a renderable or GPU claim.
2. Review G2 exact guarded payload/credit/phase allocation API; implement private
   unlit mesh+images, asymmetric D/winding/rebase tests, BC3 RGB/alpha0/217 witnesses,
   no normal/MOCV allocations, old/pending held-owner/third-refusal tests.
3. Separate canonical standalone global codec/native owner review, preserving the
   prior proposed <=6MiB streaming file and41154816B standalone child ledger, never
   adding it to the terrain121893172B envelope. Actual old context drains first.
4. Actual source→private file→native accepted pixels/shared free-flight/close proof,
   then GUI enablement with honest per-stage progress. No global hot-streaming,
   dungeon floor/minimap, water, doodad or client-lighting parity claim is implied.
