# Terrain liquid source qualification API checkpoint

Design only, following approved [raw MH2O](world-liquids-raw-checkpoint.md) and [liquid source proposal](world-liquids-proposal.md). No implementation, integration budget increase, renderer, native packet, GPU/CPU water draw or public positive constructor is granted here. Alpha21 files remain untouched.

## Narrow output and authority

The first source payload is `STATIC_CONSTANT_HEIGHT_DEPTH0`: exact decoded cells whose **actually used** height samples are identical numeric float values (canonicalizing signed zero only for the equality/height scalar). It does not use min/max to construct height. Unused finite slots are preserved during layout validation but do not affect constancy. All-clear masks yield source-qualified EMPTY, not a fabricated rectangle. Nonconstant used samples are `NONPLANAR_PROFILE` unresolved even if min=max claims otherwise; the format reader independently rejects used samples outside those bounds. Depth bytes and original raw/source ordinals remain source facts, with no optical or swim semantics. This permits exact lake/ocean planes without choosing the unresolved nonplanar quad diagonal. It does not authorize any triangle diagonal or draw yet.

A package-private `WorldTerrainLiquids` factory is owned by the existing world source service and invoked **inside its existing one-active terrain admission**, never as another acquisition pipeline. Proposed shape:

```java
// All constructors private except the service-owned factory; types shown conceptually.
static OptionalLiquids extract(TileAdmission admission, CheckedRoot root,
                               RootGridWitness grid, Cancellation token);
static <R extends AutoCloseable> R withQualifiedLiquids(
        OptionalLiquids owned, LiquidRead<R> callback); // future writer not yet granted
```

`CheckedRoot` and `RootGridWitness` are private factory-local capabilities, not public LoadedTile/TerrainGrid/RawLiquids inputs. They are constructed only while the actual admitted ROOT stream, FDID/CK/consumed length, private scope/MAID tuple, root geometry fingerprint and source lifetime are owned. The same immutable authenticated root bytes feed the shared checked ADT walker, raw MH2O reader and native grid inspection. No second root read, caller mesh reconstruction, cache-only stale area data, mutable byte-array escape or public descriptor may certify their equality. A cached root geometry alone does not prove MH2O bytes; cache restore needs a matching private liquid receipt from that exact checked root or optional liquids stay unresolved until a later real read.

`OptionalLiquids` is private-constructed, source-owned, closeable and guarded. Detached `LiquidSummary` contains bounded counts/reason enums only. A synchronous same-worker borrow exposes scalar cell/height/depth/anchor facts, never arrays, RawLiquids, CapturedRows, full receipts, source handles or mutable NIO views. Saved/foreign-thread borrows reject and detach on expiry. Active callback pins survive close until callback finally; close/new-work rejection, source health, current-or-replacement scope and cancellation drain follow the existing terrain owner contract. A descriptive summary or generic callback R never mints a packet/renderable. Unreturned outputs belong to callback-local try/finally; returned outputs stay source-owned through final gates. A future resource writer needs its own review, including file MOVE and actual native/file retirement.

## Resolution rules and exact table profile gate

The pinned Trinity resolver is normative for this geometry profile. Raw type2 is explicitly unsupported here. For secondary<42 only direct0 is accepted; direct nonzero formats stay unresolved. For secondary≥42, raw LiquidType ID selects its actual LiquidType.MaterialID, which selects actual LiquidMaterial.LVF; accept only LVF0. Secondary is retained but is **not** used as the LiquidType row index. LiquidObject is not a geometry dependency. Object42 being absent therefore cannot block raw1250→Material130→LVF0; no object42 row/value is invented. LiquidObject flow, narrowing, physics and reflection remain unqualified.

The initial native table/layout profiles are restricted to the actual prepared source combinations recorded read-only in the approved proposal:

| Table | FDID | WDC/layout | Required logical columns |
| --- | ---: | --- | --- |
| LiquidType | 1371380 | WDC5/D1ECEEC9 | ID, MaterialID |
| LiquidMaterial | 1132538 | WDC5/98E5D7AA | ID, LVF |

