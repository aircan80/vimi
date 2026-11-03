# Races concepts

Build-scoped, read-only race metadata over a borrowed `BuildTables` handle. The
component owns its bounded query worker and immutable projections; it never closes
the tables. Workspace retirement closes this component before those tables.

`catalog` retains readable `ChrRaces` records and records the number of unique
`CharBaseInfo` class links for each ID. `overview` adds localized lore, classes,
optional `FactionTemplate → Faction` names, and `ChrRaceRacialAbility` display cards.
No server/account character-creation eligibility is inferred. Missing or PARTIAL
tables preserve independent readable data and report uncertainty.

Names, types, cardinality and foreign keys must match the selected schema. The
unverified racial card names are accepted only for explicitly reviewed DBD layouts
`B6115535` and `0FC0F0D5`; other unverified shapes remain unsupported. Racial cards
have presentation text and icon FileDataIDs, not a proven SpellID relationship.
Race portrait atlas extraction and character model rendering are not implemented.

The catalog is bounded to 1,024 race rows, 128 classes and 16,384 class-link records;
ability scans stop at 8,192 rows and each overview displays at most 128 cards.
Queries use stable generated table identities, cancellation, a single worker and
32 queued operations. Construction performs no table scans.

```sh
./gradlew :concepts-races:check
```
