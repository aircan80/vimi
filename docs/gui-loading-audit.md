# GUI loading and FX-thread audit

Reviewed 2026-11-03, through `949164e`, with the inline GPU renderer integration
still undergoing its final native/GUI checks. This is a source and authored-fixture
audit, not a latency profile. A returned future does not prove its caller or
completion callback is cheap. No blocking I/O or `join()` on the FX thread was
confirmed in the reviewed paths; that is not a guarantee that the UI never stalls.

## Implemented during this review

| Area and source symbols | Implemented behavior and evidence |
| --- | --- |
| [ResearchBrowser](../gui/src/main/java/com/vimi/gui/ResearchBrowser.java): `load`, `select`, `showTableRows`, `loadMedia`, `icon` | Files, Tables, Items and Creatures show centered initial loading, retain rows with inline updating feedback, and show local detail/table/media activity. Icons distinguish pending/unavailable; bounded background conversion precedes bulk FX upload. Authored delayed/error/close checks: [ResearchBrowserUiTest](../gui/src/test/java/com/vimi/gui/ResearchBrowserUiTest.java). Integrated in `7ae21bf`. |
| [ProviderBrowser](../gui/src/main/java/com/vimi/gui/ProviderBrowser.java): `beginLoad`, search and load completion | NPC/Quest initial loading is local; refresh retains rows. Catalog and large-search work use bounded workers; stale generations retire. [ProviderBrowserUiTest](../gui/src/test/java/com/vimi/gui/ProviderBrowserUiTest.java) exercises delayed loads, stale results, close and large search. Integrated in `7ae21bf`. |
| [RacesView](../gui/src/main/java/com/vimi/gui/RacesView.java): `ensureLoaded`, `select`, `loadIcon`; [RaceModelPanel](../gui/src/main/java/com/vimi/gui/RaceModelPanel.java): constructor, `load`, `loadAppearance` | Catalog/list, overview and racial rail show local activity; errors clear pending states. Variant and mesh acquisition call the preview's central loading helper; appearance has inline activity. Icons expose pending/unavailable states and convert on a bounded worker. [RacesUiTest](../gui/src/test/java/com/vimi/gui/RacesUiTest.java) gates catalog, variants, mesh failure and appearance completion. Integrated in `924f53f`. |
| [ResearchBrowser](../gui/src/main/java/com/vimi/gui/ResearchBrowser.java): `load`, `pagingQueryMatches` | Page history commits only after current-generation success. Failed Next/Previous and same-query refresh preserve history; failed query resets cannot reuse old cursors. Three-page async/synchronous failure and stale-reset checks in `ResearchBrowserUiTest.paginationFailures`. Integrated in `d9d2938`. |
| [ModelPreviewView](../gui/src/main/java/com/vimi/gui/ModelPreviewView.java): `loading`, `request`, `deliverGpu`; [GpuPreviewService](../gui/src/main/java/com/vimi/gui/GpuPreviewService.java) | Shared preview has central asset/render activity and generation/lifetime handling. Automatic **inline** GPU presentation is the selected design; its renderer/process bridge is a separate integration and needs its own first-frame, stale-frame and cleanup evidence. Race acquisition feedback above does not establish native backend readiness. |

The browser/race authored FX harnesses and `:gui:check` passed during these changes.
Those checks use synthetic data, not a live build or every renderer/driver. They do
not cover all outstanding areas below.

## Remaining work found by the audit

“Text only” means a loading message exists but no animated local activity indicator.
“FX CPU” identifies work visible in the FX completion path; no stall duration has
been measured. Preserve readable content and accurate unknown/unavailable wording
when adding feedback; use determinate progress only when a real total exists.

