# GUI design and integration handoff

## Builds integration update

The prototype has been imported onto the current backend and included as root
`:gui`. Default mode now connects only the Builds catalog/refresh/inspector to
layers 1–3. Real opening is now connected through an app-owned cancellable single-operation
boundary; success shows verified metadata, while later screens remain deferred; `--mock` preserves the
approved full design preview. Catalog load/refresh has an indeterminate activity
indicator and explicit busy button labels; lower layers were not changed for
progress. Use root `:gui:check`, `:gui:run` and
`:gui:installDist`; the standalone settings file was removed. Read GUI README
for data paths, native SQLite options and current validation. The original design
worktree/branch remains untouched. Earlier sections describe the original mock
handoff and are retained as design history.


## Branch and scope

This prototype was developed in `/Users/aircan/Development/vimi-gui` on
`gui/design`. All implementation and build changes are under `gui/`.
Backend modules, root Gradle settings/build, CI, dependency lockfiles and the
sibling C# checkout were not modified. Integrate this branch from the main worker;
this handoff does not authorize changes to those boundaries during design work.

The user approved the current light/teal visual direction, VibeMiner branding,
build-area navigation, Overview redesign, compact Files grid, Items browser and
preparation mock. Everything remains mock-only: no backend service connections,
credential use, runtime external requests, real game parsing or persistence.

## Build and run

Use a full **JDK 25**. Gradle 9.1.0 is provided by the repository wrapper; JavaFX
25.0.1 is declared only in the independent `gui/build.gradle`.

From the repository root:

```sh
./gradlew -p gui check installDist
./gradlew -p gui run
```

