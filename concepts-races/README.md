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
Queries use stable generated table identities, cancellation and bounded workers
of 32 queued operations each: one for race, model and idle queries, one for
texture composition and one for voices, so a first voice catalog never holds back
the selected model. Construction performs no table scans.

Repeated selections are answered from memory for the handle's lifetime: variant
lists and voice catalogs (derived only from the handle's build-scoped tables), sample
previews (while the model file keeps its content key) and complete composed
appearances (while the model and every layer file keep the content keys that named
their generated derivative; at most 6 results and 160 MiB of texels). Idle results
follow the rules above; a reassessment reuses the model read and its Stand facts.
A result whose sources could not be read is prepared again on the next request, and
a workspace or source change publishes a new handle with empty caches.

```sh
./gradlew :concepts-races:check
```