These layout hashes are gates, not authority by themselves. At implementation checkpoint, genuine captured native schema evidence must pin each required field's exact stored/logical integer width, signedness, array length1, index/ID role, interpretation and narrowing before code accepts that profile. Do not infer unsignedness from nonnegative sample values, accept merely an `integer` UI column, guess a width from the raw u16 ID, or accept a narrowing annotation without a reviewed exact interpretation. This is an explicit pre-code evidence gate; this document does not invent currently uncaptured field descriptors. A profile that does not exactly match is optional `TABLE_PROFILE`, never coerced or generically resolved by table name. Implementers must submit the concrete descriptor tuple plus genuine positive/negative tests for review before qualification code promotion.

Actual rows1240/1250/1288 resolve MaterialID130, whose LVF is0. Those IDs are evidence fixtures, **not an allowlist or hardcoded positive path**. The factory derives all requested IDs from actual raw MH2O and selected source rows. Names, Flags, SoundBank, colors, texture strings and PBR arrays do not establish supported render materials and are not retained for this geometry profile.

## Native rows, issues, completeness and freshness

Use `capturePreparedLocal` sequentially, one handle at a time, no definition fetching, automatic preparation, generated writes or broad unbounded row fallback. Optional absence/default bounded-read marker returns `TABLE_UNAVAILABLE`; arbitrary provider UnsupportedOperationException, corrupt graph, unknown runtime failures or dirty cancellation remain fatal diagnostics, not source-negative water facts. Preserve original async outcomes with the reviewed trusted-source await helper.

Each table initially requires READY, native WDC5, no excluded sections/records and declared/readable/receipt row counts equal; LiquidType≤4096 rows and LiquidMaterial≤256. This does not relabel `CapturedRows.Membership.NOT_CERTIFIED` as complete-catalog certification. Establish unambiguous **observed selected ID** by a full bounded native ordinal scan with exact contiguous first/next/count progress and duplicate selected-ID refusal. Excluded/partial/protected tables are unsupported for this first liquid resolver; the Map-specific partial-ID witness must not be generalized implicitly. Selected ID absent after that scan means optional ROW_NOT_OBSERVED under this profile, not a hidden/protected/not-found catalog claim. Use native row ID metadata plus exact qualified ID cell correspondence; conflicting copy/ID observations reject. Retain only needed selected tuples (≤1024 raw types, ≤1024 material targets before layer admission), never a whole row/schema graph/index.

Before hashing any receipt identity, use the same bounded prehash scalar/column/section/native-issue metadata rules as reviewed Map qualification. Retain full canonical stamp equality, not hash-only authority. Validate VERIFIED_NATIVE issue evidence against the graph, resolved block, actual columns, reader options/revision, source identity tuple, build/locale/encoding/key scope and exact selected schema. Read with strict limits 256 KiB compressed block/128 KiB decoded block/64 KiB record/128 columns/128 issues/64 KiB cell UTF8. Required cells and row ID must have no relevant decoder issue/unknown channel; unrelated harmless columns are not interpreted, but unknown issue attribution cannot be treated as issue-free. The initial source profile may conservatively decline rows with any reported cell issue. Zero issue count without matching native issue evidence is insufficient.

After rows are consumed, close/drain each capture before the next. Before publication, reopen each table sequentially, repeat bounded metadata qualification and exact strict EOF probe, and require its full canonical stamp unchanged (including definition/key/layout/native issue/row domain); do not rescan unchanged data ordinals. Freshly stat the actual LiquidType/LiquidMaterial source FDIDs and require their original CK and consistent known consumed lengths where recorded. A changed table/source/scope is SOURCE_CHANGED; it cannot become stable ABSENT/EMPTY/height-only water. The already-qualified Map/WDT/MAID/root and every consumed binding receive the existing final admission checks after actual lower cancellation/read drains. Only then may the private optional owner transfer. No per-frame source IO is introduced.

## Overlap, chunks and coordinates

Raw inspection permits bounded unknown vertex lengths and therefore cannot prove global nonoverlap merely by guessing those ends. Source qualification must refuse a candidate known layout range if any unresolved vertex interval could overlap it; without a proven end, an earlier unresolved start remains ambiguous. A safe first implementation declines **all liquid geometry in a root containing an unresolved nonzero vertex stream whose footprint cannot be established**. Keep bounded unresolved counts, leave terrain intact. Zero vertex stream is unsupported, never a constant-height fill. Known payload/bitmap/header/attribute aliases remain malformed through the raw parser; one shared attribute object per header is legitimate.

