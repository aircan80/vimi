package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearanceState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureModelState;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.tables.TableTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CreatureAppearanceTest {
    static CreatureWorld world(String variant) throws Exception {
        var world =
                new CreatureWorld()
                        .with(
                                CreatureWorld.creatures(
                                        List.of(CreatureWorld.creature(10, "Hogger", 100, 100))),
                                CreatureWorld.displays(List.of(CreatureWorld.display(100, "33"))),
                                CreatureWorld.models(List.of(CreatureWorld.model(33, "7001"))));
        byte[] model = CreatureGoldensTest.bytes("appearance-" + variant + ".m2");
        long skin =
                M2ModelReader.read(AssetSource.bytes(model)).join().skinFileDataIds().getFirst();
        add(world, 7001, model);
        add(world, skin, CreatureGoldensTest.bytes("appearance-" + variant + ".skin"));
        add(world, 800, CreatureGoldensTest.bytes("source.blp"));
        add(world, 1234, CreatureGoldensTest.bytes("source.blp"));
        return world;
    }

    static void add(CreatureWorld world, long id, byte[] bytes) {
        world.files.add(
                CreatureWorld.BUILD,
                "enUS",
                new FileTypes.BuildFile(
                        id,
                        null,
                        FileTypes.FilePathSource.NONE,
                        (long) bytes.length,
                        String.format(java.util.Locale.ROOT, "%032x", id),
                        FileTypes.FileFormat.UNKNOWN,
                        FileTypes.FileFormatSource.NONE,
                        FileTypes.FileStorage.STORED,
                        false),
                bytes,
                List.of());
    }

    static CreatureTypes.CreatureModel read(CreatureWorld world) {
        try (var handle = world.open()) {
            return handle.get(10, TableTypes.Priority.INTERACTIVE, Cancellation.NONE)
                    .join()
                    .detail()
                    .displays()
                    .getFirst()
                    .model();
        }
    }

    @Test
    void readyAppearanceReadsActualModelSkinAndOnlyDrawnTextureHeaders() throws Exception {
        var model = read(world("simple"));
        assertEquals(CreatureModelState.READ, model.state());
        assertEquals(BuildModelTypes.ReadState.READ, model.readState());
        assertEquals(BuildModelTypes.ReadState.READ, model.skinState());
        assertEquals(CreatureAppearanceState.READY, model.appearance().state());
        assertEquals(800L, model.textures().getFirst().fileDataId());
        assertTrue(model.textures().getFirst().drawn());
        assertEquals(BlpImageTypes.State.READY, model.textures().getFirst().image());
        assertNotNull(model.textures().getFirst().reference());
        assertTrue(
                model.references().stream()
                        .anyMatch(r -> r.kind() == BuildModelTypes.ReferenceKind.SKIN));
        assertTrue(
                model.references().stream()
                        .anyMatch(r -> r.kind() == BuildModelTypes.ReferenceKind.TEXTURE));
    }

    @Test
    void twoStageResolvesBothTxidAndDisplayVariation() throws Exception {
        var model = read(world("two-stage"));
        assertEquals(CreatureAppearanceState.READY, model.appearance().state());
        assertEquals(
                List.of(1234L, 800L), model.textures().stream().map(t -> t.fileDataId()).toList());
        assertTrue(
                model.textures().stream()
                        .allMatch(t -> t.drawn() && t.image() == BlpImageTypes.State.READY));
    }

    @Test
    void onlyBlendedSurfaceIsExplicitlyUnsupported() throws Exception {
        var model = read(world("blended"));
        assertEquals(CreatureAppearanceState.BLEND_UNSUPPORTED, model.appearance().state());
        assertEquals(1, model.appearance().blendedBatches());
        assertTrue(model.textures().stream().noneMatch(t -> t.drawn()));
    }

    @Test
    void missingTextureAndProtectedHeadersPreserveTypedFailure() throws Exception {
        var world = world("simple");
        world.files.failure(
                CreatureWorld.BUILD,
                "enUS",
                800,
                new FileTypes.FileRead(
                        FileTypes.FileReadState.NEEDS_NETWORK,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        "FILES_NOT_STORED",
                        null,
                        null));
        var unavailable = read(world);
        assertEquals(CreatureAppearanceState.TEXTURE_UNAVAILABLE, unavailable.appearance().state());
        assertEquals(
                FileTypes.FileReadState.NEEDS_NETWORK,
                unavailable.textures().getFirst().fileState());
        world = world("simple");
        byte[] blp = CreatureGoldensTest.bytes("source.blp");
        world.files.add(
                CreatureWorld.BUILD,
                "enUS",
                new FileTypes.BuildFile(
                        800,
                        null,
                        FileTypes.FilePathSource.NONE,
                        (long) blp.length,
                        "f".repeat(32),
                        FileTypes.FileFormat.UNKNOWN,
                        FileTypes.FileFormatSource.NONE,
                        FileTypes.FileStorage.STORED,
                        true),
                blp,
                List.of(new FileTypes.FileHole(0, 148, "0123456789ABCDEF")));
        var protectedModel = read(world);
        assertEquals(
                CreatureAppearanceState.TEXTURE_PROTECTED, protectedModel.appearance().state());
        assertEquals(List.of("0123456789ABCDEF"), protectedModel.textures().getFirst().holeKeys());
    }

    @Test
    void characterTextureEvidenceRefusesBeforeTextureInspection() throws Exception {
        var world = world("simple");
        var row = CreatureWorld.display(100, "33");
        var values = new java.util.ArrayList<>(row.values());
        values.set(4, "123");
        world.with(
                CreatureWorld.creatures(List.of(CreatureWorld.creature(10, "Hogger", 100))),
                CreatureWorld.displays(
                        List.of(
                                new com.vibeminer.formats.db2.Db2Types.Row(
                                        100, false, null, values, null))),
                CreatureWorld.models(List.of(CreatureWorld.model(33, "7001"))));
        var model = read(world);
        assertEquals(CreatureAppearanceState.CHARACTER_MODEL, model.appearance().state());
        assertTrue(model.textures().stream().allMatch(t -> t.image() == null));
    }

    @Test
    void everyDiscoveredDependencyRemainsVisibleWithoutReadingDependencyBytes() throws Exception {
        var world = world("simple");
        var output = new java.io.ByteArrayOutputStream();
        output.write(CreatureGoldensTest.bytes("appearance-simple.m2"));
        long id = 2000;
        for (String chunk : List.of("SKID", "BFID", "PFID", "RPID", "GPID")) {
            output.write(chunk.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.write(
                    java.nio.ByteBuffer.allocate(8)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(4)
                            .putInt((int) id++)
                            .array());
        }
        output.write("AFID".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(
                java.nio.ByteBuffer.allocate(12)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(8)
                        .putShort((short) 7)
                        .putShort((short) 2)
                        .putInt(2005)
                        .array());
        add(world, 7001, output.toByteArray());
        var model = read(world);
        assertEquals(CreatureModelState.READ, model.state());
        for (var kind :
                List.of(
                        BuildModelTypes.ReferenceKind.SKELETON,
                        BuildModelTypes.ReferenceKind.BONE_FILE,
                        BuildModelTypes.ReferenceKind.PHYSICS_FILE,
                        BuildModelTypes.ReferenceKind.RECURSIVE_PARTICLE_MODEL,
                        BuildModelTypes.ReferenceKind.GEOMETRY_PARTICLE_MODEL,
                        BuildModelTypes.ReferenceKind.ANIMATION_FILE)) {
            var reference =
                    model.references().stream()
                            .filter(r -> r.kind() == kind)
                            .findFirst()
                            .orElseThrow();
            assertEquals(BuildModelTypes.ReferenceState.UNKNOWN_FILE, reference.state());
            assertTrue(reference.fileDataId() >= 2000);
        }
        assertEquals(CreatureAppearanceState.GEOMETRY_UNSUPPORTED, model.appearance().state());
        assertThrows(UnsupportedOperationException.class, () -> model.references().clear());
    }
}
