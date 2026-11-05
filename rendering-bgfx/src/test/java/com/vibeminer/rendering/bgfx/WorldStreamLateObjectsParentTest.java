package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parent metadata of late exterior objects: one credit, active residents only, exact replies. */
class WorldStreamLateObjectsParentTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, 2);
    private static final String SHA = "ab".repeat(32);

    private static WorldStreamParentState ready(boolean exteriors) {
        var state =
                new WorldStreamParentState(
                        new WorldStreamEnvelope.Session(NONCE, SHA, 1, exteriors));
        state.status(new WorldStreamStatus.Session(NONCE, exteriors));
        state.status(new WorldStreamStatus.Credit(NONCE, new WorldStreamState.Lease(0, 1, 1)));
        return state;
    }

    /** Offers, stages (no inline objects) and commits one tile together with {@code others}. */
    private static WorldStreamParentState.Resident resident(
            WorldStreamParentState state,
            int x,
            int y,
            List<WorldStreamParentState.Resident> others,
            WorldStreamState.Lease next) {
        return resident(state, true, x, y, others, next);
    }

    private static WorldStreamParentState.Resident resident(
            WorldStreamParentState state,
            boolean exteriors,
            int x,
            int y,
            List<WorldStreamParentState.Resident> others,
            WorldStreamState.Lease next) {
        var ticket = state.reserve(x, y);
        assertNotNull(ticket);
        state.beginProducer(ticket);
        state.producerDrained(ticket);
        state.offer(ticket, 4096, SHA, 1, 256);
        state.status(
                new WorldStreamStatus.InputConsumed(
                        NONCE, ticket.transaction, WorldStreamStatus.InputKind.TILE));
        // Exterior sessions stage without inline objects (0); terrain-only ones omit the field.
        state.status(
                new WorldStreamStatus.Staged(
                        NONCE, ticket.transaction, ticket.lease, 1, 256, exteriors ? 0 : -1));
        state.retireTileFile(ticket);
        var table = new java.util.ArrayList<>(others);
        table.add(ticket.resident);
        commit(state, table);
        state.status(new WorldStreamStatus.Credit(NONCE, next));
        return ticket.resident;
    }

    private static void commit(
            WorldStreamParentState state, List<WorldStreamParentState.Resident> table) {
        var commit = state.prepareCommit(1, table);
        assertNotNull(commit);
        state.status(
                new WorldStreamStatus.InputConsumed(
                        NONCE, commit.transaction, WorldStreamStatus.InputKind.COMMIT));
        state.status(
                new WorldStreamStatus.Committed(
                        NONCE, commit.transaction, commit.expected, commit.next));
        state.retireCommitFile(commit);
    }

    private static WorldStreamStatus.ObjectsDone done(
            WorldStreamParentState.Resident resident, WorldStreamStatus.LateOutcome outcome) {
        return new WorldStreamStatus.ObjectsDone(
                NONCE, resident.transaction, resident.lease, outcome);
    }

    @Test
    void activeResidentGetsOneCommandAndExactOutcome() {
        var state = ready(true);
        var resident = resident(state, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        var command = state.lateObjects(resident, 4096, SHA);
        assertNotNull(command);
        assertEquals(resident.transaction, command.transaction());
        assertEquals(resident.lease, command.lease());
        assertTrue(state.lateObjectsPending());
        // An outcome for another transaction or lease is a protocol fault, never a guess.
        assertThrows(
                IllegalStateException.class,
                () ->
                        state.status(
                                new WorldStreamStatus.ObjectsDone(
                                        NONCE,
                                        resident.transaction + 1,
                                        resident.lease,
                                        WorldStreamStatus.LateOutcome.ATTACHED)));
        assertEquals(
                WorldStreamParentState.Change.OBJECTS,
                state.status(done(resident, WorldStreamStatus.LateOutcome.ATTACHED)));
        assertSame(WorldStreamStatus.LateOutcome.ATTACHED, resident.late);
        assertFalse(state.lateObjectsPending());
        // Requested at most once per resident; a second outcome has no target.
        assertThrows(IllegalStateException.class, () -> state.lateObjects(resident, 4096, SHA));
        assertThrows(
                IllegalStateException.class,
                () -> state.status(done(resident, WorldStreamStatus.LateOutcome.ATTACHED)));
    }

    @Test
    void oneDeliveryInFlightAndOnlyInTheExteriorProfile() {
        var state = ready(true);
        var first = resident(state, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        var second = resident(state, 7, 20, List.of(first), new WorldStreamState.Lease(2, 1, 3));
        assertNotNull(state.lateObjects(first, 4096, SHA));
        assertThrows(IllegalStateException.class, () -> state.lateObjects(second, 4096, SHA));
        state.status(done(first, WorldStreamStatus.LateOutcome.FAILED));
        assertSame(WorldStreamStatus.LateOutcome.FAILED, first.late);
        assertNotNull(state.lateObjects(second, 4096, SHA));

        var terrainOnly = ready(false);
        var plain =
                resident(terrainOnly, false, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        assertThrows(IllegalStateException.class, () -> terrainOnly.lateObjects(plain, 4096, SHA));
    }

    @Test
    void retiredResidentIsDroppedWithoutAnyCommand() {
        var state = ready(true);
        var first = resident(state, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        var second = resident(state, 7, 20, List.of(first), new WorldStreamState.Lease(2, 1, 3));
        commit(state, List.of(second));
        assertNull(state.lateObjects(first, 4096, SHA));
        assertFalse(state.lateObjectsPending());
        assertNull(first.late);
        // The refusal still consumed the resident's one request.
        assertThrows(IllegalStateException.class, () -> state.lateObjects(first, 4096, SHA));
        assertNotNull(state.lateObjects(second, 4096, SHA));
    }

    @Test
    void synchronousAdmissionRefusesMisuseAndDropsRetiredResidents() {
        var state = ready(true);
        var first = resident(state, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        var second = resident(state, 7, 20, List.of(first), new WorldStreamState.Lease(2, 1, 3));
        assertTrue(state.admitLateObjects(second));
        assertThrows(IllegalArgumentException.class, () -> state.admitLateObjects(null));
        var foreign = ready(true);
        var other = resident(foreign, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> state.admitLateObjects(other));
        commit(state, List.of(second));
        assertFalse(state.admitLateObjects(first));
        // The retired resident's one request is consumed; asking again is misuse.
        assertThrows(IllegalArgumentException.class, () -> state.admitLateObjects(first));
        assertNotNull(state.lateObjects(second, 4096, SHA));
        state.status(done(second, WorldStreamStatus.LateOutcome.DROPPED));
        assertThrows(IllegalArgumentException.class, () -> state.admitLateObjects(second));
    }

    @Test
    void closedSessionRefusesLateObjects() {
        var state = ready(true);
        var resident = resident(state, 7, 19, List.of(), new WorldStreamState.Lease(1, 1, 2));
        state.close();
        assertThrows(IllegalStateException.class, () -> state.lateObjects(resident, 4096, SHA));
    }
}
