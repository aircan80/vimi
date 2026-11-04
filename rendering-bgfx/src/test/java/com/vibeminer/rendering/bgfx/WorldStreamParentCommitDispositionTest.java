package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldStreamParentCommitDispositionTest {
    private static final WorldStreamState.Nonce N = new WorldStreamState.Nonce(1, 2);

    private static WorldStreamParentState ready() {
        var s = new WorldStreamParentState(new WorldStreamEnvelope.Session(N, "ab".repeat(32), 1));
        s.status(new WorldStreamStatus.Session(N));
        s.status(new WorldStreamStatus.Credit(N, new WorldStreamState.Lease(0, 1, 1)));
        return s;
    }

    private static WorldStreamParentState.Ticket stage(WorldStreamParentState s, int x) {
        var t = s.reserve(x, 19);
        s.beginProducer(t);
        s.producerDrained(t);
        s.offer(t, 4096, "ab".repeat(32), 1, 256);
        s.status(
                new WorldStreamStatus.InputConsumed(
                        N, t.transaction, WorldStreamStatus.InputKind.TILE));
        s.status(new WorldStreamStatus.Staged(N, t.transaction, t.lease, 1, 256));
        s.retireTileFile(t);
        return t;
    }

    private static void apply(WorldStreamParentState s, WorldStreamParentState.Commit c) {
        s.status(
                new WorldStreamStatus.InputConsumed(
                        N, c.transaction, WorldStreamStatus.InputKind.COMMIT));
        s.status(new WorldStreamStatus.Committed(N, c.transaction, c.expected, c.next));
        s.retireCommitFile(c);
    }

    @Test
    void excludedStagedCandidateIsResolvedButNeverCalledActiveCommittedOrRemixed() {
        var s = ready();
        var old = stage(s, 7);
        apply(s, s.prepareCommit(1, List.of(old.resident)));
        assertEquals(WorldStreamParentState.Cancellation.COMMITTED, s.cancellation(old));
        s.status(new WorldStreamStatus.Credit(N, new WorldStreamState.Lease(1, 1, 2)));
        var candidate = stage(s, 8);
        var commit = s.prepareCommit(1, List.of(old.resident));
        assertEquals(WorldStreamParentState.Cancellation.COMMIT_PENDING, s.cancellation(candidate));
        assertNull(s.cancel(candidate));
        assertFalse(candidate.cancelRequested);
        s.status(
                new WorldStreamStatus.InputConsumed(
                        N, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        assertThrows(
                IllegalStateException.class,
                () ->
                        s.status(
                                new WorldStreamStatus.Committed(
                                        N, commit.transaction + 1, commit.expected, commit.next)));
        assertFalse(candidate.commitResolved);
        s.status(
                new WorldStreamStatus.Committed(
                        N, commit.transaction, commit.expected, commit.next));
        s.retireCommitFile(commit);
        assertEquals(List.of(old.resident), s.active());
        assertEquals(2, s.content());
        assertFalse(candidate.committed);
        assertEquals(WorldStreamParentState.Cancellation.RETIRED, s.cancellation(candidate));
        assertNull(s.cancel(candidate));
        assertFalse(candidate.cancelRequested);
        assertThrows(
                IllegalStateException.class,
                () -> s.prepareCommit(1, List.of(old.resident, candidate.resident)));
        s.status(new WorldStreamStatus.Credit(N, new WorldStreamState.Lease(1, 2, 3)));
        var next = s.reserve(9, 19);
        assertNotNull(next);
        assertEquals(5, next.transaction);
    }

    @Test
    void failedCommitLeavesCandidateStagedAndCancellationCanBeRequestedAfterResolution() {
        var s = ready();
        var candidate = stage(s, 7);
        var commit = s.prepareCommit(1, List.of(candidate.resident));
        assertEquals(WorldStreamParentState.Cancellation.COMMIT_PENDING, s.cancellation(candidate));
        assertNull(s.cancel(candidate));
        assertFalse(candidate.cancelRequested);
        s.status(
                new WorldStreamStatus.InputConsumed(
                        N, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        s.status(
                new WorldStreamStatus.Rejected(
                        N, commit.transaction, WorldStreamStatus.Rejection.INVALID_COMMIT));
        s.retireCommitFile(commit);
        assertFalse(candidate.commitResolved);
        assertEquals(WorldStreamParentState.Cancellation.PENDING, s.cancellation(candidate));
        assertNotNull(s.cancel(candidate));
        assertEquals(0, s.content());
        assertEquals(List.of(), s.active());
    }

    @Test
    void acceptedEmptyCommitRetiresCandidateAndStillRequiresActualInputAndCommitFileCleanup() {
        var s = ready();
        var t = s.reserve(7, 19);
        s.beginProducer(t);
        s.producerDrained(t);
        s.offer(t, 4096, "ab".repeat(32), 1, 256);
        s.status(
                new WorldStreamStatus.InputConsumed(
                        N, t.transaction, WorldStreamStatus.InputKind.TILE));
        s.status(new WorldStreamStatus.Staged(N, t.transaction, t.lease, 1, 256));
        var c = s.prepareCommit(1, List.of());
        apply(s, c);
        s.status(new WorldStreamStatus.Credit(N, new WorldStreamState.Lease(0, 2, 2)));
        assertNull(s.reserve(8, 19));
        s.retireTileFile(t);
        assertNotNull(s.reserve(8, 19));
        assertEquals(WorldStreamParentState.Cancellation.RETIRED, s.cancellation(t));
    }
}
