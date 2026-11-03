# Polestar data component

Independent provider repository for [Polestar's published forever-db](https://github.com/fitzforge/polestar/tree/next/forever-db).
It checks GitHub publication metadata, downloads immutable source snapshots, prepares
local query indexes, and supplies typed bounded record queries. It does not depend
on the desktop GUI, select a build's data source, or implement an NPC browser.

## Commands

```sh
./gradlew :polestar:test
./gradlew :polestar:check
```

Unit tests validate manifest, schema, path and license rules. Native SQLite belongs
to the integration suite included in `check`; use `-PsqliteLibrary=/path/to/library`
when a platform requires an explicit native library. GitHub Actions runs both suites
on Linux, Windows and macOS. Tests use authored fixtures, never live services.

## API and storage

`PolestarRepository(directory, PolestarClient)` owns a bounded asynchronous actor.
`catalog` and `snapshots` read only local state; `check` fetches GitHub metadata;
`download` verifies and retains the selected checked publication; `prepare` creates
its query database. `sync` composes these stages and reuses checksum-valid originals
and valid prepared databases. An unchanged GitHub head reuses its cached metadata
with one small reference request. A new repository commit with identical published
index/package content reuses the verified older snapshot and database, preserving
its original pinned provenance rather than relabeling it as newly downloaded.
Transport is injected through `PolestarClient`.

`lookup(sourceId, table, key, token)` returns up to 500 records for that published
key; relationship tables may have multiple records with the same target ID.
`search(sourceId, table, text, offset, limit, token)` provides a bounded page and total.
Each result retains source snapshot ID, source client build, table license and the
published fields. No GUI or consuming component needs to open provider SQLite.

Completed `sources/<commit>/` snapshots retain original JSONL, schemas, index,
package manifest and all four upstream notice/license documents. Completion metadata
is published last through a same-directory atomic rename. Invalid copies are retained
under a separate internal name when a verified replacement is published. The derived
`derived/<commit>.sqlite` is disposable; invalid databases can be prepared again.
Preparation writes a temporary database and publishes only after every row validates.
Cancellation keeps completed downloads and never publishes partial preparation.

Bounds: 32 queued operations, 128 inventory versions, 15 reviewed tables, 16 MiB per data
file, 64 MiB declared data per snapshot, 1 MiB per row, at most 1 million rows per table and
2 million per package. Metadata/notices have separate bounded sizes. Schema1 supports
the upstream's reviewed object/string/integer/number/boolean, required, enum, minimum,
minLength and additionalProperties rules; unsupported schema features fail explicitly.

## Provenance and licensing

The reviewed `2026.11.1` publication identifies Forever client `1.60.1.70532`.
A source build is recorded provenance, not compatibility with another build or proof
that every record was observed in the live game. The dataset credits several client,
legacy community and corrected sources. Published coverage is retained unchanged.
There is no declared localization matrix; names remain as published.

Database resources declare **GPL-3.0-only**; authored mechanics, steps, notes and their
schemas/documentation declare **CC BY 4.0**. Resource licenses are checked and attached
to query results. All upstream license and NOTICE files remain beside each download;
this component does not relicense imported data. See [UPSTREAM.md](UPSTREAM.md).

Spawn coordinates are world yards on client Map IDs (x north, y west), not zone-map
percentages. `ui_map` contains frames for later coordinate conversion. Item-source
chance values describe the upstream's designed loot tables where provided, not a
promise of observed live drop rates. Missing fields remain missing.
