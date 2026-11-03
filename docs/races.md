# Races browser

The Races area appears after Talents in an open build. A sidebar lists race records
with recorded class combinations; **Include other race records** exposes internal
or unlinked `ChrRaces` records. If no class-linked rows are readable, the browser
shows the available race records instead of guessing which are playable.

The overview presents names, lore, a verified faction-name relationship when
available, recorded class names and racial ability cards with icons. Initials are
used for the race badge: character-creation portrait references are texture-atlas
elements and are not treated as image FileDataIDs. Missing icons remain placeholders.
Source IDs and partial-data diagnostics are in a collapsed Source details section.

Static client combinations do not establish current realm/account unlocks. Racial
cards are not matched to spells by name. Race mask relationships, if added later,
must use the reviewed `PlayableRaceBit` rather than `raceID − 1`.

## Component and lifetime

`:concepts-races` owns typed asynchronous projections over the workspace's borrowed
build/locale table handle. The GUI does not query SQLite or enumerate tables itself.
The domain handle is created cold, loaded when Races is opened, and drained before
borrowed tables close. The view rejects stale selections, workspace changes and
late image results. Readable PARTIAL rows remain usable.

All names and relationships come from the selected pinned WoWDBDefs schema. The
reviewed racial-card layouts are `B6115535` and `0FC0F0D5`; unsupported optional
shapes do not discard race/class facts. Older layouts without lore or racial-card
tables keep the available metadata. No 3D preview is provided.

## Validation

```sh
./gradlew :concepts-races:check :gui:check
# Requires a graphical display; authored data only:
./gradlew :gui:racesUiTest
python3 -m unittest discover -s scripts/ci -p '*_test.py'
```

Focused tests cover recorded/internal race distinctions, real foreign-key joins,
readable partial data, missing race/class names, optional-table failure, unsupported
racial-card layouts, cancellation, borrowed lifetime, displayed icons, filtering
and stale selection. The component is included in Linux/macOS/Windows CI tasks.

A separate read-only probe exercised the actual component against existing
Forever 1.60.1.70707 row graphs and matching pinned schemas: 58 race records,
10 with class links, 56 unique race/class combinations and 4 racial cards per linked
race. All 10 overviews resolved with faction names. No live library writes occurred.
These are records in that build, not proof of server character-creation availability.

![Authored Races layout](images/races-authored.png)
