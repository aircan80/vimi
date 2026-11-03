# EG Link source component

Independent GitHub-only EG Link discovery, immutable original snapshots and derived
SQLite query indexes. The application owns this repository and borrows typed
results; GUI code never queries its SQLite files directly.

```sh
./gradlew :eg-link:test                  # fast parsing/provenance contracts
./gradlew :eg-link:integrationTest       # SQLite persistence, lookup and repair/cancel contracts
./gradlew :eg-link:check                # formatter, static checks and both suites
```

`EgLinkRepository.check` reads the fixed public GitHub branch reference, then pins
`data/index.json` and dataset files to that full commit. `download` retains one exact
advertised product/build snapshot; `prepare` builds a disposable local record index.
`catalog` and `snapshots` are local-only. `lookup` and bounded `search` require a
prepared source ID, dataset and collection; record IDs are collection-local, not a
global NPC namespace. Normalized record JSON retains unknown fields and original
provenance remains available through the snapshot metadata and raw files.

The parser accepts reviewed schemaVersion 1 and validates product/build/locale,
license and attribution before publishing any source. Future schemas fail clearly.
Bounds: 1024 build snapshots, 128 datasets per snapshot, 16 MiB per file, 64 MiB per snapshot,
1 million indexed records, 1 MiB per record, 32 queued repository operations. Download and
preparation publication are atomic; failed/canceled operations preserve already
completed source snapshots and prepared data. A prepared rebuild writes a new file
before replacing the old version. Original files are SHA-256 checked before prepare.
Queries validate database format and source-manifest identity.

Original datasets are CC BY 4.0, credited to Efficient Games. Game text remains
Blizzard content. This module's code uses the repository's project license.
See [source workflow and coverage](../docs/eg-link.md).

`sync(progress, token)` is the reusable refresh operation: it checks GitHub and
updates the newest advertised build per product plus each distinct installed
product/build still advertised. It does not acquire all historical builds.
The typed `Sync` result reports downloaded/prepared/reused counts and per-build
issues; failures preserve earlier snapshots and do not prevent another target
build completing. Identical commits use one branch-head request and reuse local
index/data/prepared artifacts. A changed commit downloads the selected datasets
again because upstream record counts/dates are not content hashes.
