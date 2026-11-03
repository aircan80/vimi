# Comparison inventory view

![Authored inventory comparison](../images/comparison-authored.png)

`CompareView` is an authored standalone UI for the independently approved
Foundation/inventory scope recorded in [review.md](review.md). Composition supplies
an enabled-product-filtered build catalogue, explicit locales, a controller and a
close callback. The view opens no repository, database, downloader or build job.

`Controller.start(Request, Cancellation)` is called only by **Compare local data**.
Requests contain exact `BuildInfo` endpoints, locales and Files/Tables area. The
controller returns a live session facade exposing canonical `ComparisonTypes`
descriptors, progress and result pages plus cancellation/asynchronous close.
Composition owns dedicated local-only input contexts and source capture policy.
It must drain canceled openings and any sessions it owns on navigation/shutdown.
The view cancels its opening token, disposes stale returned sessions and cancels/
closes accepted sessions; it never touches the separately browsed build.

All current areas are present. Only Files and Tables request work; other areas
say Not implemented. The first release compares selected-locale file metadata
and frozen table inventory, not arbitrary table rows or semantic concepts. No
schema-complete claim is made. Summary retains session/area/coverage context and
has no fabricated full-build change totals. The default scan stages findings only: known-equal records are omitted, while
changed, membership-only, unknown and incomparable observations remain. Scanned
identity progress is not a finding count. Search and status filters explicitly
apply to the current findings page; pagination follows the findings continuation.
There is no All records or Unchanged listing in this initial slice. Unknown states,
observed-only membership, field evidence dimensions and per-side completeness
remain visible. The source inspector exposes the captured descriptor dependencies.

Before/After selectors reject product/locale mismatch locally. Opening the view,
changing selectors, swapping, selecting areas/results and resizing never start a
comparison or acquisition. A replacement run cancels and drains its accepted old
session before opening another. At narrow widths the inspector becomes a Details
route with Back to results; the area rail becomes a labeled selector. Endpoint
controls wrap. Closing stops progress polling and is idempotent.

The authored `CompareUiTest` verifies explicit start, unsupported-area labeling,
locale rejection, typed partial evidence, responsive details and session lifetime.
It exports `gui/build/ui-review/comparison-authored.png`; all fixtures are invented
and contain no game assets. GUI Gradle wiring belongs to application composition;
register a display-required JavaExec task for `com.vimi.gui.CompareUiTest` with the
existing GUI test runtime classpath. This document does not claim durable results,
resume, export, media comparison or full #117 completion.
