# Closed GUI source-to-packet checkpoint

This is an isolated adapter, with no WorldView/GpuPreviewService routing or rendering changes.
`WorldStreamSourceBridge.Producer` is single use. Its only successful source path calls the opaque
`WorldTerrainStreaming.prepareAndWrite` with its fixed writer. The writer derives scalar header
facts, source bindings, diffuse facts, geometry and atlas views inside the actual expiring checked
borrow. Generic output, descriptive Data and structural codec success cannot construct Prepared.

One private Prepared owns exactly one source WrittenTile. The controller takes it only after the
corresponding transport preparation/STAGED success and shares that holder across residency tables.
Producer.close retires an untaken holder. Holder facts are scalar metadata; actual geometry/material
borrows still enter the source's current-or-replacement validity gate. An already entered borrow
keeps the source's existing pin until return. Close/payload/write/move run off FX.

Destination stopping is registered once against an operation Cancellation. The source's cancellation
lane dispatches lower callbacks off the requesting caller. Registration removal is metadata only;
the parent cancellation worker owns actual callback retirement. The bridge checks cancellation,
latest view and source validity before/after MOVE. PacketFile MOVE leaves the source R inert; the
returned OwnedPacket owns only the file, independently of geometry/atlas. Moved-but-unreturned file
and unreturned base cleanup each run even if the other fails. Auxiliary cleanup failure remains fatal
and retains the original failure. Only a clean typed source Refusal maps to a destination scalar
refusal; unknown providers and program errors stay fatal.

The fixed structural writer computes its separate namespaced material digest. The bridge does not
substitute a digest for the source's nullable PARTIAL composition-cache key. Per producer, temporary
header mappings are bounded by four coverage words and 64 diffuse records; existing 12 output-credit,
18 atlas-slot and source receipt bounds remain unchanged. No RGBA/geometry copy or new cache is added.

Validation: `:gui:worldStreamSourceBridgeTest` is an explicit JavaExec harness (GUI JUnit test discovery
remains disabled). It uses genuine AuthoredNativeMap preparation plus actual authored WDT/ADT/BLP
bytes. Only the test destination is structural, supplied through the package-private transport state
and reflective private destination constructor; it supplies no source authority. Cases cover full
TEXTURED, actual PARTIAL with null composition key, actual missing-material HEIGHT_FALLBACK, stale
view before/after MOVE, actual source-close preserving moved child input, canceled destination held
inside source work, clean source retirement vs unknown provider error, and fatal moved-file cleanup
that must still retire the independent base. No native GPU or JavaFX toolkit is used.

Dependencies are explicitly separate: frozen parent transport579ad70 (rendering-bgfx subtree), its
shader verification helper, material identity39cec42, and source final-handoff7e66921. The public
WorldStreamTileWriter copy is a compile-only parent-owned dependency until its frozen checkpoint is
available; this adapter does not claim authorship or approval of that implementation. No FPS or
end-to-end GUI smoothness claim is made by these source/ownership tests.
