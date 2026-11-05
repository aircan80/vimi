package com.vibeminer.formats.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.internal.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class BundledDefinitionsTest {
    @Test
    void completePinnedManifestAndReviewedCreatureDefinitionVerify() {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        assertEquals(1346, provider.tables().size());
        assertTrue(
                provider.tables().stream().allMatch(BundledDefinitions.DefinitionTable::bundled));
        var creature = provider.require("c9d6b6b3");
        assertEquals(
                "3644a603aae0eed595de31933c49e7bd8abea32bc1a1c32f9d754afbfb8f5682",
                creature.sha256());
        assertTrue(creature.sourceUrl().contains(BundledDefinitions.REVISION));
        assertNotNull(creature.document());
    }

    @Test
    void everyPinnedDefinitionRemainsIndividuallyBounded() {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        int described = 0, unsupported = 0;
        for (var table : provider.tables()) {
            var result = provider.tryGet(table.tableId());
            if (result.definition() != null) {
                described++;
                assertTrue(result.definition().document().selectionCost() <= 4L << 20);
            } else {
                unsupported++;
                assertNotNull(result.failure());
                assertEquals("DEFINITION_UNSUPPORTED", result.failure().code());
            }
        }
        assertTrue(described > 1200);
        assertEquals(1346, described + unsupported);
    }

    /**
     * The pre-blob lookup, re-inflating the resource and skipping to each slice. One stream is
     * walked in offset order (skipping forward is exactly what the old per-lookup stream did), so
     * the whole comparison costs one inflate instead of one per definition.
     */
    private static BundledDefinitions.Lookup streamed(
            InputStream input, long[] position, BundledDefinitions.File file) throws IOException {
        input.skipNBytes(file.offset() - position[0]);
        byte[] bytes = input.readNBytes(file.length());
        position[0] = file.offset() + bytes.length;
        if (bytes.length != file.length() || !Json.sha256(bytes).equals(file.sha())) {
            return new BundledDefinitions.Lookup(
                    null,
                    new DefinitionException(
                            "DEFINITION_CORRUPT", "Bundled file failed pinned hash"));
        }
        try {
            return new BundledDefinitions.Lookup(
                    new BundledDefinitions.Definition(
                            file.sha(),
                            BundledDefinitions.ORIGIN + "definitions/" + file.name() + ".dbd",
                            DbdDefinitionReader.parse(bytes)),
                    null);
        } catch (Db2FormatException e) {
            return new BundledDefinitions.Lookup(
                    null,
                    new DefinitionException(
                            "DEFINITION_UNSUPPORTED", e.code() + ": " + e.getMessage()));
        }
    }

    @Test
    void slicedLookupsEqualStreamedLookupsForEveryBundledDefinition() throws IOException {
        var provider = BundledDefinitions.open(Cancellation.NONE).join();
        var ordered =
                provider.tables().stream()
                        .map(BundledDefinitions.DefinitionTable::tableId)
                        .sorted(
                                java.util.Comparator.comparingLong(
                                        id -> provider.file(id).offset()))
                        .toList();
        int compared = 0;
        long[] position = {0};
        try (var input =
                new GZIPInputStream(
                        BundledDefinitions.class.getResourceAsStream("definitions.gz"))) {
            for (var tableId : ordered) {
                var file = provider.file(tableId);
                assertNotNull(file, tableId);
                var expected = streamed(input, position, file);
                var actual = provider.tryGet(tableId);
                assertEquals(expected.definition(), actual.definition(), tableId);
                if (expected.failure() == null) {
                    assertNull(actual.failure(), tableId);
                } else {
                    assertEquals(expected.failure().code(), actual.failure().code());
                    assertEquals(expected.failure().getMessage(), actual.failure().getMessage());
                }
                compared++;
            }
        }
        assertEquals(1346, compared);
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    private static DefinitionException inflateFailure(byte[] compressed, long length, String sha) {
        return assertThrows(
                DefinitionException.class,
                () ->
                        BundledDefinitions.inflateVerified(
                                new ByteArrayInputStream(compressed), length, sha));
    }

    @Test
    void decodedStreamMustMatchItsPinnedLengthAndHash() throws IOException {
        byte[] decoded = "synthetic definitions stream".getBytes(StandardCharsets.UTF_8);
        String sha = Json.sha256(decoded);
        byte[] compressed = gzip(decoded);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                decoded,
                BundledDefinitions.inflateVerified(
                        new ByteArrayInputStream(compressed), decoded.length, sha));
        assertEquals(
                "Repacked stream failed hash",
                inflateFailure(compressed, decoded.length, "0".repeat(64)).getMessage());
        assertEquals(
                "Truncated decoded stream",
                inflateFailure(compressed, decoded.length + 1, sha).getMessage());
        assertEquals(
                "Oversized decoded stream",
                inflateFailure(compressed, decoded.length - 1, sha).getMessage());
        assertEquals(
                "Decoded length outside bounds",
                inflateFailure(compressed, (64L << 20) + 1, sha).getMessage());
        // Every single-byte corruption of the compressed resource is refused, either by the
        // gzip codec (IOException, which load() reports as an invalid provider) or by the
        // decoded length and hash checks. Header bytes 4-9 (mtime, extra flags, OS) carry no
        // content and are not integrity protected by gzip, so they are skipped.
        for (int at = 0; at < compressed.length; at = at == 3 ? 10 : at + 1) {
            byte[] damaged = compressed.clone();
            damaged[at] ^= 0x55;
            var failure =
                    assertThrows(
                            Exception.class,
                            () ->
                                    BundledDefinitions.inflateVerified(
                                            new ByteArrayInputStream(damaged), decoded.length, sha),
                            "corrupt byte " + at);
            assertTrue(
                    failure instanceof IOException || failure instanceof DefinitionException,
                    failure.toString());
        }
    }

    private static final byte[] DBD =
            ("COLUMNS\nint ID\n\nLAYOUT 0123ABCD\nBUILD 1.0.0.1\n$id$ID<32>\n")
                    .getBytes(StandardCharsets.UTF_8);

    private static BundledDefinitions synthetic(byte[] decoded, Map<String, String> shas) {
        var tables = new ArrayList<BundledDefinitions.DefinitionTable>();
        var files = new java.util.HashMap<String, BundledDefinitions.File>();
        for (int i = 0; i < 8; i++) {
            String id = "0000000" + i;
            tables.add(new BundledDefinitions.DefinitionTable(id, "T" + i, null, true));
            files.put(
                    id,
                    new BundledDefinitions.File(
                            "T" + i,
                            shas.getOrDefault(id, Json.sha256(DBD)),
                            DBD.length,
                            (long) i * DBD.length));
        }
        return new BundledDefinitions(tables, files, decoded, Map.of(), 4);
    }

    private static byte[] repeated(int count) {
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            out.writeBytes(DBD);
        }
        return out.toByteArray();
    }

    @Test
    void sliceHashMismatchIsReportedAsCorrupt() {
        var provider = synthetic(repeated(8), Map.of("00000003", "f".repeat(64)));
        var good = provider.tryGet("00000002");
        assertNotNull(good.definition(), String.valueOf(good.failure()));
        var bad = provider.tryGet("00000003");
        assertNull(bad.definition());
        assertEquals("DEFINITION_CORRUPT", bad.failure().code());
        assertSame(bad, provider.tryGet("00000003"));
        assertEquals(
                "DEFINITION_CORRUPT",
                assertThrows(DefinitionException.class, () -> provider.require("00000003")).code());
        byte[] flipped = repeated(8);
        flipped[5 * DBD.length + 3] ^= 1;
        assertEquals(
                "DEFINITION_CORRUPT",
                synthetic(flipped, Map.of()).tryGet("00000005").failure().code());
        assertNull(provider.tryGet("ffffffff").definition());
        assertNull(provider.tryGet("ffffffff").failure());
    }

    @Test
    void slicesOutsideTheDecodedStreamAreRejected() {
        assertEquals(
                "DEFINITION_PROVIDER_INVALID",
                assertThrows(DefinitionException.class, () -> synthetic(repeated(7), Map.of()))
                        .code());
        assertThrows(DefinitionException.class, () -> synthetic(null, Map.of()));
    }

    @Test
    void concurrentLookupsAgreeAndStayBounded() throws Exception {
        var provider = synthetic(repeated(8), Map.of("00000006", "e".repeat(64)));
        var reference = new ArrayList<BundledDefinitions.Lookup>();
        for (int i = 0; i < 8; i++) {
            reference.add(
                    synthetic(repeated(8), Map.of("00000006", "e".repeat(64)))
                            .tryGet("0000000" + i));
        }
        var start = new CountDownLatch(1);
        var workers = new ArrayList<CompletableFuture<Void>>();
        for (int t = 0; t < 16; t++) {
            final int seed = t;
            workers.add(
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    start.await();
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                                for (int n = 0; n < 2000; n++) {
                                    int i = (seed * 5 + n * 3) % 8;
                                    var got = provider.tryGet("0000000" + i);
                                    var want = reference.get(i);
                                    assertEquals(want.definition(), got.definition());
                                    assertEquals(
                                            want.failure() == null ? null : want.failure().code(),
                                            got.failure() == null ? null : got.failure().code());
                                }
                            },
                            command -> Thread.ofPlatform().start(command)));
        }
        start.countDown();
        CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).join();
        assertTrue(provider.cachedEntries() <= 4);
    }
}
