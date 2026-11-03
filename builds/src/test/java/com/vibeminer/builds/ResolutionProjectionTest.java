package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.builds.formats.ArchiveIndex;
import com.vibeminer.builds.formats.ArchiveLocations;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResolutionProjectionTest {
    @Test
    void projectionPreservesWinnerAndUnsignedOffsetsAndLooseSizes() throws Exception {
        var archive = MetadataFixtures.key(1);
        var key = MetadataFixtures.key(2);
        var loose = MetadataFixtures.key(3);
        var locations =
                ArchiveLocations.build(
                        List.of(
                                new ArchiveLocations.Indexed(
                                        archive,
                                        List.of(new ArchiveIndex.Entry(key, 123, 0xfffffff0L)))),
                        List.of(new ArchiveIndex.Entry(loose, 0xffffffffL, 0)),
                        new ArchiveLocations.Limits(),
                        () -> {});
        var bytes = new ByteArrayOutputStream();
        locations.writeProjection(
                new DataOutputStream(bytes), List.of(archive), new byte[32], () -> {});
        assertEquals(locations.projectionLength(List.of(archive)), bytes.size());
        var restored =
                ArchiveLocations.readProjection(
                        bytes.toByteArray(), List.of(archive), new byte[32], () -> {});
        assertEquals(locations.locate(key), restored.locate(key));
        assertEquals(locations.looseSize(loose), restored.looseSize(loose));
        byte[] identity = new byte[32];
        identity[0] = 1;
        assertThrows(
                IOException.class,
                () ->
                        ArchiveLocations.readProjection(
                                bytes.toByteArray(), List.of(archive), identity, () -> {}));
        assertThrows(
                IOException.class,
                () ->
                        ArchiveLocations.readProjection(
                                new byte[52], List.of(archive), new byte[32], () -> {}));
        byte[] truncated = java.util.Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1);
        assertThrows(
                IOException.class,
                () ->
                        ArchiveLocations.readProjection(
                                truncated, List.of(archive), new byte[32], () -> {}));
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        ArchiveLocations.readProjection(
                                bytes.toByteArray(),
                                List.of(archive),
                                new byte[32],
                                () -> {
                                    throw new java.util.concurrent.CancellationException();
                                }));
    }

    @Test
    void memoryReuseIsBoundedAndDoesNotRetainOversizeBuilds() {
        var source = new SyntheticBuild();
        var options = new BuildOpener.Options();
        var empty =
                ArchiveLocations.build(
                        List.of(), List.of(), new ArchiveLocations.Limits(), () -> {});
        try (var tiny = new BuildResolutionCache(0)) {
            tiny.remember(source.ref, options, empty);
            assertNull(tiny.memory(source.ref, options));
        }
        var cache = new BuildResolutionCache();
        cache.remember(source.ref, options, empty);
        assertSame(empty, cache.memory(source.ref, options));
        cache.close();
        assertNull(cache.memory(source.ref, options));
    }
}
