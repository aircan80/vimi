# Raw table row comparison proposal

Next design slice for #117, following the session-only inventory foundation. **Design only; requires independent architecture and WoW/data-contract approval before implementation.** This proposal adds one explicitly selected table pair at a time. It does not authorize semantic item/spell diffs, cross-layout mapping, acquisition, persistent reports, or closing #117.

## User-visible scope

After choosing two compatible builds/locales and a table in the inventory comparison, the user can request **Compare readable rows locally**. Show supported raw-column value changes, copy-record metadata, and observed-only IDs with typed uncertainty. Label results **raw table findings**, not gameplay changes. Known-equal rows are omitted, as in the existing comparison core; unassessed columns are listed once in the field-scope manifest rather than manufactured as unknown cells on every row.

Different layouts or incompatible definitions show a table-level explanation and the existing inventory/schema differences. They do not produce an apparent deletion/addition of every row. Missing/unavailable tables remain inventory outcomes; an inaccessible endpoint is not an empty raw table.

Only one table pair is active. Raw-row comparison is an optional second local action, not an automatic scan of every table when inventory opens. Selecting a table or scrolling its inventory row does not start decoding, sorting, or comparison. Preparation/acquisition remains a separately authorized workflow.

## Inspected API facts

- [BuildTables](../../formats-tables/src/main/java/com/vibeminer/formats/tables/BuildTables.java) provides `schema`, `page`, `get`, and asynchronous close. Calls borrow the supplied session and may prepare/decode data; their public signatures do not guarantee cache-only access or zero writes.
- [TableTypes](../../formats-tables/src/main/java/com/vibeminer/formats/tables/TableTypes.java) provides Entry/content key/layout/counts, schema plus flattened columns, Page/identity/nextAfter, and exact FOUND/ABSENT/PROTECTED/UNKNOWN outcomes.
- [TableQueries.page](../../formats-tables/src/main/java/com/vibeminer/formats/tables/TableQueries.java) advances **ordinal positions**, not sorted row IDs. `Row.ordinal` and PageRequest.after must not become comparison identity or be mistaken for the unsigned-ID order required by `ComparisonInput`.
- [ClientBuildTables](../../formats-tables/src/main/java/com/vibeminer/formats/tables/ClientBuildTables.java) checks supplied page identity and returns STALE on mismatch through TableQueries. Preparation may select a different generated/direct source on a later call. Exact `get` returns ABSENT when its prepared entry is READY; misses in PARTIAL sources remain UNKNOWN or PROTECTED. An exact result does not include the same page-identity token, so it is not automatically a frozen-pair absence certificate.
- [TableSchema](../../formats-tables/src/main/java/com/vibeminer/formats/definitions/TableSchema.java) describes kind, width/signedness, array length, reference target, inline/noninline ID/relation storage, verified names, and locale. Full record equality also includes comments and match provenance: do not substitute it for a deliberately specified semantic fingerprint.
- [Db2Types](../../formats-tables/src/main/java/com/vibeminer/formats/db2/Db2Types.java) exposes unsigned32 row IDs, copy source, flattened string cells, per-cell TextIssue, and integer interpretation/narrowing metadata. [Db2Numbers](../../formats-tables/src/main/java/com/vibeminer/formats/db2/Db2Numbers.java) expands arrays as `Field[0]`, `Field[1]`, etc.; it can preserve raw physical integers whose semantic reference/calculation is explicitly unverified.

These observations are based on the approved design and core branch through `55776c6`. The inventory adapters and coordinator are being integrated separately; reuse their accepted local-session capabilities rather than assuming their implementation here.

## Pair admission and field identity

Pin both endpoint products, BuildRefs/config identities, displayed versions, locales, table name and FileDataID, CKeys, observed layout hashes, selected DBD document/block identities, decoder/projection versions, effective integer interpretation rules, keys fingerprint, and encoding/source scope. Default same product and locale only. The initial table pairing requires matching FileDataID and logical table identity within the compatible product lineage. Renamed/replaced FileDataIDs or branch-crossing record histories need a later explicit crosswalk.

First implementation admits **exact matching observed layout hashes and compatible selected schema/decoder interpretation**. Require a nonempty observed layout hash on both sides; absent hashes are not an equal-layout match. For this first slice use the same captured DBD document digest and selection policy for both sides, with verified compatible selected blocks. A differing document digest remains a separately explained interpretation mismatch until a reviewed fingerprint policy permits equivalent revisions. Equal hashes alone do not prove that two selected DBD blocks give equal meaning.

