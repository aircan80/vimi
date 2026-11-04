package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Child decoder rejection can overtake an already-issued established cancellation. */
class WorldStreamParentCancellationRaceTest {
    private record Pending(
            WorldStreamParentState state,
            WorldStreamParentState.Ticket ticket,
            WorldStreamState.Nonce nonce) {}

    private static Pending pending(boolean cancel) {
        var nonce = new WorldStreamState.Nonce(1, 2);
        var state =
                new WorldStreamParentState(
                        new WorldStreamEnvelope.Session(nonce, "ab".repeat(32), 1));
        state.status(new WorldStreamStatus.Session(nonce));
        state.status(new WorldStreamStatus.Credit(nonce, new WorldStreamState.Lease(0, 1, 1)));
        var ticket = state.reserve(7, 19);
        state.beginProducer(ticket);
        state.producerDrained(ticket);
        state.offer(ticket, 4096, "ab".repeat(32), 1, 256);
        if (cancel) {
            state.cancel(ticket);
        }
        state.status(
                new WorldStreamStatus.InputConsumed(
                        nonce, ticket.transaction, WorldStreamStatus.InputKind.TILE));
        return new Pending(state, ticket, nonce);
    }

    private static Pending rejected(boolean cancel, boolean retire) {
        var value = pending(cancel);
        value.state.status(
                new WorldStreamStatus.Rejected(
                        value.nonce,
                        value.ticket.transaction,
                        WorldStreamStatus.Rejection.INVALID_PACKET));
        if (retire) {
            value.state.retireTileFile(value.ticket);
        }
        value.state.status(
                new WorldStreamStatus.Credit(value.nonce, new WorldStreamState.Lease(0, 2, 2)));
        return value;
    }

    private static WorldStreamStatus.Rejected stale(Pending value) {
        return new WorldStreamStatus.Rejected(
                value.nonce,
                value.ticket.transaction,
                WorldStreamStatus.Rejection.STALE_TRANSACTION);
    }

    @Test
    void staleCancelResolutionRequiresExactOldIdentityPriorInvalidPacketAndOutstandingCancel() {
        var noInvalid = pending(true);
        assertThrows(IllegalStateException.class, () -> noInvalid.state.status(stale(noInvalid)));
        var noCancel = rejected(false, true);
        assertThrows(IllegalStateException.class, () -> noCancel.state.status(stale(noCancel)));
        var exact = rejected(true, true);
        assertThrows(
                IllegalStateException.class,
                () ->
                        exact.state.status(
                                new WorldStreamStatus.Rejected(
                                        exact.nonce,
                                        exact.ticket.transaction + 1,
                                        WorldStreamStatus.Rejection.STALE_TRANSACTION)));
        assertEquals(
                WorldStreamParentState.Change.IGNORED,
                exact.state.status(
                        new WorldStreamStatus.Rejected(
                                new WorldStreamState.Nonce(9, 9),
                                exact.ticket.transaction,
                                WorldStreamStatus.Rejection.STALE_TRANSACTION)));
        assertNull(exact.state.reserve(8, 19));
        assertEquals(
                WorldStreamParentState.Change.CANCEL_REJECTED, exact.state.status(stale(exact)));
        assertThrows(IllegalStateException.class, () -> exact.state.status(stale(exact)));
        assertNotNull(exact.state.reserve(8, 19));
    }

    @Test
    void cancelRejectionDoesNotReplaceActualInputFileRetirement() {
        var value = rejected(true, false);
        value.state.status(stale(value));
        assertNull(value.state.reserve(8, 19));
        value.state.retireTileFile(value.ticket);
        assertNotNull(value.state.reserve(8, 19));
    }

    @Test
    void invalidPacketThenStaleEstablishedCancelPreservesOldTableAndRetainsCreditUntilResolved() {
        var nonce = new WorldStreamState.Nonce(1, 2);
        var state =
                new WorldStreamParentState(
                        new WorldStreamEnvelope.Session(nonce, "ab".repeat(32), 1));
        state.status(new WorldStreamStatus.Session(nonce));
        state.status(new WorldStreamStatus.Credit(nonce, new WorldStreamState.Lease(0, 1, 1)));
        var old = state.reserve(7, 19);
        state.beginProducer(old);
        state.producerDrained(old);
        state.offer(old, 4096, "ab".repeat(32), 1, 256);
        state.status(
                new WorldStreamStatus.InputConsumed(
                        nonce, old.transaction, WorldStreamStatus.InputKind.TILE));
        state.status(new WorldStreamStatus.Staged(nonce, old.transaction, old.lease, 1, 256));
        state.retireTileFile(old);
        var commit = state.prepareCommit(1, List.of(old.resident));
        state.status(
                new WorldStreamStatus.InputConsumed(
                        nonce, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        state.status(new WorldStreamStatus.Committed(nonce, commit.transaction, 0, 1));
        state.retireCommitFile(commit);
        state.status(new WorldStreamStatus.Credit(nonce, new WorldStreamState.Lease(1, 1, 2)));
        var bad = state.reserve(8, 19);
        state.beginProducer(bad);
        state.producerDrained(bad);
        state.offer(bad, 4096, "cd".repeat(32), 1, 256);
        assertNotNull(
                state.cancel(bad)); // Already queued/sent before the decoder rejection arrives.
        state.status(
                new WorldStreamStatus.InputConsumed(
                        nonce, bad.transaction, WorldStreamStatus.InputKind.TILE));
        state.retireTileFile(bad);
        state.status(
                new WorldStreamStatus.Rejected(
                        nonce, bad.transaction, WorldStreamStatus.Rejection.INVALID_PACKET));
        state.status(new WorldStreamStatus.Credit(nonce, new WorldStreamState.Lease(1, 2, 3)));
        assertNull(state.reserve(9, 19)); // Keep this one ticket until its pending CANCEL resolves.
        var change =
                assertDoesNotThrow(
                        () ->
                                state.status(
                                        new WorldStreamStatus.Rejected(
                                                nonce,
                                                bad.transaction,
                                                WorldStreamStatus.Rejection.STALE_TRANSACTION)));
        assertEquals("CANCEL_REJECTED", change.name());
        assertEquals(List.of(old.resident), state.active());
        assertEquals(1, state.content());
        assertNotNull(state.reserve(9, 19));
    }
}
