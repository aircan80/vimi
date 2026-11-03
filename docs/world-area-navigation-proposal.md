# Verified area-to-terrain navigation

Status: concrete first-slice design for independent review, before implementation.
This is a separate roadmap/release boundary from GPU terrain.

## Source and UX contract

Use recorded MCNK area IDs in the already admitted split-root ADT18 profile.
The pinned primary reference [wow.export ADTLoader](https://github.com/Kruithne/wow.export/blob/4474fa460eb9b1b6130994a42a232ecd6af64681/src/js/3D/loaders/ADTLoader.js#L78-L107)
reads unsigned areaID at MCNK header offset52, after sizeShadows and before
nMapObjRefs. This is not a zone bounding box or a texture/minimap coordinate.
Existing filename tuple and screen grid mappings remain unchanged.

Selecting an area alone does not acquire terrain. An explicit **Find terrain**
action explains the inventory file count and that missing source files may be
downloaded through the existing file service. It checks that map's WDT root ADTs
sequentially, showing checked/total, matches, unavailable files and Cancel.
Failed files count as checked and unavailable. No invented overall byte/time percentage. Existing source authentication/storage
ownership remains in BuildFiles. No third-party map/provider or guessed paths
beyond the already admitted WDT ID/directory reference policy.

First slice matches the selected area's exact unsigned ID only. Child areas are
not implicitly included: parent-table completeness and zone hierarchy are separate
facts. Explain this in the UI and retain area ID/name. Area0 means unassigned and
never matches an area selection. Do not infer a zone's center from tile indices.

Matching inventory tiles receive a distinct outline while the full continent
remains visible. Present matching tiles as explicit choices; selecting a match
sets the existing starting tile. **Open explorer** uses a source height reference
inside the matching chunk, with the existing altitude offset and camera orientation.
The raw MCNK origin Z is only a height base. Derive the entry reference from
MCVT sample72 (row8/column4): X=px−TILE_SIZE/32, Y=py−TILE_SIZE/32,
Z=pz+height[72], then add the existing camera altitude offset. Retain raw origin
separately. A hole may occupy that point; this is free flight, not walkable-ground
or collision certification. Never silently jump to a nonmatching tile center.
A tile can contain multiple areas. No matches with failures/cancellation means
not found in the checked readable subset, never proof of absence.

## Parser and API boundary

Add immutable `WorldFormats.AreaChunk(x,y,areaId,origin,entryReference)` and
`AreaMetadata(List<AreaChunk>)`, with at most256 unique chunk coordinates, unsigned
32-bit area IDs and existing finite native coordinate bounds. Expose
`WorldFormats.adtAreas(byte[], BooleanSupplier)`. Refactor a shared bounded
ADT18 chunk-header walker so geometry and metadata do not diverge on version,
chunk bounds/duplicates or header offsets. Metadata admission still requires the
existing complete supported chunk layout and one valid145-float MCVT per MCNK;
validate all145 height samples and derived native positions as finite/within the
existing native coordinate bound, even though it skips geometry. It must not
accept a broader malformed format merely because it skips geometry.
No geometry arrays are allocated for metadata. Preserve Terrain constructors and
all current public APIs; no renderer representation change.

Introduce a dedicated `WorldAreaNavigation` helper over borrowed BuildFiles,
independent of BuildWorld implementations and renderer ownership. Its immutable
`Request` contains selected MapInfo, selected areaID and inventory tile list.
Its `Snapshot` contains state, checked/total counts, bounded tile matches,
unavailable count and bounded diagnostic samples. Each `Match` retains exact
Tile tuple/root reference, source content key, matching-chunk count, and first
matching chunk raw origin/derived entry reference.
One request/worker and one pending latest request; a new request cancels and
drains the old owner before reading its own files, even when an old read stalls.
Show draining and never create another worker to bypass it. Close never closes BuildFiles.
The GUI's request generation additionally rejects stale map/area/workspace results.

Reuse/factor World's bounded file-byte read routine rather than duplicating
BuildFiles open/protected/truncated behavior. Read metadata under a captured stat
content key and verify source identity before publishing each match. Missing or
changed content key is unavailable evidence; do not cache an unbound result.
Do not change existing world's source acquisition policy or geometry cache keys.
This first slice is session-only, no persistent index or generated-object writes.

## Bounds and cancellation

At most4096 unique inventory tiles, checked in inventory order. One file at a time,
32MiB existing decoded source bound, at most256 area chunks, at most4096 matches
(one per tile), and32 diagnostic samples. Retain the first matching chunk in
source order as that tile's explicit entry point; show chunk coordinates and
match count, not a guessed average across disconnected areas. Do not retain
source arrays or all nonmatching chunk records after a tile completes. Avoid
per-progress full-list cloning: publish counts at most10Hz and immutable match
pages/list only on request or bounded delivery checkpoints. Queue admission and
source reads observe explicit cancellation; close drains the worker off FX.
A canceled job preserves already observed matches labeled incomplete, and can be
restarted explicitly. Selecting a new map/area clears old result presentation.
No automatic retries or background whole-continent scans.

## Required acceptance

- Authored ADT bytes pin offset52 vs neighboring fields, unsigned IDs, native
  positions, mixed-area tiles and matching chunk coordinates.
- Header/MCVT/version/duplicate/truncation/cancellation acceptance agrees with
  geometry parser; metadata must not allocate geometry or admit extra profiles.
- File fixtures cover unavailable/protected files, source-key change, failure
  isolation and exactly one read at a time with complete stream cleanup.
- No metadata/files/scene work on FX; delayed scan keeps local activity visible,
  Cancel/map/area/source retirement cannot publish stale matches, and borrowed
  BuildFiles remains open after helper close.
- Asymmetric inventory proves highlight, click, exact root reference and native
  entry position agree without undoing the corrected continent orientation.
- Cold/no-match/partial/canceled outcomes never claim complete zone coverage.
- Independent review and standard checks precede integration; one separate GitHub
  prerelease follows successful packaged checks.

Review: acceptance approved e42a347 with explicit implementation gates above.
Domain required deriving entry height from MCVT rather than raw originZ; corrected
here before implementation. Await corrected entry-reference approval.
