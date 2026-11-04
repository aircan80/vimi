# Revalidate proven Map bindings without rescanning unchanged rows

Status: proposal only, based on isolated scope engine5545fd7. Do not implement
until independent review. No GPU/UI performance claim.

The current engine scans up to10,000 Map ordinal rows to establish a unique
requested ID→qualified numeric WDT binding. It does that twice on scope creation
and again for each recorded tile validation. Each ordinal can decompress a block
and start a capture read worker. Correctness is bounded, but this repeated source
work could dominate movement preparation. The source/native draw owner remains
unblocked; that alone does not make preparation fast enough.

Keep the initial full bounded scan. Only a successful unique selected-row proof
may create a private MapBinding containing its selected Map ID/WDT/row digest and
an additional32-byte MapSourceStamp. An unknown/duplicate/missing requested ID
never creates a reusable proof. No global map→binding index or new Map cache.
The stamp remains inside the same at-most-two private heavy proof registry;
external Scope values remain small detached capabilities. Its extra32-byte digest
and scalar flags fit the existing2KiB scope metadata allowance, no payload cap
increase.

Compute the stamp from a canonical length-prefixed streaming digest of the freshly
opened genuine native captured receipt after all existing qualification checks:

- BuildRef/locale and Map table numeric FileDataID/name/checked READY state/CKey.
- Definition SHA, resolved schema block identity, selected observed layout,
  qualified WdtFileDataID field descriptor/ordinal and actual native columns SHA.
- Graph identity, source/native tuple identity SHA, reader options/revision,
  row encoding and encoding scope.
- Row count, relevant complete/excluded/copy/physical counts and source header
  identity, so a different traversal domain cannot reuse a uniqueness proof.
- Native issue policy/producer/certificate context and consumed key/section/hole
  identities, preserving capture scope and decoder provenance.

The implementation must bound receipt lists and scalar lengths before hashing;
stream into MessageDigest rather than building another full-schema byte array.
Use existing producer identities, not a hash of display text or just Map ID/WDT.
The stamp is a revalidation optimization for a source-owned immutable graph, not
source authentication or a replacement for the source factory's qualification.
Do not accept an arbitrary caller-provided stamp/receipt or unknown native issue
channel. A stamp never certifies whole-table membership beyond the original
successful bounded complete selected-row scan.

During scope creation, first scan validates the full unique selected row and
captures its stamp. Read/parse the checked WDT/MAID as today, explicitly retire
that phase's input, then open a fresh native capture and revalidate the receipt
stamp with zero row reads. Equal stamp proves that the original completed selected
row/uniqueness proof still belongs to the same immutable source graph. Any change
rejects this in-progress scope; it does not rescan and silently publish a different
binding under the old WDT work.

For a current scope's recorded tile validation, resolve its private registry
entry, open one fresh native capture, run the same qualification/stamp check, close
and drain that capture, then fresh-check WDT CKey and compare all eight MAID
references. Equal Map stamp skips the already-proved full row scan. A changed
stamp invalidates the old scope immediately and requires an explicit replacement
scope/session; do not repair its proof or silently switch bindings. Unknown,
unsupported, limit-refused or unavailable capture cannot reuse the old stamp and
must reject new preparation while the last accepted image may remain visible.

Fresh capture remains mandatory on every operation: it is responsible for current
source/root/table identity and native certificate validation. No TTL, cached old
capture handle, unchecked get/map DTO or generic page fallback. Existing generation,
build/locale/map/source-close cancellation and two-proof limits remain intact.
Current native graph identity ties the rows to their immutable stored graph; a
provider that changes row bytes without changing validated source/graph identity
is outside this producer contract and cannot be admitted as genuine native input.

This only removes repeated ordinal reads for unchanged Map source. Capture open
still constructs a prepared reader with manifest/schema/certificate owners, whose
separate prepared-table ledger and latency remain. WDT stat/revalidation and
geometry/material work remain. The strict4MiB post-open read envelope and3MiB
sequential WDT phase stay unchanged. The optimization must not claim all capture
or source work becomes constant-time or allocation-free.

Before coding, review the exact stamp tuple against ClientBuildTables capture
freshness and immutable-graph guarantees. Required authored tests:

- Initial proof scans every ordinal and rejects duplicate/unknown/missing IDs.
- Same-scope second validation opens a fresh capture yet reads zero ordinals;
  no new full Map index or retained graph/schema/page exists.
- Changed table CK, definition/block/layout/qualified field, graph/native source,
  reader options/row encoding, row count/completion and key/issue scope each reject
  old proof. Recreating the scope must perform a new full scan.
- Existing genuine changed-Map-row native fixture, forged caller WDT, all-eight
  MAID mismatch, changed WDT CK and stale/foreign/closed token negatives still fail.
- Held fresh capture, source cancellation/close and absent bounded/native
  capability cannot fall back to unchecked cached success.

Instrument capture opens, ordinal reads and source-stage elapsed time outside the
native owner. Compare the baseline engine and candidate using the same authored
large Map graph and a private prepared-game source, with unchanged scopes and
source-change cases. Report row/capture work counts plus median/p95/max elapsed
preparation for fixed repeated runs. The acceptance gate is zero repeated ordinal
reads for a stamp-equal scope, unchanged freshness negatives and bounded complete
owner drain. An observed lower preparation time is diagnostic evidence; this
stage cannot establish game-like frame pacing until the persistent renderer and
GUI coordinator are wired and tested separately.
