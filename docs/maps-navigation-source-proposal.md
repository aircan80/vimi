# Maps source facade checkpoint

Status: proposal for review; no facade or UI implementation. Builds on the approved pure navigation model and `maps-explorer-proposal.md`.

## Optional API and compatibility

Keep all existing `BuildLocationMaps` constructors, abstract methods, and custom providers working. Add optional default methods returning a completed explicit `UNSUPPORTED` result. Do not add abstract methods or change old artwork/tile semantics. Proposed signatures (names subject to review):

```java
CompletableFuture<NavigationResult> navigation(Cancellation cancellation);
CompletableFuture<NavigationPage> navigationPage(NavigationPageRequest request,
                                                Cancellation cancellation);
CompletableFuture<NavigationTile> navigationTile(NavigationTileRequest request,
                                                Cancellation cancellation);
```

`NavigationResult` contains an explicit state, optional immutable `MapNavigationGraph`, source stamp, and at most 64 bounded diagnostics. States distinguish READY, PARTIAL, UNAVAILABLE, UNSUPPORTED, STALE, and LIMIT_EXCEEDED. Assignment failure may leave a usable names/hierarchy graph; it must not imply usable click geometry. Callers retain existing progress/activity mechanisms around futures; metadata, manifest, and image phases remain separately visible.

`NavigationPageRequest` carries a strong `UiMapId` and expected navigation stamp. `NavigationPage` carries the selected node, hierarchy, `Regions` including its completeness state, selected-page artwork manifest evidence, and floor evidence. A hit wrapper carries both `Regions.state` and `Hit`: NONE or UNIQUE among available rectangles must never imply complete geographic coverage when the page is LIMITED or UNAVAILABLE. Direct switching to a known map remains possible in those states.

`NavigationTileRequest` carries the expected page/artwork stamp and tile row ID. Its result wraps the existing decoded tile with file identity/evidence and the validated stamp. Neither navigation nor page metadata reads image bodies. A READY manifest means the route is recorded, while image evidence starts NOT_CHECKED. Pixels remain outside the graph and history.

## Source qualification and snapshot identity

The first graph profile admits exactly the reviewed UiMap DB51D55F and UiMapAssignment C9CC8DFB layouts. Reuse `MapTable` field-kind/physical-column checks and verify declared foreign keys. Never turn absent ParentUiMapID into a root, absent System into system zero, or an unknown flag value into supported geometry. Invalid required assignments produce typed incomplete evidence; they do not invent coordinates.

A facade `SourceStamp` augments the pure model's CK/row provenance with build, locale, profile revision, and bounded per-table descriptors: table identity, content key, layout, readiness, counts, prepared page identity, and a canonical schema/column fingerprint. Hash explicitly framed canonical fields, not Java `toString()` or `hashCode()`. The inspected prepared identities are 32 hex characters; enforce the actual identity contract rather than silently truncating future formats. Keep the full bounded stamp or its collision-resistant digest in requests; never substitute UiMap ID alone for snapshot identity.

Read fresh schema/entry descriptors before scanning. Stream pages of 256 into bounded projected DTOs, checking each page's source descriptors and stable identity, and forward cursor progress. Check final schema equality and revalidate **all** participating dependency stamps after the last table has been read. A source or schema change discards the candidate result as STALE; no positive cache entry is published. Per-row evidence preserves exact table CK and row ID. No private library paths belong in diagnostics.

Factor an internal streaming scan primitive from `MapTable.rows` if needed, preserving its old collector for compatibility. Do not simultaneously retain all raw rows and a second projected navigation index merely to build the new graph.

## Scheduling, cancellation, and ownership

Use `LocationMapsService`'s existing single bounded worker/queue and lifetime token. All scanning, projection, manifest resolution, source validation, and decoding stay off JavaFX. Cancellation is checked at page, row, pair, and decode boundaries; canceled or closing work cannot publish a positive result. Preserve asynchronous completion off the owned worker and explicit queue rejection.

Page acquisition must call a synchronous internal artwork helper on that worker, never enqueue `artwork()` and join its future from the same worker. Reuse the current BLP decoder and generated cache. The service borrows tables/files/generated store; close drains its own work before the workspace closes those dependencies.

Keep at most one latest positive navigation snapshot per handle, keyed by the full validated stamp. Probe freshness before reuse. Existing schema/raw-row/artwork caches cannot certify freshness by table name alone: invalidate dependent cached metadata when descriptors, schema, or page identities change; clear mapRows when UiMap changes. Bound the existing artwork cache with a 16-entry LRU. Final cross-table validation remains necessary even on cache hits.

