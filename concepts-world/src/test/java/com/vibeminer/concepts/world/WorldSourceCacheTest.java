package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorldSourceCacheTest {
    private static final long E = WorldSourceCache.ENTRY_BYTES;

    private static WorldSourceCache.File file(int size, byte fill) {
        var bytes = new byte[size];
        java.util.Arrays.fill(bytes, fill);
        return new WorldSourceCache.File("00112233445566778899aabbccddeeff", bytes, (long) size);
    }

    private static WorldSourceCache.Key parsed(long id) {
        return new WorldSourceCache.Key("reader", 1, id);
    }

    @Test
    void aFileIsKeptWithItsBindingAndEveryReaderGetsItsOwnCopy() {
        var cache = new WorldSourceCache(1 << 20);
        var original = file(100, (byte) 7);
        cache.putFile(5, original);
        original.bytes()[0] = 1; // the caller's array is not the cache's
        var first = cache.file(5);
        assertNotNull(first);
        assertEquals("00112233445566778899aabbccddeeff", first.contentKey());
        assertEquals(100L, first.declaredLength());
        assertEquals(7, first.bytes()[0]);
        first.bytes()[1] = 9; // nor is a reader's
        var second = cache.file(5);
        assertNotSame(first.bytes(), second.bytes());
        assertEquals(7, second.bytes()[1]);
        assertEquals(100 + E, cache.bytes());
        assertEquals(2, cache.hits());
        assertNull(cache.file(6));
        assertEquals(1, cache.misses());
    }

    @Test
    void filesAndParsedValuesShareOneBudgetLeastRecentlyUsedFirst() {
        var cache = new WorldSourceCache(3 * (100 + E));
        cache.putFile(1, file(100, (byte) 1));
        var value = new Object();
        cache.putParsed(parsed(2), value, 100 + E);
        cache.putFile(3, file(100, (byte) 3));
        assertNotNull(cache.file(1)); // 1 is now the most recent; 2 the eldest
        cache.putFile(4, file(100, (byte) 4));
        assertNull(cache.parsed(parsed(2)), "the eldest went, whatever its kind");
        assertNotNull(cache.file(1));
        assertNotNull(cache.file(3));
        assertNotNull(cache.file(4));
        assertEquals(1, cache.evictions());
        assertEquals(3 * (100 + E), cache.bytes());
        assertEquals(3 * (100 + E), cache.peakBytes());
    }

    @Test
    void aParsedValueIsSharedAndKeyedByReaderAndRevision() {
        var cache = new WorldSourceCache(1 << 20);
        var value = new Object();
        cache.putParsed(parsed(9), value, 64);
        assertSame(value, cache.parsed(parsed(9)));
        assertNull(cache.parsed(new WorldSourceCache.Key("reader", 2, 9)), "a new revision");
        assertNull(cache.parsed(new WorldSourceCache.Key("other", 1, 9)), "another reader");
        assertNull(cache.file(9), "a parsed value is not the file");
        assertEquals(1, cache.entries("reader"));
        assertEquals(64, cache.bytes("reader"));
    }

    @Test
    void anEntryOverTheWholeBudgetIsNotKeptAndDisplacesNothing() {
        var cache = new WorldSourceCache(200 + E);
        cache.putFile(1, file(100, (byte) 1));
        cache.putFile(2, file(300, (byte) 2));
        assertNull(cache.file(2));
        assertNotNull(cache.file(1));
        assertEquals(0, cache.evictions());
    }

    @Test
    void removeIfAndClearReleaseTheirBytes() {
        var cache = new WorldSourceCache(1 << 20);
        cache.putFile(1, file(100, (byte) 1));
        var keyed = new Object();
        cache.putParsed(parsed(2), keyed, 64);
        cache.putParsed(parsed(3), new Object(), 64);
        cache.removeIf((key, value) -> value == keyed);
        assertNull(cache.parsed(parsed(2)));
        assertEquals(100 + E + 64, cache.bytes());
        cache.clear();
        assertEquals(0, cache.bytes());
        assertEquals(0, cache.entries());
        assertNull(cache.file(1));
    }

    @Test
    void fileKeysAreNotParsedKeys() {
        var cache = new WorldSourceCache(1 << 20);
        var key = new WorldSourceCache.Key(WorldSourceCache.FILE, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> cache.parsed(key));
        assertThrows(IllegalArgumentException.class, () -> cache.putParsed(key, new Object(), 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldSourceCache(0));
    }

    @Test
    void collisionKeepsItsParsedSourcesInTheSharedCache() {
        var cache = new WorldSourceCache(1 << 20);
        cache.putFile(77, file(100, (byte) 1));
        var reader = new WorldTileCollision((id, token) -> new byte[0], cache);
        assertEquals(0, reader.cachedEntries());
        cache.putParsed(
                new WorldSourceCache.Key(
                        WorldTileCollision.CACHE_KIND, WorldTileCollision.CACHE_REVISION, 5),
                new Object(),
                512);
        assertEquals(1, reader.cachedEntries());
        assertEquals(512, reader.cachedBytes());
        assertArrayEquals(file(100, (byte) 1).bytes(), cache.file(77).bytes());
    }
}
