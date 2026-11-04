# WMO exterior source checkpoint

Status: actual-source investigation supporting `world-city-wmo-proposal.md`, awaiting independent source/profile review. No production parser, material qualifier, transform, renderer, UI or game asset is added. This is not a city-identity, visual-alignment or client-shading approval.

## Safe isolated acquisition

The existing source pipeline supports the required isolation: open the real application library through `ObjectStore.openReadOnly`; use a new writable ObjectStore under `/tmp/vimi-world-city-private/acquired` as the `CachingObjectSource` reader/writer and history-append target; supply the real library only as the shared read-only object/history source. Normal `NetworkClients`, CDN object verification, encoded-key selection, BLTE and file-stream validation remain in use. Do not build raw HTTP asset URLs or bypass validators. This needs no account/settings changes.

The proof bounded file reads to8MiB, checked reported size before opening, limited aggregate consumed sample bodies to16MiB, and read one stream/source at a time. Texture bodies additionally required at most2MiB before acquisition. Every consumed source was COMPLETE with no holes/protected ranges, had unchanged pre/post FileDataID/content key, and independently matched decoded-body MD5 to its content key. All selected sources were unencrypted; no private keys or key bytes were needed or printed. Missing encrypted sources would remain explicitly protected/unavailable. The probe does not implement a new key-discovery policy.

New objects, observations, encoded ranges and derived data stayed under private `/tmp`; the real library was never opened for writes. All streams, borrowed file handles, opener/source/network clients and scratch store closed. This is evidence for isolation of this experiment, not approval of a production source-service lifetime or total process memory ceiling. Existing lower pipeline caches/index reads have their own owners.

An initial survey acquired three recorded roots106905/106885/106973 and their eight declared GFID groups. A focused warm proof reacquired/revalidated the first root chain plus its six declared textures through the same pipeline. Logs and Java bootstraps remain private under `/tmp/vimi-world-city-private`; no game payload or image is committed.

## Exact focused source chain

Selected build: wowf1.60.1.70755 enUS, build config9d9888f2d89a2dcc9eacc68a2d7cb3dc, CDN configa9ae49e88bd7579068e940e480a726a5, regionus.

The proof reparsed actual obj0 FileDataID777948, CKebff6b81c3d73d404babbf90bdd3b5f9,39,192bytes with the existing production `WorldPlacements.readObj0`. MODF ordinal2 / unique ID173696 explicitly records direct root ID106905, flags0xC, position(17156.709,54.0478,17470.643), heading5degrees, zero pitch/roll, scale1024, doodad/name sets0. It did not substitute a model ID from a name or infer a city.

| Dependency | FileDataID | Bytes | Content key |
| --- | ---: | ---: | --- |
| WMO root |106905|712|6304edd95070891d675438415302bcbd|
| Recorded GFID group ordinal0 |106906|19,404|d3ba749fd2004006aaab29a48c3e0f3e|
| Material0 diffuse |127280|44,876|506f323bc973a70daa92c45f63cdb256|
| Material1 diffuse |127322|44,876|3020a8d7eaf811c152b05ebf0646a9dc|
| Material2 diffuse |127299|44,876|7afb1d92cbfd1d1a3a19c730f7f0bb25|
| Material3 diffuse |127324|6,652|a5a664305a90698a5f79795faedda900|
| Material4 diffuse |127301|44,876|2655c1906c525c467fea3f9c9ef3a3b4|
| Material5 diffuse |127325|44,876|adb63b1b8a0e023d3274e71de9023942|

Including obj0, the focused nine-source bodies total290,340bytes. These are descriptive receipt facts, not source authentication performed by a new API.

## Root/group and material facts

Root MVER is17; MOHD is64bytes with six materials, one group, flags0, LOD count0. GFID is exactly one recorded unsigned ID106906. MOMT is exactly6×64bytes. No MOTX string block is present. Each material records flags0, shader0, blend0 and one positive first texture reference; texture2/texture3 references are0. All six first references resolve to actual BLP bodies through the selected build, providing concrete evidence for this exact direct-texture profile.

Group MVER is17. MOGP flags are0x9, batch counts A/B/C=0/0/6. Nested geometry is424MOVT vertices,424MONR normals, one424-entry MOTV UV stream,1,134MOVI indices,378MOPY triangle records and six24-byte MOBA batches. No MOCV stream is present. The independent private scanner checked nesting/lengths, finite position/normal/UV values, global indices, each batch's index span and inclusive vertex range, material index0..5 and flags0. Batches partition all1,134recorded indices; no collision-only MOPY filtering was used to select visible surfaces. Normal lengths range0.9999999650566262..1.000000039144058.

These facts make it a strong **candidate** for a first static opaque exterior. They do not settle lighting or every material operand. Root ambient is0xFF0B0B0B. Material color1 is0xFF000000, color2 is0xFF7F7F7F, groupType10; materials1..5 carry nonzero raw color3 bits1028443341. Flags3/runtime words are0. Preserve all declarations and independently identify which operands shader0 consumes; do not interpret every nonzero color as a tint, assume unused fields must be zero, or ignore a consumed operand merely to admit the sample. Sampler, winding, normal space and ambient/diffuse recipe still require explicit material review.

The broader survey gives useful negative-profile candidates: root106885 has one group106886 with ten batches, including material9(flags4, blend1), so a blanket opaque rule would be wrong. Root106973 declares six groups106974..106979, portal/doodad data, nontrivial material flags and MOCV/interior evidence; it must not be admitted wholesale by the focused profile.

## Texture facts and residency implication

All six focused references decode through the current strict `Blp2Decoder.decodeLargestWithin` path as BC1 with alphaDepth0 and selected mip0. Five are256×256; material3 is64×128. Total selected residency is335,872pixels /1,343,488RGBA bytes. This is six distinct source images, not six512²images. No handmade-profile fallback, replacement texture or guessed texture name was needed.

The earlier four512²-equivalent pixel allowance is sufficient, but a separate hard limit of four image handles would unnecessarily omit this tiny complete candidate. Propose at most eight distinct image handles while retaining the aggregate1,048,576pixel and512maximum-side limits. This is a design adjustment for review, not a production limit change or automatic approval of extra CPU/GPU copies.

## Heading/bounds evidence

The private proof consumes the parsed MODF record, not hand-entered placement coordinates, and applies the proposed heading-only native transform to root-local MOHD bounds. Transforming all eight corners yields:

- Native minimum(-421.6062038061927,-107.67261005619267,51.195812463760376).
- Native maximum(-386.34561911047155,-72.41202536047157,100.65787124633789).
- Maximum component residual versus the recorded MODF world bounds, converted to native axes:0.0005670271394535575.

Actual transformed mesh bounds are tighter than the conservative transformed root box by up to approximately1.4466horizontal units; that is not an error to remove by ground snapping or arbitrary scaling. The heading result supports source translation/basis/scale consistency for this placement only. It does not prove full Euler order, normals/winding under all transforms, ADT terrain intersection, city identity, client appearance or a same-camera rendered match. Independent WMO-specific authored transform oracles and real terrain/image alignment remain gates before production rendering.

## Next reviewable slice

Review these source facts, then implement only the bounded raw WMO17 root/group parser and authored positive/negative binary fixtures. Start with root/group structure and exact GFID inventory; preserve unsupported material/UV/color/LOD declarations without claiming appearance. A later concepts source qualifier should bind complete receipts and source states, and a separately reviewed material/transform factory should produce renderable assets. No renderer or protocol edits should precede those gates. A real city source/coverage proof remains separate from this first small exterior asset.
