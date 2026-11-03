package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeMaterialFailureTest {
    private static MemoryTables.FakeTable table(
            long fid, String name, String field, String target, long value) {
        var locale = WowLocale.parse("enUS");
        var column =
                new TableSchema.SchemaColumn(
                        field,
                        TableSchema.ColumnKind.INT,
                        32,
                        true,
                        1,
                        new TableSchema.ForeignKey(target, "ID"),
                        TableSchema.KeyStorage.NONE,
                        TableSchema.KeyStorage.NONE,
                        true,
                        locale,
                        null,
                        null);
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        fid,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(1, 1, 0, 0, "fixture", "0FC0F0D5"),
                        "c".repeat(32)),
                List.of(new Db2Types.Column(field, "integer", false, null, null)),
                List.of(new Db2Types.Row(1, false, null, List.of(Long.toString(value)), List.of())),
                new TableSchema(
                        List.of(column),
                        List.of("0FC0F0D5"),
                        TableSchema.SchemaMatch.BUILD,
                        locale,
                        null),
                Map.of(),
                true);
    }

    private static boolean hasPreviewReceipt(MemoryGeneratedStore store) {
        return store.keys().stream()
                .map(k -> store.read(k, com.vibeminer.generated.Cancellation.NONE).join().bytes())
                .anyMatch(b -> b.length >= 4 && java.nio.ByteBuffer.wrap(b).getInt() == 0x56445031);
    }

    @Test
    void failedOpacityFactsLeaveSentinelSectionVisibleWithoutSavingIncompleteDerivative()
            throws Exception {
        var f = new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
        // Two native sections share ordinary geometry; only section 1 bypasses opacity evidence.
        byte[] firstSection = Arrays.copyOfRange(f.skin.array(), 92, 140);
        byte[] batch = Arrays.copyOfRange(f.skin.array(), 140, 164);
        f.skin.position(140);
        f.skin.put(firstSection);
        f.skin.put(batch);
        f.skin.put(batch);
        f.skin.putInt(28, 2).putInt(36, 2).putInt(40, 188);
        f.skin.putShort(212 + 4, (short) 1).putShort(212 + 20, (short) 1);
        int weights = f.body.position();
        f.body.putShort((short) 0).putShort((short) -1);
        f.declaration(144, 2, weights);
        byte[] valid = f.modelBytes();
        // The native geometry reader accepts descriptors; the separate opacity proof must read this
        // slice.
        int track = f.body.getInt(92);
        f.body.putInt(track + 16, 0x7ffffff0);
        byte[] bad = f.modelBytes();
        var files =
                new AssetFilesFixture()
                        .add(100, "1".repeat(32), bad)
                        .add(9000, "2".repeat(32), Arrays.copyOf(f.skin.array(), 236));
        byte[] blp;
        try (var in = getClass().getResourceAsStream("/golden/source.blp")) {
            blp = in.readAllBytes();
        }
        files.add(9001, "3".repeat(32), blp).add(9002, "4".repeat(32), blp);
        var store = new MemoryGeneratedStore();
        try (var tables =
                new MemoryTables()
                        .add(
                                files.build(),
                                "enUS",
                                List.of(
                                        table(
                                                10,
                                                "CreatureDisplayInfo",
                                                "ModelID",
                                                "CreatureModelData",
                                                1),
                                        table(
                                                11,
                                                "CreatureModelData",
                                                "FileDataID",
                                                "FileData",
                                                100)))
                        .forBuild(files.build(), "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new DisplayModelService(tables, files, store);
            try {
                var preview = service.preview(1, Cancellation.NONE).join();
                assertNotNull(preview.appearance(), preview.message());
                assertEquals(
                        List.of(1),
                        preview.appearance().surfaces().stream()
                                .map(s -> s.sectionIndex())
                                .toList());
                assertTrue(preview.issues().stream().anyMatch(s -> s.contains("opacity facts")));
                assertFalse(hasPreviewReceipt(store));
                files.add(100, "5".repeat(32), valid);
                var retry = service.preview(1, Cancellation.NONE).join();
                assertFalse(retry.restored());
                assertEquals(2, retry.appearance().surfaces().size());
                assertTrue(hasPreviewReceipt(store));
            } finally {
                service.closeAsync().join();
            }
        } finally {
            files.close();
        }
    }

    private static final class ReopenFailure extends AssetFilesFixture {
        final long target;
        final boolean cancellation;
        int seen;

        ReopenFailure(long target, boolean cancellation) {
            this.target = target;
            this.cancellation = cancellation;
        }

        @Override
        public java.util.concurrent.CompletableFuture<com.vibeminer.files.FileTypes.FileOpen>
                openRead(com.vibeminer.files.FileTypes.FileRef file, Cancellation token) {
            if (java.util.Objects.equals(file.fileDataId(), target) && ++seen == 2) {
                if (cancellation) {
                    token.cancel();
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new java.util.concurrent.CancellationException(
                                    "authored cancellation"));
                }
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("authored optional reopen failure"));
            }
            return super.openRead(file, token);
        }
    }

    @Test
    void optionalModelReopenFailurePreservesSentinelMaterialAndAllowsRetry() throws Exception {
        exerciseReopen(100, false);
    }

    @Test
    void optionalPaletteReopenFailurePreservesOrdinaryMaterialAndAllowsRetry() throws Exception {
        exerciseReopen(9000, false);
    }

    @Test
    void optionalPaletteReopenCancellationPropagates() throws Exception {
        exerciseReopen(9000, true);
    }

    private void exerciseReopen(long target, boolean cancellation) throws Exception {
        var f = new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
        // Append a separate quad so the card's vertices are exclusively owned by its section.
        int vertices = f.body.position();
        byte[] quad = Arrays.copyOfRange(f.body.array(), f.vertices, f.vertices + 192);
        f.body.put(quad).put(quad);
        f.declaration(60, 8, vertices);
        int materials = f.body.position();
        f.body.putShort((short) 0).putShort((short) 0).putShort((short) 17).putShort((short) 4);
        f.declaration(112, 2, materials);
        int weights = f.body.position();
        f.body.putShort((short) -1);
        f.declaration(144, 1, weights);
        var skin = java.nio.ByteBuffer.allocate(272).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        skin.putInt(0x4e494b53)
                .putInt(8)
                .putInt(56)
                .putInt(12)
                .putInt(72)
                .putInt(8)
                .putInt(96)
                .putInt(2)
                .putInt(128)
                .putInt(2)
                .putInt(224)
                .putInt(3)
                .putLong(0);
        for (int i = 0; i < 8; i++) {
            skin.putShort((short) i);
        }
        for (int i : new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}) {
            skin.putShort((short) i);
        }
        for (int i = 0; i < 8; i++) {
            skin.putInt(1);
        }
        byte[] section = Arrays.copyOfRange(f.skin.array(), 92, 140);
        skin.put(section).put(section);
        skin.putShort(176 + 4, (short) 4).putShort(176 + 8, (short) 6);
        byte[] batch = Arrays.copyOfRange(f.skin.array(), 140, 164);
        skin.put(batch).put(batch);
        int card = 248;
        skin.put(card, (byte) 16)
                .putShort(card + 2, (short) 16)
                .putShort(card + 4, (short) 1)
                .putShort(card + 10, (short) 1)
                .putShort(card + 14, (short) 1);
        var files = new ReopenFailure(target, cancellation);
        files.add(100, "1".repeat(32), f.modelBytes()).add(9000, "2".repeat(32), skin.array());
        byte[] blp;
        try (var in = getClass().getResourceAsStream("/golden/source.blp")) {
            blp = in.readAllBytes();
        }
        files.add(9001, "3".repeat(32), blp).add(9002, "4".repeat(32), blp);
        var store = new MemoryGeneratedStore();
        try (var tables =
                new MemoryTables()
                        .add(
                                files.build(),
                                "enUS",
                                List.of(
                                        table(
                                                10,
                                                "CreatureDisplayInfo",
                                                "ModelID",
                                                "CreatureModelData",
                                                1),
                                        table(
                                                11,
                                                "CreatureModelData",
                                                "FileDataID",
                                                "FileData",
                                                100)))
                        .forBuild(files.build(), "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new DisplayModelService(tables, files, store);
            try {
                var pending = service.preview(1, new Cancellation());
                if (cancellation) {
                    assertThrows(java.util.concurrent.CancellationException.class, pending::join);
                    assertFalse(hasPreviewReceipt(store));
                    return;
                }
                var partial = pending.join();
                assertNotNull(partial.appearance(), partial.message());
                assertEquals(
                        List.of(0),
                        partial.appearance().surfaces().stream()
                                .map(surface -> surface.sectionIndex())
                                .toList());
                assertTrue(partial.appearance().billboards().isEmpty());
                assertFalse(hasPreviewReceipt(store));
                var retry = service.preview(1, Cancellation.NONE).join();
                assertFalse(retry.restored());
                assertEquals(2, retry.appearance().surfaces().size());
                assertEquals(1, retry.appearance().billboards().size());
                assertTrue(hasPreviewReceipt(store));
            } finally {
                service.closeAsync().join();
            }
        } finally {
            files.close();
        }
    }
}
