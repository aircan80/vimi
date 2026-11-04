# Opaque terrain source and closed packet bridge

Design/API checkpoint atop the internal combined factory de2f7f6. This proposal
adds no GUI route or renderer wiring. It connects that actual source factory to the
planned WorldStreamTransport facade and its separately reviewed structural tile
codec. The internal combined factory/ownership checkpoint still needs independent
review before implementation of this public boundary.

## Source capability and dependency boundary

Add public final WorldTerrainStreaming in concepts-world. Its constructor is
private; the package-owned factory wraps only WorldService's own internal
WorldPreparedTerrain. WorldService implements an optional BuildWorld.terrainStreaming()
accessor. The default custom-provider implementation is Optional.empty with no IO;
missing configured files/pool also remains unsupported. Accessing the configured
facade performs only small owner construction. Qualifying Map/WDT/root/materials
continues off FX, using configured acquisition only where already authorized.
No concepts-world dependency on rendering-bgfx or GUI is introduced.

The planned public surface is narrow:

```java
public final class WorldTerrainStreaming {
    public static final class Scope { // private constructor; detached small token
        public long mapId();
        public String scopeSha256();
    }
    public Scope createScope(WorldService.MapInfo comparison, Cancellation cancel);
    public void activate(Scope scope);

    @FunctionalInterface public interface Output<R extends AutoCloseable> {
        R writeUnadvertised(CheckedBorrow borrow, Cancellation cancel);
    }
    public <R extends AutoCloseable> WrittenTile<R> prepareAndWrite(
        Scope scope, WorldFormats.Tile comparison, Cancellation cancel, Output<R> writer);

    public static final class CheckedBorrow { // private constructor
        public TerrainScene.Tile geometry();
        public TerrainTextureBinding texture(); // null only for explicit qualified height
        public Facts facts(); // detached bounded descriptive scalars/source tuples
    }
    public static final class WrittenTile<R extends AutoCloseable> implements AutoCloseable {
        public void withPayload(Consumer<CheckedBorrow> use);
        public void withWrittenResource(Consumer<R> moveOnly);
        public boolean isOpen(); // current validity observation, not a source certificate
        public void close();
    }
}
```

Scope holds no source/facade/table/service/MAID arrays or callbacks. It wraps the
engine's detached token and cannot be made from caller IDs/digests. Engine owner,
generation, all eight MAID references and current proof remain authoritative; the
public comparison MapInfo/Tile cannot authorize another source chain. Activation
uses the existing current/replacement registry, not a new map cache.

CheckedBorrow delegates to the live private internal borrow. It is synchronous,
same-thread and expires on every exit, clearing payload/owner references. Facts
contains only bounded descriptive build/locale, map authority, scope/owner/generation,
WDT/root actual bindings, root geometry fingerprint, outcome/fallback category,
material state/diagnostic/support counts and optional actual qualified material
receipt. Snapshot those facts before a payload borrow so source/cache invalidation
cannot break an already accepted callback merely by making a metadata getter query
a closed cache. The existing cache owns geometry/area payloads; no new arrays or
RGBA copies are made. Computing camera bounds/current-area summaries happens inside
checked geometry callbacks off FX. Detached public facts, even if forgeable, are
never accepted as positive factory inputs.

withWrittenResource runs only after final source success. It independently pins the
same WrittenTile while invoking the callback OUTSIDE metadata monitors, just like a
payload callback. Source close rejects new callbacks but waits an accepted callback's
finally before closing R/base owners. No raw R getter is exported. Its fixed bridge
consumer only moves file ownership; it cannot publish to the child or wait for a
child/future while this callback runs. R is generic unadvertised output, never a
certificate that its content matches the checked payload. Arbitrary public Output
success therefore cannot mint the closed GUI bridge's private qualified holder.

## Public structural writer adapter

Add a narrow source-neutral adapter in rendering-bgfx, alongside the planned
WorldStreamTransport. Keep WorldStreamTileFile/Header/Expected/wire enums private
to their current package. The adapter has no concepts-world, table, source, network
or preparation dependency.

```java
public final class WorldStreamTileWriter {
    public static PacketFile writeUnadvertised(
        WorldStreamTransport.PacketDestination destination,
        Data descriptiveData, TerrainScene.Tile geometry,
        TerrainTextureBinding optionalTexture, BooleanSupplier stopping) throws IOException;

    public static final class PacketFile implements AutoCloseable {
        public WorldStreamTransport.OwnedPacket moveToTransport();
        public void close(); // only its currently owned unadvertised file
    }
}
```

Data is explicitly descriptive, bounded, forgeable structural data: map/tile facts,
terminal outcome/reason, source tuples/diffuse encoding/cache digest and recipe.
It has no verified/source-positive flag. Bound all fixed/count/string fields before
copying, use the existing exact numeric mapping and validate against the same codec.
The adapter takes nonce/scope/transaction/slot/generation and exact private path
ONLY from the opaque PacketDestination, never caller protocol setters. It streams
from synchronous borrowed buffers using the existing bounded writer, closes/drains
its channel before return, and retains no Data/geometry/atlas/source view. Output
is a source-neutral file capability containing only exact destination identity,
length/SHA and unique file ownership. No public path/checksum/cleanup-callback
constructor can mint PacketFile or OwnedPacket.

