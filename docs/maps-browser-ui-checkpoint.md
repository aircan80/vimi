# Hierarchical Maps browser UI checkpoint

The new Maps tab uses selected-build UiMap hierarchy with Back, Up, breadcrumbs,
a direct map picker, child list and recorded assignment-rectangle hotspots.
Overlapping hits ask the user to select among the actual alternatives. Wheel zoom,
drag pan and Fit retain small view state in the bounded visit history. Rectangles
are explicitly source assignments, not polygons or proof of current player phase.

One source worker retains at most one active and one replaceable metadata request.
Cancellation runs on one separate lane; the active operation drains any issued
lower cancellation before releasing its slot. No source callbacks, navigation,
artwork acquisition, decode or pixel conversion run on FX. FX owns controls,
canvas presentation and one final composite upload. The browser borrows its
BuildLocationMaps/BuildTables; it never closes them. Graph and image publication
check generation, exact handle and workspace revision; hierarchy reload retires
old interactive controls before issuing new work.

Hierarchy activity remains visible through artwork completion. Each known tile
updates completed/total status. The worker composes a bounded map snapshot, then
publishes it atomically. At most32tiles and2048×2048 composite pixels are shown;
unsupported/missing tiles retain a visible background and readable counts. The
single-tile decoder remains separately bounded by the existing service. Artwork
must match map ID/build/locale; each image must be READY with its exact requested
tile descriptor. No guessed asset substitutes for missing artwork.

Prepare map tables is an explicit user action for UiMap/UiMapAssignment native
evidence. Its tooltip/status explains configured source access may download.
It uses the reviewed native-preparation backend, keeps progress truthful, then
reloads strict navigation. Merely opening or capturing navigation never upgrades
legacy graphs. Source details retain partial/unsupported diagnostics. Dungeon
floors, marker overlays and cross-phase evaluation are not implemented here.

Authored display test `:gui:mapsBrowserUiTest` checks hierarchy/texture activity,
accepted pixels, direct selection/Up/Back, same-revision handle replacement,
retired-result activity cleanup, wrong-tile rejection and a deliberately held
lower cancellation without blocking FX. Fixtures do not authenticate sources.
The test requires a display; game assets and user settings are unnecessary.
Independent UI review and an actual selected-build browse proof remain pending.

Independent UI implementation review approves554a574; defensive table-owner
retirement10f48a2 follows. Isolated authored UI proof passes91tasks, full GUI check
passes124tasks. Source bindingf7162bc and bounded schema-comment followup47fb1ba
also have independent approval; the latter admits an actual434-character pinned
UiMap.Flags comment under unchanged64KiB aggregate metadata limits.

Actual selected wowf1.60.1.70755 enUS GUI proof on frozen06a3b89 passes:
Azeroth947→Eastern Kingdoms1415→Elwynn1429, each1002×668 artwork and12/12 readable
tiles, varied actual pixels, activity held through actual tile completion, Up to
1415 versus Back to1429, direct947/direct1429 and clean close. The final fresh
scratch generated overlay retained legacy UNKNOWN UiMap/Assignment captures
unchanged while explicit native preparation produced verified new graphs. Across
the route84 actual artwork tile calls completed. Original library handles were
read-only; verified normal acquisition downloaded missing recorded artwork ONLY
into private scratch and generated writes stayed in the overlay. This proof
allowed network access; the first local-only attempt honestly showed0/12 missing
artwork. Private proof and screenshots remain `/tmp/vimi-maps-live-ui-proof/`;
`proof-final.log` exits successfully. No assets or images are committed.
