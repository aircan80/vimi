# Standalone global pure layout, hash and status primitives

Implements the next pure checkpoint from `world-global-standalone-render-proposal.md`. Three new rendering-bgfx types have no file/native/GUI APIs or concepts-world dependency. They provide bounded layout arithmetic, Source64 encoding, literal MODF checks, canonical streaming digests/CRC and status syntax. There is **no complete header/body encoder or reader yet**. A structural value or hash never authenticates game sources or creates an application Source/file/MOVE capability.

## Limits and ownership

`WorldGlobalLayout.Counts` bounds materials1..24, groups1..8, batches(groups..64), images1..18 (<=materials), vertices(groups..32768, <=groups×16384), raw indices3×groups..98304 (triangle-aligned, <=groups×49152), drawn triangles(batches..raw triangles), pixels(images..1179648, <=images×65536). Products follow these checks. It derives contiguous offsets for header1024, Source64, Material64, Group64, Batch16, Image64, XYZUV20, global uint16 indices and RGBA8. Maximum exact file is5,577,600B under6,291,456B admission. Freely constructed descriptive Offsets are not accepted as canonical input by any positive factory.

The hash feed uses one4KiB scratch and no ByteArrayOutputStream or whole-payload copy. Metadata lists are count-capped before indexed bounded snapshotting; provider `toArray`/iterators are not trusted for allocation. Recipe inputs retain at most28 sources,24 sixteen-word materials and18 image facts. RawModf copies64bytes once; public reads copy only those64bytes. Material owns one bounded sixteen-long copy. Each native group hashes sequentially; only up to8 fixed-length group SHA strings survive. FileHasher retains one4KiB scratch, accepts <=262144B chunks, never changes or retains their cursor/storage, and zeroes scratch on finish. No source read, file handle, decoder, array-payload owner or GPU owner is introduced.

## Exact policy and raw/source distinction

Profile1 is `GLOBAL_UNLIT_OPAQUE_RGB_V1`, transform1 is strict zero-placement D at native origin(+0d,+0d,+0d). Fixed omission mask511/0x1FF has nine policy bits:

| Bit | Omitted/intentionally simplified policy |
| --- | --- |
|1|Source lighting/normals/ambient operands|
|2|Vertex color/MOCV|
|4|Source alpha; output opaque RGB1|
|8|Liquids/MLIQ|
|16|Doodads|
|32|Portal/BSP culling|
|64|Floors/collision|
|128|Unbatched triangles|
|256|Outdoor fog|

Every bit is set for the recipe, even if an individual source has no such operand. Counts separately preserve actual omitted coverage.

RawModf requires exactly64bytes and checks duplicated expected root uint32>0 and UID uint32, flags8, doodad/name sets0, raw scale1024. Numeric raw position/rotation must be0; **both literal +0/-0 bits are retained**. Raw bounds remain finite within±1,000,000 and ordered, without pretending they are native geometry bounds. The recipe binds those actual64bytes. G2 native XYZ instead requires canonical positive zero and finite±4096; native UV preserves literal ±0 and finite±16. No scale application, general rotation or ADT translation is added.

Source descriptor roles are fixed WDT=1,ROOT=2,GROUP=3,IMAGE=4. Order is WDT0,ROOT0,GROUP ordinals0..g-1,IMAGE slots0..i-1. IDs are positive uint32 and unique. Source64 layout is IDu32/roleu8/ordinalu8/flagsu16/actualLengthu32/declaredLengthu32/CK16/bodySHA32. Flag1 means unknown declared length and stores0; known declared equals actual. Lengths12..(WDT1MiB/root64KiB/group1MiB/image64KiB). Source root ID matches literal MODF root. Texture1 must reference an actual image slot; image slots follow first recorded material use. All declared images must be used. These are structural cross-links, not a formats-to-Source authority path.

Material hash words are the original sixteen u32 MOMT words; zero guards match G1 (0,1,2,6,9,11..15 zero; texture1 word3 positive; colors/groupType words4,5,7,8,10 preserved). Image facts admit BC1wire1/depth0/alphaEncoding0 with original alpha255, or BC3wire2/depth8/alphaEncoding7; mipFlags1/mipLevel0 are fixed, declared prefix mipCount1..terminal level. Alpha min/max/nonopaque relations are checked without assuming BC3 opacity. Original and opaque RGBA SHA fields are descriptive identities; a reader cannot recompute stripped original alpha from the opaque file.

## Canonical hash domains and field order

All integer fields are little endian. Domain tags below are US-ASCII followed by exactly one NUL byte. Fixed hashes/CKs are lowercase hexadecimal on the Java boundary and binary bytes inside digests. Fields are validated/bounded before hashing. No implicit enum ordinal, locale-sensitive normalization, provider message or unframed concatenation is used.

