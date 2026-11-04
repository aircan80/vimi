# Explicit source-origin refusals

Design checkpoint after approved14e4f16. Concepts remains independent of rendering-
bgfx. This adds no writer/GUI route or retry loop. Map-only extraction proceeds
separately from the frozen facade; its behavior-preserving helpers will receive
the reviewed Map-only throw-site patch after extraction freezes.

## Public and private shape

Add nested public final WorldTerrainStreaming.Refusal with private constructor and
Reason { RETRY_LATER, CAPABILITY_UNAVAILABLE, SOURCE_INVALIDATED }. It extends
IllegalStateException so existing private-engine failure expectations remain valid.
It carries only that reason plus a fixed bounded origin-site identifier/message
(max256 ASCII chars), never source objects, IDs/arrays, provider messages or provider
causes. Initialize its Throwable cause to null; suppress stack construction because
the fixed site identifies the origin. Construction is only through package-private
WorldSourceRefusals factories accepting an internal finite Site enum, not arbitrary
strings/callbacks/causes. There is no sourceVerified or fallback boolean.

The public facade normalizes only a clean expected refusal at its entry boundary.
Unwrap at most eight exact standard CompletionException/ExecutionException links,
checking EVERY wrapper for unexpected suppression before reading its cause. Do not
walk arbitrary cause classes. A Refusal with any auxiliary/cause/suppressed cleanup
failure is FATAL, not an expected refusal: wrap it in a distinct ordinary failure
that is not a Refusal and preserve the original failure for diagnosis. No cleanup
error may be hidden behind retry/capability/source-invalidated. Known cancellation,
direct or bounded async-wrapped, stays cancellation and keeps existing semantics;
it is not converted into one of these reasons. Unknown exceptions/errors remain
unchanged/fatal even if validateProof also invalidated the token.

The future fixed GUI bridge maps ONLY a clean typed Refusal to the matching
PacketDestination.refusal reason/site and never retains the source exception as
its cause. Optional.empty is an explicit missing capability handled directly by
that bridge, without calling a fallback provider. Unexpected provider/program/file/
cleanup failures propagate as fatal producer failures. Never infer a reason from
an exception message, isKnown=false, current scope state or a generic Runtime type.

## Explicit origin table

Factories are called AT these known branches, not in a blanket surrounding catch.
The site enum below is descriptive; it does not authorize asset reads or packets.

| Existing branch/context | Typed reason/site |
| --- | --- |
| WorldPreparedTerrain.reserve,12 output/construction credits occupied | RETRY_LATER / OUTPUT_CREDIT |
| WorldGeometryCache explicit preparing/output-credit/pinned-version guards | RETRY_LATER / GEOMETRY_CREDIT |
| WorldStreamScopes.begin or activate one-active guard occupied | RETRY_LATER / SOURCE_WORKER |
| Actual owned material TileResult.POOL_BUSY | RETRY_LATER / MATERIAL_POOL |
| Actual native selected-ID inspector State.BUSY | RETRY_LATER / ID_INSPECTION |
| Default/no configured optional streaming feature | Optional.empty; bridge CAPABILITY_UNAVAILABLE |
| Native capture explicitly returns UNSUPPORTED/NOT_PREPARED/SCHEMA_UNSUPPORTED | CAPABILITY_UNAVAILABLE / NATIVE_CAPTURE |
| Optional strict captured-row API explicitly lacks supported bounded operation | CAPABILITY_UNAVAILABLE / STRICT_ROWS |
| Own explicit supported-profile refusal: prehash shape/count/row limit, native copy/domain profile, selected-ID inspector UNSUPPORTED_DOMAIN | CAPABILITY_UNAVAILABLE / MAP_PROFILE |
| Genuine prepared graph lacks optional verified-native issue feature (not a contradictory verified proof) | CAPABILITY_UNAVAILABLE / NATIVE_ISSUES |
| Selected native Map schema has no eligible numeric WDT field/ref | CAPABILITY_UNAVAILABLE / NUMERIC_WDT |
| Actual recorded selected tile has no positive numeric ROOT reference | CAPABILITY_UNAVAILABLE / NUMERIC_ROOT |
| Checked pure terrain parse/profile rejection at exact parser call (no provider calls in catch) | CAPABILITY_UNAVAILABLE / TERRAIN_PROFILE |
| Actual source read structured unavailable/unsupported/protected/incomplete state, input over profile cap or observed truncation before any root proof exists | CAPABILITY_UNAVAILABLE / SOURCE_INPUT |
| Facade/backend/workspace closed or issued output already retired | SOURCE_INVALIDATED / OWNER_RETIRED |
| This engine's previously issued scope no longer current OR replacement | SOURCE_INVALIDATED / SCOPE_RETIRED |
| Explicit fresh Map source stamp/row/identity comparison changes | SOURCE_INVALIDATED / MAP_BINDING |
| Explicit fresh known WDT/root/other consumed positive identity ID/CK/known-length mismatch | SOURCE_INVALIDATED / SOURCE_BINDING |
| Negative material observation changes (including absent/unbound becoming available) | SOURCE_INVALIDATED / MATERIAL_BINDING |
| Actual selected-ID inspector State.SOURCE_CHANGED | SOURCE_INVALIDATED / MAP_BINDING |
| Actual strict helper's private SourceFailure.CHANGED | SOURCE_INVALIDATED / MATERIAL_BINDING |

