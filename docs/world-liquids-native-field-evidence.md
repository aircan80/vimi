# Native LiquidType / LiquidMaterial field evidence

Read-only source audit for the selected Forever `1.60.1.70755` build, not a qualification implementation or rendering grant. Build identity: product wowf, buildConfig `9d9888f2d89a2dcc9eacc68a2d7cb3dc`, cdnConfig `a9ae49e88bd7579068e940e480a726a5`, region us, locale enUS. Original library and prior acquired sources opened read-only; local-only source access, no network. New prepared native evidence and capture outputs were written only beneath `<scratch>/liquid-native-fields/run` and `run-2`. No game source assets enter Git.

Both tables were freshly prepared through the actual native producer into scratch, then read through actual `capturePreparedLocal`. Matching graph-bound VERIFIED_NATIVE issue evidence, schema/layout selection and completion receipts were printed. A second audit opened each actual numeric source, bounded the single source to≤1 MiB, consumed exact length and EOF, and read WDC5 physical descriptors using the same header/field/storage ordinal arithmetic as `Db2Reader.storage/bind`. The audit is separate from production; no guessed widths from values or UI columns.

## Logical schema versus physical storage

Every listed logical field is `INT`, arrayLength1, verifiedName=true, locale=null, with no column/field comments. Required decoded columns have no raw interpretation or narrowing annotation.

| Table.field | Logical bits/sign | ID / relation / foreign key | Actual physical descriptor |
| --- | --- | --- | --- |
| LiquidType.ID | signed32 | NONINLINE ID; no relation/FK | external uint32 ID domain; logical ID descriptor remains signed32 |
| LiquidType.MaterialID | unsigned8 | no ID/relation; FK LiquidMaterial.ID | inline ordinal14, kind1 unsigned immediate, bitOffset266, bitWidth8, p1=42,p2=8,p3=0, additional0 |
| LiquidType.Flags | signed32 | no ID/relation/FK | ordinal2, kind5 signed immediate, bitOffset224, bitWidth15, p1=0,p2=15,p3=0, additional0 |
| LiquidMaterial.ID | signed32 | NONINLINE ID; no relation/FK | external uint32 ID domain; logical ID descriptor remains signed32 |
| LiquidMaterial.LVF | signed8 | no ID/relation/FK | ordinal1, kind5 signed immediate, bitOffset2, bitWidth3, p1=2,p2=3,p3=0, additional0 |
| LiquidMaterial.Flags | signed32 | no ID/relation/FK | ordinal0, kind5 signed immediate, bitOffset0, bitWidth2, p1=0,p2=2,p3=0, additional0 |

All physical field descriptors above have fieldBits32. LiquidType fieldOffset28, LiquidMaterial fieldOffset0. Those values are WDC metadata, **not a claim that every physical value uses32 bits**: immediate storage uses its explicit bitWidth. LiquidType header has21 stored fields, one section, recordSize40, flags4. LiquidMaterial has2 stored fields, one section, recordSize1, flags4. `Db2Numbers` reads kind1/kind5 using the explicit packed width; kind5 is signed immediate, separately from the selected DBD logical width. No widening/narrowing permission is inferred from the fact that observed LVF happens to be0.

Pinned bundled WoWDBDefs revision `b9a63096cdd99b26967f17f9515377ae62cadfd7` was independently extracted losslessly and SHA-checked against its source index. LiquidType definition SHA256 `d6907c30630ef2518583e4e4dbc0fa8d42e54a400f952dc6c11ce1a5eae6ea5e`; its unique selected layout D1ECEEC9 specifies MaterialID<u8>, Flags<32>, noninline ID<32>, and the MaterialID column FK. LiquidMaterial SHA256 `f6c6a777d685939f1e2a172b914742328e62375a6f40c0403469b34bef38e774`; unique layout98E5D7AA specifies LVF<8>, Flags<32>, noninline ID<32>. These match the fresh captured native schemas. Selection is LAYOUT, not a claim that70755 is explicitly listed among pinned definition build labels.

## Bound identities and copy rows

| Fact | LiquidType | LiquidMaterial |
| --- | --- | --- |
| numeric FDID | 1371380 | 1132538 |
| table hash / layout | 6613BED3 / D1ECEEC9 | 8C379329 / 98E5D7AA |
| source content key | d784f88dd10590fd4c0f86002537d8ac | 1309378564e73a7c2a78af8745d2c44d |
| actual consumed bytes | 15438 | 330 |
| native graph identity | d9b72291badbe0ef496900c6f1fe7a34 | 11a5bc3a4e9a96b5b271cfa8b93ab8a0 |
| selected block SHA256 | 33ce98117bbcd70559f9344c2d0633b7720062675bcf6e1394d77af4312d8a5c | 3f132251adc5d57a8a1923d5a44cf9c70c5f61c9f1327b1369be6b5240a9db9d |
| completion |57 physical +0 copy =57 readable|4 physical +1 copy =5 readable|

Both captures are READY, complete scope, zero excluded encrypted/physical/copy records and textIssueCount0, with reader `db2-values-v3`, row encoding `rows-v1`, native issue policy `native-row-issues-v1`, producer `java-db2-native-producer-v1`. Reader options SHA256 is `472c476561a21e663540e0054b0ef8dc3f3d3457768d36ac3205de5ce3f82531`. Full native issue/source/column identity hashes are retained in private logs. Zero issues alone is not authority; implementation must validate the complete native evidence linkage and row issue channels.

The copy row is an important correction to the initial design wording: requiring declared physical records equal readable rows would reject genuine LiquidMaterial. Require declaredRecords==physicalRows, receipt rowCount==entry readable count==physicalRows+copyRows, zero exclusions, and an exact complete ordinal scan including copies with unique selected IDs. A copy can provide an observed selected row under the native reader's qualified copy resolution; it cannot be silently skipped or create duplicate ID authority. The native capture still reports membership NOT_CERTIFIED.

## Concrete initial supported / refused profile

Support only these exact logical scalar/role/FK tuples and selected native layouts, verified issue provenance, no relevant cell issue/raw interpretation/narrowing, actual strict bounded contiguous rows including copies, and stable full source/schema/key/native identity. MaterialID values must fit uint8 and reference an unambiguous observed LiquidMaterial.ID; LVF is signed8 and only exact0 qualifies height+depth0. Negative LVF, positive unknown LVF, raw type2, unresolved/missing material, alternate widths/signs/arrays/roles/FKs, partial/protected/ambiguous rows or unknown issue attribution decline optional liquids with finite reasons. Flags are retained as qualified signed32 facts if used at all; Flags1 does **not** select LVF, water optics or an opaque/transparent shader. Direct secondary0 can establish the layout selector, but raw type2 remains unsupported; indirect secondary≥42 requires the actual Material relationship. LiquidObject42 remains irrelevant to this geometry resolution.

Physical descriptor values document the actual compressed source. Future qualification must trust only the actual native producer's checked physical→logical binding plus the exact logical profile; a public schema/receipt DTO or separately parsed raw header is not a positive factory. If implementation proposes accepting unreviewed physical/raw interpretation variants, it must add evidence and review rather than silently extend this profile. No source identity above is a hardcoded positive allowlist: current actual capture and final fresh binding checks remain mandatory.

Private reproducible evidence: `LiquidNativeFields.java`, `WorldRealSource.java`, `proof.log`, `proof-2.log` and SHA-checked `pinned-dbd-blocks.txt` beneath `<scratch>/liquid-native-fields/`. No qualification code has been added. This evidence and the source API/ledger require independent review before implementation.