- `vimi.global.build-locale.v1\0`: product lengthu32+ASCII(1..32), buildConfig16bytes, cdnConfig16bytes, region lengthu32+ASCII(1..32), locale lengthu32+ASCII(1..16). Null region uses length0xFFFFFFFF and no bytes; empty region is rejected. Case is preserved; keys are canonical lowercase input.
- `vimi.global.recipe.v1\0`: profileu32=1, omissionsu32=511, MapIDu64 (uint32 range), scopeSHA32, buildLocaleSHA32, rawMODF64, sourceCountu32+Source64 records, materialCountu32+sixteen-u32 records, imageCountu32+image fact blocks. Each image block is twelveu32(ID,width,height,encoding,alphaDepth,alphaEncoding,mipFlags1,mipLevel0,mipCount,alphaMin,alphaMax,nonopaque), originalRGBA SHA32, opaqueRGBA SHA32. Source body hashes/CKs are bound descriptions, never recomputed/authenticated by this pure helper.
- Native prefix: profileu32=1, transformu32=1, threef64 origin literal+0. Group block is eightu32(ordinal,vertexFirst,vertexCount,indexFirst,indexCount,batchFirst,batchCount,rawFlags). These logical hash fields are **not** the literal first32bytes of Group64 (which contains a source descriptor index). The latter will be separately structurally cross-checked by the full reader.
- `vimi.global.native-group.v1\0`: native prefix, group block, its recorded Batch16 records, its actual native XYZUV20 vertex slice, then its raw **global** uint16 index slice. Batch16 is groupu8/materialu8/flagsu16zero/globalFirstIndexu32/indexCountu32/reservedu32zero. No index localization or invented batches. Group ranges partition arrays; batches are valid/disjoint within their group; all raw indices stay within that group's global vertex interval. Drawn total is the sum of actual spans. Group flags retain the five reviewed G1 shapes.
- `vimi.global.native-mesh.v1\0`: native prefix, sixu32(groups,batches,vertices,indices,rawTriangles,drawnTriangles), then each group block+its native-group SHA32 in ordinal order. This binds the derivative mesh/coverage, not G1's raw float/source-body digest. Group hashes stream one group at a time and metadata products are checked before scalar reads.

Whole-file SHA has **no extra domain prefix**: it hashes actual canonical file bytes with160..191 zero, matching the reviewed header format. Header magic/version provide its file-domain framing. Header CRC32C covers exactly1024bytes with160..191 and428..431 zero; bodyCRC at424 remains included. Body CRC32C covers1024..actualEOF. The writer fills bodyCRC, fills headerCRC, computes masked whole-file SHA, then stores that SHA at160. This ordering has no checksum cycle.

`verifyIntegrity` requires stored header SHA == streamed computed SHA == closed owner's expected SHA, plus matching body/header CRC32C. **It is integrity-only, not a canonical header reader or source/file authority**: it intentionally does not validate magic, counts, mode, reserved fields, descriptors, offsets, nonce or scope. FileDigest is a descriptive public value. The future closed reader must compute hashes from its actual same open bytes, validate all canonical header/descriptor fields and owned identity before payload allocation, and bind exact terminalEOF/handle length afterward. Caller-constructed digests or a successful helper call cannot substitute for that read or the actual Source transaction.

## Status syntax and lifecycle meaning

API lines exclude LF; the future bounded byte reader allows at most256bytes including LF. Parsing rejects oversized input before splitting, nonprintable ASCII/Unicode, empty/extra fields, noncanonical decimal, uppercase hex, unknown statuses and unknown codes. No message-text inference. Identity is nonce32/scope64/fileSHA64; hashes here match the owned expected file, not Source capabilities.

- `GLOBAL_INPUT_CONSUMED nonce32 fileSHA64`: exactly120bytes includingLF. Only after all input file reads close. It permits transport-owned deletion; it is not initialization, a rendered frame, or parent acceptance.
- `GLOBAL_READY nonce32 scope64 fileSHA64 1 vertices rawTriangles drawnTriangles groups batches images 511`: <=208bytes includingLF. **Only after the first successful native draw/readback is written to that request's owned mapping**, not merely handles created or uploads queued. The frame must retain actual captured draw camera revision/dimensions. It is still NOT parent FX acceptance: parent checks owned identity, shape, request/generation, resize policy and frame metadata before detached upload and after acceptance. Loading remains until that actual accepted frame. Ready.matches checks all reported counts against owned header counts.
- `GLOBAL_UNAVAILABLE nonce32 scope64 fileSHA64 INPUT|CAPABILITY`: only explicit classified input or required-backend capability negatives. Allocation errors, arbitrary exceptions and dirty cleanup stay fatal; there is no ALLOCATION/UNKNOWN normalization code. No scene-update/COMMITTED/terrain lease state is invented for immutable global content.

## Validation and next gate

Seven deterministic test methods cover independent Python SHA vectors for build/recipe/native-group/native-mesh; literal raw -0 vs native+0/UV-0; identity/profile substitutions; independent Source64 bytes; exact max/min offsets and malformed products; group/index/batch/finite/cancellation guards; bounded provider-list preallocation; independent small-byte SHA/CRC oracles including arbitrary chunks across hash holes/header/body boundaries and cursor preservation; three-way SHA/CRC tampering; and canonical status lengths/identity/numeric/ASCII negatives. No game assets or screenshots are used.

`JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :rendering-bgfx:check` passes (private log `<scratch>/global-pure-codec-check.log`). Existing shader/resource/protocol files are unchanged; there is no runtime/GPU proof claim.

Next implementation requires review of the complete canonical header+descriptor encoder/reader and closed source writer/MOVE bridge, with same-handle/CREATE_NEW/NOFOLLOW/actual-close/failure ownership. Native renderer/shaders/standalone owner and GUI integration remain separate grants and cannot be inferred from these pure types.
