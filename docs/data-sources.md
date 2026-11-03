# External data sources

Data Sources manages QuestieDB, the community listfile, bundled WoWDBDefs, and
public wowdev/TACTKeys.
Blizzard CDN acquisition remains in Builds. Native screens use cached typed service
snapshots; persistence, provider calls and inventory work run off the JavaFX thread.
The earlier illustrative source figures have been replaced with local measurements.

## Settings and retained data

Versioned `sources_*` tables in the shared library database persist source enablement,
manual/startup/daily policies, successful check times, per-flavor Questie defaults,
and canonical per-build flavor overrides. Disabling keeps completed downloads and
prepared data. It cancels active source acquisition and prevents new source jobs.
Explicitly close and reopen a build to change its captured provider policy.

A listfile update checks the release and prepares the index; its automatic policy
therefore says **Automatic listfile updates**. Questie automatic checks discover
releases without downloading or activating them. These providers default to manual;
TACTKeys defaults to enabled with daily updates. Native build filename enrichment reads the installed local
listfile; updates are performed by this page or the selected automatic policy,
so opening a build does not bypass the manual setting. Failed daily
checks do not spin in an automatic retry loop. Bundled definitions update with the
application; there is no fabricated runtime definition updater.

Open workspaces retain their table-definition policy. Disabled definitions produce
an explicit issue and a separate preparation identity; an enabled-definition cache
cannot silently restore into a disabled workspace. Metadata and Files still work.
Filename acquisitions are canceled when the listfile is disabled; already-bound
workspace data and completed provider objects are retained.

## QuestieDB

The page lists retained archives and prepared databases by source identity and
flavor. It exposes observed release checks, named-release/flavor downloads,
preparation/reuse, safe cancellation and default selection for each flavor.
Local files are not downloaded merely by opening the screen. No deletion is offered.

Archive bytes and prepared SQLite file lengths are separate provider-local figures,
excluded from per-build object attribution. Entity counts, locales and diagnostics
come from completed prepared metadata. Unreadable data is reported, not deleted or
silently interpreted as a clean empty library. Default selection opens and validates
the prepared database before saving its identity.

Build Overview offers **Automatic**, **None**, or a flavor override. `wowf` maps
directly to Forever; reviewed Classic progression channels use their version family
for Vanilla/TBC/Wrath/Cata/Mists, with reviewed 1.60 builds mapping to Forever.
Unknown channels and Retail receive no guessed Classic mapping. The selected flavor
resolves its configured default prepared database; changing mapping performs no
implicit download. Exact client-build compatibility is not claimed. These management
and mapping controls do not yet add Questie item-source results to the Items browser.

## TACTKeys

The public source is [wowdev/TACTKeys](https://github.com/wowdev/TACTKeys), fetched
from its fixed `master/WoW.txt` URL through the bounded network component. The page
shows enablement, manual/startup/daily policy, update now, key count, cached bytes,
last successful check and the cached revision. It is enabled and daily by default,
including existing installations that have no preference for this new provider.

Updates run while the app is open; overdue daily updates run after startup. Build
preparation waits for an applicable shared update so its first table pass can use
the keys. Disabled/manual policy does not cause implicit network requests. A local
validated copy remains available offline; invalid or failed updates retain the last
valid set. Requests are canceled on disable/shutdown and accepted work is drained.
The original list is attributed to the global TACTKeys provider in the unified store;
source preferences publish its active object pointer only after storage succeeds.

Public keys supplement each build's TactKey/TactKeyLookup keys. A conflict keeps the
build-provided value and reports the key name. Effective per-key fingerprints feed
the decoded-table identity; a stable aggregate fingerprint covers prepared table
inventories and item catalogs. New key values therefore invalidate affected prepared
data on the next build preparation. Existing workspaces keep their key snapshot;
close and reopen a build to apply an update. Public keys can only recover records
actually present in encrypted data; they cannot supply absent spell IDs.

The parser accepts the documented two hex fields and future trailing columns,
rejects conflicting duplicate names or malformed/empty lists, and bounds input to
4 MiB and 65,536 unique keys. It never displays or logs raw key values.

![TACTKeys source management](images/tact-keys-source.png)

## Other providers

The listfile page shows actual installed tag, timestamps, indexed name count,
preparation state and original/derived catalog payload totals. Payload totals cover
retained provider objects, not just the active snapshot. Per-build filename coverage
stays in the build Overview. Definitions show the pinned revision, provider table
count, upstream source and license; provider count is not readable-table coverage.

## Validation

```sh
./gradlew :network-clients:check :files:check :gui:dataSourcesTest :gui:settingsServiceTest
./gradlew :gui:check :questie:check
```

Offline checks cover persisted enablement/update policies, conservative flavor
mapping and overrides, unavailable local data, read-only future-schema refusal,
product filtering, enabled/disabled workspace cache isolation, daily TACT key reuse,
restart/offline recovery, malformed update retention and disabled/manual behavior.
An isolated live TACTKeys check loaded 19,706 keys from 985,300 bytes without modifying
the application library. A separate live
smoke test in temporary storage checked official releases, downloaded Forever
v1.0.10, prepared 46,957 records with 10 locales, and selected/resolved its default
through `wowf`. It did not alter the user's application library.

Source page rendered from an isolated live-test library (not the user library):

![Questie source management](images/data-sources-live.png)
