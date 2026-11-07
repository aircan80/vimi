package com.vimi.gui;

import com.vibeminer.concepts.world.TerrainAtlasPool;
import com.vibeminer.concepts.world.WorldTerrainTextureSource;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owned texture claims for one immutable geometry scene; never owns or exposes RGBA copies. */
final class WorldTextureSnapshot implements AutoCloseable {
    private record Entry(TerrainTextureBinding.Metadata metadata, TerrainAtlasPool.Lease lease) {}

    private final TerrainScene scene;
    private final int coveredChunks, emptyChunks;
    private List<Entry> entries;

    private WorldTextureSnapshot(
            TerrainScene scene, List<Entry> entries, int coveredChunks, int emptyChunks) {
        this.scene = scene;
        this.entries = List.copyOf(entries);
        this.coveredChunks = coveredChunks;
        this.emptyChunks = emptyChunks;
    }

    /** Preparation worker only. Consumes and closes the batch even on cancellation or rejection. */
    static WorldTextureSnapshot prepare(
            TerrainScene scene, WorldTerrainTextureSource.BatchResult batch,
            BooleanSupplier canceled) {
        Objects.requireNonNull(batch);
        var entries = new ArrayList<Entry>();
        int covered = 0, empty = 0;
        try (batch) {
            Objects.requireNonNull(scene);
            Objects.requireNonNull(canceled);
            for (int ordinal = 0; ordinal < scene.tiles().size(); ordinal++) {
                check(canceled);
                var tile = scene.tiles().get(ordinal);
                var result = batch.tiles().stream()
                        .filter(item -> item.tile().x() == tile.x() && item.tile().y() == tile.y())
                        .findFirst().orElse(null);
                if (result == null || result.receipt() == null || result.supportedChunks() == 0) continue;
                var retained = batch.retainAtlas(tile.x(), tile.y());
                if (retained.isEmpty()) continue;
                var lease = retained.get();
                boolean accepted = false;
                try {
                    var receipt = result.receipt();
                    var sources = new TerrainTextureBinding.Sources(
                            receipt.root().fileId(), receipt.root().contentKey(),
                            receipt.textures().fileId(), receipt.textures().contentKey());
                    var metadata = new TerrainTextureBinding.Metadata[1];
                    int index = ordinal;
                    lease.withAtlas(atlas -> metadata[0] = TerrainTextureBinding.qualify(
                            index, tile, atlas, receipt.geometrySha256(), sources, canceled));
                    check(canceled);
                    entries.add(new Entry(metadata[0], lease));
                    covered += result.supportedChunks();
                    empty += metadata[0].emptyRectangles();
                    accepted = true;
                } catch (IllegalArgumentException unsupportedBinding) {
                    // This geometry cannot safely use the basic uniform mapping; keep height shading.
                } finally {
                    if (!accepted) lease.close();
                }
            }
            check(canceled);
            return new WorldTextureSnapshot(scene, entries, covered, empty);
        } catch (RuntimeException | Error failure) {
            for (var entry : entries) entry.lease().close();
            throw failure;
        }
    }

    TerrainScene scene() { return scene; }
    int coveredChunks() { return coveredChunks; }
    synchronized int atlasCount() { requireOpen(); return entries.size(); }
    /** Drawable chunks: every scene chunk except those with no texture layers. */
    int totalChunks() { return scene.tiles().size() * 256 - emptyChunks; }

    /** Own a separate claim before queuing asynchronous work. No pixel view escapes. */
    synchronized WorldTextureSnapshot retain() {
        requireOpen();
        var retained = new ArrayList<Entry>(entries.size());
        try {
            for (var entry : entries) {
                retained.add(new Entry(entry.metadata(), entry.lease().retain()));
            }
            return new WorldTextureSnapshot(scene, retained, coveredChunks, emptyChunks);
        } catch (RuntimeException | Error failure) {
            for (var entry : retained) entry.lease().close();
            throw failure;
        }
    }

    /** Synchronous callback only. Bindings, lists and pixel views MUST NOT escape this callback. */
    void withBindings(Consumer<List<TerrainTextureBinding>> consumer) {
        Objects.requireNonNull(consumer);
        try (var pinned = retain()) {
            borrow(pinned.entries, 0, new ArrayList<>(), consumer);
        }
    }

    private static void borrow(List<Entry> entries, int index,
            List<TerrainTextureBinding> bindings, Consumer<List<TerrainTextureBinding>> consumer) {
        if (index == entries.size()) {
            consumer.accept(java.util.Collections.unmodifiableList(bindings));
            return;
        }
        var entry = entries.get(index);
        entry.lease().withAtlas(atlas -> {
            bindings.add(TerrainTextureBinding.borrow(entry.metadata(), atlas.pixels()));
            try { borrow(entries, index + 1, bindings, consumer); }
            finally { bindings.removeLast(); }
        });
    }

    private void requireOpen() {
        if (entries == null) throw new IllegalStateException("World texture snapshot closed");
    }

    @Override
    public synchronized void close() {
        if (entries == null) return;
        var released = entries;
        entries = null;
        for (var entry : released) entry.lease().close();
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("World texture preparation canceled");
        }
    }
}
