# Questie backend

Independent QuestieDB release storage, preparation and local queries. It does not
require an opened WoW build and has no GUI dependency. Existing build/file/format
layers are unchanged. The application facade is `QuestieRepository`.

## Responsibilities

- `sources/QuestieSourceRepository`: check official latest releases, fetch a named
  release, retain exact per-flavor ZIPs and original release/manifest metadata,
  verify SHA-256/size/ZIP integrity, preserve upstream notices, and reopen offline.
- `data/QuestieDataRepository`: import supported baked TOC metadata into a separate,
  completed SQLite snapshot. Preparation identities include source hash, flavor and
  importer/schema policy. Source archives survive failed or canceled imports.
- `data/QuestieDataHandle`: local asynchronous entity lookup, paged search and typed
  item-source queries. Item sources return lightweight localized summaries; full
  entity details are a separate lookup.
- `QuestieRepository`: compose checking, downloading and preparation; retain multiple
  source/prepared versions; compare named fields between explicitly chosen versions.
- `cli/QuestieCli`: exercise these services without a GUI or WoW installation.

Originals use the module's own filesystem archive repository, not the CDN-specific
object store or the derived-bytes generated store. SQLite projections reuse the
existing `DerivedDatabase` public API in separate instances.

```text
DIRECTORY/
  sources/
    repository-format
    releases-v1/       retained original GitHub release observations
    sources-v1/ID/     archive.zip, source/release manifests and upstream notices
    staging-v1/        unpublished temporary downloads
  derived/
    ID.sqlite          one completed static prepared snapshot per identity
```

A changed upstream release/asset creates a retained revision; download/reaction
counts alone do not report a new version. `releases` lists locally observed
releases. Fetch older releases explicitly by tag. Identical source identities are
reused rather than downloaded again; prepared snapshots reopen without importing
all records again. Nothing automatically removes old originals or prepared data.

## Setup and commands

Use the repository's Java 25 JDK, Gradle wrapper and native SQLite setup. There are
no additional runtime tools: preparation uses Java base64/zlib/CBOR readers and
**does not execute Lua**.

```sh
./gradlew :questie:installDist
# If SQLite is not on the platform loader path, set this for the installed CLI:
export JAVA_OPTS='-Dvibeminer.sqlite.library=/absolute/path/to/sqlite-library'
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie check
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie sync Forever
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie sources
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie versions
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie download v1.0.9 Forever
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie prepare SOURCE_ID
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie entity VERSION_ID item 25 deDE
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie item-sources VERSION_ID 117 enUS
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie search VERSION_ID npc wolf enUS 0 100
questie/build/install/vimi-questie/bin/vimi-questie --data /path/to/questie compare LEFT_ID RIGHT_ID item 117 enUS
```

On Windows, use the generated `vimi-questie.bat` launcher and set `JAVA_OPTS` through
your shell. CLI results are JSON on stdout; throttled measured progress goes to
stderr. The source and data components also expose futures and explicit
`com.vibeminer.generated.Cancellation` tokens for application callers. Canceling a
returned future alone does not cancel accepted work; cancel its token. Close
rejects new requests and drains accepted operations before releasing resources.

## Interpretation and provenance

The initial profile reads current contract 2–4 baked metadata, reviewed against
QuestieDB producer `a37877c1490027ee48e5722bf602adf34f95a6dc`. It validates packaged
schema declarations, reassembles chunks, decodes bounded base64/zlib/CBOR, handles
Lua byte strings, sparse arrays/maps and positional translations, and retains
unrecognized metadata. Unsupported structural profiles fail preparation while
keeping the original download.

Results represent **static corrected packaged QuestieDB data**. Character-, season-
and faction-dependent runtime corrections and consumer policies are not evaluated.
The original package is retained, including unevaluated providers. The manifest
also records the exact producing commit's source URL; the full authoring repository
is not downloaded automatically. Baked field provenance identifies the packaged
value, not its original individual correction author.

English is the base locale. Stored translations and their fallback remain explicit.
Entity IDs are scoped by item/NPC/quest/object kind; they are not FileDataIDs or
model/display IDs. Declared and reverse relationship evidence remain distinct.
`QUEST_PROVIDED` identifies an item supplied by a quest starter, not a drop or quest
requirement. Empty source lists mean no recorded relationship in this snapshot,
not proof that an item has no source. No drop rates are invented.

Field comparisons preserve array ordering, sparse holes and recorded numeric/map
representation. They are representation-sensitive comparisons of named stored
values, not a full Lua-equivalence evaluator. `sameInterpretation` identifies
matching flavor/profile; cross-flavor equal IDs do not establish equal gameplay
identity. An absent record means absent from that snapshot, not removed from WoW.

The upstream release ZIP, raw metadata and license notices are preserved unchanged.
See [upstream license](https://github.com/Questie/QuestieDB/blob/master/LICENSE.md),
[provenance](https://github.com/Questie/QuestieDB/blob/master/PROVENANCE.md) and
[storage format](https://github.com/Questie/QuestieDB/blob/master/docs/storage-format.md).
This module does not relicense their data. Product archives are not checked into
this repository; automated tests use authored fixtures.

## Validation

```sh
./gradlew :questie:test
./gradlew :questie:check :questie:installDist -PsqliteLibrary=/absolute/path/to/sqlite-library
```

Unit tests use local fake HTTP responses and authored binary/ZIP fixtures, never
live services. Native integration tests cover durable versions, locale fallback,
source summaries, corruption/cancellation, offline reopen and field comparison.
Hosted unit and native quality/integration checks include this module.

An opt-in developer smoke test downloaded and prepared official Forever v1.0.9
and v1.0.10 side by side: 46,957 entities each, English plus nine stored
translations, German item names and independently verified item-source counts.
Repeated latest check/sync reported no new revision and reused both retained
original and derived data without downloading/importing again. This verifies those
static packages, not exact client-build compatibility or full runtime behavior.
