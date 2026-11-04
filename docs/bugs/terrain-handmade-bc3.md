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
