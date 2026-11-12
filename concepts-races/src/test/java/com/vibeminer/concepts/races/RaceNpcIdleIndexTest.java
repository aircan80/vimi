package com.vibeminer.concepts.races;

import static com.vibeminer.concepts.races.RaceAppearancePlanTest.BUILD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
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
