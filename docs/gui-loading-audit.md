# GUI loading and FX-thread audit

The November 3 audit and its follow-up address the identified loading-feedback
gaps below. This is a source review backed by authored delayed/error/retirement
fixtures, not a latency profile or a guarantee that the UI can never stall.
Only real known totals produce determinate counts.

## Implemented feedback and background work

| Area | Behavior |
| --- | --- |
| Files, Tables, Items, Creatures | Initial loading, retained rows during updates, local detail/table/media activity, pending/unavailable icons and bounded background pixel conversion. Failed paging preserves committed history. |
| NPCs, Quests | Initial loading and retained rows during updates; bounded catalog and large-search workers with generation guards. |
| Races and model previews | Local catalog/overview/racial activity, model/variant/appearance activity, pending/unavailable racial icons, background pixel conversion. Inline GPU model rendering has separate first-frame, stale-frame and cleanup evidence in [GPU review](gpu-preview-review.md). |
| Talents | Catalog, tree and description activity; pending/unavailable icons; bounded background RGBA conversion followed by bulk FX upload. |
| Professions | Catalog, abilities and detail activity; pending/unavailable recipe icons; optional item-name activity retains numeric IDs on failure. Appending recipes preserves pending work for the selected recipe. |
| Mounts | Catalog and detail activity; the shared preview shows activity throughout model asset acquisition before rendering. |
| Dungeons | Catalog and detail activity; encounter icons distinguish pending/unavailable and use bounded cached background conversion before bulk upload. |
| World explorer | Local catalog/inventory/initial tile/nearby streaming and first-frame activity. Terrain bounds scans and scene-array preparation run on bounded background workers. Terrain rendering remains CPU rendering; this change does not add GPU terrain rendering. |
| Location maps | Local observation/artwork activity, real completed/total tile counts, retained markers/readable tiles, bounded background BGRA conversion and sequential tile delivery. |
| Race voices | Separate catalog, decode and output-preparation activity. Playing begins after the audio-line start callback. One admitted playback and one owned asynchronous close bound audio work; stop/close does not wait on FX. |
| Compare | Opening/scanning activity and page-local activity; existing findings survive a failed page with retry. |
| Build/comparison close | A visible sibling overlay remains active while the underlying controls are disabled and asynchronous resources drain. |
| Size accounting | Selected-build and loaded-build refresh activity; generations prevent stale selected-build completions from affecting newer activity. |
| Settings and Questie mapping | Local saving activity outside disabled controls; failures clear pending state and show an error. |

## Lifetime and retirement

Talents, Professions, World/maps, Mounts, Dungeons and Races retire pending
activity and obsolete actions when current-generation work discovers an external
workspace change. Provider browsers clear activity and ask for Reload while
retaining previous rows. Older generations and selections cannot clear newer
activity. Close cancels owned requests and retires callbacks. Readable partial
data and unknown/unavailable wording remain explicit. Worker queues and image
limits bound conversion/preparation; no arbitrary overall percentage is shown.

## Validation and practical limits

Authored JavaFX harnesses cover delayed catalog/detail/icon/name/terrain/map/voice
phases, errors, cancellation, source retirement, stale ordering and close. The
audio fixture uses fake lines to gate actual start and stalled close, verify
bounded admission, and ensure device cleanup never runs on the caller thread.
Talents fixtures wait for actual rendered conditions instead of fixed delays.
Compare covers delayed opening, page failure and retry.

Application close/accounting and settings/mapping indicators have source review
and component checks; they do not have new dedicated delayed JavaFX fixtures.
Synthetic UI evidence does not qualify a live build, every audio device or driver.
Real-build latency profiling and native-device acceptance remain follow-up work.
FX still constructs controls, performs final bulk image uploads and draws the UI.

Existing build catalog/history/opening, preparation, Overview, Data Sources and
source-refresh activity is retained. The separate [GPU review](gpu-preview-review.md)
records actual Linux model-rendering evidence and its platform limitations.
