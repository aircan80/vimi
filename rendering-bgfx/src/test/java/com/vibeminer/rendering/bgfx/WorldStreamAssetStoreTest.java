package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The helper's asset store and the parent's mirror of it (#380 stage 4). */
class WorldStreamAssetStoreTest {
    private static WorldStreamObjectAssets.Digest d(int i) {
        return new WorldStreamObjectAssets.Digest(i, ~i);
    }

    private static <V>
            Map<WorldStreamObjectAssets.Digest, WorldStreamAssetStore.Received<V>> received(
                    V value, int... digests) {
        var out = new HashMap<WorldStreamObjectAssets.Digest, WorldStreamAssetStore.Received<V>>();
        for (int i : digests) {
            out.put(d(i), new WorldStreamAssetStore.Received<>(100, value));
        }
        return out;
    }

    @Test
    void aFileAddsWhatItReceivedAndTouchesWhatIsHeld() {
        var store = new WorldStreamAssetStore<String>(1000);
        store.begin();
        assertEquals(0, store.apply(List.of(d(1), d(2), d(1)), received("x", 1, 2)));
        var report = store.report();
        assertTrue(report.applied());
        assertEquals(2, report.entries());
        assertEquals(200, report.bytes());
        assertEquals("x", store.value(d(1)));
        // Held assets need nothing received; an asset neither held nor received is refused.
        store.begin();
        assertEquals(0, store.apply(List.of(d(2)), Map.of()));
        store.begin();
        assertThrows(IllegalArgumentException.class, () -> store.apply(List.of(d(3)), Map.of()));
        // One file per command.
        store.begin();
        store.apply(List.of(), Map.of());
        assertThrows(IllegalStateException.class, () -> store.apply(List.of(), Map.of()));
    }

    @Test
    void theLeastRecentlyNamedGoFirstButNeverTheFilesOwn() {
        var store = new WorldStreamAssetStore<Void>(300);
        store.begin();
        store.apply(List.of(d(1), d(2), d(3)), received(null, 1, 2, 3));
        // Naming 1 again makes 2 the oldest.
        store.begin();
        store.apply(List.of(d(1)), Map.of());
        store.begin();
        assertEquals(1, store.apply(List.of(d(4)), received(null, 4)));
        assertFalse(store.holds(d(2)));
        assertTrue(store.holds(d(1)) && store.holds(d(3)) && store.holds(d(4)));
        assertEquals(1, store.report().evicted());
        // A file larger than the store keeps all of its own and evicts every other.
        store.begin();
        assertEquals(3, store.apply(List.of(d(5), d(6), d(7), d(8)), received(null, 5, 6, 7, 8)));
        assertEquals(4, store.size());
        assertEquals(400, store.bytes());
        // Its own stay held, and the next file trims to the capacity.
        store.begin();
        assertEquals(1, store.apply(List.of(d(8)), Map.of()));
        assertFalse(store.holds(d(5)));
    }

    @Test
    void aResetEmptiesIntoTheNewEpochAndReportsCompareHoldingsOnly() {
        var store = new WorldStreamAssetStore<Void>(1000);
        store.begin();
        store.apply(List.of(d(1)), received(null, 1));
        var before = store.report();
        store.reset(4);
        assertEquals(4, store.epoch());
        assertEquals(0, store.size());
        var empty = store.report();
        assertFalse(empty.applied());
        assertEquals(new WorldStreamAssetStore.Report(4, false, 0, 0, 0, 0), empty);
        assertFalse(before.holdsAs(empty));
        var mirror = new WorldStreamAssetStore<Void>(1000);
        mirror.reset(4);
        assertTrue(mirror.report().holdsAs(empty));
        // The fingerprint follows the held digests, not the order they came in.
        var a = new WorldStreamAssetStore<Void>(1000);
        var b = new WorldStreamAssetStore<Void>(1000);
        a.begin();
        a.apply(List.of(d(1), d(2)), received(null, 1, 2));
        b.begin();
        b.apply(List.of(d(2), d(1)), received(null, 1, 2));
        assertTrue(a.report().holdsAs(b.report()));
        b.begin();
        b.apply(List.of(d(3)), received(null, 3));
        assertNotEquals(a.report().hash(), b.report().hash());
    }

    @Test
    void aMirrorFollowingTheSameFilesReportsWhatTheStoreHolds() {
        var random = new Random(380);
        var store = new WorldStreamAssetStore<Object>(2500);
        var mirror = new WorldStreamAssetStore<Void>(2500);
        for (int file = 0; file < 500; file++) {
            if (random.nextInt(50) == 0) {
                store.reset(store.epoch() + 1);
                mirror.reset(mirror.epoch() + 1);
            }
            var named = new java.util.ArrayList<WorldStreamObjectAssets.Digest>();
            var toStore =
                    new HashMap<
                            WorldStreamObjectAssets.Digest,
                            WorldStreamAssetStore.Received<Object>>();
            var toMirror =
                    new HashMap<
                            WorldStreamObjectAssets.Digest, WorldStreamAssetStore.Received<Void>>();
            for (int k = random.nextInt(12); k > 0; k--) {
                int asset = random.nextInt(80);
                named.add(d(asset));
                // The parent sends exactly what its mirror lacks.
                if (!mirror.holds(d(asset))) {
                    int bytes = 50 + asset * 7;
                    toStore.put(d(asset), new WorldStreamAssetStore.Received<>(bytes, asset));
                    toMirror.put(d(asset), new WorldStreamAssetStore.Received<>(bytes, null));
                }
            }
            store.begin();
            mirror.begin();
            assertEquals(store.apply(named, toStore), mirror.apply(named, toMirror));
            assertTrue(
                    store.report().holdsAs(mirror.report()),
                    "file " + file + " " + store.report() + " " + mirror.report());
            for (var digest : named) {
                assertTrue(store.holds(digest) && store.value(digest) != null);
            }
        }
        assertNull(mirror.value(d(0)));
    }
}
