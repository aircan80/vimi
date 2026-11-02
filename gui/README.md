# Vimi JavaFX application

The first connected screen is **Builds**: offline history listing, explicit published
version refresh, product/status/search filters, selection and regional provenance.
Real opening uses the selected regional CDN configuration and the backend build
opener. Its popup offers Hide (continue in background) and Cancel opening. No
percentage is invented. Successful opens show authenticated metadata; later
Overview measurements and research screens remain unconnected. Download coverage
remains explicitly unmeasured.

The original approved design remains available with `--mock`, including preparation,
Overview, Files, Tables, Items and Creatures. Mock mode uses authored fixtures and
performs no backend I/O. See [handoff.md](handoff.md) for the design decisions.

## Build and run

Requires a full JDK 25 and the native SQLite setup documented in the root README.
JavaFX 25.0.1 and backend dependencies are pinned/locked. From the repository root:

```sh
./gradlew :gui:check :gui:installDist
./gradlew :gui:run
# Design preview; no local store or network requests:
./gradlew :gui:run --args='--mock'
# Optional local paths on Apple Silicon:
./gradlew :gui:run -PsqliteLibrary=/opt/homebrew/opt/sqlite/lib/libsqlite3.dylib -PdataDirectory=/absolute/path/to/vimi-data
```

Use `gradlew.bat` on Windows. The GUI is now a root subproject; JavaFX remains
confined to layer 7. The prior `gui/settings.gradle` has been removed.
`installDist` produces `gui/build/install/vimi-gui-prototype/`; its launcher uses
the native artifacts for the build machine's OS/architecture. For a direct launcher
use `JAVA_OPTS` to supply `-Dvibeminer.sqlite.library=/path/to/library` and
`-Dvimi.dataDirectory=/path/to/data` when needed.

Default data lives under `~/Library/Application Support/vimi` on macOS,
`%LOCALAPPDATA%/vimi` on Windows and `$XDG_DATA_HOME/vimi` (or
`~/.local/share/vimi`) on Linux. It contains `objects/` and the disposable
`catalog.sqlite` projection. No existing C# data is imported automatically.
Startup reads saved history without network requests; **Refresh builds** observes
published metadata and persists it through the existing backend. This does not
download game content, establish content availability or measure retention.
Refresh issues remain inspectable without discarding readable history.
An indeterminate activity indicator and a “Refreshing builds…” button label stay
visible while refresh is running. Loading saved history also shows activity; no
percentage or transfer progress is inferred from the catalog API.

Application startup, refresh and resource closure run off the JavaFX thread;
refreshes coalesce and shutdown cancels/drains work before exiting. The check task
runs the original fixture harness and deterministic backend adapter checks. Native
Windows/Linux GUI execution, responsive/accessibility certification and native
release packaging remain separate work.

The interactions below describe **design preview** mode.

## Interaction review

- Search product/version, filter All/Prepared/Current, and select rows using
  the mouse or arrow keys. Columns can be sorted.
- A filtered-out selection stays visible in the inspector with an explanation.
- Open the selected build; Choose build restores the list and filters.
- Compare a measured Retail overview with Beta's unknown-size overview.
- Switch download mode, leave the overview and reopen it: the choice stays
  with that exact mock build for the current process.
- Resize down to 1000×680. The overview and inspector scroll independently.

## Layout and future adapters

- `src/main/java/com/vimi/gui/VimiApp.java`: JavaFX screens and session state.
- `BuildInfo.java`: immutable presentation facts; null size means unknown.
- `BuildService.java`: asynchronous catalog/open seam. A real adapter must keep
  I/O off the JavaFX thread; completion dispatch already uses `Platform.runLater`.
- `MockBuildService.java`: deterministic in-memory fixtures, no persistence.
- `src/main/resources/vimi.css`: shared light/teal desktop styling.
- `src/test/java/com/vimi/gui/PrototypeTest.java`: dependency-free checks of
  opening identity, fixture immutability, and unknown versus unopened sizes.

`check` runs the mock-boundary checks and compiles with compiler warnings treated
as errors. Automated UI coverage, actual preparation, download activity, Settings,
production Files, Tables, Items and Creatures services remain outside this design pass. New real
services should adapt to presentation models rather than expose store types to
controls. Download mode is deliberately local UI state until its command boundary
is designed.

Build source distinguishes Current (advertised at the last check), Saved
(previously observed by VibeMiner), and Community (third-party history, never
observed by VibeMiner). The Community fixture uses an explicitly fictional
provider, unknown availability, and no verified source route; Open is disabled
with an explanation. Source filters include all three states.

## Build area navigation

The build shell has Overview, Files, Tables, Items and Creatures. The exact build
identity stays above the menu; Choose build returns to discovery. Tables and Creatures use `MockResearchData`; Files and Items have dedicated
fixture catalogs and virtualized grids with selected-record inspectors. Views preserve search and selection per build during the session.
Items/Creatures source links select the corresponding mock table. All names,
IDs and field values are authored samples; no game data is decoded or fetched.

