package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import java.nio.ReadOnlyBufferException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class TerrainSourceWindowTest {
    static CompactTerrainSource.Descriptor descriptor(
            int x, int y, CompactTerrainSource.Outcome outcome) {
        return new CompactTerrainSource.Descriptor(
                x,
                y,
                outcome == CompactTerrainSource.Outcome.ABSENT ? 0 : 1,
                outcome == CompactTerrainSource.Outcome.READABLE ? "a".repeat(32) : null,
                outcome == CompactTerrainSource.Outcome.READABLE ? "b".repeat(64) : null,
                outcome);
    }

    static TerrainGrid grid(float height, long holes) {
        var chunks = new ArrayList<TerrainGrid.Chunk>();
        for (int i = 0; i < 256; i++) {
            float[] h = new float[145];
            java.util.Arrays.fill(h, height);
            chunks.add(
                    new TerrainGrid.Chunk(
                            i,
                            i % 16,
                            i / 16,
                            0,
                            (float) (10000 - (i / 16) * WorldFormats.TILE_SIZE / 16),
                            (float) (20000 - (i % 16) * WorldFormats.TILE_SIZE / 16),
                            3,
                            h,
                            holes,
                            TerrainGrid.ColorState.ABSENT,
                            new byte[0]));
        }
        return new TerrainGrid(chunks);
    }

    static TerrainSourceWindow window(CompactTerrainSource readable) {
        var sources = new ArrayList<CompactTerrainSource>();
        for (int x = 29; x <= 35; x++) {
            for (int y = 29; y <= 35; y++) {
                sources.add(
                        readable != null && x == 32 && y == 32
                                ? readable
                                : CompactTerrainSource.omitted(
                                        descriptor(x, y, CompactTerrainSource.Outcome.ABSENT)));
            }
        }
        return TerrainSourceWindow.of(32, 32, sources);
    }

    @Test
    void compactCopyRetainsOnlyBoundedHeightsOriginsHolesAndExactFloatBits() {
        var grid = grid(7, 1);
        var work = new ErrorWork(100_000, 1);
        var source =
                CompactTerrainSource.copyOf(
                        descriptor(32, 32, CompactTerrainSource.Outcome.READABLE),
                        grid,
                        work,
                        () -> false);
        assertEquals(38400, work.arithmeticUsed());
        assertEquals(153856, source.retainedBytes());
        assertThrows(ReadOnlyBufferException.class, () -> source.heights().put(0, 4));
        assertFalse(source.heights().hasArray());
        float[] scratch = new float[145];
        var restored = source.chunk(37, scratch, work, () -> false);
        scratch[0] = 500;
        for (int row = 0; row < 17; row++) {
            for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                var a = grid.chunks().get(37).position(row, col);
                var b = restored.position(row, col);
                assertEquals(Float.floatToRawIntBits(a.x()), Float.floatToRawIntBits(b.x()));
                assertEquals(Float.floatToRawIntBits(a.y()), Float.floatToRawIntBits(b.y()));
                assertEquals(Float.floatToRawIntBits(a.z()), Float.floatToRawIntBits(b.z()));
            }
        }
        assertEquals(1, restored.holes());
        assertEquals(49, window(source).sources().size());
    }

    @Test
    void sourceWindowChecksCountBeforeAnyTraversalOrCopy() {
        var oversized =
                new AbstractList<CompactTerrainSource>() {
                    public int size() {
                        return 50;
                    }

                    public CompactTerrainSource get(int i) {
                        throw new AssertionError("Oversized list traversed");
                    }
                };
        assertThrows(
                IllegalArgumentException.class, () -> TerrainSourceWindow.of(32, 32, oversized));
        assertThrows(
                IllegalArgumentException.class, () -> TerrainSourceWindow.of(32, 32, List.of()));
        var list = new ArrayList<>(window(null).sources());
        list.set(1, list.getFirst());
        assertThrows(IllegalArgumentException.class, () -> TerrainSourceWindow.of(32, 32, list));
        assertThrows(UnsupportedOperationException.class, () -> window(null).sources().clear());
    }

    @Test
    void copyBudgetAndCancellationPrecedeOwnedArraysAndMissingSourceIsNotZeroHeight() {
        var source = grid(0, 0);
        var descriptor = descriptor(32, 32, CompactTerrainSource.Outcome.READABLE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CompactTerrainSource.copyOf(
                                descriptor, source, new ErrorWork(1, 1), () -> false));
        var canceled = new ErrorWork(100_000, 1);
        assertThrows(
                CancellationException.class,
                () -> CompactTerrainSource.copyOf(descriptor, source, canceled, () -> true));
        assertEquals(0, canceled.arithmeticUsed());
        var missing =
                CompactTerrainSource.omitted(
                        descriptor(32, 32, CompactTerrainSource.Outcome.UNAVAILABLE));
        assertFalse(missing.readable());
        assertEquals(256, missing.retainedBytes());
        assertThrows(IllegalStateException.class, missing::heights);
        assertThrows(
                IllegalArgumentException.class, () -> CompactTerrainSource.omitted(descriptor));
    }
}
