# Draft streaming material identity amendment

Approved direction by root and independent architecture review, before implementation.
This amends the private, unreleased tile-v2 contract; no wire offsets, field widths,
resource caps, source authority, shaders or released APIs change.

The actual material helper can produce a qualified PARTIAL atlas whose Receipt.cacheKey
is null because a referenced diffuse source is absent. That nullable composition cache
key must remain null. Neither an IPC writer nor a successful checksum may invent a
complete or persistable composition receipt.

Rename the private tile header field at offset160 from cacheDigest to materialDigest.
It is a nonzero opaque presentation identity for TEXTURED, zero/absent for height.
Existing complete cache digests remain structurally valid opaque identities; their
presence never establishes complete material coverage, cache persistence or source
qualification. Readers have always checked only its shape and whole-file integrity,
not performed cache lookup or authenticated its origin. Thus this explicit amendment
keeps private wire version2 while correcting its draft terminology.

The forthcoming fixed application writer always computes a separately namespaced,
deterministic packet material digest inside the actual checked geometry/atlas borrow.
Bind profile/recipe versions, actual geometry fingerprint, scope/tile identity,
coverage, native anchors/UV error, ordered actual qualified structural and diffuse
source/mip receipts, and actual RGBA bytes. Hash pixels incrementally with cancellation;
no full-atlas clone, second pixel owner or cache write. Do not include transport nonce,
transaction or slot counters in the material identity. Source final checks and actual
operation drain still precede file MOVE/publication.

This digest describes the admitted visible payload; it does not assert completeness
of missing inputs or replace the source engine's final positive/negative observations.
The opaque source owner alone qualifies that chain. Detached Facts preserve material
state and coverage; the genuine nullable source composition key stays available only
through its expiring material view.

Implementation gates: rename private codec accessor and draft descriptions; preserve
all existing version2 roundtrips; add a partial coverage roundtrip with distinct
material identity and no new cache claim. Fixed-writer tests must prove digest changes
with pixels, coverage, profile and source operands while source Receipt.cacheKey stays
null for the actual PARTIAL fixture. No DTO or arbitrary digest becomes a source
certificate. The public fixed writer remains a separately reviewed implementation.

## Private digest helper implementation

`WorldStreamMaterialDigest.compute` consumes descriptive TEXTURED header metadata and
one readonly exact-size atlas synchronously. Its `VIMI_WORLD_STREAM_MATERIAL_V1`
namespace uses length-prefixed ASCII and little-endian uint64 scalar words. It binds
profile, scope, tile/counts, geometry fingerprint, recipe1, four coverage words,
raw anchor/UV-error bits, ordered WDT/root/tex0/diffuse tuples including nullable-length
sentinel and encoding/mip operands, and the actual RGBA byte sequence. Nonce, transaction,
slot/credit counters and the header's prior materialDigest are deliberately excluded.
This helper neither checks source qualification nor replaces pixel/mapping validation.
The fixed writer must use actual matching checked data inside the source borrow.

The helper retains no input and allocates one4096-byte scratch, small bounded text bytes
and a SHA-256 context; it preserves input buffer position/limit and checks cancellation
before and during pixel traversal. Its scratch belongs to the existing producer's
64KiB scratch allowance in the pre-write hashing phase; it does not add a second atlas,
cache entry, packet mirror or live file-IO buffer. Actual fixed-writer allocation overlap
still requires review when that writer is connected.

Six authored tests cover a separately calculated Python SHA-256 oracle, transport/template
identity independence, profile/coverage/pixel/source/order changes, mid-pixel cancellation
and invalid shape/profile refusals. A separate codec fixture roundtrips one covered
rectangle with transparent zeros elsewhere. These establish descriptive hashing and
structural IO only; the source facade's genuine PARTIAL/null-cache receipt and eventual
fixed bridge must retain their own tests and authority.
