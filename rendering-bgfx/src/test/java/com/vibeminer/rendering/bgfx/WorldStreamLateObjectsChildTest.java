package com.vibeminer.rendering.bgfx;

import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.BAD_LEASE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.BUSY;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.CLOSED;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.INVALID_TABLE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.OK;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.STALE;
import static com.vibeminer.rendering.bgfx.WorldStreamState.Result.WRONG_SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Child-side admission, generation binding and failure isolation of late exterior objects. */
final class WorldStreamLateObjectsChildTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(41, 77);
    private static final String SCOPE = "ab".repeat(32);
    @TempDir Path directory;

    /** Stages and commits one tile at (x, y) under transaction tx; returns its active lease. */
    private static WorldStreamState.Lease resident(
            WorldStreamState state, long tx, int x, int y, List<WorldStreamState.Lease> others) {
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, tx, lease));
        assertEquals(OK, state.decoded(lease, true, x, y));
        assertEquals(OK, state.staged(lease));
        var table = new java.util.ArrayList<>(others);
        table.add(lease);
        assertEquals(
                OK,
                state.commit(
                        NONCE,
                        tx + 1,
                        state.contentRevision(),
                        state.contentRevision() + 1,
                        table));
        return lease;
    }

    @Test
    void lateObjectsAttachOnlyToTheExactActiveGeneration() {
        var state = new WorldStreamState(NONCE);
        var lease = resident(state, 1, 7, 19, List.of());
        assertEquals(19 * 64 + 7, state.coordinate(lease));
        // Foreign session, transaction, generation or serial never admit the objects.
        assertEquals(WRONG_SESSION, state.objectsOffer(new WorldStreamState.Nonce(1, 1), 1, lease));
        assertEquals(STALE, state.objectsOffer(NONCE, 3, lease));
        var older = new WorldStreamState.Lease(lease.slot(), lease.generation() + 1, 1);
        assertEquals(STALE, state.objectsOffer(NONCE, 1, older));
        var serial = new WorldStreamState.Lease(lease.slot(), lease.generation(), 9);
        assertEquals(STALE, state.objectsOffer(NONCE, 1, serial));

        assertEquals(OK, state.objectsOffer(NONCE, 1, lease));
        assertEquals(lease, state.objectsInFlight());
        assertEquals(BUSY, state.objectsOffer(NONCE, 1, lease));
        assertTrue(state.objectsLive(lease));
        assertEquals(BAD_LEASE, state.objectsDone(serial, true));
        assertEquals(OK, state.objectsDone(lease, true));
        assertNull(state.objectsInFlight());
        // One objects set per generation: a duplicate is dropped, never replaces the first.
        assertEquals(STALE, state.objectsOffer(NONCE, 1, lease));
    }

    @Test
    void heldObjectsAdmitOnlyReplacementsAndAnEmptyGenerationNone() {
        var state = new WorldStreamState(NONCE);
        var lease = resident(state, 1, 7, 19, List.of());
        // Nothing held yet: nothing to replace.
        assertEquals(STALE, state.objectsOffer(NONCE, 1, lease, true));
        assertEquals(OK, state.objectsOffer(NONCE, 1, lease));
        assertEquals(OK, state.objectsDone(lease, true));

        assertEquals(OK, state.objectsOffer(NONCE, 1, lease, true));
        assertTrue(state.objectsLive(lease));
        // A replacement that does not attach leaves the held set; another may follow.
        assertEquals(OK, state.objectsDone(lease, false));
        assertEquals(OK, state.objectsOffer(NONCE, 1, lease, true));
        assertEquals(OK, state.objectsDone(lease, true));
        assertEquals(STALE, state.objectsOffer(NONCE, 1, lease));
        assertEquals(OK, state.objectsOffer(NONCE, 1, lease, true));
        assertEquals(OK, state.objectsDone(lease, true));
    }

    @Test
    void stagedButUncommittedTilesDoNotAdmitObjects() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease));
        assertEquals(OK, state.decoded(lease, true, 7, 19));
        assertEquals(OK, state.staged(lease));
        assertEquals(STALE, state.objectsOffer(NONCE, 1, lease));
        assertEquals(-1, state.coordinate(lease));
    }

    @Test
    void retiredAndReusedSlotsDropInFlightAndLaterObjects() {
        var state = new WorldStreamState(NONCE);
        var first = resident(state, 1, 7, 19, List.of());
        var second = resident(state, 3, 7, 20, List.of(first));
        assertEquals(OK, state.objectsOffer(NONCE, 1, first));
        // A commit that retires the first tile makes its in-flight objects stale.
        assertEquals(OK, state.commit(NONCE, 5, 2, 3, List.of(second)));
        assertFalse(state.objectsLive(first));
        assertEquals(INVALID_TABLE, state.objectsDone(first, true));
        assertEquals(OK, state.objectsDone(first, false));
        assertEquals(STALE, state.objectsOffer(NONCE, 1, first));

        // The retired slot's next generation starts empty and admits only its own objects.
        var third = resident(state, 6, 7, 21, List.of(second));
        var reused = resident(state, 8, 7, 22, List.of(second, third));
        assertEquals(first.slot(), reused.slot());
        assertTrue(reused.generation() > first.generation());
        assertEquals(STALE, state.objectsOffer(NONCE, 1, first));
        assertEquals(STALE, state.objectsOffer(NONCE, 8, first));
        assertEquals(STALE, state.objectsOffer(NONCE, 1, reused));
        assertEquals(OK, state.objectsOffer(NONCE, 8, reused));
        assertEquals(22 * 64 + 7, state.coordinate(reused));
        assertEquals(OK, state.objectsDone(reused, true));
    }

    @Test
    void canceledTileGenerationNeverAdmitsObjects() {
        var state = new WorldStreamState(NONCE);
        var lease = state.credit();
        assertEquals(OK, state.offer(NONCE, 1, lease));
        assertEquals(OK, state.decoded(lease, true, 7, 19));
        assertEquals(OK, state.staged(lease));
        assertEquals(OK, state.cancel(NONCE, 1, lease));
        assertEquals(STALE, state.objectsOffer(NONCE, 1, lease));
        var next = state.credit();
        assertEquals(lease.slot(), next.slot());
        assertEquals(STALE, state.objectsOffer(NONCE, 1, next));
    }

    @Test
    void closeRefusesNewObjectsAndMakesInFlightObjectsDead() {
        var state = new WorldStreamState(NONCE);
        var lease = resident(state, 1, 7, 19, List.of());
        assertEquals(OK, state.objectsOffer(NONCE, 1, lease));
        state.close();
        assertTrue(state.drained());
        assertFalse(state.objectsLive(lease));
        assertEquals(OK, state.objectsDone(lease, false));
        assertEquals(CLOSED, state.objectsOffer(NONCE, 1, lease));
    }

    @Test
    void rendererAttachesOnlyToItsReadyActiveGenerationWithoutObjects() {
        var lease = new WorldStreamState.Lease(3, 5, 8);
        var active = List.of(lease);
        assertTrue(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, false, active));
        // A newer generation began in the slot (stale), the slot was canceled or retired from the
        // committed table, or it already holds objects: the late set is dropped.
        var newer = new WorldStreamState.Lease(3, 6, 9);
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, false, newer, false, active));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, newer, false, List.of(newer)));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, false, lease, false, active));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, false, List.of()));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, true, active));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, null, false, active));
        // A replacement needs the objects it replaces, in that same ready active generation.
        assertTrue(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, true, active, true));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, false, active, true));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, newer, true, active, true));
        assertFalse(BgfxWorldStreamRenderer.lateTarget(lease, true, lease, true, List.of(), true));
    }

    @Test
    void lateObjectsDecodeOnlyForTheirTileIdentityAndFailIsolated() throws Exception {
        var session = new WorldStreamEnvelope.Session(NONCE, SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(3, 5, 8);
        var identity = new WorldStreamObjectsFile.Identity(NONCE, SCOPE, 42, lease, 7, 19);
        var reference =
                WorldStreamObjectsFile.write(
                        directory, identity, data(WorldStreamObjectsFileTest.small()), () -> false);
        var command =
                new WorldStreamControl.LateObjects(
                        NONCE, 42, lease, reference.length(), reference.sha256());
        try (var packed =
                WorldStreamSession.decodeLateObjects(
                        session, directory, command, 7, 19, () -> false)) {
            assertEquals(2, packed.placementCount());
        }
        // The objects file is bound to its tile: other coordinates or a foreign lease refuse.
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                session, directory, command, 8, 19, () -> false));
        var foreign =
                new WorldStreamControl.LateObjects(
                        NONCE,
                        42,
                        new WorldStreamState.Lease(3, 6, 8),
                        reference.length(),
                        reference.sha256());
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                session, directory, foreign, 7, 19, () -> false));
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                new WorldStreamEnvelope.Session(NONCE, SCOPE, 1, false),
                                directory,
                                command,
                                7,
                                19,
                                () -> false));
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                session, directory, command, 7, 19, () -> true));
        // Changed bytes and a missing file are failures of these objects alone.
        var path = directory.resolve("tile-42.wso");
        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                session, directory, command, 7, 19, () -> false));
        Files.delete(path);
        assertThrows(
                IOException.class,
                () ->
                        WorldStreamSession.decodeLateObjects(
                                session, directory, command, 7, 19, () -> false));
    }

    @Test
    void referencedObjectsDecodeFromTheStoreAndPackAndNameWhatIsMissing() throws Exception {
        var session = new WorldStreamEnvelope.Session(NONCE, SCOPE, 1, true);
        var lease = new WorldStreamState.Lease(3, 5, 8);
        var data = data(WorldStreamObjectsFileTest.small());
        var store = new WorldStreamAssetStore<Object>();
        var mirror = new WorldStreamAssetStore<Void>();
        // The inline file, as without references.
        var inline =
                WorldStreamObjectsFile.write(
                        directory,
                        new WorldStreamObjectsFile.Identity(NONCE, SCOPE, 41, lease, 7, 19),
                        data,
                        () -> false);
        // First file: the mirror is empty, so the pack carries every asset it names.
        var first = WorldStreamAssetPlan.of(data, mirror);
        assertEquals(1, first.references.models().length);
        assertEquals(2, first.references.images().length);
        assertEquals(3, first.payloads.size());
        var command = referenced(lease, 42, data, first, 0);
        assertTrue(command.hasPack());
        try (var expected =
                        WorldStreamSession.decodeLateObjects(
                                session,
                                directory,
                                new WorldStreamControl.LateObjects(
                                        NONCE, 41, lease, inline.length(), inline.sha256()),
                                7,
                                19,
                                () -> false);
                var packed =
                        WorldStreamSession.decodeLateObjects(
                                session, directory, command, 7, 19, () -> false, 0xE, store)) {
            assertSamePacked(expected, packed);
        }
        follow(mirror, first);
        assertTrue(store.report().applied());
        assertEquals(3, store.size());
        assertTrue(mirror.report().holdsAs(store.report()));

        // Second file of the same assets: nothing to send, every asset comes from the store.
        var second = WorldStreamAssetPlan.of(data, mirror);
        assertTrue(second.payloads.isEmpty());
        var again = referenced(lease, 43, data, second, 0);
        assertFalse(again.hasPack());
        try (var packed =
                WorldStreamSession.decodeLateObjects(
                        session, directory, again, 7, 19, () -> false, 0xE, store)) {
            assertEquals(2, packed.placementCount());
        }
        follow(mirror, second);
        assertTrue(mirror.report().holdsAs(store.report()));

        // A new epoch empties the store first: the same file now names three missing assets.
        var fresh =
                new WorldStreamControl.LateObjects(
                        NONCE, 43, lease, again.length(), again.sha256(), false, 1, 0, null);
        var missing =
                assertThrows(
                        WorldStreamObjectsFile.MissingAssets.class,
                        () ->
                                WorldStreamSession.decodeLateObjects(
                                        session, directory, fresh, 7, 19, () -> false, 0xE, store));
        assertEquals(3, missing.missing.size());
        assertEquals(new WorldStreamAssetStore.Report(1, false, 0, 0, 0, 0), store.report());
    }

    /** Writes {@code data}'s file with {@code plan}'s references and its pack, if any. */
    private WorldStreamControl.LateObjects referenced(
            WorldStreamState.Lease lease,
            long tx,
            WorldStreamObjectsFile.Data data,
            WorldStreamAssetPlan plan,
            long epoch)
            throws IOException {
        var identity = new WorldStreamObjectsFile.Identity(NONCE, SCOPE, tx, lease, 7, 19);
        var file =
                WorldStreamObjectsFile.write(
                        directory, identity, data, plan.references, () -> false);
        if (plan.payloads.isEmpty()) {
            return new WorldStreamControl.LateObjects(
                    NONCE, tx, lease, file.length(), file.sha256(), false, epoch, 0, null);
        }
        var pack = WorldStreamAssetPack.write(directory, identity, plan.payloads, () -> false);
        return new WorldStreamControl.LateObjects(
                NONCE,
                tx,
                lease,
                file.length(),
                file.sha256(),
                false,
                epoch,
                pack.length(),
                pack.sha256());
    }

    /** The parent's side of an applied file. */
    private static void follow(WorldStreamAssetStore<Void> mirror, WorldStreamAssetPlan plan) {
        var received =
                new java.util.HashMap<
                        WorldStreamObjectAssets.Digest, WorldStreamAssetStore.Received<Void>>();
        for (var p : plan.payloads) {
            received.put(p.digest(), new WorldStreamAssetStore.Received<>(p.bytes().length, null));
        }
        mirror.begin();
        mirror.apply(WorldStreamAssetStore.order(plan.references), received);
    }

    private static void assertSamePacked(
            WorldStreamPackedObjects expected, WorldStreamPackedObjects actual) {
        assertEquals(expected.vertices, actual.vertices);
        assertEquals(expected.indices, actual.indices);
        assertEquals(expected.pixels, actual.pixels);
        assertTrue(java.util.Arrays.equals(expected.meshes, actual.meshes));
        assertTrue(java.util.Arrays.equals(expected.ranges, actual.ranges));
        assertTrue(java.util.Arrays.equals(expected.images, actual.images));
        assertTrue(java.util.Arrays.equals(expected.placements, actual.placements));
        assertTrue(java.util.Arrays.equals(expected.modelKeys, actual.modelKeys));
    }

    static WorldStreamObjectsFile.Data data(WorldStreamTileWriter.Exterior exterior)
            throws Exception {
        var field = WorldStreamTileWriter.Exterior.class.getDeclaredField("data");
        field.setAccessible(true);
        return (WorldStreamObjectsFile.Data) field.get(exterior);
    }
}
