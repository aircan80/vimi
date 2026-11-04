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
