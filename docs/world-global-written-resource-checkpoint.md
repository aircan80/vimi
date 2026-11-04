# Private global source-owned written resources

The private factory now supports `createAndWrite` inside its actual G2 inspection.
This is a trusted synchronous unadvertised-resource callback, not a public file,
packet, native or Source facade. Production resource-bearing calls and closes run
on a worker; the eventual public service must dispatch blocking Source close off
FX. No viewer or public service routes to this code yet.

The fixed G2 source walk builds actual geometry and opaque images first, then the
writer receives an expiring scalar borrow and the actual active admission token.
It must close its own partial resource if it throws before returning it. A returned
non-null resource attaches to the source payload before final ALL-derived binding,
native Map/WDT, cancellation-drain, source scope and publication gates. Its actual
construction pin remains held through these gates and failure cleanup. No writer
can accept descriptive Manifest input as authenticated payload.

`WrittenAsset.withWrittenResource` obtains a new real source→owner pin on the
qualified payload, requiring its exact scope remains known and healthy. Its trusted
callback runs outside locks and performs MOVE only; the neutral file owner makes
the old source handle inert. Source close waits that callback, then closes the
resource outside locks before clearing payload arrays/returning the credit. No
array or raw Source handle escapes. Plain G2 reads retain their original contract.

Each of the existing two actual payload credits owns at most one written resource;
construction consumes one of those credits. No new thread, executor, array payload
or Source acquisition pipeline is introduced. Source metadata retains at most two
failed resource owners in quarantine. A genuine resource close failure clears known
payload buffers, records sticky Source failure, preserves exceptional drain, never
retries unknown cleanup, and does not skip closing the other owner. This is not a
claim of successful file retirement on failure. The later application bridge must
also enforce one finalized pending file and the existing process/file permit; the
private generic resource callback supplies neither that permit nor packet authority.

Five authored tests use actual CREATE_NEW files and actual deletion failure, plus
held writer cancellation and MOVE callbacks. They verify expired payload borrows,
last-binding staleness deleting unreturned output, Source close waiting actual pins,
post-MOVE file preservation, cancellation reaching the admission token, and fatal
cleanup still attempting both owners. Full `:concepts-world:check` passes36 tasks.
No game assets, complete global codec or GPU rendering is exercised here.
