# Partial native Map selected-ID witness implementation

Implements40c7c31/3659343 with explicit configured-source access, not a general
PARTIAL acceptance. Complete READY qualification remains unchanged. The partial
path requires the actual client-native private witness, a unique observed selected
row and its existing qualified WdtFileDataID/issue channel. Membership stays
NOT_CERTIFIED and hidden values/catalog completeness are never claimed.

Db2Reader records whether every hidden WDC4/5 clear ID list exactly covers that
section's declared physical IDs, with no copies anywhere. Existing reader/find
behavior remains unchanged. The new metadata-only observation refuses incomplete
per-section coverage even when global excluded counts match and find returns Found.
Known readable/protected conflicts still fail actual structural open. No row values
are decoded by the inspection. Missing IDs return NOT_OBSERVED, not proven absence.

CapturedRows optional inspection defaults to no-I/O UNSUPPORTED_DOMAIN. The native
implementation resolves only the existing local definition and opens actual numeric
source bytes with fixed LOWER limits. It binds header/sections/completion, current
definition/options/key scope and source FDID/CK/length to the exact held native graph.
Witness implementation and constructor are sealed/private. Detached proof contains
only bounded scalars/hashes and no capture/service/source/definition owner. The shared
receipt fingerprint prechecks every serialized scalar, including table name and
all exact issue-evidence fields, before hashing. SourceMode is explicit in the
MapBinding/AdmissionMetadata and its evidence enters the scope digest.

One process-wide inspection credit is held through source/structural-owner cleanup
and real lower cancellation callback drain. Pressure returns BUSY, never unsupported
or height fallback. Ordinal reads and inspection are exclusive per capture. Capture
close and external cancellation return promptly; repeated close dispatches once.
Unexpected runtime/errors survive concurrent cancellation; only actual cancellation
is normalized. Cancellation callback failures propagate after owned work drains.
No graph repair, new generated graph or invalidation/discard on profile/limit refusal.

Bounds: actual Map input1MiB,256 physical rows,128 fields/columns,16 sections/holes,
record/aux/strings64KiB,512KiB index charge,64KiB/256 relationship entries, no copies.
Actual definition row-shaping fields and configured raw-rule strings/counts are
bounded before block/options hashing. One4MiB phase remains sequential with row/WDT
work; existing prepared/definition subsystem ownership is separately accounted.
The two private registry witnesses fit the existing2KiB-per-proof header allowance.
Source/projection/range allowances are unchanged; no source-heavy inspection queue.

Cached partial witnesses are reused only after fresh native full-stamp equality and
strict EOF capability, PLUS fresh actual Map FDID/CK/known-length validation; this
catches a changed live source even when the saved graph/inventory stays stable.
All eight recorded MAID references and final consumed-source checks remain intact.

Authored tests use genuine encrypted WDC5 data through nativeDb2/generated storage/
ClientBuildTables. They cover readable/protected/unknown IDs; missing or redistributed
excluded inventories; conflicting IDs; copied profiles; default unsupported; foreign
and wrong-ordinal private witnesses; duplicate selected visible rows; definition,
key, CK and hole changes; actual oversized stream/unsafe status before read; metadata
prehash bounds; busy credits, close/cancel drain, unexpected concurrent failure and
recovery. Full formats-tables/concepts-world checks pass61 tasks, log
`/tmp/vimi-partial-id-final-check.log`.

Private ACTUAL proof: `/tmp/vimi-partial-map-actual-proof/proof-overlay.log` uses
70755 Map1349477, CKf8fc790a503d856e64fbb552aba2897f,73/81 readable physical rows,
three excluded sections/eight explicit excluded IDs, WDC5/D43AFAC3,10209bytes.
Map0 qualifies through its witness to WDT775971. Actual root777947 then prepares
37120vertices/256area records and native geometry SHA
c032876b1c24752cb8f2bedf95e710510402addf9ee98e89b6bff675601ae6df;
repeat preparation shares the exact immutable tile and rootReads==1. Original
library AND prior acquired instance store are opened read-only; fresh temporary
object/generated stores only, localOnly/no network. No game assets enter Git.
This proves source/geometry readiness, not GUI/native hot-stream integration.