Use `gradlew.bat` on Windows. On this Mac:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/tmp/vimi-gui-gradle
./gradlew -p gui check installDist
./gradlew -p gui run
```

The standalone `gui/settings.gradle` intentionally avoids root project inclusion.
First use requires dependency downloads. The current cache supports offline builds.
`installDist` creates `gui/build/install/vimi-gui-prototype/`; its `bin/` launcher
can be run directly with JAVA_HOME set. That distribution is OS/architecture
specific. JavaFX native classifiers cover macOS/Linux x64/ARM64 and Windows x64;
Windows ARM64 packaging is not qualified. `gui/build/` and `.gradle/` are ignored.

`check` invokes the explicit dependency-free `prototypeTest` JavaExec harness.
The standard Gradle `test` discovery task is disabled because these are plain Java
checks, not JUnit tests. Compiles use Java 25 release and `-Xlint:all -Werror`.

## Code map

All Java classes are in `gui/src/main/java/com/vimi/gui/`.

| File | Responsibility |
| --- | --- |
| `Launcher` | Launches JavaFX through a separate main class |
| `VimiApp` | Main window, build discovery, shared build header/navigation, view cache and preparation attachment/navigation |
| `BuildInfo` | Immutable build presentation facts, provenance state and nullable size semantics |
| `BuildService` | CompletableFuture catalog/open seam for a future adapter |
| `MockBuildService` | Eight deterministic build fixtures; no I/O |
| `BuildOverviewView` | Overview landing page, metrics, research entry cards and download-mode controls |
| `MockOverviewSnapshot` | Explicitly authored full-scale research statistics, independent of browser sample counts |
| `FilesView` / `MockFileCatalog` | Compact file grid, filtering, selected variants and size/local-status summaries |
| `ItemsView` / `MockItemCatalog` | Item grid, dependent filters, cached icons and authored WoW-style tooltip |
| `MockResearchData` | Small Tables and Creatures fixtures |
| `MockPreparationService` | App-owned simulated jobs, clock, stage transitions, cancellation, failures and generations |
| `PreparationPopup` | Stable popup controls and stage presentation; no ownership of the operation clock |

`src/main/resources/vimi.css` owns styling. Branding and item-art provenance live
next to the bundled assets. `src/test/java/com/vimi/gui/PrototypeTest.java` contains
the deterministic, credential-free checks.

## Builds and navigation

Builds has search, product/status filters, row selection and a right inspector.
Build source distinguishes **Current**, **Saved** and **Community**. Community
means third-party history never directly observed by VibeMiner; the sample names
its provider as fictional, has no verified route, and disables Open with a reason.
This status does not establish upstream file availability.

The inspector includes source selection, Open/View preparation and an explicit
**Preview first-open preparation** control for design review. Filtered-out build
selection remains in the inspector with an explanation.

After opening, the menu order is:

**Overview → Files → Tables → Items → Creatures**

The shared 60px header shows Game/Edition, Product ID, Build, Locale, Status and
Retained. Channel/Region was deliberately removed. Download mode was deliberately
moved out of the header into Overview. Choose build returns to the existing
filters and selection. Research views are cached per configuration + area during
the session, preserving their controls, selections and scroll state.

## Overview

The landing page shows distinct FileDataIDs, readable tables versus definitions,
community-name coverage and stored encoded content. The Retail fixture uses
realistic **illustrative** totals. Browser lists contain only small authored
subsets; the page says so. Other fixtures retain unmeasured research values as
null, displayed as **Not measured**, never zero.

Four research cards navigate into the corresponding area. A readiness strip and
an expandable identity/measurement disclosure separate configuration readiness,
file-catalog scope, table coverage and unchecked upstream availability.

Download & storage is the sole place to select **On demand / Download all**.
The selection is per mock build for this process only. It starts no transfers.
With unknown encoded scope, Download all is disabled with an explanation, while
known stored bytes remain visible and no percentage/remaining calculation is
invented. Shared content is not added twice; encoded scope is not installed size
or reclaimable disk space.

## Files

The virtualized grid has **28px single-line rows**:

**FileDataID · Name · Type · Size · Downloaded**

Search matches ID, filename or full path; type and download-state filters combine.
The inspector shows full path, community-name attribution, extension-based type
inference, encoded summary, individual locale variants, decoded lengths and local
status. Twenty fixtures include unnamed files, unknown sizes, size ranges, partial
retention and mixed known/unknown statuses. Unknown does not mean missing or zero.
FileDataID is identity; an attributed filename is not identity.

Size is encoded download size, summarized as a range when variants differ, never
summed as though all variants were one file. A partially stored file says, for
example, **1 of 2 stored**. Unknown variants remain explicit. Size-summary sorting
is disabled; other columns are sortable. All names and statuses are mock examples.

Folder browsing has been discussed but not implemented. A future tree would be
virtual, based on attributed paths, with an unnamed-file group; ROOT does not
supply a traditional directory tree. Encryption indicators were explicitly deferred
by the user. Do not add an Encrypted column as part of initial integration.

## Items

The grid uses **36px single-line rows** with 28px icons, quality-colored name,
class, subclass, quality, slot and base item level. It has name/exact-ID search
and class/subclass/quality/slot filters. Selecting a class resets and narrows the
subclass choices. Filters combine; clearing them restores the whole sample list.
A selected item hidden by filters stays in the inspector with a notice.

The right panel resembles a WoW tooltip: dark background, quality-colored name,
item level, binding, slot, stats, effect text and flavor text. Ten item names and
all numbers/effects are authored. They are not calculated Retail stats or actual
client/server item evidence. The item-level dash is a sample with no applicable
level value. Source buttons open the mock Item or ItemSparse table; they do not
claim to select a fully implemented source record or field.

Eight unmodified 56px Blizzard sample icon JPEGs are bundled for offline display,
with source URLs and attribution in `src/main/resources/items/README.md`. No image
fetch occurs at runtime. This sample view mounts ten row icons plus the detail
icon and caches decoded images by asset name. Production image delivery needs a
bounded semantic/asset service and explicit failure states, not direct UI HTTP.

## Preparation flow

First opens of unprepared fixtures simulate four stages:

1. Verifying configurations.
2. Preparing published download size.
3. Assessing retained content.
4. Publishing Overview.

The popup overlays Builds. It traps Tab, disables background controls and returns
focus when hidden. Escape means **Hide**, never Cancel. Stable controls survive
stage changes. Its spinner is indeterminate; stage ordinal is not a percentage.
There are no fabricated transfer counters or ETA. The mock service advances every
3.5 seconds, solely so the experience can be reviewed. Mock controls provide
**Next stage** and **Simulate failure** without waiting.

- Popup attached at success: close it and enter that exact build’s Overview.
- **Hide**: keep work running, remove automatic navigation, keep per-build grid
  status and a header activity entry. Completion says **Ready to open**.
- **View preparation**: reattach the existing operation; do not duplicate it.
- **Cancel opening**: show **Stopping…**, then **Opening canceled** after retirement.
- Failure: keep stage/problem and offer **Retry opening**. No automatic retry.
- Retry: new generation. Old callbacks/ticks cannot mutate the replacement job.
- Prepared builds normally open directly. Preview deliberately replays preparation.
- One simulated preparation runs at a time. Other builds can be selected, and
  already prepared builds can open while hidden work continues.

Jobs are app-owned, keyed by product, version, configuration and source. UI delivery
uses Platform.runLater. A navigation generation prevents a late open reply from
replacing a different screen. Closing the application stops the mock scheduler;
there is no persistence or process-restart recovery in this prototype.

The intended real Overview gate is verified essential configuration/download
metadata and a published Overview context. Authentic absent/unsupported download
size may still produce an unknown-size Overview; integrity/malformed required
inputs block entry. ROOT/file-catalog expansion, names, item/creature decoding and
Download all do not gate Overview. The mock does not implement those independent
background workers. The C# reference distinguishes these scopes; see its
`docs/PRODUCT.md`, `docs/ASYNC-AND-JOBS.md`, and `contracts/download-v1.md`.

## Visual decisions and known limitations

- Keep the OS system font; the user chose not to bundle a custom font.
- Header captions are 11px bold; values are 13px **regular**, by user preference.
- On this macOS JavaFX runtime, **System Bold at 13px clips the percent glyph even
  in the middle of a string**. A standalone render reproduced it; regular weight
  renders correctly. Do not fix this by growing the header, adding scrolling, or
  switching Retained to Text nodes: those attempts caused height/alignment issues.
- Both screens use the original VibeMiner logo and split-color wordmark. Logo is
  36px; wordmark is 22px. Branding provenance is alongside the PNG.
- Open build has a flat teal fill, larger text and 10px corners. Modena’s layered
  background insets were explicitly reset to remove the unwanted beveled edge.
- Filter dropdowns use compact internal label/arrow padding to match button heights.
- Tables and Creatures are basic sample list/inspectors, not redesigned production
  explorers. There are no real previews, Settings or general Downloads panel.
- Prepared state, source routes, retention evidence and readiness are fixture facts.
  The current BuildService seam is intentionally much smaller than production
  orchestration; it is not a promise that lower layers expose this exact API.
- Native launch currently logs JavaFX’s unnamed-module configuration warning. The
  app runs, but modular packaging and platform-native distribution remain future work.
- At narrow windows, the header does not implement a full responsive identity menu.
  Current visual review primarily used 1320×850; broader accessibility/platform QA
  remains necessary before production.

## Validation and integration guidance

Latest `check installDist` passes with compiler warnings treated as errors.
Deterministic checks cover build identity/unknown states, community restrictions,
research links, Overview denominator consistency, file variant summaries and
filters, preparation busy/reattach/retry/stale-generation/cancellation/success, item
filters and offline icon resource availability.

Additional local JavaFX render probes checked Overview, Files, Items and the
preparation popup. A temporary in-process JavaFX lifecycle probe verified popup
background disabling, hidden completion without navigation, explicit open after
hidden completion, and attached completion closing the popup. Those probes and
screenshots live in `/tmp`, are not committed, and are not part of `check`.
There is no committed automated end-to-end UI suite. Windows/Linux UI execution
and full 1000×680 / enlarged-text coverage have not been certified.

For integration:

1. Preserve the approved visual structure and mock mode as an iteration path.
2. Add adapters between real layer interfaces and presentation facts; keep storage,
   CDN resolution, format parsing and bulk payloads out of controls.
3. Keep asynchronous bounded work/cancellation off the JavaFX thread and dispatch
   view changes to it. Preserve exact context + generation and hidden-job ownership.
4. Replace fake totals with scoped snapshots. Do not interpret unknown, partial,
   protected, unsupported or stale evidence as zero or complete data.
5. Treat download policy, operation progress and retained coverage as separate facts.
6. Coordinate actual layer-7 build inclusion/packaging from the main worker; this
   branch deliberately contains no root build/CI/lockfile changes.
