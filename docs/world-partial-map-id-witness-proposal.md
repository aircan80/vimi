# Selected observed Map row in a partial native capture

Design checkpoint only. Do not change READY qualification or allow PARTIAL globally.
The actual Map source is WDC5,81 declared physical records,73 readable, three hidden
encrypted sections/eight excluded physical records, no copies,10209 source bytes.
Its native issue channel is VERIFIED_NATIVE, but CapturedRows membership remains
NOT_CERTIFIED. A native issue certificate is never an excluded-ID inventory proof.

## Existing evidence and the missing fact

Captured ordinal pages enumerate only emitted readable rows; generated ID lookup
also covers only that emitted index. Db2Table.find returns Found for an observed
readable ID and NotInReadableSections for unknown IDs in a partial file. Neither
currently certifies the full excluded-ID domain. Db2Reader reads the clear WDC4/5
keyed-ID prefix, rejects duplicates across protected/listed IDs and conflicts with
readable physical/copy IDs, but does not require each hidden list's count to cover
all declared hidden records/copies. WDC3 has no equivalent keyed-ID prefix. Therefore
reusing existing Found or protectedIds alone would silently weaken qualification.

## Narrow new capability

Add optional CapturedRows.inspectNativeIdDomain(long selectedId, Cancellation),
default UNSUPPORTED without invoking ordinary reads. It is implemented only on the
actual client-owned CapturedRowsHandle, through a private ClientBuildTables inspector
bound to that held native graph. No new generated graph, repair, table preparation,
whole-row decode or definition fetch occurs. The legacy UNKNOWN/native-unverified
capture has no inspector. A custom wrapper must explicitly support the capability;
a genuine-looking receipt alone does not enable it.

NativeIdDomainTypes.State is READABLE_UNIQUE, PROTECTED_ID, NOT_OBSERVED,
UNSUPPORTED_DOMAIN, SOURCE_CHANGED, UNAVAILABLE, LIMIT_EXCEEDED or CANCELED.
Only READABLE_UNIQUE has a witness. Its public sealed witness interface permits one
client-native implementation with a PRIVATE constructor; no caller DTO/factory can
mint a positive. The detached witness contains selected unsigned ID/readable ordinal,
complete excluded-ID digest/count, producer/profile revision, actual Map FDID/CK/
consumed length/layout and a bounded canonical fingerprint of the exact held capture
receipt (including graph, definition/block, reader/options/encoding, schema/columns,
header/completion/sections, issue evidence, keys and holes). No source/table/capture
handle, service, raw bytes, ID map or definition object escapes in the witness.
A shared bounded receipt-fingerprint helper validates/counts strings BEFORE hashing;
no toString/whole-object serialization. Two registry witnesses remain within the
existing2KiB-per-proof scalar/header allowance.

The actual inspector resolves the existing local selected definition, verifies its
native GeneratedTableSource against the held graph, freezes/rechecks current key
fingerprints and opens the actual numeric Map source through the existing Db2Reader
structural parser with a LOWER fixed inspection profile. It compares actual header,
sections and structural completion with the held capture, and final FDID/CK/known
length plus definition/key identity before delivery. It never trusts a caller file
ID, WDT value, definition or claimed domain. Receipt matching is explicit at the
consumer using the shared fingerprint; a witness for another capture is rejected.

Add a metadata-only Db2Table ID-domain observation: no find/decode of row bodies.
Its new complete-excluded flag comes from actual parser facts: WDC4/5 only, zero
copies in EVERY section/header/completion, every hidden encrypted section's clear
unique ID list has EXACTLY its declared physical-record count, and the existing
readable/protected duplicate checks all passed. Every excluded ID is therefore
explicitly inventoried. The selected ID must occur in the readable native index,
never in the excluded inventory; its ordinal must match the captured selected row.
An incomplete inventory yields UNSUPPORTED_DOMAIN, never inferred absence/uniqueness.
A selected excluded ID yields PROTECTED_ID without values. A missing ID remains
NOT_OBSERVED; there is no complete catalog/hidden-row value or membership claim.
WDC3/copy-bearing/unknown profiles remain unsupported for this partial-only path.

## Bounds and lifetime

One inspection per capture, exclusive with its ordinal reads; busy rejects rather
than queueing another inspector. Fixed source maximum1MiB, declared physical rows256,
fields/columns128, sections16, record64KiB, auxiliary64KiB, strings64KiB, index charge
512KiB, relationship64KiB/256 entries, no copy traversal. Policies affecting numeric
interpretation remain identical to the captured configured reader; only allocation/
count ceilings are lowered. Existing configured readerOptions identity is checked
separately and never recomputed as though lower inspection caps changed provenance.

The new phase is bounded by the already named4MiB Map-validation allowance and is
SEQUENTIAL with captured row decoding/WDT parsing:1MiB actual source/projection,
1MiB conservative range/adapter allowance,1MiB conservative boxed/index/storage
allowance,512KiB auxiliary/descriptors/relations,512KiB control/receipt hashing.
No full input duplication, row values or new generated-reader metadata owner exists.
Definition provider and the already-open captured graph retain their existing,
separately named subsystem allowance; this is not a whole-process48MiB claim.
Actual per-read byte/count reservations and index-limit failure precede allocations;
no hash input escapes the bounded prehash profile. Native indexes and source handles
retire before inspector completion. Source close/cancel uses one coalesced off-caller
cancellation lane, keeps capture active through source/lower callback drain and
completes outside monitors; rejected/stale results retain no inspector closure.

## Scope admission and revalidation

Keep complete READY path unchanged. PARTIAL may enter ONLY the recognized encrypted-
section profile with verified native issue provenance, consistent captured row/
completion/section/key/hole counts and this matching private witness. First read the
complete contiguous bounded VISIBLE ordinal domain, reject duplicate visible selected
IDs, require its existing qualified numeric WdtFileDataID and clean issue channel,
then inspect the selected native ID after row-read drain. Require witness ordinal/
ID/receipt fingerprint equality; unknown/default capability cannot qualify.

The private MapBinding names OBSERVED_SELECTED_ROW authority; it does not certify
all rows readable, all maps listed or hidden row values. Scope digest includes this
mode, selected-ID witness digest/profile and the existing COMPLETE canonical capture
stamp (all holes/completion/key scope retained). Initial absence/protected/unknown
states reject without guessing a MapInfo WDT. WDT is still freshly read/checked and
all eight MAID refs are exact. Once a private proof exists, the approved fresh native
capture/stamp equality plus bounded EOF probe can revalidate it without reparsing
unchanged ID bytes: a changed source/definition/key/reader/partial scope invalidates
rather than reusing the old witness. No repeated whole Map decode or unbounded cache.

## Causal fixtures before promotion

Author a genuine WDC5 two-section Map through Db2Reader→nativeDb2→GeneratedTables→
ClientBuildTables capture: readable requested row plus protected other IDs. Prove
admission with its actual witness and unchanged legacy membership NOT_CERTIFIED.
Missing/truncated hidden inventory, selected protected/conflicting ID, duplicate
visible selected ID, WDC3, any copies, unknown/unsupported witness, foreign/wrong
ordinal/capture witness, changed CK/definition/key/holes/header and limits must refuse.
No scalar issue/nonzero WDT alone may supply the missing ID-domain proof. Hold source/
cancellation/close, confirm active ownership drains and no new source I/O starts;
no graph mutation/discard/repair on unsupported/limit. Finally rerun the actual Map0
read-only scope/root proof before claiming the real partial gate is resolved.
