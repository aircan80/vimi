# Durability policy

**Policy (repository owner, app-wide):** a power loss or OS crash may lose recently acknowledged
writes, but it must never leave corruption. `synchronous=OFF` is never used.

This document records, for every `synchronous=FULL` transaction and every explicit fsync in the
application, whether it can drop to WAL + `synchronous=NORMAL` (or skip the fsync) under that
policy, and why. Sites that change are marked **NORMAL**. Every other site stays as it was.

## Why NORMAL is safe here: one WAL, one order

Every application store that records "X was committed" lives in **one SQLite database**: the
unified library index `library/objects.sqlite3`. `BuildApplication` passes `library.databasePath()`
to each of them:

- object and history rows, pack lengths and ownership claims (`ObjectStore`, `LibraryOwnership`);
- the generated-table graphs and the saved table inventory (`PreparedTablesCache`, as generated
  objects in the same index);
- the reference catalog (`LibraryCatalog`), preparation receipts (`SqlPreparationReceipts`) and the
  build registry (`BuildRegistry`);
- accounting (`UnifiedStorageAccounting`), download intents (`DownloadIntents`), source and app
  settings (`SourcePreferences`, `SettingsPreferences`), and world bookmarks (`WorldBookmarkStore`).

These stores use separate connections, but they all append to the same WAL file. SQLite's WAL
recovery gives three guarantees:

1. **WAL + NORMAL is never corrupt.** SQLite fsyncs the WAL before every checkpoint copies frames
   into the database, fsyncs the database after the checkpoint, and syncs the WAL header when it
   restarts the log. A commit without an fsync can only be lost, never torn.
2. **Lost commits are a suffix.** Each frame's checksum chains from the previous frame, and every
   restart changes the salts. Recovery keeps frames up to the first missing, stale or garbage frame
   and drops every transaction after the last valid commit frame. If the OS keeps a later frame but
   loses an earlier one, the later frame is still discarded. So a crash loses some last N
   transactions in WAL order, across all connections, and never one from the middle.
3. **Any FULL commit, or any checkpoint, syncs everything before it.** An fsync of the WAL file
   covers every earlier frame from every connection.

So **an invariant that is kept by commit order inside this database survives NORMAL unchanged.**
After a crash the database holds exactly the state at some earlier commit. That state was consistent
when it was live, because the application writes a record only after the records it depends on are
acknowledged:

- table objects, then the table node, then the inventory;
- tables, then the receipt;
- metadata objects, then the registry's `LOADED`;
- objects, then the catalog generation that names them.

What NORMAL cannot protect is a **file action outside the database that depends on a commit being
durable.** Two kinds exist:

- **Data before metadata:** bytes that index rows will point at must be fsynced before the
  transaction that adds those rows. Only then can a surviving row never point at bytes that never
  reached the disk.
- **Metadata before a destructive or intent-dependent file action:** a commit must be durable
  before the application deletes a pack it supersedes, creates a file that only that commit lets
  recovery clean up, or truncates a tail that the commit accounts for.

