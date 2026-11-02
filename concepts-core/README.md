# Shared game-concept rules

`RelationIndexes` builds reverse integer relations over a `BuildTables` handle.
Each (table, column) is scanned once, shared by concurrent callers, with later pages
promoted to Interactive when an interactive caller joins. Indexes hold unsigned32
keys and row ordinals in sorted eight-byte pairs. All indexes on one instance
share a default four-million-entry budget. Refusal is explicit and sticky for that
handle; unsuccessful scans return their reservations. Partial-table misses are
never reported as proven absence.

Every lookup checks the source identity when reading matching rows, including a
one-row check for empty matches. A changed identity drops and rebuilds once, then
returns Stale. Explicit caller cancellation stops only that wait; canceling an
owner scan allows live waiters to restart. Returned future cancellation does not
cancel accepted work. Relation operations have fail-fast admission at 1,024 active
calls, and private futures are never exposed to callers. Concept services own and
drain their table handles.

`ConceptQueries` provides bounded scalar/UTF-8 validation, canonical decimal IDs,
ordinal-ignore-case literal matching, request bindings and canonical unpadded
base64url cursor framing. The query policy follows pinned C# behavior, including
simple Unicode casing without multi-character expansions or normalization.
Original C# query/cursor code regenerates checked-in synthetic goldens.

```sh
./gradlew :concepts-core:check :concepts-core:assemble
./gradlew :concepts-core:interopTest -Pdotnet=/path/to/dotnet -PreferenceRoot=/path/to/vibeminer
```

Native Windows machine certification and large live-data performance remain
separate. Pair budgets count logical entries; Java array growth and DTO collection
allocation add physical overhead, so this is not an equal-heap-size claim.

## Preparation and on-demand media

Concept handles expose `warmup(priority, token)` with immutable per-lookup results.
Preparation builds only reviewed, bounded table indexes and reports unsupported,
partial or resource-limited inputs explicitly. Retain the same warmed handles in
the application context; closing and reopening them discards in-memory indexes.
Warmup performs no icon/model/texture I/O and does not claim universal table readiness.
