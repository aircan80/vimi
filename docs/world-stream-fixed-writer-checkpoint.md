# Fixed terrain packet writer checkpoint

`WorldStreamTileWriter` connects bounded borrowed geometry and optional checked
atlas bindings to the existing private packet codec. Its public descriptions are
structural data; source qualification stays in the closed application bridge.
The writer checks scope, tile coordinates, geometry identity, source operands,
coverage and exact mapping parameters before publication. PARTIAL atlases need no
composition cache key: actual borrowed pixels and ordered descriptions determine
the separately namespaced material digest.

The source owns the newly published unadvertised file until its accepted resource
callback performs one-shot MOVE. Destination adoption failure closes the moved
file locally. Existing target/part files are never claimed or removed. The inert
source shell clears its destination and header references after MOVE or close,
so it does not retain the producer cancellation graph. All IO runs off FX.

Hashing uses at most 4 KiB scratch within the existing producer allowance; codec
streaming uses its existing bounded buffers. No full atlas or geometry copy is
introduced. Source tuples are captured once with a maximum of 64 entries.

Validation: full `:rendering-bgfx:check` passes (40 tasks), including six authored
fixtures covering height and partial payloads, actual-pixel digest changes,
one-shot MOVE, expired adoption, cancellation and mismatched scope/coordinates,
preexisting-file preservation and a changing-list bounded snapshot. Fixtures
construct only structural transport destinations, never positive source facts.
This checkpoint alone does not enable the GUI or establish movement smoothness.
