package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GpuSceneFileTest {
    @TempDir Path directory;

    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(77, 0, 3)));
    }

    @Test
    void sceneBoundaryPreservesQualifiedMaterialAndRecordedVisibility() throws Exception {
        var mesh = mesh();
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 0, 1, 0, .5f, 1},
                        List.of(new MeshAppearance.Surface(0, 0, true, false, true)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0x80ff1234})));
        Path file = directory.resolve("scene.bin");
        GpuSceneFile.write(file, mesh, appearance, Set.of(77));
        var restored = GpuSceneFile.read(file);
        assertEquals(Set.of(77), restored.visibleParts());
        assertEquals(77, restored.mesh().parts().getFirst().id());
        assertEquals(appearance.surfaces(), restored.appearance().surfaces());
        assertEquals(0x80ff1234, restored.appearance().textures().getFirst().argb()[0]);
        assertThrows(
                IOException.class,
                () -> GpuSceneFile.write(file, mesh, appearance, Set.of(77)),
                "An active immutable snapshot is never overwritten");
    }

    @Test
    void untexturedEmptyRecordedSelectionIsNotInventedAsAVisiblePart() throws Exception {
        Path file = directory.resolve("scene.bin");
        GpuSceneFile.write(file, mesh(), null, Set.of());
        var restored = GpuSceneFile.read(file);
        assertNull(restored.appearance());
        assertEquals(Set.of(), restored.visibleParts());
        assertFalse(
                GpuModelScene.create(restored.mesh(), null)
                        .draws()
                        .getFirst()
                        .visible(Set.of(Integer.MIN_VALUE)));
    }

    @Test
    void invalidLengthTruncationTamperedPayloadAndUnknownFlagsAreRejected() throws Exception {
        Path original = directory.resolve("original.bin");
        GpuSceneFile.write(original, mesh(), null, Set.of(77));
        byte[] valid = Files.readAllBytes(original);
        for (int kind = 0; kind < 4; kind++) {
            byte[] changed =
                    kind == 0 ? java.util.Arrays.copyOf(valid, valid.length - 1) : valid.clone();
            if (kind == 1) {
                changed[changed.length - 1] ^= 1;
            }
            if (kind == 2) {
                ByteBuffer.wrap(changed).putInt(8, 2);
            }
            if (kind == 3) {
                ByteBuffer.wrap(changed).putInt(16, Integer.MAX_VALUE);
            }
            Path file = directory.resolve("bad-" + kind);
            Files.write(file, changed);
            assertThrows(IOException.class, () -> GpuSceneFile.read(file));
        }
        Path oversized = directory.resolve("oversized");
        try (var channel =
                Files.newByteChannel(
                        oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(GpuSceneFile.MAX_BYTES);
            channel.write(ByteBuffer.wrap(new byte[1]));
        }
        assertThrows(IOException.class, () -> GpuSceneFile.read(oversized));
    }
}
