# Urgent client hotfix cache review

## Finding

The reported gap needs fixing. Current ClientBuildTables prepares/generated-indexes
physical DB2 rows through Db2Reader and has no DBCache/XFTH overlay. TalentsService
uses ordinary table lookups, so hotfix-only spell rows are absent and hotfixed base
rows remain old. BuildTactKeys similarly ID-joins ordinary base TactKey and
TactKeyLookup pages. This is a source-data problem, not a talent-label fix.

The reported Forever70707 cache is not available in the inspected Linux paths yet.
Its28567 rows and specific spell/key rows remain user-reported evidence. Existing
local prior-project evidence is XFTHv9 build70450,3827112 bytes,49919 entries; it
must NEVER be substituted for the selected70707 build. Read-only structural scan
observed region70 and all four nonzero states. No acquired bytes or key values
are committed or logged.

## Primary format and state evidence

Pinned implementation reference: wowdev/DBCD commit
`c0aeb5f6be0e9fbffc8eadb4f8b9b4e36b42af7c`.

- [HTFXStructs](https://github.com/wowdev/DBCD/blob/c0aeb5f6be0e9fbffc8eadb4f8b9b4e36b42af7c/DBCD.IO/Common/HTFXStructs.cs): v9 entry has region, push, unique ID, table hash, record ID, payload size, state and three padding bytes. With the XFTH signature this is32 bytes. States0..4 are NotSet, Valid, Delete, Invalid, NotPublic.
- [HTFXReader](https://github.com/wowdev/DBCD/blob/c0aeb5f6be0e9fbffc8eadb4f8b9b4e36b42af7c/DBCD.IO/Readers/HTFXReader.cs): file header44 bytes for v9; row decoding follows logical field types and C strings. Noninline ID is omitted from payload while relation fields remain logical fields. DB2 bit packing is not the hotfix body encoding.
- [HotfixReader](https://github.com/wowdev/DBCD/blob/c0aeb5f6be0e9fbffc8eadb4f8b9b4e36b42af7c/DBCD.IO/HotfixReader.cs): operation handling considers push order and cached records; NotPublic is ignored by this reader. Its special invalidation exception covers TactKey and BroadcastText, NOT TactKeyLookup. This is community reader behavior, not proof that every client/server cache uses identical masking semantics.

Bundled manifest identities: TactKey DF2F53CF/file1302850; TactKeyLookup
AFC190D1/file1302851; BroadcastText021826BB. In the70450 evidence, TactKey has14
valid cached(Push=-1) bodies of16 bytes and17493 NotPublic cached entries with
empty bodies; TactKeyLookup has no cache entries. A hotfix row count therefore
cannot be equated to usable encryption keys; the actual same-build ID join remains
required. Secret values and per-key fingerprints are excluded from this evidence.

## Parser first slice

HotfixCacheReader is a source-neutral structural reader only. Default bounds:
64MiB source,65536 physical records,1MiB per body, one64KiB streaming buffer.
It reads exactly44 bytes before matching the expected build, rejects unsupported
versions/states/reserved bytes and malformed intervals, hashes the whole actual
read stream and each body, preserves every physical ordinal/duplicate/region/push,
and requires exact EOF. It applies no row operation and loads no key.

The immutable private-constructed Index borrows the original immutable
Db2ByteSource identity; the caller keeps that source alive through all reads.
Selected payload reads require that literal source, enforce a caller body bound
before allocation, recheck length/readability and compare the actual selected body
SHA before transfer. It retains no full-file/body mirror. Underlying read failures
remain failures; cancellation waits for the actual outstanding lower read.

This metadata does not authenticate Blizzard/server origin and is never a native
DB2 receipt. The application must first import a selected local cache into a
private immutable snapshot, pin its whole digest and bind build/product/locale and
source association. Equal numeric build alone is insufficient to silently mix
custom server caches. The opaque32-byte header extension is not treated as a
verified signature or layout identifier.

## Required completion partitions

1. Explicit read-only local cache selection/import with no guessed external fallback;
   build mismatch disables overlay, and mutation forces reimport/new generation.
2. Schema-aware bounded flat row decoder, exact selected logical DBD layout and
   body exhaustion. Unsupported rows stay explicit, never silently NOT_FOUND.
3. Effective ordinary table view: add/replace valid rows, handle explicit deletion,
   preserve incompleteness/protection and define duplicate/push/region policy.
   NotPublic/Invalid cannot blindly become known deletion or expose stale base
   data as effective truth. A product masking policy must be recorded explicitly.
4. Cache/page/lookup identities include snapshot digest, overlay/decoder policy,
   selected schema and build/locale association. Raw captureRows and native
   certificates remain physical DB2; no forged base CKey/stamp for effective rows.
5. Key bootstrap uses effective ordinary TactKey plus Lookup rows through the
   existing ID join and source/key policy. Conflicts, missing lookup, zero keys,
   partial base data and invalidation are explicit; rebuild key-sensitive caches.
6. Source details distinguish base DB2, local hotfix, protected/unresolved overlay,
   and cache incompleteness. Missing local rows never prove server-wide absence.

Required tests span valid replacement/addition, actual deletion, masking/unknown
states, duplicate ordering/regions, stale or mismatched build/locale/layout,
malformed bodies, cancellation/drain, mutation, cache identity changes and
hotfix-provided keys without expanding network/community-key consent. Verify the
reported70707 spell rows against that exact snapshot before claiming the talent
examples fixed. Do not use the comparison website's70753 content as70707 truth.

Status: pure container implementation and tests are isolated for review. No app
activation, effective rows, keys, physical DB2 receipts or GUI behavior changed.

## First-slice validation

Frozen container fa718f74 passes full formats-tables checks:47tasks,14executed.
Six authored tests cover physical repeats/order, unsigned IDs, exact same-stream
SHA, strict header-only build refusal, chunk-boundary reads, malformed/version/
state/reserved/range/row/body limits, original source failures, held lower-read
cancellation, immutable-source association and selected-body mutation rejection.

Read-only Java proof using that exact parser passes on the existing70450 cache:
3827112bytes,49919entries, VALID16074/DELETE244/INVALID16068/NOT_PUBLIC17533.
Five selected non-key bodies pass the actual body-SHA reread. The original file
was opened READ/NOFOLLOW; no overlay/key activation or new game asset was written.
Private diagnostic log: `/home/aircan/.cache/vimi-tmp/hotfix-container-proof/proof.log`.
These are format/byte-read facts only, not evidence for the requested70707 rows.

## Explicit conservative effective-row policy v1

Root review pins an application policy rather than claiming exact DBCD/client
parity. Stable signed push order and physical ordinal break ties. VALID nonempty
bodies add/replace only after layout decoding. NOT_PUBLIC masks as protected;
INVALID, NOT_SET, empty VALID and unsupported bodies mask as unknown, so stale
base values never masquerade as effective data. Explicit DELETE is a known
negative, subject only to the narrow cached exception below.

For TactKey DF2F53CF and BroadcastText021826BB only, preserve an already selected
VALID nonempty cached(Push=-1) row when the SAME record ID later has a cached
Push=-1 DELETE/INVALID/NOT_SET/empty-VALID entry. NOT_PUBLIC still masks. A real
positive-push update/deletion follows normal policy; an unrelated cached-valid ID
never suppresses another ID's operation. This intentionally differs from DBCD's
global table-wide exception and must be named in effective-source provenance and
regression tests. TactKeyLookup has no such exception.

[TrinityCore's HotfixHandler](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Handlers/HotfixHandler.cpp)
also distinguishes missing/invalid hotfix data from explicit record removal. This
supports keeping unknown states separate from known absence, but does not prove
our masking policy is byte-for-byte client behavior. Local cache incompleteness
and region/product/build/locale association remain explicit limitations.