| Area and source symbols | Remaining feedback or responsiveness issue |
| --- | --- |
| [TalentsView](../gui/src/main/java/com/vimi/gui/TalentsView.java): `ensureLoaded`, `loadClass`, `select`, `loadImage` | Catalog/tree/description loads are text only. Icon placeholders do not distinguish pending from failed. **FX CPU:** RGBA conversion loops in `loadImage` completion. |
| [MountsView](../gui/src/main/java/com/vimi/gui/MountsView.java): `ensureLoaded`, `select`, `preview` | Catalog/details/model asset acquisition remains text only (`model.clear` during acquisition). Shared preview rendering activity does not cover this preceding decode/load interval. |
| [ProfessionsView](../gui/src/main/java/com/vimi/gui/ProfessionsView.java): `ensureLoaded`, `loadAbilities`, `loadDetail`, `render`, `itemLabel` | Catalog/abilities/details are text only. Icons can remain blank; optional item-name resolution silently replaces an item-ID label. **FX CPU:** per-pixel icon conversion in `render` completion. |
| [DungeonsView](../gui/src/main/java/com/vimi/gui/DungeonsView.java): `ensureLoaded`, `select`, `encounterCell` | Catalog/details are text only; encounter icons lack pending/failure distinction. **FX CPU:** icon pixel conversion in encounter-cell completion. |
| [WorldView](../gui/src/main/java/com/vimi/gui/WorldView.java): `ensureLoaded`, `selectMap`, `loadTile`, `loadNext`, `rebuildScene`, `requestFrame` | Catalog/inventory/initial tile use text only. Nearby streaming relies on `loadingTile`; first-frame rendering lacks activity. **FX CPU:** initial tile bounds scan vertices twice; `rebuildScene` constructs [TerrainRenderer.Scene](../rendering-world/src/main/java/com/vibeminer/rendering/world/TerrainRenderer.java), which clones vertex/index arrays for up to nine tiles. Move preparation off FX before judging runtime improvement. |
| [LocationMapView](../gui/src/main/java/com/vimi/gui/LocationMapView.java): `select`, `loadArtwork`, `loadTile` | Artwork metadata is text only; the art description replaces loading text before sequential tiles finish. Preserve markers while showing tile activity; a known tile total can support `n/N` feedback. **FX CPU:** RGBA/BGRA conversion in tile completion. |
| [RaceVoicePanel](../gui/src/main/java/com/vimi/gui/RaceVoicePanel.java): `selection`, `play` | Voice catalog/decode is text only. “Playing” appears before the native audio line actually starts; distinguish preparation from playback. |
| [CompareView](../gui/src/main/java/com/vimi/gui/CompareView.java): `start`, `refreshProgress`, `loadPage` | Opening/scanning has textual counts without animated activity. Findings paging disables controls without a page-local busy state. |
| [VimiApp](../gui/src/main/java/com/vimi/gui/VimiApp.java): `leaveComparison`, `closePreparedBuild`, `inspect`, `showOpenedBuild` | Comparison/build drains disable the root without visible activity. Selected-build accounting and loaded-build metrics refresh silently. These are feedback gaps, not confirmed synchronous drains. |
| [SettingsView](../gui/src/main/java/com/vimi/gui/SettingsView.java): `command`; [QuestieMappingControls](../gui/src/main/java/com/vimi/gui/QuestieMappingControls.java): flavor action handler | Settings says saving without animation. Per-build mapping disables its combo without a saving label. |

## Existing feedback retained

Build catalog/history/opening already have progress in
[VimiApp](../gui/src/main/java/com/vimi/gui/VimiApp.java) (`showCatalogActivity`,
`loadBuilds`, opening activity), and preparation/overview have progress in
[PreparationPane](../gui/src/main/java/com/vimi/gui/PreparationPane.java) (`update`)
and [BuildOverviewView](../gui/src/main/java/com/vimi/gui/BuildOverviewView.java)
(`update`). [DataSourcesView](../gui/src/main/java/com/vimi/gui/DataSourcesView.java)
(`command`, `tick`) and
[SourceRefreshDialog](../gui/src/main/java/com/vimi/gui/SourceRefreshDialog.java)
(`render`) also expose operation progress. Existing feedback does not establish
that every sub-operation or completion callback is responsive.
