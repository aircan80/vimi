# Pure Maps navigation checkpoint

This checkpoint adds no UI, source acquisition, image decoding, quest icons or
new dungeon-map data. It implements the immutable model/API portion of
[maps-explorer-proposal.md](maps-explorer-proposal.md) in `concepts-locations`.
Existing `BuildLocationMaps`, `LocationMapsService` and sidebar callers are unchanged.

## API surface

`MapNavigationTypes` separates `UiMapId` from `WorldMapId`; client Map0 is valid,
but UiMap0 is not. Nodes preserve optional name/type/system/flags/visibility facts,
a typed unknown/root/known parent, and a row/table identity. `Scope` binds the
selected build/locale and exact map/assignment snapshots. Qualified row projection
remains the source owner's job: these DTOs are descriptive, not authentication or
an approval of additional schema layouts.

`MapNavigationGraph(scope, nodes, assignments, canceled)` owns detached immutable
indices. Construction belongs on a bounded worker; caller collections must remain
stable during construction. Graph scope admits the existing DB51D55F UiMap and
C9CC8DFB assignment profiles. Unsupported assignment tables can retain map-name
navigation with explicit unavailable hit geometry. Duplicate/mixed-snapshot rows
are rejected. Names have ID fallbacks; unknown fields do not fabricate values.

`hierarchy(id)` returns a bounded recorded path and precise COMPLETE,
UNKNOWN_PARENT, MISSING_PARENT, CYCLE, TOO_DEEP, MISSING_SYSTEM or SYSTEM_MISMATCH
state. Missing system fields remain unknown; only unequal known fields establish a
mismatch. Region diagnostics preserve that same distinction. Up is
unavailable for any incomplete/invalid parent chain; direct known-map visits and
visit-history Back still work. No geographic containment changes a recorded parent.

`regions(parentId, pairLimit, canceled)` returns child choices, rectangles and both
assignment row identities. The first profile requires equal client Map IDs,
known equal systems, unscoped assignments, full child UI extent and supported
flags. Only observed non-frame bits0x902 are allowed; NoWorldPositions0x80,
unknown fields and other bits refuse derived geometry while preserving choices.
World X/Y domains are intersected before reversed-axis projection through the
parent inset. This is source-based rectangular navigation, not a geographic
polygon or a claim to match native client hit testing. Visibility/character
conditions are retained but unevaluated.

`Regions.hit(x,y)` returns NONE, UNIQUE or AMBIGUOUS choices, preserving all hit
assignment evidence. Two assignments for the same child do not produce duplicate
chooser entries. Distinct overlapping children are not resolved by nearest area,
size or input order. Rectangle edges are half-open except the outer image's1edge.

`MapVisitHistory` is immutable and keeps at most64visits. Entries contain only a
UiMap ID and normalized pan/zoom state. Back/Forward follow visits; Up follows the
recorded hierarchy; direct switching clears forward visits. Pan/zoom updates the
current visit without inserting a new history entry. There are no image or graph
references per visit; the history owner borrows one graph.

`MapResourceEvidence` keeps artwork-manifest, actual-image and floor outcomes
separate. A ready manifest does not mean image bytes have loaded. Unassessed,
unavailable, partial, unsupported, ambiguous and no-recorded-data states differ.
NONE_RECORDED requires complete positive relationship-table evidence; it cannot
be inferred from map type/name/null, partial group rows or absent image reads.
No-recorded-images is deliberately not an allowed inference. Actual file outcomes
can retain a FileDataID/content key and NOT_LOCAL/PROTECTED/etc state without pixels.
The backend must qualify and stably scan those sources before creating these facts.

## Bounds and remaining gates

Catalogue ceilings are8192nodes and16384assignments, checked before list copies.
A conservative16MiB logical metadata charge (1MiB base plus bounded per-node/name
and assignment charges) is checked before owned indices. Counts are ceilings,
not a promise every maximum-sized input fits: excess bytes refuse the candidate
rather than silently truncating roots. This is a logical component budget, not a
process heap/RSS measurement; source parsing and eventual UI snapshots remain
separate ownership ledgers.

A requested page charges every tested pair, including mismatched Map IDs, against
an explicit1..65536allowance. Regions are capped at16384. Work/region exhaustion
returns UNAVAILABLE with **no** partial hit rectangles, preserving the child list;
it cannot favor whichever child happened to finish first. Structural unsupported
frames remain explicit LIMITED results. Cancellation is checked during construction
and pair traversal. Hierarchies are capped at64; resource evidence lists cap tables
at8and files at256. No worker thread, future, cache, file or HTTP handle is created.

Fourteen authored tests cover typed IDs/Map0, Back versus Up/direct switch, bounded
history/pan, missing/cyclic/deep/cross-system parents, signed asymmetric/inset and
clipped projection, overlap/evidence, WMO/profile refusal, partial inputs,
no-biased-work/region-budget refusal, immutable snapshots/count/byte/cancellation gates,
and the distinction between ready manifests, unavailable images and empty floors.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :concepts-locations:check
```

The local module check passes. Before UI wiring, independently review these APIs,
then implement the source facade's qualified table reads and actual selected-build
projection proof. New link/floor source profiles and final artwork alignment still
need their separate gates. This checkpoint neither invents dungeon maps for the
inspected Forever build nor expands existing artwork decoders or image budgets.
