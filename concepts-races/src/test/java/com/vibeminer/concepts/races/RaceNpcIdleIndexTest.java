package com.vibeminer.concepts.races;

import static com.vibeminer.concepts.races.RaceAppearancePlanTest.BUILD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetFilesFixture;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The persistent NPC idle entries over the Orc NPC fixture (NPC 50 over model 1234): a resolution
 * and a Stand written by one races handle are read by the next one while their tables and sources
 * are unchanged, and anything changed is a miss.
 */
final class RaceNpcIdleIndexTest {
    private static final String MODEL_KEY = "1".repeat(32);

    private static BuildTables raw() {
        return new MemoryTables()
                .add(BUILD, "enUS", RaceNpcAppearanceTest.npcFixture())
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static AssetFilesFixture files(String modelKey) throws Exception {
        return new AssetFilesFixture(BUILD, "enUS")
                .add(1234, modelKey, RaceNpcAppearanceTest.withWeight(false))
                .add(900, "2".repeat(32), RaceNpcAppearanceTest.golden("authored-274.skin"))
                .add(4242, "3".repeat(32), RaceNpcAppearanceTest.bake());
    }

    /** Runs {@code body} with a fresh races handle that has asked for NPC 50's appearance. */
    private static void handle(
            BuildTables tables,
            BuildFiles files,
            GeneratedStore generated,
            Consumer<RacesService> body) {
        var service = new RacesService(tables, files, generated);
        try {
            // Reads the NPC's tables, as the World does before it asks for the idle.
            service.npcAppearance(50, Cancellation.NONE).join();
            body.accept(service);
        } finally {
            service.closeAsync().join();
        }
    }

    /** {@code raw} with the content key of every schema answer for {@code table} replaced. */
    private static BuildTables changed(BuildTables raw, String table) {
        return (BuildTables)
                java.lang.reflect.Proxy.newProxyInstance(
                        RaceNpcIdleIndexTest.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            try {
                                Object answer = method.invoke(raw, args);
                                if (!method.getName().equals("schema")) {
                                    return answer;
                                }
                                return ((CompletableFuture<?>) answer)
                                        .thenApply(
                                                value -> {
                                                    var schema = (TableTypes.SchemaResult) value;
                                                    var entry = schema.table();
                                                    if (!table.equals(entry.name())) {
                                                        return schema;
                                                    }
                                                    return new TableTypes.SchemaResult(
                                                            new TableTypes.Entry(
                                                                    entry.fileDataId(),
                                                                    entry.name(),
                                                                    entry.state(),
                                                                    entry.checked(),
                                                                    entry.reason(),
                                                                    entry.counts(),
                                                                    "e".repeat(32)),
                                                            schema.columns(),
                                                            schema.schema());
                                                });
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    /** A Stand on a two-vertex rest surface whose only bone lifts it. */
    private static RaceNpcIdleIndex.Stand stand(Map<Long, String> sources) {
        var rest =
                new PreviewMesh(
                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        var lift =
                new PreviewAnimation.Track(
                        PreviewAnimation.Interpolation.LINEAR,
                        -1,
                        3,
                        new int[] {0},
                        new float[] {0, 0, 1});
        var bone = new PreviewAnimation.Bone(-1, 0, 0, 0, lift, null, null, false);
        int[] bones = new int[12], weights = new int[12];
        for (int v = 0; v < 3; v++) {
            weights[v * 4] = 255;
        }
        var animation =
                new PreviewAnimation(
                        "Stand",
                        1000,
                        new long[0],
                        List.of(bone),
                        rest,
                        new float[9],
                        bones,
                        weights);
        return new RaceNpcIdleIndex.Stand(
                RaceTypes.State.READY,
                List.of(),
                List.of("Stand is bound to 3 rest vertices."),
                "Stand idle",
                animation,
                Map.of(11, 4, 1, 2),
                sources);
    }

    @Test
    void aResolutionIsReadByTheNextHandleUntilATableItNamesChanges() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var resolved = new RaceNpcIdleIndex.Resolution(2, 49);
        handle(
                raw,
                files,
                generated,
                service -> {
                    assertNull(service.idleIndex.resolution(50, Cancellation.NONE));
                    assertTrue(service.idleIndex.writeResolution(50, resolved, Cancellation.NONE));
                });
        handle(
                raw,
                files,
                generated,
                service -> {
                    assertEquals(resolved, service.idleIndex.resolution(50, Cancellation.NONE));
                    assertNull(service.idleIndex.resolution(51, Cancellation.NONE));
                });
        // A table the NPC was resolved from changed: a miss.
        handle(
                changed(raw, "NPCModelItemSlotDisplayInfo"),
                files,
                generated,
                service -> assertNull(service.idleIndex.resolution(50, Cancellation.NONE)));
    }

    @Test
    void aStandIsReadByTheNextHandleUntilASourceChanges() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var written = stand(Map.of(1234L, MODEL_KEY, 900L, "2".repeat(32)));
        handle(
                raw,
                files,
                generated,
                service -> {
                    assertNull(service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE));
                    assertTrue(
                            service.idleIndex.writeStand(
                                    49, 1234, MODEL_KEY, written, Cancellation.NONE));
                });
        handle(
                raw,
                files,
                generated,
                service -> {
                    var read = service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE);
                    assertNotNull(read);
                    assertEquals(written.state(), read.state());
                    assertEquals(written.facts(), read.facts());
                    assertEquals(written.message(), read.message());
                    assertEquals(written.attachments(), read.attachments());
                    assertEquals(written.sources(), read.sources());
                    assertTrue(
                            read.animation()
                                    .matches(
                                            new PreviewMesh(
                                                    new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                                    new int[] {0, 1, 2},
                                                    List.of(new PreviewMesh.Part(0, 0, 3)))));
                    // Another ChrModel or model content key is another entry.
                    assertNull(service.idleIndex.stand(50, 1234, MODEL_KEY, Cancellation.NONE));
                    assertNull(
                            service.idleIndex.stand(49, 1234, "9".repeat(32), Cancellation.NONE));
                });
        // The SKIN it was built from has another content key now: a miss.
        var skinChanged =
                new AssetFilesFixture(BUILD, "enUS")
                        .add(1234, MODEL_KEY, RaceNpcAppearanceTest.withWeight(false))
                        .add(900, "8".repeat(32), RaceNpcAppearanceTest.golden("authored-274.skin"))
                        .add(4242, "3".repeat(32), RaceNpcAppearanceTest.bake());
        handle(
                raw,
                skinChanged,
                generated,
                service ->
                        assertNull(
                                service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE)));
    }