The reviewed structural fingerprint must include ordered schema fields, kind, integer width/signedness, array shape, reference target, ID/relation storage, verified-name flag, locale where relevant, and the flattened column metadata including interpretation policy and narrowing. Descriptive comments, endpoint version labels, and source timestamps are evidence, not column identity. Any difference in a required structural property refuses raw-value comparison for that table pair in the initial slice. Later cross-layout alignment is a new reviewed capability, not matching columns by name/ordinal on the fly.

Row identity is `Db2Types.Row.id` in the table namespace, validated as unsigned32 including zero. Canonical `ComparisonTypes.Record.key` uses the framework's fixed-width20decimal representation. Never use row ordinal, string name, section number, foreign-key value, or copy source as the row identity. Copies retain their own ID. Emit explicit copy metadata (`isCopy`, known copy-source ID when applicable) under a raw-record metadata label; a physical-to-copy representation change is not automatically a gameplay change.

Each assessed scalar or array element gets a deterministic field key derived from the compatible schema field identity plus array element index, with explicit kind and a versioned semantics string. Derive flattening from schema array lengths and verify the corresponding actual Column name/kind/ID/interpretation metadata. Check cell counts before reading indices. A mismatch is a malformed/unsupported row outcome, never positional truncation followed by a claim of equality.

## Supported cells and explicit exclusions

| Input | Proposed initial comparison policy |
| --- | --- |
| Verified integer scalar/array element | Validate against captured signedness/width; canonical decimal integer string and INTEGER kind. No boolean/enum/stat/unit conversion merely because a field name looks familiar. |
| Foreign-key integer | Compare recorded ID as REFERENCE with the exact target table/column in semantics; do not resolve target labels or recursively follow links. A missing referenced row is independent of whether the source ID changed. Zero remains a recorded zero; do not silently map it to null or absent target. |
| Finite float | Preserve the decoder's deterministic SingleText representation and DECIMAL kind under an explicit float32-text policy. [SingleText](../../formats-tables/src/main/java/com/vibeminer/formats/db2/SingleText.java) round-trips finite float bits and distinguishes `-0` from `0`. No decimal rounding, tolerance, unit guessing, or NaN normalization. This compares supported decoded values, not raw storage/compression bytes. |
| String / localized string | Compare exact decoded text in the same requested locale. No whitespace trimming, case folding, Unicode normalization, tooltip substitution, or locale fallback. Decoded empty text with no issue is KNOWN_EMPTY; unavailable/invalid text is not empty. |
| Arrays | Compare each recorded element at its explicit zero-based index. Preserve ordering and zero entries; no sorting/deduplication or treating an array as a set. Array-shape mismatch is admission failure for this slice. |
| Missing noninline relationship | `DB2_RELATION_ABSENT` currently accompanies a placeholder empty string. Emit UNKNOWN for that cell, retaining issue evidence; never interpret it as zero or a removed relationship. |
| Invalid text evidence | `DB2_TEXT_ENCODING_INVALID` can accompany an empty placeholder. Emit INVALID and bounded raw-byte/code evidence; never compare the placeholder as a real empty localized name. Other valid cells survive. Unknown issue codes conservatively make the affected cell unassessed/unknown. |
| Raw/unverified integer interpretation, candidate narrowing | Excluded from initial assessed-column scope unless an individually reviewed policy admits both sides with identical physical/candidate interpretation. A raw numeric string must not become an authoritative foreign key or narrowed gameplay value. Report exclusion once in the table field manifest. |
| Unverified column name | Excluded from first assessed scope. A later opaque physical-column diff can be useful but must have its own exact-layout, physical-field identity contract and explicitly avoid semantic labels. |

If no cells are admissible, report unsupported field scope instead of staging a finding for every row. If a particular readable row contains invalid/protected/limited cells, retain the row and its valid cells. Field semantics/state changes remain distinct from confirmed value changes. The adapter must not turn text issues or unexpected missing cells into a global table-wide failure when useful cells remain.

Reference target names, enum meanings, spell formulas, item stat scaling, locale fallback, class masks interpreted as eligibility, and model previews are outside this slice. Column values changing is the narrow claim; gameplay interpretation belongs to later reviewed concept adapters.

## Local-only capture and lifetime

Reuse dedicated comparison-owned endpoint sessions, not the app's currently browsed BuildTables. Freeze definitions, key status, source configuration, and the source-retention contract before capture. A local-only ObjectSource and local definitions are required for opening **and** reading; merely passing BACKGROUND priority or using an already open BuildTables does not disable network access.