The graph retains its existing 8,192-node, 16,384-assignment, 16 MiB logical-charge, pair-work, and region limits. Refuse exhausted work instead of publishing a biased truncated hit set. A builder retains one raw page plus bounded DTOs. The 16 MiB charge is not a whole-process heap guarantee: old caller-held snapshots, pending replacement snapshots, decoder buffers, prepared tables, and images need separate accounting. Future UI wiring must own a bounded current/pending image ledger; history remains IDs plus small view state only.

## Artwork and floors

Reuse the recorded UiMapXMapArt → UiMapArt → UiMapArtStyleLayer/UiMapArtTile route, the existing explicit phase-zero/base-layer selection rules, dimension/tile limits, and FileDataIDs. No guessed filenames or nearest-art selection. Report “no recorded phase-zero base artwork” only from complete evidence; this is not a claim that no image exists anywhere in the build.

The strict tile endpoint must validate file ID/content key and artwork dependencies before and after acquisition, attaching that evidence to the result. The old `TileImage` decoder result alone does not expose file CK and cannot establish a new facade snapshot. Stale requests must be rejected before decoding or downloading where detectable. Reuse decoder code; do not introduce another image pipeline.

UiMapLink, UiMapGroup, and UiMapGroupMember are not yet admitted by the current MapTable layout allowlist. Their inspected empty tables establish build-specific absence, not positive support for arbitrary group/floor layouts. Stage explicit floor/link profile qualification separately, with reviewed foreign keys, ordering/flags, positive authored fixtures, and a real positive-build proof before enabling floor selection. Complete empty supported group/member sources can report NONE_RECORDED; unreadable/partial/unsupported sources cannot. Never infer floors from height, terrain IDs, names, or proximity. Parent hierarchy and group/floor relationships remain distinct.

## Readonly selected-build proof

A private local-only executable projected the selected wowf 1.60.1.70755 enUS build into the approved graph. It opened the library read-only, used no network source, wrote generated scratch data only beneath a temporary directory, and read no image bodies. No game assets or private proof output are committed.

- UiMap: layout DB51D55F, READY 60/60 rows, CK `521ec5030f69b979d9cc21ed2ba23132`.
- UiMapAssignment: layout C9CC8DFB, READY 61/61 rows, CK `47db4b120a379ad5e94743e43e0b7933`.
- Graph: 60 nodes, 61 assignments, logical charge 1,159,756 bytes.
- Azeroth 947 → Eastern Kingdoms 1415 → Elwynn 1429 hierarchy passed; direct-visit Back and recorded-parent Up differ correctly.
- All 28 derived rectangles across Azeroth and Eastern Kingdoms matched an independent 50-decimal BigDecimal source-string oracle within 1e-12.
- Azeroth is LIMITED: three recorded children, two usable continent rectangles. Zephras remains selectable directly but has no falsely projected hotspot across a different world-map domain. Eastern Kingdoms has 26 children/26 rectangles and READY geometry.
- Elwynn center uniquely identifies Elwynn. Stormwind center overlaps Elwynn and Stormwind and returns both choices; this is concrete justification for the ambiguity chooser.
- The inspected Group and GroupMember tables contain zero rows, and UiMap contains no dungeon/micro Type 4/5 entries. This supported route supplies no recorded dungeon floors for this build; it does not prove dungeon art is absent from all assets.

This proves source projection and geometry consistency, not visual pixel alignment. Image composition/alignment, positive dungeon-floor data, cache freshness under mutation, and future GUI behavior still require their own tests.

## Implementation gates

1. Review this optional API/source-stamp contract, then implement bounded projection/freshness and authored tests. Cover schema/CK/page-identity changes before and after scanning, malformed optional fields, cap-before-copy, unsupported assignments preserving direct choices, queue rejection, cancellation, and borrowed-dependency close ordering.
2. Add selected-page manifest and strict tile wrappers with stale-stamp, file-change, cache eviction, protected/missing image, and generated-cache restore fixtures. Keep manifest/image activity distinct. Qualify positive floor/link profiles separately.
3. Only after source review and image alignment proof, wire the Maps UI with breadcrumbs, Back/Forward, Up, search/direct switch, overlap choices, and activity states. No quest icons in this slice. Future entity overlays must carry explicit source coordinate-system provenance and validated transforms; do not equate UiMap IDs with terrain Map IDs.