    @Test
    void aRefusedStandRoundTripsWithoutAnAnimation() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var refused =
                new RaceNpcIdleIndex.Stand(
                        RaceTypes.State.UNSUPPORTED,
                        List.of("The model declares no LOD0 SKIN."),
                        List.of(),
                        "The model declares no LOD0 SKIN.",
                        null,
                        Map.of(),
                        Map.of(1234L, MODEL_KEY));
        handle(
                raw,
                files,
                generated,
                service ->
                        assertTrue(
                                service.idleIndex.writeStand(
                                        49, 1234, MODEL_KEY, refused, Cancellation.NONE)));
        handle(
                raw,
                files,
                generated,
                service -> {
                    var read = service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE);
                    assertNotNull(read);
                    assertEquals(refused.reasons(), read.reasons());
                    assertNull(read.animation());
                });
    }

    @Test
    void anEntryOfAnotherCodeDigestOrDamagedIsAMiss() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var resolved = new RaceNpcIdleIndex.Resolution(2, 49);
        handle(
                raw,
                files,
                generated,
                service -> {
                    service.idleIndex.writeResolution(50, resolved, Cancellation.NONE);
                    service.idleIndex.writeStand(
                            49,
                            1234,
                            MODEL_KEY,
                            stand(Map.of(1234L, MODEL_KEY)),
                            Cancellation.NONE);
                    var other = new RaceNpcIdleIndex(service.npcIndex, "another-code");
                    assertNull(other.resolution(50, Cancellation.NONE));
                    assertNull(other.stand(49, 1234, MODEL_KEY, Cancellation.NONE));
                    // A Stand that does not name the model it is keyed by is never written.
                    assertTrue(
                            !service.idleIndex.writeStand(
                                    49,
                                    1234,
                                    MODEL_KEY,
                                    stand(Map.of(900L, "2".repeat(32))),
                                    Cancellation.NONE));
                    // Truncated bytes under the key are a miss.
                    var key = service.idleIndex.resolutionKey(50);
                    byte[] bytes = generated.read(key).join().bytes();
                    generated.delete(List.of(key)).join();
                    generated
                            .put(
                                    List.of(
                                            new GeneratedTypes.Put(
                                                    key,
                                                    java.util.Arrays.copyOf(
                                                            bytes, bytes.length - 3))))
                            .join();
                    assertNull(service.idleIndex.resolution(50, Cancellation.NONE));
                });
    }

    /** {@code files} with every open of {@code fileDataId} counted. */
    private static BuildFiles counted(BuildFiles files, long fileDataId, AtomicInteger opens) {
        return (BuildFiles)
                java.lang.reflect.Proxy.newProxyInstance(
                        RaceNpcIdleIndexTest.class.getClassLoader(),
                        new Class<?>[] {BuildFiles.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("openRead")
                                    && args[0] instanceof FileTypes.FileRef ref
                                    && ref.fileDataId() != null
                                    && ref.fileDataId() == fileDataId) {
                                opens.incrementAndGet();
                            }
                            try {
                                return method.invoke(files, args);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    /** One handle's appearance and idle for NPC 50. */
    private static RaceMediaTypes.Idle idle(
            BuildTables tables, BuildFiles files, GeneratedStore generated) {
        var service = new RacesService(tables, files, generated);
        try {
            var appearance = service.npcAppearance(50, Cancellation.NONE).join();
            assertNotNull(appearance.result(), String.valueOf(appearance));
            return service.npcIdle(50, appearance.result().appearance(), Cancellation.NONE).join();
        } finally {
            service.closeAsync().join();
        }
    }

    @Test
    void aSecondSessionReusesTheStoredResolutionAndStand() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var firstOpens = new AtomicInteger();
        var first = idle(raw, counted(files, 1234, firstOpens), generated);
        // The fixture's model has no bones: a complete refusal of the model, so it is kept.
        assertEquals(RaceTypes.State.UNSUPPORTED, first.state(), first.message());
        assertTrue(firstOpens.get() > 0, "the first session reads the race model");
        var probe = new RaceNpcIdleIndex(new RaceNpcIndex(raw, files, generated));
        assertTrue(
                generated.read(probe.resolutionKey(50)).join().state()
                        == GeneratedTypes.ReadState.PRESENT,
                "the resolution was written");
        assertTrue(
                generated
                                .read(probe.standKey(first.variant().chrModelId(), 1234, MODEL_KEY))
                                .join()
                                .state()
                        == GeneratedTypes.ReadState.PRESENT,
                "the Stand was written");

        var secondOpens = new AtomicInteger();
        var second = idle(raw, counted(files, 1234, secondOpens), generated);
        assertEquals(
                0, secondOpens.get(), "the next session reads neither the model nor its Stand");
        assertEquals(first.state(), second.state());
        assertEquals(first.message(), second.message());
        assertEquals(first.reasons(), second.reasons());
        assertEquals(first.facts(), second.facts());
        assertEquals(first.variant(), second.variant());
    }

    @Test
    void aStandIsAMissWhenATableItNamesChangesOrItsBodyIsDamaged() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        var written = stand(Map.of(1234L, MODEL_KEY));
        handle(
                raw,
                files,
                generated,
                service ->
                        assertTrue(
                                service.idleIndex.writeStand(
                                        49, 1234, MODEL_KEY, written, Cancellation.NONE)));
        // A table the handle had read when the Stand was written changed: a miss.
        handle(
                changed(raw, "NPCModelItemSlotDisplayInfo"),
                files,
                generated,
                service ->
                        assertNull(
                                service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE)));
        // A truncated, altered or extended body is a miss, never a partial Stand.
        handle(
                raw,
                files,
                generated,
                service -> {
                    var key = service.idleIndex.standKey(49, 1234, MODEL_KEY);
                    byte[] bytes = generated.read(key).join().bytes();
                    for (var damaged :
                            List.of(
                                    java.util.Arrays.copyOf(bytes, bytes.length - 1),
                                    java.util.Arrays.copyOf(bytes, bytes.length - 200),
                                    java.util.Arrays.copyOf(bytes, bytes.length + 4),
                                    flipped(bytes, bytes.length - 40))) {
                        generated.delete(List.of(key)).join();
                        generated.put(List.of(new GeneratedTypes.Put(key, damaged))).join();
                        assertNull(service.idleIndex.stand(49, 1234, MODEL_KEY, Cancellation.NONE));
                    }
                });
    }

    private static byte[] flipped(byte[] bytes, int at) {
        var out = bytes.clone();
        out[at] = (byte) ~out[at];
        return out;
    }

    @Test
    void theScopePinsTheReadersAStandIsBuiltFrom() throws Exception {
        assertEquals(
                List.of(
                        com.vibeminer.formats.models.M2ModelReader.REVISION,
                        com.vibeminer.formats.models.M2RestSkinReader.REVISION,
                        com.vibeminer.formats.models.M2SkeletonFile.REVISION,
                        com.vibeminer.formats.models.M2IdleAnimation.REVISION),
                RaceNpcIdleIndex.READER_REVISIONS);
        var index =
                new RaceNpcIdleIndex(
                        new RaceNpcIndex(raw(), files(MODEL_KEY), new MemoryGeneratedStore()));
        String scope = index.scope("stand 49 1234 " + MODEL_KEY);
        for (String revision : RaceNpcIdleIndex.READER_REVISIONS) {
            assertTrue(scope.contains(revision), revision);
        }
        assertTrue(
                scope.contains(RaceNpcIdleIndex.REVISION) && scope.contains(RaceNpcIdleIndex.CODE));
        for (String reader :
                List.of(
                        "M2RestSkinReader",
                        "M2SkeletonFile",
                        "M2SkinReader",
                        "M2Input",
                        "M2Types",
                        "M2IdleAnimation",
                        "BuildModels")) {
            assertTrue(
                    RaceNpcIdleIndex.CODE_PREFIXES.contains(
                            "com/vibeminer/formats/models/" + reader),
                    reader);
        }
    }

    /**
     * A stored READY Stand comes back through {@code RacesService.npcIdle} in a later session, and
     * the fit attaches the NPC's helmet vertices with the stored attachment points: no race model
     * is read. The Stand is authored over NPC 50's own surface (the fixtures carry no boned race
     * model or drawn helmet), with its root still and bone 1 lifted by 1; the helmet part follows
     * attachment point 11, stored on bone 1.
     */
    @Test
    void aStoredReadyStandIsFittedWithItsStoredAttachmentPoints() throws Exception {
        var raw = raw();
        var files = files(MODEL_KEY);
        var generated = new MemoryGeneratedStore();
        // A first session resolves NPC 50 and keeps its resolution.
        var service = new RacesService(raw, files, generated);
        com.vibeminer.rendering.models.MeshAppearance base;
        long chrModel;
        try {
            base = service.npcAppearance(50, Cancellation.NONE).join().result().appearance();
            chrModel = service.npcIdle(50, base, Cancellation.NONE).join().variant().chrModelId();
            var mesh = base.mesh();
            int vertices = mesh.vertexCount();
            int[] bones = new int[vertices * 4], weights = new int[vertices * 4];
            for (int v = 0; v < vertices; v++) {
                weights[v * 4] = 255;
            }
            var stand =
                    new PreviewAnimation(
                            "Stand",
                            1000,
                            new long[0],
                            List.of(
                                    new PreviewAnimation.Bone(-1, 0, 0, 0, null, null, null, false),
                                    new PreviewAnimation.Bone(
                                            -1,
                                            0,
                                            0,
                                            0,
                                            new PreviewAnimation.Track(
                                                    PreviewAnimation.Interpolation.LINEAR,
                                                    -1,
                                                    3,
                                                    new int[] {0},
                                                    new float[] {0, 0, 1}),
                                            null,
                                            null,
                                            false)),
                            mesh,
                            base.normals(),
                            bones,
                            weights);
            assertTrue(
                    service.idleIndex.writeStand(
                            chrModel,
                            1234,
                            MODEL_KEY,
                            new RaceNpcIdleIndex.Stand(
                                    RaceTypes.State.READY,
                                    List.of(),
                                    List.of("Stand is bound to the authored surface."),
                                    "Stand idle",
                                    stand,
                                    Map.of(11, 1),
                                    Map.of(1234L, MODEL_KEY)),
                            Cancellation.NONE));
        } finally {
            service.closeAsync().join();
        }
        // NPC 50 wearing a helmet: three item vertices after its surface, on attachment point 11.
        var mesh = base.mesh();
        int vertices = mesh.vertexCount();
        float[] positions = java.util.Arrays.copyOf(mesh.positions(), (vertices + 3) * 3);
        positions[vertices * 3] = 5;
        positions[vertices * 3 + 4] = 5;
        positions[vertices * 3 + 8] = 5;
        int[] indices = java.util.Arrays.copyOf(mesh.indices(), mesh.indices().length + 3);
        for (int k = 0; k < 3; k++) {
            indices[mesh.indices().length + k] = vertices + k;
        }
        var parts = new java.util.ArrayList<>(mesh.parts());
        parts.add(
                new PreviewMesh.Part(
                        RaceNpcAttachments.FIRST_PART + 11 * 100, mesh.indices().length, 3));
        var helmeted =
                new com.vibeminer.rendering.models.MeshAppearance(
                        new PreviewMesh(positions, indices, parts),
                        new float[(vertices + 3) * 2],
                        java.util.Arrays.copyOf(base.normals(), (vertices + 3) * 3),
                        List.of(),
                        List.of());

        var opens = new AtomicInteger();
        var later = new RacesService(raw, counted(files, 1234, opens), generated);
        try {
            later.npcAppearance(50, Cancellation.NONE).join();
            var idle = later.npcIdle(50, helmeted, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, idle.state(), idle.message());
            assertEquals(0, opens.get(), "neither resolved nor assessed: no race model read");
            assertTrue(idle.animation().matches(helmeted));
            float[] posed = new float[(vertices + 3) * 3];
            idle.animation().skin(250, posed, new float[(vertices + 3) * 3]);
            for (int v = 0; v < vertices + 3; v++) {
                float lift = v < vertices ? 0 : 1;
                for (int axis = 0; axis < 3; axis++) {
                    assertEquals(
                            positions[v * 3 + axis] + (axis == 2 ? lift : 0),
                            posed[v * 3 + axis],
                            1e-5f,
                            "vertex " + v + " axis " + axis);
                }
            }
        } finally {
            later.closeAsync().join();
        }

        // The stored points decide: without attachment point 11 the helmet cannot follow. (The
        // appearance entry goes first, so the rewriting handle reads the tables it names.)
        generated.delete(List.of(new RaceNpcIndex(raw, files, generated).key(50))).join();
        handle(
                raw,
                files,
                generated,
                each -> {
                    var stored = each.idleIndex.stand(chrModel, 1234, MODEL_KEY, Cancellation.NONE);
                    assertTrue(
                            each.idleIndex.writeStand(
                                    chrModel,
                                    1234,
                                    MODEL_KEY,
                                    new RaceNpcIdleIndex.Stand(
                                            stored.state(),
                                            stored.reasons(),
                                            stored.facts(),
                                            stored.message(),
                                            stored.animation(),
                                            Map.of(1, 1),
                                            stored.sources()),
                                    Cancellation.NONE));
                });
        var without = new RacesService(raw, files, generated);
        try {
            without.npcAppearance(50, Cancellation.NONE).join();
            var idle = without.npcIdle(50, helmeted, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNSUPPORTED, idle.state(), idle.message());
            assertTrue(idle.message().contains("attachment point"), idle.message());
        } finally {
            without.closeAsync().join();
        }
    }

    @Test
    void theCodeDigestCoversTheIdlePath() {
        assertNotNull(RaceNpcIdleIndex.CODE);
        assertTrue(
                !RaceNpcIdleIndex.CODE.equals(
                        RaceNpcIndex.code(
                                RaceNpcIdleIndex.class.getClassLoader(),
                                RaceNpcIndex.CODE_PREFIXES)),
                "the idle path's code reaches beyond the appearance path's");
    }
}
