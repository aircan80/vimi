package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldMapProofsTest {
    @Test
    void actualMapOnlyBindingAndRevalidationNeverOpenWdtOrRetainCaptureGraph() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var binding = source.bind(7, Cancellation.NONE);
            assertEquals(7, binding.mapId());
            assertEquals(1, binding.wdtId());
            assertEquals(WorldMapProofs.Authority.COMPLETE_READY, binding.authority());
            assertEquals(64, binding.identity().length());
            assertEquals(64, binding.sourceStamp().length());
            assertNull(binding.witness());
            assertNull(tables.last, "Actual captured rows retire before detached binding returns");
            assertTrue(source.revalidate(binding, Cancellation.NONE));
            assertNull(tables.last);
            assertTrue(tables.eofProbes.get() >= 2);
            assertEquals(0, files.totalOpens(), "Map-only qualifier never opens WDT/root/MAID");
            assertTrue(files.stats.isEmpty(), "READY Map-only path performs no asset stat");
            for (var constructor : WorldMapProofs.Binding.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
            for (var field : WorldMapProofs.Binding.class.getDeclaredFields()) {
                assertFalse(field.getType().isArray());
                assertFalse(Collection.class.isAssignableFrom(field.getType()));
                assertFalse(
                        field.getType()
                                .getSimpleName()
                                .matches("CapturedRows|Receipt|SchemaResult|Proof|WorldMapProofs"));
            }
        } finally {
            tables.close();
        }
    }

    @Test
    void changedStampAndStrictEofRemainIndependentFromWdt() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var binding = source.bind(7, Cancellation.NONE);
            tables.stampMode = 1;
            assertFalse(source.revalidate(binding, Cancellation.NONE));
            assertNull(tables.last);
            tables.stampMode = 0;
            tables.eofMode = 1;
            var contradiction =
                    assertThrows(
                            IllegalStateException.class,
                            () -> source.revalidate(binding, Cancellation.NONE));
            assertFalse(contradiction instanceof WorldTerrainStreaming.Refusal);
            assertNull(tables.last);
            assertEquals(0, files.totalOpens());
        } finally {
            tables.close();
        }
    }

    @Test
    void cancellationAndRejectedRowsCloseAllCapturesWithoutCreatingTerrainProof() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(CancellationException.class, () -> source.bind(7, canceled));
            assertEquals(0, tables.captures.get());
            assertThrows(IllegalStateException.class, () -> source.bind(99, Cancellation.NONE));
            assertNull(tables.last);
            tables.duplicate = true;
            assertThrows(IllegalStateException.class, () -> source.bind(7, Cancellation.NONE));
            assertNull(tables.last);
            assertEquals(0, files.totalOpens());
        } finally {
            tables.close();
        }
    }
}
