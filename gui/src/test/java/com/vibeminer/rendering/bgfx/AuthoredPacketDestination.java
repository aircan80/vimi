package com.vibeminer.rendering.bgfx;

import java.nio.file.Files;
import java.nio.file.Path;

/** Structural transport fixture only; supplies no native/source positivity. */
public final class AuthoredPacketDestination {
    private AuthoredPacketDestination() {}

    public static WorldStreamTransport.PacketDestination create(Path path, String scope)
            throws Exception {
        return controlled(path, scope).destination();
    }

    public record Control(WorldStreamTransport.PacketDestination destination,
            WorldStreamProducerCancellation lane, WorldStreamProducerCancellation.Producer producer) {
        public void stop() { producer.stop(); }
        public void runCancellation() throws InterruptedException { lane.work(); }
        public void finish() { producer.finished(); lane.stopSession(); }
    }

    public static Control controlled(Path path, String scope) throws Exception {
        return controlled(path, scope, false);
    }

    /** With {@code exteriors}, the destination's session admits a per-tile objects file. */
    public static Control controlled(Path path, String scope, boolean exteriors) throws Exception {
        Files.createDirectories(path.getParent());
        var descriptor = new WorldStreamEnvelope.Session(
                new WorldStreamState.Nonce(12, -34), scope, 1, exteriors);
        var state = new WorldStreamParentState(descriptor);
        state.status(new WorldStreamStatus.Session(descriptor.nonce(), exteriors));
        state.status(new WorldStreamStatus.Credit(descriptor.nonce(),
                new WorldStreamState.Lease(0, 1, 1)));
        var ticket = state.reserve(7, 19);
        var lane = new WorldStreamProducerCancellation();
        var cancellation = lane.begin();
        var ctor = WorldStreamTransport.PacketDestination.class.getDeclaredConstructor(
                WorldStreamEnvelope.Session.class, WorldStreamParentState.Ticket.class,
                Path.class, WorldStreamProducerCancellation.Producer.class);
        ctor.setAccessible(true);
        return new Control(ctor.newInstance(descriptor, ticket, path, cancellation), lane, cancellation);
    }
}
