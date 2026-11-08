# Terrain textures missing: handmade BLP check rejects BC3 (DXT5)

Reported against alpha.21 dc7c5a45 on macOS Apple Silicon. WorldTerrainTextureSource
records DIFFUSE_PROFILE when the handmade BLP guard rejects otherwise supported
BC3/DXT5. The report observed52 rejections across these five256×256 textures,
all colour encoding2, alpha depth8, alpha encoding7, mip byte0x02:

| FileDataID | Recorded path | Reported map |
| --- | --- | --- |
| 188808 | tileset/stonetalon mts/stonetalonrockbrown_s.blp | 1 |
| 188812 | tileset/stonetalon mts/stonetalonrocksalpha_s.blp | 1 |
| 250171 | tileset/stonetalon mts/stonetalonrockbrownsmooth_s.blp | 1 |
| 187911 | tileset/expansion02/howlingfjord/hfjords_bushyc_s.blp | 2991 |
| 186819 | tileset/araithihighlands/arathihighlandsgrassbase_s.blp | 2991 |

The decoder already recognizes and decodes this BC3 tuple. Its separate
DECLARED_HANDMADE_V1 guard admitted only BC2/8/1. The existing generic mip-storage
calculation already uses16-byte blocks for both BC2 and BC3; no block-size formula
change is required.

Candidate fix: admit BC3/8/7 alongside BC2/8/1 under explicit handmade opt-in,
retain complete power-of-two chain/range/order/EOF validation, and report actual
colour encoding, decoded encoding, alpha depth and alpha encoding on refusal.
The streaming packet diffuse-tuple guard receives the same narrow BC3 amendment;
otherwise successful decoding would still be refused during hot tile transport.
Other handmade profiles remain unsupported pending their own evidence. Strict
flag policy and persisted-image authority are unchanged. Decoder revision advances
to blp2-rgba8-v3 so dependent generated/terrain cache identities change.

Regression uses a synthetic256×256 BLP with exactly the reported header and nine
declared levels through1×1, including16-byte terminal blocks. Every level checks
exact RGBA, including distinct BC3 alpha; tests cover4×4 terrain mip selection,
strict-profile refusal, wrong block lengths, truncated unselected payload and
unsupported tuple diagnostics. Additional tests pass BC3 through actual terrain
source/atlas qualification and the textured streaming packet roundtrip, while
rejecting unreviewed handmade tuples. No acquired game texture is committed. The new
regression reproduced the original BC2-only refusal before the fix.

Status: independently reviewed and integrated into the alpha.22 candidate,
PR162. Decoder tests passed35 forced tasks; source/packet tests passed43 forced
tasks independently. Publication and platform package checks remain pending. Actual reported texture files and macOS runtime have not been rerun here.

Follow-up coverage (#169 verification): a synthetic non-square 16×4 handmade BC3
chain checks exact 64/32/16/16/16-byte level sizes (one whole 16-byte block for
the 4×1, 2×1 and 1×1 levels), interpolated eight-value alpha, per-level colour
selectors and alpha shifts that make every level and block byte-distinct, and
cropping of partial terminal blocks. BC3 payloads using BC1's
8-byte blocks, every terminal-block truncation, an 8-byte terminal descriptor and
wrong strict-mode lengths remain BLP_INVALID. Handmade BC1 (alpha depth 0 or 1)
and BC2 depth 4 remain BLP_UNSUPPORTED pending their own evidence; strict BC1 with
and without 1-bit alpha and strict BC2/BC3 decode as before. Terrain diffuse is
CPU-decoded to RGBA8 and uploaded as RGBA8; no bgfx BC3 texture format is used.

Follow-up (paletted handmade chains): two terrain diffuse BLPs in the 2.5.6 classic
client use colour encoding 1 (palette), alpha depth 8, alpha encoding 8 and mip
byte 0x02, and were refused as DIFFUSE_PROFILE. The palette decoder already reads
this tuple and the generic mip-storage size (one index byte plus one alpha byte per
pixel) already applies, so the declared handmade guard and the streaming packet
diffuse-tuple guard now admit PALETTED/8/8 beside BC2/8/1 and BC3/8/7 with the same
complete, exact, EOF-ending chain validation. Other handmade palette tuples (alpha
depth 0, 1 or 4, or alpha encoding 0) stay BLP_UNSUPPORTED. Strict decoding is
unchanged, so the decoder revision stays blp2-rgba8-v3: tiles with a refused diffuse
were PARTIAL and never cached. Synthetic 8×8 chains check exact RGBA and alpha at
every level, malformed chains and terrain/packet qualification; no game texture is
committed.
