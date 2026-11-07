package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldStreamParentStateTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, 2);
    private static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(NONCE, "ab".repeat(32), 1);

    private static WorldStreamParentState ready() {
        var state = new WorldStreamParentState(SESSION);
        state.status(new WorldStreamStatus.Session(NONCE));
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(0, 1, 1)));
        return state;
    }

    private static WorldStreamParentState.Ticket offered(
            WorldStreamParentState state, int x, int y) {
        var ticket = state.reserve(x, y);
        assertNotNull(ticket);
        state.beginProducer(ticket);
        state.producerDrained(ticket);
        state.offer(ticket, 4096, "ab".repeat(32), 1, 256);
        return ticket;
    }

    private static void staged(WorldStreamParentState state, WorldStreamParentState.Ticket ticket) {
        state.status(
                new WorldStreamStatus.InputConsumed(
                        NONCE, ticket.transaction, WorldStreamStatus.InputKind.TILE));
        state.status(new WorldStreamStatus.Staged(NONCE, ticket.transaction, ticket.lease, 1, 256));
        state.retireTileFile(ticket);
    }

    private static void committed(
            WorldStreamParentState state, WorldStreamParentState.Commit commit) {
        state.status(
                new WorldStreamStatus.InputConsumed(
                        NONCE, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        state.status(
                new WorldStreamStatus.Committed(
                        NONCE, commit.transaction, commit.expected, commit.next));
    }

    @Test
    void exactAcksAndActualFileRetirementPrecedeNextProducerAndHandleReuse() {
        var state = ready();
        var first = offered(state, 7, 19);
        staged(state, first);
        var commit = state.prepareCommit(1, List.of(first.resident));
        assertEquals(2, commit.transaction);
        committed(state, commit);
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(1, 1, 2)));
        assertNull(state.reserve(8, 19)); // Commit INPUT_CONSUMED is not file-delete completion.
        state.retireCommitFile(commit);
        var next = state.reserve(8, 19);
        assertNotNull(next);
        assertEquals(3, next.transaction);
        assertNull(state.cancel(first)); // Retired old handle cannot cancel the replacement.
        assertEquals(List.of(first.resident), state.active());
        assertEquals(1, state.content());
    }

    @Test
    void unadvertisedCancelWaitsForProducerAndFileDrainAndCannotBeOvertakenByCommit() {
        var state = ready();
        var ticket = state.reserve(7, 19);
        state.beginProducer(ticket);
        assertNull(state.prepareCommit(1, List.of()));
        assertNull(state.cancel(ticket));
        state.producerDrained(ticket);
        assertNull(state.cancel(ticket));
        assertThrows(
                IllegalStateException.class,
                () -> state.offer(ticket, 4096, "ab".repeat(32), 1, 256));
        state.retireTileFile(ticket);
        assertInstanceOf(WorldStreamControl.CancelUnused.class, state.cancel(ticket));
        assertNull(state.cancel(ticket));
        state.status(new WorldStreamStatus.Canceled(NONCE, 1, ticket.lease));
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(0, 2, 2)));
        assertEquals(2, state.reserve(8, 19).transaction);
    }

    @Test
    void offeredCancelRequiresOldInputConsumedAndCreditStillCannotBypassFileCleanup() {
        var state = ready();
        var ticket = offered(state, 7, 19);
        assertInstanceOf(WorldStreamControl.Cancel.class, state.cancel(ticket));
        assertThrows(
                IllegalStateException.class,
                () -> state.status(new WorldStreamStatus.Canceled(NONCE, 1, ticket.lease)));
        state.status(
                new WorldStreamStatus.InputConsumed(NONCE, 1, WorldStreamStatus.InputKind.TILE));
        state.status(new WorldStreamStatus.Canceled(NONCE, 1, ticket.lease));
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(0, 2, 2)));
        assertNull(state.reserve(8, 19));
        state.retireTileFile(ticket);
        assertNotNull(state.reserve(8, 19));
    }

    @Test
    void frameHintIsAWakeUpOnlyAndNeedsAnOpenSession() {
        var uninitialized = new WorldStreamParentState(SESSION);
        assertThrows(
                IllegalStateException.class,
                () -> uninitialized.status(new WorldStreamStatus.FramePublished(NONCE, 1)));
        var state = ready();
        assertEquals(
                WorldStreamParentState.Change.FRAME,
                state.status(new WorldStreamStatus.FramePublished(NONCE, 3)));
        // Hints carry no ordering authority: the mapping remains the only frame source.
        assertEquals(
                WorldStreamParentState.Change.FRAME,
                state.status(new WorldStreamStatus.FramePublished(NONCE, 1)));
        assertEquals(
                WorldStreamParentState.Change.IGNORED,
                state.status(
                        new WorldStreamStatus.FramePublished(new WorldStreamState.Nonce(3, 4), 1)));
        assertNotNull(state.reserve(7, 19));
    }

    @Test
    void duplicateForeignAndPrematureStatusCannotCreateAResidentOrCredit() {
        var uninitialized = new WorldStreamParentState(SESSION);
        assertEquals(
                WorldStreamParentState.Change.IGNORED,
                uninitialized.status(
                        new WorldStreamStatus.Session(new WorldStreamState.Nonce(3, 4))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        uninitialized.status(
                                new WorldStreamStatus.Credit(
                                        NONCE, new WorldStreamState.Lease(0, 1, 1))));
        var state = ready();
        var active = state;
        assertThrows(
                IllegalStateException.class,
                () -> active.status(new WorldStreamStatus.Session(NONCE)));
        var ticket = offered(state, 7, 19);
        assertThrows(
                IllegalStateException.class,
                () -> active.status(new WorldStreamStatus.Staged(NONCE, 1, ticket.lease, 1, 256)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        active.status(
                                new WorldStreamStatus.Credit(
                                        NONCE, new WorldStreamState.Lease(0, 2, 2))));
        active.status(
                new WorldStreamStatus.InputConsumed(NONCE, 1, WorldStreamStatus.InputKind.TILE));
        assertThrows(
                IllegalStateException.class,
                () ->
                        active.status(
                                new WorldStreamStatus.InputConsumed(
                                        NONCE, 1, WorldStreamStatus.InputKind.TILE)));
        assertThrows(
                IllegalStateException.class,
                () -> active.status(new WorldStreamStatus.Staged(NONCE, 1, ticket.lease, 2, 0)));
        assertNull(ticket.resident);
    }

    @Test
    void staleOrDuplicateCoordinatesAndCanceledStagedNodesCannotEnterCommit() {
        var state = ready();
        var old = offered(state, 7, 19);
        staged(state, old);
        var first = state.prepareCommit(1, List.of(old.resident));
        committed(state, first);
        state.retireCommitFile(first);
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(1, 1, 2)));
        var next = offered(state, 7, 19);
        staged(state, next);
        assertThrows(
                IllegalStateException.class,
                () -> state.prepareCommit(1, List.of(old.resident, next.resident)));
        state.cancel(next);
        assertThrows(
                IllegalStateException.class, () -> state.prepareCommit(1, List.of(next.resident)));
        assertEquals(List.of(old.resident), state.active());
    }

    @Test
    void commitValidatesTheSameBoundedSnapshotThatItRetains() {
        var state = ready();
        var own = offered(state, 7, 19);
        staged(state, own);
        var other = ready();
        var foreign = offered(other, 8, 20);
        staged(other, foreign);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var changing =
                new java.util.AbstractList<WorldStreamParentState.Resident>() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public WorldStreamParentState.Resident get(int index) {
                        return reads.getAndIncrement() == 0 ? own.resident : foreign.resident;
                    }
                };
        var commit = state.prepareCommit(1, changing);
        assertEquals(List.of(own.resident), commit.ordered);
        assertEquals(1, reads.get());
    }

    @Test
    void callerListReentrantCloseCannotPublishCommitAfterItsSnapshot() {
        var state = ready();
        var own = offered(state, 7, 19);
        staged(state, own);
        var closes =
                new java.util.AbstractList<WorldStreamParentState.Resident>() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public WorldStreamParentState.Resident get(int index) {
                        state.close();
                        return own.resident;
                    }
                };
        assertNull(state.prepareCommit(1, closes));
        assertEquals(0, state.content());
        assertNull(state.reserve(8, 19));
    }

    @Test
    void heldCallerGetNeverOwnsStateMonitorAndExceptionsLeaveTransactionCounterUntouched()
            throws Exception {
        var state = ready();
        var own = offered(state, 7, 19);
        staged(state, own);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.CompletableFuture<WorldStreamParentState.Commit>();
        var held =
                new java.util.AbstractList<WorldStreamParentState.Resident>() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public WorldStreamParentState.Resident get(int index) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                        return own.resident;
                    }
                };
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                result.complete(state.prepareCommit(1, held));
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        });
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofMillis(500), () -> assertNull(state.reserve(8, 19)));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofMillis(500), state::close);
        } finally {
            release.countDown();
        }
        assertNull(result.get(3, java.util.concurrent.TimeUnit.SECONDS));
        var untouched = ready();
        var throwsFromGet =
                new java.util.AbstractList<WorldStreamParentState.Resident>() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public WorldStreamParentState.Resident get(int index) {
                        throw new IllegalArgumentException("authored caller failure");
                    }
                };
        assertThrows(
                IllegalArgumentException.class, () -> untouched.prepareCommit(1, throwsFromGet));
        assertEquals(1, untouched.reserve(7, 19).transaction);
    }

    @Test
    void rejectionPreservesOldWorldAndClosedOrEofAloneCannotPermitInputDeletion() {
        var state = ready();
        var ticket = offered(state, 7, 19);
        state.close();
        assertThrows(IllegalStateException.class, () -> state.retireTileFile(ticket));
        state.processExited(); // The lifecycle owner's actual Process.onExit observation.
        state.retireTileFile(ticket);
        assertTrue(ticket.fileRetired);
        assertNull(state.cancel(ticket));
        assertNull(state.reserve(8, 19));

        var live = ready();
        var first = offered(live, 7, 19);
        staged(live, first);
        var commit = live.prepareCommit(1, List.of(first.resident));
        live.status(
                new WorldStreamStatus.InputConsumed(
                        NONCE, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        live.status(
                                new WorldStreamStatus.Rejected(
                                        NONCE,
                                        commit.transaction,
                                        WorldStreamStatus.Rejection.GPU_UNAVAILABLE)));
        live.status(
                new WorldStreamStatus.Rejected(
                        NONCE, commit.transaction, WorldStreamStatus.Rejection.INVALID_COMMIT));
        assertTrue(commit.terminal);
        assertFalse(commit.accepted);
        assertEquals(0, live.content());
        assertEquals(List.of(), live.active());
    }

    @Test
    void oneHorizonPerSessionAndUnmatchedAnswersAreIgnored() {
        var state = ready();
        // An answer before any HORIZON was sent is ignored, never fatal.
        assertEquals(
                WorldStreamParentState.Change.IGNORED,
                state.status(
                        new WorldStreamStatus.HorizonDone(
                                NONCE, WorldStreamStatus.HorizonOutcome.DRAWN)));
        var command = state.horizon(4096, "a".repeat(64));
        assertNotNull(command);
        assertEquals(4096, command.length());
        assertNull(state.horizon(4096, "a".repeat(64)));
        assertEquals(
                WorldStreamParentState.Change.HORIZON,
                state.status(
                        new WorldStreamStatus.HorizonDone(
                                NONCE, WorldStreamStatus.HorizonOutcome.FAILED)));
        // A late duplicate answer is ignored too.
        assertEquals(
                WorldStreamParentState.Change.IGNORED,
                state.status(
                        new WorldStreamStatus.HorizonDone(
                                NONCE, WorldStreamStatus.HorizonOutcome.DRAWN)));
        // Before the session hello there is no horizon to send.
        assertNull(new WorldStreamParentState(SESSION).horizon(4096, "a".repeat(64)));
    }
}
