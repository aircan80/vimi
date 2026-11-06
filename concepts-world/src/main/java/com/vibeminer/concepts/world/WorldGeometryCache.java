package com.vibeminer.concepts.world;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The service's single twelve-version geometry cache, including pinned retired versions. */
final class WorldGeometryCache {
    private static final int LIMIT = 12;
    private final LinkedHashMap<String, Entry> current = new LinkedHashMap<>(16, .75f, true);
    private final ArrayList<Entry> retained = new ArrayList<>();
    private final ArrayList<Prepared> outputs = new ArrayList<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private int constructing;
    private volatile boolean closed;
    private boolean preparing;

    private record RootProof(
            long id,
            String key,
            long length,
            Long declaredLength,
            int sourceLimit,
            String fingerprint) {}

    private static final class Entry {
        private final String key;
        private WorldService.LoadedTile loaded;
        private TerrainScene.Tile nativeTile;
        private RootProof proof;
        private int pins;

        private Entry(String key, WorldService.LoadedTile loaded) {
            this.key = key;
            this.loaded = loaded;
        }
    }

    /** A source-owned output credit. Close retires the pin after synchronous payload callbacks. */
    static final class Prepared implements AutoCloseable {
        private WorldGeometryCache owner;
        private Entry entry;
        private int callbacks;
        private boolean closed;

        private Prepared(WorldGeometryCache owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        void withTile(Consumer<TerrainScene.Tile> use) {
            Objects.requireNonNull(use);
            Entry borrowed;
            synchronized (this) {
                if (closed || owner.closed) {
                    throw new IllegalStateException("Geometry output closed");
                }
                if (callbacks != 0) {
                    throw new IllegalStateException("Geometry callback busy");
                }
                callbacks++;
                borrowed = entry;
            }
            try {
                use.accept(borrowed.nativeTile);
            } finally {
                synchronized (this) {
                    callbacks--;
                }
                retireIfDrained();
            }
        }

        synchronized WorldFormats.Tile recorded() {
            if (closed || owner.closed) {
                throw new IllegalStateException("Geometry output closed");
            }
            return entry.loaded.tile();
        }

        synchronized WorldFormats.AreaMetadata areas() {
            if (closed || owner.closed) {
                throw new IllegalStateException("Geometry output closed");
            }
            return entry.loaded.areas();
        }

        synchronized String fingerprint() {
            if (closed || owner.closed) {
                throw new IllegalStateException("Geometry output closed");
            }
            return entry.proof.fingerprint();
        }

        synchronized WorldTerrainTextureSource.FileBinding rootBinding() {
            if (closed || owner.closed) {
                throw new IllegalStateException("Geometry output closed");
            }
            var proof = entry.proof;
            return new WorldTerrainTextureSource.FileBinding(
                    proof.id(),
                    proof.key(),
                    proof.length(),
                    proof.declaredLength(),
                    proof.sourceLimit());
        }

        @Override
        public void close() {
            synchronized (this) {
                closed = true;
            }
            retireIfDrained();
        }

        private void retireIfDrained() {
            WorldGeometryCache releaseOwner;
            Entry releaseEntry;
            synchronized (this) {
                if (!closed || callbacks != 0 || owner == null) {
                    return;
                }
                releaseOwner = owner;
                releaseEntry = entry;
                owner = null;
                entry = null;
            }
            releaseOwner.release(this, releaseEntry);
        }
    }

    static String key(long mapId, String directory, WorldFormats.Tile tile) {
        return mapId
                + ":"
                + (tile.rootId() == 0 ? directory + ":" : "")
                + tile.key()
                + ":"
                + tile.rootId();
    }

    synchronized WorldService.LoadedTile legacyGet(String key) {
        var entry = current.get(key);
        return closed || entry == null ? null : entry.loaded;
    }

    synchronized void legacyPut(String key, WorldService.LoadedTile loaded) {
        if (closed || loaded.terrain() == null) {
            return;
        }
        var previous = current.get(key);
        if (previous != null) {
            return;
        }
        if (!makeRoom(null)) {
            return;
        } // Legacy delivery remains descriptive even without cache credit.
        var entry = new Entry(key, loaded);
        current.put(key, entry);
        retained.add(entry);
    }

    Prepared prepare(WorldStreamScopes.TileAdmission admission) {
        if (admission.profile() != WorldStreamScopes.UseProfile.TERRAIN_BASIC) {
            throw new IllegalArgumentException("Terrain geometry construction profile required");
        }
        synchronized (this) {
            checkOpen();
            if (preparing) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
            }
            preparing = true;
        }
        try {
            return prepareOwned(admission);
        } finally {
            boolean finish;
            synchronized (this) {
                preparing = false;
                finish = isDrained();
            }
            if (finish) {
                drained.complete(null);
            }
        }
    }

