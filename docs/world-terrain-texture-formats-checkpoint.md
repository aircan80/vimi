# Terrain texture format checkpoint

Status: implementation checkpoint for independent root/domain review. This adds
raw grid and basic-coverage texture facts only. No compositor, source acquisition
service, cache, scene wire, shader or GUI is added.

`WorldFormats.adtGrid` shares the existing checked ADT walker. Its optional branch
retains source ordinals/header coordinates, native staggered height samples,
normalized fine-cell holes and bounded optional MCCV facts. Heights/colors are
private defensive copies with readonly views. Wrong-size/duplicate MCCV declines
optional color evidence while existing geometry admission/output stays unchanged.
The raw format API retains its existing root byte limit; the proposed2MiB first
texture source cap belongs to the future source helper.

`WorldTextureFormats.inventory` requires the exact reviewed WDT flags0x3ca,
unique MPHD32 and complete recorded MAID. `TextureData` owns one detached≤8MiB
source copy, immutable ordered IDs/layers/ranges and bounded chunk-local decline
reasons. It accepts only split MVER18/MAMP0/MDID/MHID-all-zero/256-MCNK declarations.
One decode writes at most28672 caller-owned scratch bytes; it never retains all
256×7 decoded masks or the caller's buffer. Raw4096 and exact4096-output RLE maps
must consume their whole slices, with no zero/overflow runs or sum>255. Closing
releases the detached source and rejects further decodes.

These APIs provide no authenticated file authority: the future concepts source
helper must validate WDT/root/tex0 receipts and their exact association before
calling a pure compositor. MCLY effect IDs remain raw metadata; they do not cause
object/model acquisition. A valid metadata record is not a complete terrain
appearance or client material profile.

Actual private proof used WDT775971 and recorded root/tex0 pairs777947/777950,
777952/777955 and778147/778150. Every raw root/WDT MD5 matched its previously
recorded content key. WDT inventory retained736 recorded tiles. Across the three
roots,111360 grid vertices match the legacy geometry's float values exactly;
normalized holes match its emitted triangle counts. Grid retained metadata is
331776bytes/tile. All768 texture chunks decoded, with masks707+639+678 = **2024**
(the earlier proposal total2074 was an arithmetic typo). Mask byte-stream SHA256s
match an independent Python raw/RLE decoder:

| tex0 | SHA256 of all decoded masks in source order |
| --- | --- |
| 777950 | f8717d745bdc65006f800dcfe5e06f54744cca6de750cd9cf1b2e66960dadc12 |
| 777955 | 81dc92ffd6ecfb144d6a55ad4216213df39467a8b7990539296e79cb20f1b48f |
| 778150 | 9919aef9ca1182eb441a3e690b7241a7a4c261dacd76460ffd9ada10efbb1ce2 |

Evidence: `/tmp/vimi-terrain-texture-proof/native-grid-texture-proof.log`,
`independent-mask-hashes.log`, `NativeGridTextureProof.java` and
`compare-masks.py`. All game files remain in private scratch, outside Git.

Authored tests cover RAW and RLE bytes/literal order, full-eight-layer256-chunk
source with one reused28672-byte scratch buffer, input/view immutability, owner
close, cancellation during decoding/reuse, bad references/flags/intervals/lengths,
overflow/zero/trailing masks, excess weights, missing/duplicate global declarations,
canonical source order, staggered samples and unchanged old geometry. Format-world,
concepts-world and rendering-world checks pass. Native texture rendering and
single-atlas UV precision/seam admission remain future gates.

The proposed compositor policy is explicitly **BASIC_COVERAGE_PREVIEW**. WDT0x80
remains recorded: a pinned upstream height shader can sharpen weights nonlinearly
even with zero MHID/absent MTXP. Direct byte coverage does not prove that client
final blend is linear. The first coarse unlit diffuse/MCCV/fog preview deliberately
does not implement that client height-shader behavior; its recipe must preserve
this distinction and may not silently change the approved math.