Each of these keeps its fsync or its FULL commit. One file action follows a NORMAL commit on
purpose, because losing it loses data but cannot corrupt anything: deleting a staged download
after it is committed (#3a).

### What reopening does after lost commits

- **Pack tails.** The library truncates every active pack back to its indexed `committed_length`
  (`ObjectStore` open), so fsynced pack bytes whose index commit was lost disappear. Pack files the
  index does not list are deleted, and so are packs in a switched or retired state (`state >= 2`).
  A lost commit therefore leaves no unreferenced bytes the index can see. It can never leave a row
  without its bytes, because the pack fsync came before the row.
- **Tables.** A table whose node was lost is "not prepared". Later passes regenerate it
  deterministically (#231's argument: a store keeps at most an ordered prefix of a put, and the node
  is last).
- **Inventory, receipts and registry.**
  - A lost or deleted inventory is a cache miss, so the app runs the known-table pass again.
  - A lost receipt means preparation runs again.
  - A lost `LOADED` leaves `LOADING`, which the next open marks `INTERRUPTED`, and the metadata is
    resolved again.
- **Catalog.** A lost catalog publish leaves the previous generation current, or an `INCOMPLETE`
  pending generation that the next `replace` retires. A lost `retainOwner` or `expect` is
  re-declared on the next start, because `BuildApplication` re-retains every receipt's owner.
- **User preferences, bookmarks and download intents.** These fall back to their previous value.
  The policy allows this.

### The loss window

With everything at NORMAL, the WAL is synced at:

- every checkpoint (SQLite's auto-checkpoint at 1000 pages, about 4 MiB);
- every remaining FULL commit (listed below);
- clean close (`wal_checkpoint(TRUNCATE)`).

A crash can lose at most the commits since the last of these.

## Decision table

| # | Site | Before | Decision | Reasoning |
|---|------|--------|----------|-----------|
| 1 | `ObjectStore.commitPending`: the library's grouped index transaction for each generated table and each batch of objects | pack fsync, then FULL | **NORMAL.** The pack fsync stays. | The fsync orders the bytes before their rows. A lost commit drops a suffix: the pack tail is truncated on reopen and the table is regenerated. This is the hot path, one commit per cold table. |
| 2 | `ObjectStore.append`: the library's ungrouped index transaction | pack fsync, then FULL | **NORMAL.** The pack fsync stays. | Same argument as #1. |
| 3 | `ObjectStore.claimExisting`: an owner attaches to an object that is already stored | FULL | **NORMAL** | Same database as the object rows and every reader of the claim. Nothing outside the database acts on the claim. Payload bytes are removed only by compaction, which is FULL (#14) and syncs this claim first. `ObjectStore.delete` drops index rows (tombstones) through the same NORMAL append without consulting other owners' claims, which predates this change. The tombstone, its row change and any claim are all in one WAL, so the suffix argument covers them too. A lost claim leaves the object unowned by this owner, and the next put or claim re-attaches it. |
| 3a | `Staging.commit`: a staged download (up to 512 MiB) is copied into the pack, the pack is fsynced, the row commits through `append`, then `release(true)` deletes the staged `.part` file | pack fsync, then FULL | **NORMAL. An allowed loss.** | The one file action after a NORMAL commit. If the OS drops the commit but keeps the unlink, reopen truncates the pack tail, the staged file is gone too, and the object is downloaded again from the start. That is loss, not corruption: no row ever points at missing bytes. The policy allows losing a recent acknowledged write, and this is one. |
| 4 | `LibraryCatalog` writes (`retainOwner`, `expect`, `replace` begin/append/publish, `forget`, `reclaim`) | FULL per transaction | **NORMAL** (the connection now sets it explicitly) | The catalog shares the library's WAL. It "never touches stored payloads", and `reclaim` deletes only reference rows. Lost commits are handled as described under Catalog above. |
| 5 | `SqlPreparationReceipts` (receipts) | FULL | **NORMAL** | Written after preparation, in the same WAL as what it summarizes. A surviving receipt therefore implies its tables and inventory survived. A lost receipt means preparation runs again, which the inventory restore makes cheap. |
| 6 | `BuildRegistry` | FULL | **NORMAL** | `LOADED` is written after `openMetadataBuild` returns, so after its objects committed in the same WAL. Its catalog counterpart `catalog.sqlite` is a separate, refetchable observation cache that is already NORMAL (#17). The registry stores its own `BuildRef` and needs nothing in that cache to be durable. |
| 7 | `UnifiedStorageAccounting` | FULL | **NORMAL** | Reporting over the shared catalog. It never mirrors physical presence, and its figures are recomputed from catalog rows in the same WAL. |
| 8 | `DownloadIntents` | FULL | **NORMAL** | User policy and last outcome. A lost write restores the previous intent, and a download resumes from what the library actually holds. |
| 9 | `SourcePreferences` | FULL | **NORMAL** | Hints and timestamps (`checked.*`, `latest.*`, defaults). `tact.document` names a library object in the same WAL. Questie defaults name release folders that are fsynced before the preference is written and are never deleted by a preference change, so a lost default falls back to an existing or empty value. |
| 10 | `SettingsPreferences` | FULL | **NORMAL** | User settings. A lost write restores the previous value. |
| 11 | `WorldBookmarkStore` | FULL | **NORMAL** | User bookmarks. A lost write loses the bookmark. The policy allows this. |
| 12 | Pack fsync before index rows (`StoreIo.sync` in #1 and #2) | fsync | **Keep** | Data before metadata. Without it, a surviving row could point at bytes that never reached the disk. |
| 13 | `ObjectStore.activePack`: new pack header fsync, directory fsync, then FULL commit that lists the pack and seals the previous one | fsync + FULL | **Keep** | It runs once per 2 GiB pack. A sealed pack (`state=1`) must match its length exactly. The listing must be durable before objects go into the new file. |
| 14 | `Maintenance.compact`: listing the target as `state=2` (FULL), target fsync and directory fsync, then the switch (FULL), then deleting the source pack | FULL + fsync | **Keep** | The listing is an intent record that lets recovery delete a partial target. The switch must be durable before the source pack is deleted, or a lost switch would point rows at a deleted file. It is off the hot path. |
| 15 | `Maintenance.recover`: tail truncate, fsync, FULL | fsync + FULL | **Keep** | It records the truncated length before later writes reuse the tail. It runs only at open time. |
| 16 | `Maintenance.scan`: seal and rebuild transactions | FULL | **Keep** | An explicit full rescan that rewrites the index. It is rare. |
| 17 | `ObjectStore` open: `clean=0` (FULL), `LibraryOwnership.initialize` (FULL), index rebuild (checkpoint, replace, directory fsync), tail truncation and fsync | FULL + fsync | **Keep** | Once per open, so not worth weakening. The clean marker brackets the session's file actions (tail truncation, unlisted-pack deletion). |
| 18 | `ObjectStore` close: `clean=1` (FULL) then `wal_checkpoint(TRUNCATE)` | FULL | **Keep** | Once per close. The checkpoint syncs everything anyway. |
| 19 | `abortPending` / failed append: truncate and fsync the pack after a confirmed rollback | fsync | **Keep** | A file action that follows a rollback, not a commit. It removes uncommitted bytes. |
| 20 | `CatalogStore` (`catalog.sqlite`, a separate file) | NORMAL | Unchanged | The direction that matters is `catalog.sqlite` depending on the library. Its `entries` rows are projections of library history rows and carry their `record` and `document` keys. It has its own WAL and checkpoints, so it can now durably outlive a lost history commit, which FULL library commits used to prevent. That is harmless: main code never dereferences `BuildRegion.lastRecord` or the document key, and `has()` only skips re-ingesting. The worst outcome is a listed build whose observation the library lost, which the next discovery observes again. |
| 21 | `ItemGridStore` / `DerivedDatabase` (`derived/grids`, separate files) | default | Unchanged | Disposable grids keyed by build identity and computed deterministically from the tables. Regenerated tables give the same grid. |
| 22 | `ClientHotfixStore` staged file `force` before rename | fsync | **Keep** | Data before the rename that publishes it. Independent of the library. |
| 23 | Questie, EG link and Polestar repositories (`force` and their own SQLite files) | fsync | Unchanged | Independent source caches, durable before any preference names them (#9). |
| 24 | `WorldStreamTileFile` / `WorldStreamObjectsFile` `force(false)` | fsync | Unchanged | Handoff files to the renderer process. Not part of the library and outside this audit's scope. |
| 25 | `SqliteObjectData` (`synchronous=FULL`) | FULL | Unchanged | Not used in production (`SqliteGeneratedStore` and `LibraryImporter` only). |
| — | `NativeSqlite.transaction(full, ...)` | — | Unchanged | Still offers FULL for #13–#18. |

## Tests

`TableLostCommitCrashTest` (formats-tables integrationTest) simulates lost commits, not just a
killed process. It reuses #231's `TableCommitScenario`, the `CrashProcess` handshake and
`WriteProbes`, and it uses `WalFrames`, a WAL frame reader and editor in the object-store test
fixtures.

**The session.** The previous state is tables 300 and 400, their saved inventory and catalog
generation v1, closed cleanly. A child JVM then makes a FULL sync-point commit, records the WAL
length, header and database hash, and writes each record the application keeps, each acknowledged
before the next:

1. a known-table pass (tables 100 and 200);
2. the inventory replacement (a delete, then a put);
3. catalog generation v2 (three transactions);
4. a receipt written through its own NORMAL connection, like the application's stores;
5. a second build's claim on table 100's existing node (`claimExisting`);
6. a staged download, committed with its staged file deleted (`Staging.commit`, #3a).

The child is then SIGKILLed. The parent asserts that the WAL header and database are unchanged since
the sync point, so no checkpoint happened and every frame after it is a valid candidate for loss.

**The losses applied:**

- **Every suffix.** Keep the first k frames, for every k from the sync point to the end. That is 49
  cases, including torn transactions.
- **Reordered writeback.** Lose each commit frame but keep the frames after it. The state must equal
  the prefix before that transaction.
- **Torn page.** For each commit frame, keep its header (valid salts) but corrupt half its page, so
  only SQLite's checksum chain can reject it. The state must again equal the earlier prefix.
- **Pre-commit snapshot.** The database and WAL as copied just before the second table's index
  commit, with every later fsynced pack byte kept.

**After each loss**, the library is reopened (the `-shm` file is removed, as after a reboot) and the
test asserts:

- `verify(full)` reports nothing corrupt or lost;
- the previous tables are intact;
- each crashed table is all or nothing;
- a surviving inventory verifies against its graphs;
- a current catalog generation names only stored objects;
- the whole state is exactly one prefix of the session;
- the survivors only grow with the frames kept, and the cases reach every stage.

The test then rebuilds: with no receipt it prepares again, a lost claim is claimed again, and a
lost download is fetched again. It asserts the reference ledger,
identities and rows, then reopens once more.

**Mutation check:** with the library's tail truncation disabled, the reopened store replays the
fsynced pack tails and resurrects commits whose index was lost. The prefix and snapshot assertions
then fail.

### Pinned modes

A simulated crash cannot tell a durable FULL commit from a NORMAL one that happened to reach the
disk, so `LibraryDurabilityModeTest` (object-store integrationTest) pins the mode instead. On a
library store with small packs it records `PRAGMA synchronous` at every index `COMMIT`, together
with the maintenance points, and asserts:

- open's commits are all FULL (#17);
- a first put is a FULL pack listing, then NORMAL rows (#13, #1);
- a claim, a staged commit and a delete are NORMAL (#3, #3a, #2);
- a pack rollover is FULL listing and seal, then NORMAL rows (#13);
- compaction's commits are all FULL: the intent row commits before `COMPACT_TARGET_LISTED`, and the
  switch commits FULL right after `COMPACT_SWITCHING` and before `COMPACT_OLD_DELETED` (#14);
- scan's commits are all FULL (#16);
- close ends with a FULL commit (#18).

Each of these mutations fails it: making the compaction switch NORMAL, making the intent row
NORMAL, and returning library data commits to FULL. `Maintenance.recover` (#15) runs only for a
non-library tail at open and is not pinned here.