    private Prepared prepareOwned(WorldStreamScopes.TileAdmission admission) {
        var tuple = admission.recorded();
        String key = key(admission.mapId(), null, tuple);
        Entry previous;
        Prepared reused = null;
        RootProof previousProof = null;
        WorldFormats.Tile previousTuple = null;
        synchronized (this) {
            checkOpen();
            if (outputs.size() >= LIMIT) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
            }
            previous = current.get(key);
            if (previous != null && previous.proof != null) {
                previousProof = previous.proof;
                previousTuple = previous.loaded.tile();
                reused = pin(previous);
            }
        }
        if (reused != null) {
            boolean transferred = false;
            try {
                var proof = previousProof;
                boolean[] matches = {false};
                reused.withTile(
                        ignored ->
                                matches[0] =
                                        admission.reuseRecordedRoot(proof.key(), proof.length()));
                if (matches[0]) {
                    if (!tuple.equals(previousTuple)) {
                        reused.close();
                        reused = null;
                        return rebindProven(key, tuple, proof);
                    }
                    transferred = true;
                    return reused;
                }
            } finally {
                if (!transferred && reused != null) {
                    reused.close();
                }
            }
        }
        // Reserve a counted construction slot BEFORE any source read or parser allocation.
        synchronized (this) {
            checkOpen();
            previous = current.get(key);
            if (!makeRoom(previous)) {
                // If only this unpinned old version can retire, do not keep a dead local alias.
                if (previous != null && previous.pins == 0) {
                    remove(previous);
                    previous = null;
                }
                if (!makeRoom(previous)) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
                }
            }
            constructing++;
            if (previous != null) {
                previous.pins++;
            }
        }
        boolean published = false;
        try {
            WorldService.LoadedTile loaded;
            TerrainScene.Tile nativeTile;
            RootProof proof;
            try (var root = admission.readRecorded(WorldStreamScopes.RecordedInput.ROOT)) {
                WorldFormats.TerrainAreas parsed;
                try {
                    parsed =
                            WorldFormats.adtTile(
                                    root.bytes(), admission.cancellation()::isCanceled);
                } catch (IllegalArgumentException unsupportedProfile) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.TERRAIN_PROFILE);
                }
                nativeTile = new TerrainScene.Tile(tuple.x(), tuple.y(), parsed.terrain());
                String fingerprint = TerrainGeometryFingerprint.sha256(nativeTile);
                proof =
                        new RootProof(
                                root.fileDataId(),
                                root.contentKey(),
                                root.consumedLength(),
                                root.declaredLength(),
                                root.sourceLimit(),
                                fingerprint);
                var terrain = parsed.terrain();
                if (previous != null) {
                    if (previous.nativeTile == null) {
                        previous.nativeTile =
                                new TerrainScene.Tile(
                                        tuple.x(), tuple.y(), previous.loaded.terrain());
                    }
                    // Reuse needs equal normals and colours too: the fingerprint is positions
                    // and indices only (#373).
                    if (fingerprint.equals(TerrainGeometryFingerprint.sha256(previous.nativeTile))
                            && terrain.sameAttributes(previous.loaded.terrain())) {
                        terrain = previous.loaded.terrain();
                        nativeTile = previous.nativeTile;
                    }
                }
                // Area metadata and companion tuple ALWAYS come from this checked parse/admission.
                loaded = new WorldService.LoadedTile(tuple, terrain, null, parsed.areas());
            }
            var next = new Entry(key, loaded);
            next.nativeTile = nativeTile;
            next.proof = proof;
            synchronized (this) {
                checkOpen();
                if (outputs.size() >= LIMIT) {
                    throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
                }
                var displaced = current.put(key, next);
                retained.add(next);
                constructing--;
                published = true;
                if (displaced != null && displaced.pins == 0) {
                    remove(displaced);
                }
                return pin(next);
            }
        } finally {
            synchronized (this) {
                if (!published) {
                    constructing--;
                }
                if (previous != null) {
                    previous.pins--;
                    if ((closed || current.get(previous.key) != previous) && previous.pins == 0) {
                        remove(previous);
                    }
                }
            }
        }
    }

    private synchronized Prepared rebindProven(
            String key, WorldFormats.Tile tuple, RootProof proof) {
        checkOpen();
        if (outputs.size() >= LIMIT) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
        }
        var old = current.get(key);
        if (old == null || old.proof != proof) {
            throw new IllegalStateException("Geometry proof retired");
        }
        var refreshed =
                new WorldService.LoadedTile(tuple, old.loaded.terrain(), null, old.loaded.areas());
        if (old.pins == 0) {
            old.loaded = refreshed;
            return pin(old);
        }
        if (!makeRoom(old)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GEOMETRY_CREDIT);
        }
        var next = new Entry(key, refreshed);
        next.nativeTile = old.nativeTile;
        next.proof = proof;
        current.put(key, next);
        retained.add(next);
        return pin(next);
    }

    private Prepared pin(Entry entry) {
        var result = new Prepared(this, entry);
        entry.pins++;
        outputs.add(result);
        return result;
    }

    private void release(Prepared output, Entry entry) {
        boolean finish;
        synchronized (this) {
            if (!outputs.remove(output)) {
                return;
            }
            entry.pins--;
            if ((closed || current.get(entry.key) != entry) && entry.pins == 0) {
                remove(entry);
            }
            finish = isDrained();
        }
        if (finish) {
            drained.complete(null);
        }
    }

    private boolean makeRoom(Entry preserve) {
        if (retained.size() + constructing < LIMIT) {
            return true;
        }
        for (var entry : new ArrayList<>(current.values())) {
            if (entry != preserve && entry.pins == 0) {
                remove(entry);
                return true;
            }
        }
        return false;
    }

    private void remove(Entry entry) {
        current.remove(entry.key, entry);
        retained.remove(entry);
        entry.loaded = null;
        entry.nativeTile = null;
        entry.proof = null;
    }

    void invalidate() {
        closed = true;
    }

    CompletableFuture<Void> closeAsync() {
        ArrayList<Prepared> toClose;
        boolean finish;
        synchronized (this) {
            closed = true;
            toClose = new ArrayList<>(outputs);
            current.clear();
            for (var entry : new ArrayList<>(retained)) {
                if (entry.pins == 0) {
                    remove(entry);
                }
            }
            finish = isDrained();
        }
        for (var output : toClose) {
            output.close();
        }
        if (finish) {
            drained.complete(null);
        }
        return drained.copy();
    }

    private boolean isDrained() {
        return closed && !preparing && outputs.isEmpty() && constructing == 0 && retained.isEmpty();
    }

    private void checkOpen() {
        if (closed) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
    }
}