Associate header ordinal with the pinned source MH2O header grid only after an authored asymmetric X/Y oracle pins it against actual native MCNK x/y, source anchors and WorldFormats/TerrainGrid sampling. Do not equate MH2O header ordinal with parse-order MCNK ordinal. The private root witness records source chunk key, actual checked anchor and geometry fingerprint; missing/duplicate/mismatched MCNK coverage refuses that header's liquids. Keep source-local rectangle/cell facts until native mapping is independently proven. An actual private overlay proof across two neighboring chunks/tiles remains a separate required gate before world-coordinate geometry, not inferred from parser agreement.

Overlapping present cells between layers are not solved by height sorting. First profile declines overlapping headers/layers for source geometry until a separately reviewed multilayer policy. Terrain holes never erase liquid-present cells. A capped or unsupported liquid profile must preserve original terrain geometry/texture ownership and reason counters.

## Sequential phases and ledger

One active source admission, at most one latest descriptor; no independent liquid worker/cache or duplicated nine-root read. Root source limit stays unchanged. Allocate optional work only after a fixed optional credit has been reserved before payload copies/table IO; unavailable credit is retryable pressure, never source ABSENT. Proposed initial service limit: one raw construction and two completed optional bundles (current/replacement), aggregate128 admitted layers/8192 cells/10368 vertex samples across the existing nine-tile desired scene, stable nearest-first selection. Counts are **per scene, not per root**. Construction must reserve against that aggregate before adding output. A later scene planner/writer must define exact transfer/residency credits; this document grants no parallel9-root retention.

Phases are explicit: (1) existing root worker owns source bytes and completes primitive terrain construction; after builder scratch retires, extract native anchors and raw MH2O; (2) retire full root input/grid/parser scratch before any table-row phase, retaining only raw owner, detached anchors and root receipt; (3) sequential table capture/scan/revalidation, one decoded ordinal at a time; (4) decode one ≤81-vertex layer at a time into the aggregate capped private output, release raw owner; (5) final source checks/transfer, or cleanup before optional credit returns. No material helper/raw source owner may overlap a new liquid source read. The exact phase ordering must be represented in code/tests, not assumed from GC.

Proposed **separate new optional-liquid component ceiling: 8 MiB logical parent ownership**, requiring independent combined-budget approval before integration; it is not silently inside the texture component's48 MiB or primitive root construction's16 MiB claim. This design raises neither existing cap in code.

| New optional ownership | Bound |
| --- | ---: |
| Single raw MH2O payload | 2 MiB |
| Raw retained descriptors/attributes/fixed intervals | 256 KiB |
| Raw interval/decode scratch | 256 KiB |
| Strict native row read/identity scratch phase | 4 MiB |
| Selected tuples, detached anchors, stamps, receipts/control | 256 KiB |
| Two completed capped optional bundles | 512 KiB total |
| Remaining fixed envelope/headroom | 768 KiB |
| Total | 8 MiB |

A completed bundle uses ≤128 KiB height/depth/mask/rectangle facts plus ≤128 KiB bounded source metadata, no triangle/XYZUV arrays yet. At most two such bundles; construction output counts against one of those credits. Root buffer and existing geometry cache are existing separately charged owners; transient grid/area parsing must retire before table phase. Prepared-capture opening can own up to the existing prepared-table manifest/JSON/certificate limits and remains an explicit **separate prepared-table subsystem ledger**; strict row caps do not imply total process8 MiB. Native/GPU/upload/file/presentation lifetimes do not exist here and require a subsequent ledger. No measured RSS/FPS claim.

## Required next gates

Before implementation promotion: submit actual native field descriptor profiles; genuine-native selected-row fixtures including issue-channel/key/schema/source changes, duplicates and absent Object42; cancellation and held lower capture/borrow cleanup; one-root-at-a-time phase/payload-credit proof; known/unknown overlap refusal; no table IO for genuinely absent MH2O; exact constant used heights versus unused zeros/nonplanar output; capped nearest-first selection without terrain damage. Before coordinates/draw: asymmetric source-grid mapping and actual adjacent overlay, explicit diagnostic appearance/depth policy and native/CPU agreement. No source-qualified output may be rendered merely because the raw layout reader succeeded.