PacketFile starts OWNED. Its moveToTransport atomically changes OWNED to MOVED and
privately constructs the exact WorldStreamTransport.OwnedPacket. This is a metadata
ownership move, not a second file write/rename/reopen. Its close atomically changes
OWNED to CLOSED and deletes only its own unadvertised file off caller; close in
MOVED is an inert no-delete operation. Move from CLOSED/MOVED fails; repeat close
is harmless. Transport alone owns the moved file through INPUT_CONSUMED or actual
child/input/process drain. Neither PacketFile nor OwnedPacket retains WrittenTile,
source handles, geometry, atlas, R cleanup closures or GUI holder references.

## Fixed GUI composition bridge and split handoff

Implement the trusted application bridge in GUI, which already depends on both
concepts-world and rendering-bgfx. It calls only the public neutral structural
adapter; it does not create a split Java package or add concepts to bgfx. Its
qualified-holder constructor and producer callback are private/fixed. There is no
public promote(WrittenTile), accept(Data), receipt-success boolean or user-supplied
packet writer path.

The fixed producer obtains one actual opaque Scope/source and one opaque transport
PacketDestination. Inside prepareAndWrite it maps ONLY CheckedBorrow facts and
actual payload to Data/codec input. Compare actual scope digest with destination
scope; derive WDT/root/tex0/diffuse tuples exclusively from those checked facts.
For terminal height without a positive material receipt tex0 remains absent;
negative observations cannot become positive source tuples. The packet channel
closes before callback return. The actual engine then performs all positive AND
negative material observations plus fresh Map/EOF/WDT/scope checks. Structural
writer success before those final checks is never positively handed off.

After that successful return a local pending producer owns the unique WrittenTile
and its PacketFile. It calls withWrittenResource to MOVE the file into a local
OwnedPacket. It then offers the source payload to the single bounded pending-holder
slot for the exact view generation/reservation. That private holder owns the ONE
WrittenTile base and is the sole source-qualified payload holder; pending/current/
presented maps contain metadata aliases to it, not retained payload copies. Before
returning OwnedPacket the bridge rechecks stopping/latest-view/source validity.
If source close wins before callback acceptance, no move succeeds. An accepted
resource callback may finish through close, but an invalidated payload cannot be
handed off as a current qualified holder. If file move wins, later source close
can retire its base owners but PacketFile is inert and cannot delete child input.

Use explicit local ownership flags, not future completion as a drain proxy:

1. Before successful source return: callback cleans any locally allocated unreturned
   PacketFile; the source cleans returned unadvertised R on failed final validation.
2. After source return/before MOVE: producer locally owns WrittenTile. Rejection or
   cancellation closes it, which closes the still-unadvertised PacketFile/base.
3. After MOVE/before producer return: producer locally owns OwnedPacket INDEPENDENTLY
   of the source holder. Any throwable/cancellation finally closes a MOVED-but-
   unreturned OwnedPacket; closing source R alone would leak it. An accepted pending
   holder is revoked by exact token before its payload closes; stale token removal
   cannot remove a newer holder. Resource/file close executes outside GUI monitors.
4. After normal producer return: transport owns OwnedPacket even if the reservation
   subsequently fails/cancels. The producer never reclaims that file. Controller
   retains its independent pending payload holder until exact STAGED/cancel/drain
   handling determines its metadata aliases are gone.

No packet starts/advertises inside source callbacks. Enqueuing payload metadata
before producer return is necessary for exact STAGED association, but it is not
commit or presentation. A canceled reservation cannot promote its holder. Actual
frame receipt/FX acceptance, rather than COMMITTED alone, retires outgoing
last-presented holders. The union of all holders/constructions remains at most12;
backpressure waits real borrow/presentation cleanup before preparing another tile.
Do not interpret union/atlas/native slot pressure as terminal source height.

## Explicit transport stopping registration

A BooleanSupplier alone cannot cancel a blocked source read before the producer
reaches its next poll. Waiting for terminatedAndDrained to cancel is circular when
that drain itself includes the producer. Use root's approved direction for a small
opaque PacketDestination.onStopping(Runnable requestCancellation) registration:

- At most ONE active callback for its one producer. Register/unregister is metadata
  only. Already stopping schedules callback off caller; callbacks are coalesced once.
- Invoke only on a bounded cancellation lane, outside ALL monitors, FX and pipe
  workers. Never invoke the callback synchronously from register/cancel/unregister.
- Bridge callback only requests its private externalSourceCancellation.cancel. The
  existing source engine dispatches/drains actual lower callbacks off caller.
- Producer finally unregisters/seals the callback. Unregister does not pretend an
  already scheduled/running callback stopped; actual transport drain includes its
  finally and all resulting producer/input ownership. Callback never joins producer.
- Controller's explicit supersede/close also requests that same private source
  cancellation. Packet chunk/source gates observe the same stopping state; no polling
  watcher, extra source pipeline or per-scroll cancellation worker is introduced.

## Causal review gates

Authored real-native Map/WDT/root/tex0/BLP fixtures must pass through the actual
opaque source and fixed callback into structural readback under exact destination.
Forged Data/public generic R cannot construct the private qualified GUI holder.
Hold source final checks, callback unregister, actual lower cancellation and writer
finally independently; prove no early source credit, next root IO or transport drain.
Hold before/after MOVE and throw after MOVE before return; prove exactly one file
owner/deletion, no unreturned moved packet leak and no child-read deletion by source
close. Hold child input while source closes; transport keeps the moved file until
actual input/process drain. Exercise stale generation, scope mismatch, unsupported
custom provider, terminal/partial materials, twelve-union pressure, source changes
and both source-close/MOVE orderings. Inspect that transport packets retain only
bounded file metadata and that expired source views retain no payload/service.
Actual source+packet proof uses private scratch and read-only original stores.
GUI enablement, same-child traversal and measured movement latency remain later
controller integration gates, not claims from this API design.
