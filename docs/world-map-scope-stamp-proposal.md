# Revalidate proven Map bindings without rescanning unchanged rows

Status: independently approved design; isolated implementation checkpoint below.
Based on corrected source engine ce55dba. No renderer/GUI/factory/cache wiring and
no GPU/UI performance claim.

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
stamp with zero decoded/data ordinal reads. The fresh capture must also pass the
bounded EOF capability probe below. Equal stamp proves that the original completed selected
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

A matching receipt alone does not prove that the new handle supports the optional
bounded API. On every fresh capture used for stamp reuse, make exactly one
`readOrdinalPage(receipt.rowCount(), 1, READ_LIMITS, token)` EOF metadata probe.
Require READ, the same graph identity, firstOrdinal=rowCount, zero rows, zero
cellIssues and nextOrdinal=null. Unsupported default, canceled/closed/invalidated
or LIMIT_EXCEEDED refuses new preparation, even with an equal receipt. Never use
the legacy method as fallback or reflection to infer implementation capability.
For the actual native handle this count-zero path decodes no ordinal/record and
does no RecordFile payload IO. Report one bounded EOF API probe, not zero API
calls. The initial complete strict scan established that the same immutable graph's
actual rows fit the policy; the new probe validates the fresh handle's capability.

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
- Same-scope second validation opens a fresh capture, decodes zero data ordinals
  and performs exactly one bounded EOF metadata probe; no new full Map index or retained graph/schema/page exists.
- Changed table CK, definition/block/layout/qualified field, graph/native source,
  reader options/row encoding, row count/completion and key/issue scope each reject
  old proof. Recreating the scope must perform a new full scan.
- Existing genuine changed-Map-row native fixture, forged caller WDT, all-eight
  MAID mismatch, changed WDT CK and stale/foreign/closed token negatives still fail.
- Held fresh capture, source cancellation/close and absent bounded/native
  capability cannot fall back to unchecked cached success. Include a fresh wrapper
  returning a genuine unchanged receipt but default/explicit Unsupported bounded
  API; equal stamp must still reject. EOF wrong identity/ordinal, nonempty
  rows/issues/next, closed/canceled/LIMIT responses each reject.

Instrument capture opens, bounded EOF probes, actual ordinal reads and source-stage elapsed time outside the
native owner. Compare the baseline engine and candidate using the same authored
large Map graph and a private prepared-game source, with unchanged scopes and
source-change cases. Report row/capture work counts plus median/p95/max elapsed
preparation for fixed repeated runs. The acceptance gate is exactly one successful bounded EOF metadata probe and
zero repeated decoded/data ordinal reads for a stamp-equal scope, unchanged freshness negatives and bounded complete
owner drain. An observed lower preparation time is diagnostic evidence; this
stage cannot establish game-like frame pacing until the persistent renderer and
GUI coordinator are wired and tested separately.


## Isolated implementation evidence

The engine now completes one full initial unique Map-domain scan, then validates
fresh captures using the canonical stamp plus strict EOF capability. It keeps only
a64-character digest in private MapBinding; no captured receipt/schema/page or
reader is retained. Changed stamp invalidates the old private proof. Unknown or
unsupported fresh capability cannot reuse it. Explicit caller cancellation drains
the fresh operation without inventing source change; close still invalidates.

Canonical v1 encoding streams directly through DataOutputStream/DigestOutputStream
into SHA-256. The domain is `vimi-world-map-source-stamp-v1`. Fixed primitives use
DataOutput's big-endian encoding; nullable scalars have a presence boolean followed
by bounded writeUTF (Java's modified UTF-8 with16-bit byte length). Source order is
preserved for schema/layout, sections and keys. The implementation explicitly lists
table counts, full admitted schema, native provenance/producer context, header,
row/completion domain, sections and key facts. Transient table reason/message/time
is deliberately excluded. The selected-row binding includes this complete stamp,
so the scope digest also changes with its proven Map context.

Prehash profile extends the existing128-column/64KiB aggregate bound to header
columns,≤128 sections/hole keys/effective-key records, key/fingerprint≤64 chars,
header/counts format/hash≤16 chars and completion scope≤64 chars. Header/receipt
columns must agree. Bounds run before canonical hashing/tuple scratch; new stamp
fields do not evade the4MiB post-open allowance or move prepared-open metadata
inside it. Every fresh validation performs a bounded EOF call and checks READ,
graph, ordinal, zero rows/issues and next=null; it never invokes a legacy reader.

Seventeen scope fixtures cover unchanged work counts,13 changed stamp field
families,12 failed EOF/native/capability cases,23 prehash bounds, source CK/MAID
checks and held fresh EOF cancellation/close. Removing only stamp equality or only
the fresh EOF probe makes the corresponding regression fail. The restored
concepts-world/formats-tables checks pass.

A fixed authored comparison used corrected pre-stamp engine3865581 and the same
genuine native256-row Map graph, one fresh JVM per side, then7 repeated validations.
These are single diagnostic runs on this Linux host, not a frame-pacing benchmark:

| Work/timing | Corrected baseline | Stamp checkpoint |
| --- | ---: | ---: |
| Fresh capture opens | 9 | 9 |
| Actual data ordinals decoded, total | 2,304 | 256 |
| Repeated data ordinals | 1,792 | 0 |
| Bounded EOF metadata probes | 0 | 9 |
| Initial creation | 190.412 ms | 172.841 ms |
| Repeated median | 12.429 ms | 3.595 ms |
| Repeated p95/max (7 observations) | 15.441 ms | 4.897 ms |

Initial creation includes native graph/fixture startup, so it is not pure stamp or
GPU draw latency. The stable result is removal of repeated ordinal work; capture
open, source stat, WDT/geometry/material preparation and actual UI pacing remain.
The current production GUI does not call this engine. Private actual-game source
validation/streaming and frame traces remain later integration gates.