Default first slice reads an already prepared local table graph under a captured source identity. If the current public capability cannot certify that a page/schema operation will only read that graph, return Unavailable/Not prepared and keep the inventory usable. Do not fall back silently to normal prepare/decode/download. A separately reviewed explicit local-decoding capability may later permit decoding already downloaded table bytes with independent parser limits; that must not be introduced accidentally through `BuildTables.page` during browse. Existing generated graph reuse is not permission to publish persistent comparison reports.

Source capture makes a bounded ordinal pass with no filters, PageRequest.after initially `-1`, limit no greater than256, and subsequent requests carrying the first returned page identity. Validate every page's table ID/CKey/layout/schema scope against the captured receipt; the first response must also match the receipt, not simply establish a new identity for whatever source happens to be current. Never restart silently on STALE. Pin exact observed source availability/keys; live daily key refresh can create a new run, not mutate this capture.

Preserve page diagnostics and stop with typed partial/limited/failure on stale identity, I/O error, unexpected cell shape, impossible cursor/count, or quota. No filters, ordinal sampling, maximum-scan prefix, or count approximation may be labelled a complete source. A canceled capture can be displayed as an incomplete session only if its identity and readable prefix remain valid.

Adapters own their scratch input snapshots and their pending reads; the core drains adapters before the coordinator closes borrowed table/file/build sessions. Closing/leaving cancels capture, sort, merge and result jobs in dependency order. Do not close a shared provider repository or the browsed build. No source lease/reproducibility claim until the actual table graph/definition retention path is verified.

## ID ordering and shared resource budget

Ordinal pages cannot feed `ComparisonService` directly. Introduce a bounded session-local sorting/capture adapter that emits the existing `ComparisonInput` interface in strict canonical ID order. It uses no SQL, GUI parsing, persistent GeneratedStore publication, or complete in-memory row map.

Suggested practical implementation:

1. Capture one selected endpoint at a time into bounded canonical chunks (target at most12MiB of conservative record/object accounting). Sort each chunk by canonical row key and write a temporary sorted run. Reject duplicate IDs within/across runs rather than collapsing records.
2. Merge runs with a two-reader fan-in and one bounded writer on the same comparison job. Each input/output buffer has a byte cap as well as a row cap; no unbounded open-file or per-run-head collection. Multi-pass merge accounts for simultaneous source and replacement run bytes; replace/delete old runs only after a sealed successor is available.
3. After both endpoints are captured/sorted, expose ordered pages up to256rows to the existing findings-only core. Release exhausted canonical pages before reading replacements, retaining the reviewed core turnover invariant. Input adapter sources stay immutable for the run.

The **32MiB working-buffer and512MiB temporary staging policies apply to the whole selected-table comparison**, not independently to each endpoint sorter and again to the core. Capture/sort and comparison phases must not overlap their full memory reservations. Source snapshots retained during result generation and old/new runs during sorting count toward the same disk quota. The current core has a private per-service staging cap, not a shared temporary-budget capability: implementing reviewed shared reservations/admission is a prerequisite, not something an adapter can assume already exists. No disk-quota multiplication through separate temp directories.

Maintain one active job, at most two borrowed-source reads concurrently, queue8, input pages256, result pages100, canonical/result frame1MiB and5million observed keys per selected-table scope. New sorter limits must account for individual oversized fields, conservative object estimates, sparse indexes and framing overhead. Return LIMITED with remaining membership unassessed when shared storage/memory/identity quotas are reached. Existing DB2 parser defaults separately include256MiB decoded bytes,2million rows and other indexes/auxiliary limits; a32MiB comparison budget must not be presented as a cap on those internal allocations.

Scratch belongs to this live session. Close removes all sorter/input/result staging, including cleanup after errors. Application startup needs a safe stale-session cleanup policy after crashes. No durable pause/resume, immutable report reuse, two-build publication, or new catalog owner/reference protocol is authorized here.

## Coverage and membership truthfulness

First implementation can deliver useful changed-field findings with **UNKNOWN/PARTIAL membership coverage**. End of all readable ordinal pages proves the readable scan ended; it does not by itself prove that every logical row was visible. PARTIAL sources retain readable records even when other sections are encrypted or excluded.

To set `ComparisonTypes.Coverage.COMPLETE`, a reviewed source certificate must establish all of:

- A pinned full selected-table/locale source, compatible row identity/schema and complete unfiltered scan under one page identity.
- An audited reader/graph completeness receipt covering physical and copy records, with no inaccessible sections, holes, unresolved/protected copies, omitted IDs, or source truncation.
- Consistent declared/readable/enumerated counts and a duplicate-free sorted merge, with no stale/cancel/error/limit outcome. Counts are consistency checks, not the certificate by themselves.

Current `BuildTables` exposes READY and aggregate Counts but not the complete typed reader receipt through a stable captured-row handle. **Do not certify COMPLETE from READY + exhausted pages alone.** Decide in independent review whether a small public captured-row/completeness capability is justified; otherwise this raw-row MVP intentionally keeps observed-only membership labels. Do not query private generated tables/SQLite from GUI or let the comparison component infer completeness from error-message text.

An optional exact `get(id)` result of ABSENT has narrower meaning. It is only usable with the same captured source guarantees and explicit API semantics. The current comparison core classifies membership from whole opposite-scope Coverage, not per-ID absence: do not promote one exact miss to COMPLETE for an entire table. A future detail-only exact-absence annotation or per-key certificate requires explicit review; no unbounded lookup for every merged ID or N×table scan is proposed.

Changes from protected/unknown to readable after key updates are availability outcomes, not proof that rows were newly added by Blizzard. With unequal key fingerprints, comparison still reports observed known field values where admissible, while missing-side records remain observed-only without absence certification. Copy aliases keep their distinct IDs; an absent source-copy dependency does not justify fabricating an empty alias row.

## Existing framework integration

Use `ComparisonInput.Descriptor` for the side-specific captured identity, product/locale, `raw-table` namespace including verified table identity and compatible schema policy, explicit assessed scope, and dependency manifest. Source CKeys and page identities differ between endpoints and belong in their own descriptors/evidence, not an artificial requirement that both builds have equal content.

Emit `InputPage` with a stable captured snapshot identity and sorted `Record` keys. Fields carry typed state/kind/semantics and bounded evidence. Relevant interpretation or winning-source facts must be explicit INTERPRETATION/EXTERNAL_EVIDENCE fields if assessed; `Evidence` is explanatory annotation and is not directly compared. Endpoint-only source identities must not create a finding on every otherwise equal row. Changed schema/definition admission is reported once as an area capability/inventory outcome rather than injected into all row fields.

Use existing core known-equal omission, delayed opposite-coverage membership classification, session paging and cancellation. Do not duplicate the merge/classifier in the adapter. Distinguish scanned identities from staged findings in progress. A complete run over readable fields can still have unknown membership or excluded columns: publish those scopes explicitly in the run description.

## Implementation/review gates and offline validation

Before code: independent review of this proposal, local captured-row/read capability, shared sorter/core resource reservations, and the precise selected-schema fingerprint. No approval for these prerequisites is inferred from the earlier inventory-only approval.

Recommended first deliverable after approval is one selected exact-layout table pair, verified scalar/string/array/reference fields, partial-state findings, bounded sorting and session-only paging. If captured completeness is unavailable, ship honest observed-only IDs; do not delay useful known-cell comparisons or relax absence rules.

Required deterministic fixtures:

- Ordinals deliberately unsorted by ID; unsigned IDs including zero/max; duplicates across sort chunks; copy and physical rows with the same values but different own IDs.
- Exact layout with compatible fields; same layout with changed signedness/reference/interpretation; different layout; mismatched cell/column counts; unverified fields excluded once.
- Ordered arrays, large signed/unsigned values, negative zero/tiny finite float, exact localized whitespace, invalid UTF-8 placeholder and missing relation placeholder beside valid cells.
- READY but uncertified input, excluded encrypted section, protected copy, stale identity after an initial page, known-value change amid unknown cells, and keys changed without changed file content.
- Forbidden network client during open/schema/rows; unavailable local graph does not fall back; closing comparison leaves browsed build handles alive.
- More rows than a chunk, two-way merge across several passes, combined old/new-run quota, oversized cells, duplicate rejection, cancellation during capture/sort/merge, drain-before-close and complete scratch cleanup.
- Many equal rows plus sparse changes fit a small findings budget; endpoint-only evidence identities create no findings; per-side interpretation/cell limitations remain visible without an unchanged-row listing.

No production data or live credentials are needed for these tests. A later read-only paired-build proof must report the exact source identities, assessed columns, coverage limitations and measured resource use; it must not commit Blizzard payloads or local configuration.
