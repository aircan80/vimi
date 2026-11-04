package com.vibeminer.rendering.bgfx;

import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.BAD_LEASE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.BUSY;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.CLOSED;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.INVALID_TABLE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.OK;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.REVISION_MISMATCH;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.STALE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.WRONG_SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldStreamStateTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(41, 77);

    @Test
    void unknownCoordinatesHoldReadOwnershipWhileCameraAndCancelRemainResponsive() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease));
        assertEquals(WorldStreamState.Phase.READING, state.phase());
        assertEquals(INVALID_TABLE, state.decoded(lease, true));
        assertFalse(state.inputConsumed());
        assertEquals(BUSY, state.staged(lease));
        assertEquals(INVALID_TABLE, state.commit(NONCE, 2, 0, 1, List.of(lease)));
        var mailbox = new WorldControlMailbox(NONCE);
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                mailbox.offer(
                        new WorldStreamControl.Camera(
                                NONCE,
                                1,
                                1,
                                800,
                                500,
                                new com.vibeminer.rendering.world.TerrainCamera(0, 0, 0, 0, 0))));
        assertNotNull(mailbox.takeCamera());
        assertEquals(OK, state.cancel(NONCE, 1, lease));
        assertNull(state.credit());
        assertEquals(OK, state.decoded(lease, false));
        assertNotEquals(lease, state.credit());
        assertEquals(BAD_LEASE, state.decoded(lease, true, 7, 19));
        var next = state.credit();
        assertEquals(OK, state.offer(NONCE, 3, next));
        assertEquals(INVALID_TABLE, state.decoded(next, true));
    }

    @Test
    void decodedCoordinatesValidateBeforeConsumptionAndCannotBeReplayedOrChanged() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease));
        assertEquals(BAD_LEASE, state.decoded(new WorldStreamState.Lease(1, 1, 2), true, 7, 19));
        assertEquals(INVALID_TABLE, state.decoded(lease, true, -1, 19));
        assertEquals(INVALID_TABLE, state.decoded(lease, true, 7, 64));
        assertFalse(state.inputConsumed());
        assertEquals(OK, state.decoded(lease, true, 7, 19));
        assertEquals(BAD_LEASE, state.decoded(lease, true, 8, 19));
        assertEquals(OK, state.staged(lease));
        assertEquals(OK, state.commit(NONCE, 2, 0, 1, List.of(lease)));
        var replacement = state.credit();
        assertEquals(OK, state.offer(NONCE, 3, replacement));
        assertEquals(OK, state.decoded(replacement, true, 7, 19));
        assertEquals(OK, state.staged(replacement));
        assertEquals(INVALID_TABLE, state.commit(NONCE, 4, 1, 2, List.of(lease, replacement)));
        assertEquals(OK, state.commit(NONCE, 5, 1, 2, List.of(replacement)));
    }

    @Test
    void reusesTenSlotsForHundredsOfOrderedAtomicCommitsWithoutReplayOrAba() {
        var state = new WorldStreamState(NONCE);
        long tx = 0;
        var oldLeases = new ArrayList<WorldStreamState.Lease>();
        for (int cycle = 0; cycle < 240; cycle++) {
            var lease = state.credit();
            assertNotNull(lease);
            for (var old : oldLeases) {
                assertNotEquals(old, lease);
            }
            long offered = ++tx;
            assertEquals(OK, state.offer(NONCE, offered, lease, cycle % 64, cycle / 64));
            assertNull(state.credit());
            var oldTable = state.active();
            assertEquals(OK, state.decoded(lease, true));
            assertTrue(state.inputConsumed());
            assertNull(state.credit(), "Closing input is not upload credit");
            assertEquals(OK, state.submittedReference(lease));
            assertEquals(OK, state.staged(lease));
            assertEquals(oldTable, state.active(), "Inactive incoming pair never becomes visible");
            var table = new ArrayList<>(oldTable);
            if (table.size() == 9) {
                table.removeFirst();
            }
            table.addFirst(lease); // Intentional insertion order disagrees with slot ordering.
            assertEquals(OK, state.commit(NONCE, ++tx, cycle, cycle + 1, table));
            assertEquals(table, state.active());
            assertNull(
                    state.credit(), "Commit ACK cannot return credit while callbacks own buffers");
            assertEquals(
                    STALE,
                    state.cancel(NONCE, offered, lease),
                    "Committed data cannot be canceled");
            assertEquals(OK, state.consumedReference(lease));
            assertNotNull(state.credit());
            assertEquals(STALE, state.offer(NONCE, offered, lease, 0, 0));
            assertEquals(BAD_LEASE, state.offer(NONCE, ++tx, lease, 0, 0));
            assertEquals(BAD_LEASE, state.consumedReference(lease));
            assertTrue(state.active().size() <= 9);
            oldLeases.add(lease);
        }
        state.close();
        assertTrue(state.drained());
        assertTrue(state.active().isEmpty());
    }

    @Test
    void cancelReferencesOfferEvenAfterLaterRejectedCommitAndWaitsForEveryOwner() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease, 17, 21));
        assertEquals(REVISION_MISMATCH, state.commit(NONCE, 2, 3, 4, List.of()));
        assertEquals(OK, state.cancel(NONCE, 1, lease));
        assertEquals(OK, state.cancel(NONCE, 1, lease));
        assertNull(state.credit(), "Held decoder still owns input and payload");
        assertEquals(OK, state.decoded(lease, true));
        var next = state.credit();
        assertNotNull(next);
        assertEquals(STALE, state.cancel(NONCE, 1, lease));
        assertEquals(next, state.credit());
        assertEquals(OK, state.offer(NONCE, 3, next, 18, 21));
        assertEquals(OK, state.decoded(next, true));
        for (int i = 0; i < WorldStreamState.MAX_REFERENCES; i++) {
            assertEquals(OK, state.submittedReference(next));
        }
        assertEquals(BUSY, state.submittedReference(next));
        assertEquals(OK, state.cancel(NONCE, 3, next));
        for (int i = WorldStreamState.MAX_REFERENCES; i > 0; i--) {
            assertNull(state.credit());
            assertEquals(OK, state.consumedReference(next));
        }
        assertNotNull(state.credit());
        assertEquals(0, state.references());
    }

    @Test
    void canceledUnadvertisedWriterReturnsOnlyItsUnusedGenerationWithoutInventingInput() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        // Authored host sequence: writer starts, is canceled, and closes its private temp owner.
        var writer = new AuthoredWriter(lease);
        writer.cancel();
        assertFalse(writer.maySendUnusedCancel());
        assertEquals(
                lease, state.credit(), "No OFFER was advertised while writer owned the candidate");
        writer.drained();
        assertTrue(writer.maySendUnusedCancel());
        assertEquals(OK, state.cancelUnused(NONCE, 1, lease));
        assertFalse(state.inputConsumed(), "No input file ever existed in the child");
        var next = state.credit();
        assertNotNull(next);
        assertNotEquals(lease.generation(), next.generation());
        assertEquals(STALE, state.cancelUnused(NONCE, 1, lease));
        assertEquals(BAD_LEASE, state.cancelUnused(NONCE, 2, lease));
        assertEquals(next, state.credit());
        assertEquals(
                BAD_LEASE,
                state.offer(NONCE, 3, lease, 0, 0),
                "Stale writer completion cannot publish");
        assertEquals(OK, state.offer(NONCE, 4, next, 0, 0));
        assertEquals(
                BAD_LEASE,
                state.cancelUnused(NONCE, 5, next),
                "Advertised input requires established CANCEL");
        assertEquals(OK, state.cancel(NONCE, 4, next));
        assertNull(state.credit());
        assertEquals(OK, state.decoded(next, false));
        assertNotNull(state.credit());
    }

    @Test
    void rejectsBadTablesWithoutChangingCurrentAndOptionalFailureDoesNotKillSession() {
        var state = new WorldStreamState(NONCE);
        var first = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, first, 17, 21));
        assertEquals(OK, state.decoded(first, true));
        assertEquals(INVALID_TABLE, state.commit(NONCE, 2, 0, 1, List.of(first)), "Unstaged pair");
        assertEquals(OK, state.staged(first));
        assertEquals(INVALID_TABLE, state.commit(NONCE, 3, 0, 1, List.of(first, first)));
        assertEquals(OK, state.commit(NONCE, 4, 0, 1, List.of(first)));
        var second = state.credit();
        assertEquals(OK, state.offer(NONCE, 5, second, 17, 21));
        assertEquals(OK, state.decoded(second, true));
        assertEquals(OK, state.staged(second));
        assertEquals(
                INVALID_TABLE,
                state.commit(NONCE, 6, 1, 2, List.of(first, second)),
                "Duplicate tile identity");
        assertEquals(List.of(first), state.active());
        assertEquals(
                OK,
                state.commit(NONCE, 7, 1, 2, List.of(second)),
                "Atomic same-tile material replacement");
        var third = state.credit();
        assertEquals(OK, state.offer(NONCE, 8, third, 18, 21));
        assertEquals(OK, state.decoded(third, false));
        assertEquals(List.of(second), state.active());
        assertNotNull(state.credit());
        assertEquals(
                WRONG_SESSION,
                state.offer(new WorldStreamState.Nonce(1, 2), 100, state.credit(), 0, 0));
        assertEquals(8, state.highWater());
    }

    @Test
    void closeWaitsForDecoderAndUploadReferencesAndRejectsNewWork() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease, 17, 21));
        state.close();
        assertFalse(state.drained());
        assertEquals(CLOSED, state.offer(NONCE, 2, lease, 17, 21));
        assertEquals(OK, state.decoded(lease, true));
        assertTrue(state.drained());
        state.close();
        assertTrue(state.drained());
        var uploads = new WorldStreamState(NONCE);
        var pending = uploads.credit();
        uploads.offer(NONCE, 1, pending, 17, 21);
        uploads.decoded(pending, true);
        uploads.submittedReference(pending);
        uploads.close();
        assertFalse(uploads.drained());
        assertEquals(OK, uploads.consumedReference(pending));
        assertTrue(uploads.drained());
        assertNull(uploads.credit());
    }

    @Test
    void commitsMayExcludeStagedAndAdvanceDuringCommittedDrainWithoutReturningExtraCredit() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        state.offer(NONCE, 1, lease, 17, 21);
        state.decoded(lease, true);
        state.submittedReference(lease);
        state.staged(lease);
        assertEquals(OK, state.commit(NONCE, 2, 0, 1, List.of()));
        assertEquals(WorldStreamState.Phase.STAGED, state.phase());
        assertNull(state.credit());
        assertEquals(OK, state.commit(NONCE, 3, 1, 2, List.of(lease)));
        assertEquals(WorldStreamState.Phase.COMMITTED_DRAIN, state.phase());
        assertEquals(OK, state.commit(NONCE, 4, 2, 3, List.of()));
        assertTrue(state.active().isEmpty());
        assertNull(
                state.credit(), "Dropped active member does not free incoming source references");
        assertEquals(OK, state.consumedReference(lease));
        assertNotNull(state.credit());
        assertNotEquals(lease, state.credit());
        assertEquals(0, state.references());
    }

    @Test
    void transactionExhaustionClosesWithoutWrappingAndDrainsAnExistingDecoder() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, Long.MAX_VALUE - 1, lease, 17, 21));
        assertEquals(STALE, state.commit(NONCE, Long.MIN_VALUE, 0, 1, List.of()));
        assertEquals(CLOSED, state.commit(NONCE, Long.MAX_VALUE, 0, 1, List.of()));
        assertNull(state.credit());
        assertFalse(state.drained());
        assertEquals(OK, state.decoded(lease, true));
        assertTrue(state.drained());
        assertEquals(BAD_LEASE, state.consumedReference(lease));
    }

    @org.junit.jupiter.api.Test
    void commitReservationConsumesReplayBeforeIoAndCompletionSurvivesLaterHighwater() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease, 7, 19));
        assertEquals(OK, state.reserveCommit(NONCE, 2));
        assertEquals(BUSY, state.reserveCommit(NONCE, 3));
        assertEquals(3, state.highWater());
        assertEquals(STALE, state.reserveCommit(NONCE, 2));
        assertEquals(STALE, state.rejectReserved(NONCE, 3));
        assertEquals(OK, state.cancel(NONCE, 1, lease));
        assertEquals(OK, state.decoded(lease, false));
        assertEquals(OK, state.commitReserved(NONCE, 2, 0, 1, java.util.List.of()));
        assertEquals(1, state.contentRevision());
        assertEquals(STALE, state.commitReserved(NONCE, 2, 1, 2, java.util.List.of()));
        assertEquals(OK, state.reserveCommit(NONCE, 4));
        assertEquals(OK, state.rejectReserved(NONCE, 4));
        assertEquals(STALE, state.reserveCommit(NONCE, 4));
        assertEquals(1, state.contentRevision());
    }

    @org.junit.jupiter.api.Test
    void commitReservationKeepsCloseUndrainedUntilExactDecoderCompletion() {
        var state = new WorldStreamState(NONCE);
        assertEquals(OK, state.reserveCommit(NONCE, 1));
        var wrong = new WorldStreamState.Nonce(99, 88);
        assertEquals(WRONG_SESSION, state.rejectReserved(wrong, 1));
        assertEquals(WRONG_SESSION, state.commitReserved(wrong, 1, 0, 1, java.util.List.of()));
        state.close();
        assertFalse(state.drained());
        assertEquals(STALE, state.rejectReserved(NONCE, 2));
        assertFalse(state.drained());
        assertEquals(CLOSED, state.commitReserved(NONCE, 1, 0, 1, java.util.List.of()));
        assertTrue(state.drained());
        assertEquals(0, state.contentRevision());
    }

    private static final class AuthoredWriter {
        private final WorldStreamState.Lease lease;
        private boolean canceled, owned = true;

        AuthoredWriter(WorldStreamState.Lease lease) {
            this.lease = lease;
        }

        void cancel() {
            canceled = true;
        }

        void drained() {
            owned = false;
        }

        boolean maySendUnusedCancel() {
            return lease != null && canceled && !owned;
        }
    }
}