## Overview landing page

`BuildOverviewView` renders the landing page independently of the application
shell. `MockOverviewSnapshot` owns full-scale illustrative research counts for
the Retail fixture, distinct from the curated browser sample lists. Other
fixtures retain unmeasured scope as null. Counts are not claims about real builds.

Headline metrics show distinct FileDataIDs, readable tables versus definitions,
community-name coverage over the same catalog, and encoded content stored locally.
Research cards navigate into Tables, Files, Items and Creatures. Identity and
measurement scope expand beneath the readiness strip.

Download mode lives only in Overview’s Download & storage panel. On demand and
Download all are mutually exclusive, session-only mock settings. With unknown
encoded scope, Download all is disabled with an explanation and no percentage or
remaining-byte calculation is shown. No mode change starts a transfer.

## Compact file browser

`FilesView` uses a virtualized JavaFX TableView with fixed 28px single-line rows:
FileDataID, Name, Type, Size, Downloaded. Twenty authored fixtures live in
`MockFileCatalog`; filenames are community-attributed examples and types are
inferred from extensions, never claimed as content-verified. Search matches IDs,
names and full paths. Type and download-state filters combine with search.

The inspector shows full path/provenance and individual locale variants with
encoded/decoded lengths and local status. Grid sizes summarize encoded bytes
using a range when variants differ; unknown sizes/status remain explicit.
Size-summary sorting is disabled because ranges and unknown values do not have
a single natural ordering. Other columns are sortable, and filters respect it.
Selections and filters persist when navigating away and back. No real files,
folder trees, payload reads or backend connections are introduced.

## Simulated first-open preparation

Open a **Not prepared** build (for example PTR), or choose **Preview first-open
preparation** in a prepared build’s inspector. A popup over Builds shows four
stages: configuration verification, published download-size preparation, retained
content assessment, and Overview publication. The mock service advances every
3.5 seconds. Its spinner is indeterminate: stage ordinals are never percentages.
No real network, disk or backend work takes place.

- **Hide** or Escape leaves preparation running. Grid status and a header entry
  remain available. Hidden completion says **Ready to open** and never navigates.
- **View preparation** reattaches the existing job; successful completion while
  attached enters Overview. No duplicate operation is started.
- **Cancel opening** shows **Stopping…** before **Opening canceled**.
- **Simulate failure** latches a recoverable outcome; **Retry opening** starts a
  new generation. **Next stage** advances the mock without waiting for its clock.
- Prepared builds normally open directly. Preview explicitly replays preparation.
- One simulated preparation runs at a time. Other builds stay selectable, and
  already prepared builds can open while hidden work continues.

`MockPreparationService` owns jobs keyed by product, version, configuration and
source, independent of popup lifetime. `PreparationPopup` retains stable controls
while progress changes. Background content is disabled while the popup is shown;
Tab stays within it, Escape hides, and Hide restores invoking focus. No state is
persisted across app restarts. The deterministic lifecycle checks use manual
advancement with no timers, sleeps, credentials or backend dependencies.

Preparation gates only the Overview context. File catalog materialization,
research records, community names and Download all are separate future jobs, not
prerequisites represented by this popup. The Overview’s catalog statistics remain
authored illustrations; this mock does not implement those background workers.

## Items browser and tooltip

`ItemsView` renders ten authored items from `MockItemCatalog`. Each 36px
single-line row has a bundled 28px icon, quality-colored name, class, subclass,
quality, equipment slot and base item level. Name search is case-insensitive;
exact numeric Item ID search is also supported. Class, subclass, quality and
slot filters combine. Class changes reset and narrow the subclass choices.
An item hidden by filtering stays in the inspector with a notice.

The inspector uses a WoW-style dark tooltip with quality-colored name, item
level, binding, slot, stats, effect text and flavor text. Every displayed number
and effect is authored sample content, not computed gameplay values. Source
buttons open the mock Item and ItemSparse tables. Search and selection persist
when switching areas. Production tooltip fidelity requires the future semantic
item/facts/effects services; missing calculations must not be guessed.

Eight unmodified Blizzard sample icon JPEGs are bundled for offline display;
provenance is in `src/main/resources/items/README.md`. No runtime icon downloads,
game-byte decoding, external APIs or backend connections are introduced.

## Real opening

Opening may fetch and persist authenticated configurations, encoding, ROOT and
indexes; it is not a Download all operation. One opening is admitted at a time.
Cancellation retains the admission slot until lower work retires; failures leave
the catalog usable and permit explicit retry. Hiding the popup prevents automatic
navigation on completion; its activity button returns to the result. Closing the
application cancels/drains opening before releasing stores and network resources.
`openingTest` exercises deterministic provider/cancellation/lifecycle contracts.
