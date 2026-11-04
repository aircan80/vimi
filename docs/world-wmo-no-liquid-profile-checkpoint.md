# Recorded exterior group without a liquid body

The first actual source-factory proof reached root106905/group106906 but declined
its MOGP header because the initial authored profile required liquidType0. Actual
consumed header has flags9, liquidType15, firstPortal/portalCount0, batch counts
0/0/6, raw padding/unused/tails0 and groupId3700. There is no MLIQ chunk. Preserve
that raw value; neither this header nor its numeric descriptor creates water geometry.

The narrow source profile accepts raw liquidType0 or15 only within its unchanged
exact flags9 and closed no-MLIQ chunk profile. Other descriptors, changed group flags
and any MLIQ body still decline before an output callback. No parser, source/resource
cap, transform, material, packet or native shader changes are included. The raw group
reader already preserves the descriptor. Consumed group CK binds the exact source
header; neutral presentation bytes are not source authority.

Authored acceptance covers both accepted descriptors and refusals for descriptor1,
MLIQ with descriptor15, and flags0x1009 with descriptor15. Existing source binding,
normal, batch, opaque-image and final-revalidation tests remain in force.

Actual private read-only proof `/tmp/vimi-wmo-root-actual-proof/proof-liquid15.log`
passes Map0→WDT775971→root777947/OBJ0→MODF ordinal2/UID173696→WMO106905→group106906
and all six actual BC1 images. The private source callback writes the terrain base
and structural annex before final source checks; only the returned owned packet is
subsequently decoded against its actual consumed base. Counts:424vertices,
378triangles,6batches,6images/335872pixels,11bindings; annex1361404bytes.
Terrain geometry SHA remains
c032876b1c24752cb8f2bedf95e710510402addf9ee98e89b6bff675601ae6df.

Original library and both prior acquired stores are opened READONLY; only fresh
scratch object/generated/output stores are writable, localOnly/no network. The
first diagnostic lacked the city object overlay and correctly reported unavailable;
adding that existing READONLY source exposed the exact header refusal above.
No game assets or proof images enter Git. This is source/codec evidence, not a
visible building/native pass/GUI or complete city claim.
