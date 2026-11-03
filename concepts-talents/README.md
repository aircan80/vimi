# Classic talents

A read-only domain projection over a borrowed `BuildTables` handle. It provides
localized player classes, three ordered Classic talent trees, node positions,
recorded rank spell IDs and prerequisite talent-row IDs/rank indices. It owns no
SQLite connection and does not decode media or allocate character points.

The first version supports reviewed Classic product families with client version
major 1–4, including Anniversary and Forever. Actual verified schema fields are
required; complete tables must establish three class-associated tabs. Retail and Mists require different
layouts and return an explicit unsupported-model result; legacy tables in those
clients do not establish support.

`TalentsService.catalog(token)` loads bounded ChrClasses/TalentTab/Talent metadata.
`trees(classId, token)` validates references, coordinates and prerequisite cycles.
ClassMask selects class ownership. Readable rows from PARTIAL tables are always
used: known classes and available trees remain browsable, even when other trees
or talent rows are protected. Populated PARTIAL results distinguish incomplete
data from complete READY results. Missing prerequisite targets preserve their
IDs and PROTECTED/UNKNOWN lookup outcome, with no invented connector or name.
Contradictions among readable rows, such as duplicate positions or cycles, remain
invalid. Verified definition names/types and bounded cursors are required.
Wholly unreadable or unsupported tables retain explicit failure codes.

Optional spell names/raw descriptions use SpellName/Spell lookups, including
readable rows in PARTIAL tables. Unreadable individual rows use explicit fallback
names/descriptions without hiding other readable spell text. SpellMisc is
streamed with a row bound, retaining only requested spell IDs; a unique
DifficultyID=0 entry supplies SpellIconFileDataID. Missing/ambiguous media metadata
never invents an image or blocks tree structure. Raw descriptions may contain WoW
formatting/substitution tokens; this component does not evaluate them as tooltip
numbers. TalentTab's background descriptor is retained but the UI uses a neutral
fallback until a real build asset is resolved.

The native workspace owns the talent handle and closes/drains it before closing
its borrowed tables. No additional talent-domain projections are built during initial build preparation.
The GUI resolves the current workspace handle, invalidates on revision changes,
and discards stale class/tree/media replies. Visible icons use the existing
bounded on-demand image path and shared library accounting.

```sh
./gradlew :concepts-talents:check
./gradlew :gui:check
# Optional authored screenshot, requires a display; writes /tmp/vimi-talents.png:
./gradlew :gui:talentsUiTest
```

The UI is a reference browser, not a planner: rank badges mean recorded maximum
ranks, not learned/available points or calculated gameplay eligibility.