Pressure is only real bounded admission/credit contention. Input-already-open,
wrong callback thread, duplicate callback, invalid phase, exceeded binding-count
invariant, contradictory rows, missing returned R or counter exhaustion are NOT
pressure. Presentation union wait belongs to the controller before source work;
source must not speculate about presentation or grow its output count.

CAPABILITY_UNAVAILABLE means this configured qualified path cannot currently
supply a packet; it is not a permanent claim about the game asset or its catalog.
It does not mean HEIGHT_FALLBACK. A genuine source-qualified HEIGHT_FALLBACK already
has actual checked root geometry and terminal material outcome and remains a
successful output/packet, with its existing explicit reason. No pool/source refusal
may be folded into that packet category.

SOURCE_INVALIDATED is an observed identity mismatch or explicit retirement at its
origin. Foreign-owner scope/caller tuple mismatch remains a caller/program failure,
not an expected changed-source event. A known source-input acquisition failure
before proving root geometry is capability-unavailable, not a fabricated height.
A private SourceFailure.CHANGED is recognized only by its actual internal type/
reason in the owned-material path through bounded standard async wrappers; do not
walk arbitrary causes looking for a familiar nested error. Legacy public material
read behavior remains unchanged.

## Map extraction patch boundary

WorldMapProofs will own bind/qualify/revalidate/stamp/EOF/partial witnesses with the
same bounded source arguments and receipts. It can call the shared package-private
origin factories directly at the explicit branches above. WorldStreamScopes keeps
lifecycle/current+replacement/active/cancel/drain and non-Map source binding checks.
No Map DTO creates a positive scope. For a revalidation call with an existing proof,
actual stamp/identity mismatch is INVALIDATED; absence of a documented optional
qualified capability remains CAPABILITY_UNAVAILABLE. Both may invalidate that
associated scope according to the existing noncancel proof policy; that metadata
invalidation itself is NEVER the basis for classifying the failure. Unknown proof
errors invalidate conservatively but still remain fatal.

Do not broadly catch UnsupportedOperationException from an entire Map operation.
Only the documented optional strict-row invocation boundary may convert its
unsupported result/exception; arbitrary capture/provider/writer exceptions keep
fatal semantics. Actual native CaptureState.CANCELED and the reviewed c995 bounded
wrapper handling remain unchanged. Structured CORRUPT/contradictory native proof,
noncontiguous/duplicate rows, wrong returned ordinal, mismatched verified witness/
issue certificate and ambiguous duplicate numeric fields remain fatal in this
first mapping; do not weaken authority merely to produce a friendly refusal.

## Causal fixtures

1. Fill twelve issued outputs, block a cache version/worker, exhaust actual pool
   and inspector credits: exact retry reason, no packet writer/extra root IO or
   thirteenth geometry/atlas admission. Release real owners and retry succeeds.
2. Actual default optional feature and bounded-native unsupported profiles produce
   capability refusal; no legacy/wide/native-repair fallback or fake height occurs.
3. Initial/final actual Map/WDT/root/material/negative-lookup identity mutations
   produce invalidation at their explicit sites and retire the unadvertised R.
   Retired own scope is expected invalidation; foreign scope/caller mismatch fatal.
4. Inject plain/nested unknown provider errors and arbitrary cause getter traps;
   they stay fatal even when proof metadata becomes unknown. A writer or cleanup
   failure never becomes refusal; expected refusal plus a held/throwing cleanup
   also escalates fatal, with no early resource/source credit.
5. Direct/bounded-wrapped cancellation remains separate, current proof healthy,
   no false height or permanent unsupported classification; retry recovery works.
6. Full/partial/terminal successful source outputs stay unchanged. Nullable actual
   composition cache SHA is not replaced by a surrogate. File ownership and actual
   source/lower-callback drain retain the approved factory semantics.

The parent transport keeps the old child/table while a refused unused reservation
retires only after actual producer/stop-callback/file drain. It consumes a new valid
packet only after actual source final success, structural writing and one-shot file
MOVE. A failed future is not permission to reuse source/native/app GPU ownership.
