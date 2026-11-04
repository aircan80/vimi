package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainScene;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/** Private source-to-neutral-view seam; no native module dependency or public positive DTO. */
final class WorldWmoPresentation {
    private WorldWmoPresentation() {}

    interface Output<R extends AutoCloseable> {
        /**
         * Only unadvertised owned output may return. Locally close every unreturned partial owner.
         */
        R writeUnadvertised(
                BasicWmoData.View view, TerrainScene actualCandidate, Cancellation token);
    }

    /** Caller supplies its bounded source worker; final source validation follows this callback. */
    static <R extends AutoCloseable> R prepare(
            WorldStreamScopes engine,
            WorldStreamScopes.Scope scope,
            WorldBasicWmo.Selection selection,
            TerrainScene candidate,
            Output<R> output,
            Cancellation token) {
        return WorldBasicWmo.prepare(
                engine,
                scope,
                selection,
                candidate,
                (borrow, match, scoped) ->
                        output.writeUnadvertised(adapt(borrow, match), match.scene(), scoped),
                token);
    }

    private static BasicWmoData.View adapt(
            WorldBasicWmo.Borrow borrow, WorldBasicWmo.SceneMatch match) {
        var scope = borrow.metadata();
        var p = borrow.placement();
        var scene = match.scene();
        int ordinal = -1;
        for (int i = 0; i < scene.tiles().size(); i++) {
            var tile = scene.tiles().get(i);
            if (tile.x() == match.x() && tile.y() == match.y()) {
                ordinal = i;
            }
        }
        if (ordinal < 0 || borrow.bindings().size() != 5 + borrow.imageCount()) {
            throw new IllegalArgumentException("WMO neutral adapter binding inventory differs");
        }
        var metadata =
                new BasicWmoData.Metadata(
                        scope.mapId(),
                        ordinal,
                        match.x(),
                        match.y(),
                        p.ordinal(),
                        p.uniqueId(),
                        scope.owner(),
                        scope.generation(),
                        scope.scopeDigest(),
                        match.geometry(),
                        borrow.ambientColor(),
                        scene.origin(),
                        new BasicWmoData.Placement(
                                p.position().x(),
                                p.position().y(),
                                p.position().z(),
                                p.rotation().x(),
                                p.rotation().y(),
                                p.rotation().z(),
                                p.scale(),
                                p.flags(),
                                p.doodadSet(),
                                p.nameSet()));
        var batches = new ArrayList<BasicWmoData.Batch>(6);
        for (var batch : borrow.batches()) {
            batches.add(
                    new BasicWmoData.Batch(
                            Math.toIntExact(batch.firstIndex()),
                            batch.indexCount(),
                            batch.materialByte()));
        }
        var sources = new ArrayList<BasicWmoData.Source>(11);
        int role = 0;
        for (var binding : borrow.bindings()) {
            sources.add(
                    new BasicWmoData.Source(
                            binding.fileDataId(),
                            Math.min(role++, 5),
                            binding.contentKey(),
                            binding.length(),
                            binding.declaredLength(),
                            binding.limit()));
        }
        return new BorrowedView(borrow, metadata, List.copyOf(batches), List.copyOf(sources));
    }

    /**
     * Retains no scene/scope/input, and no primitive storage beyond the expiring factory borrow.
     */
    private record BorrowedView(
            WorldBasicWmo.Borrow borrow,
            BasicWmoData.Metadata descriptive,
            List<BasicWmoData.Batch> commands,
            List<BasicWmoData.Source> identities)
            implements BasicWmoData.View {
        private void check() {
            borrow.metadata();
        }

        public BasicWmoData.Metadata metadata() {
            check();
            return descriptive;
        }

        public FloatBuffer positions() {
            return borrow.positions();
        }

        public FloatBuffer normals() {
            return borrow.normals();
        }

        public FloatBuffer uv() {
            return borrow.uv();
        }

        public IntBuffer indices() {
            return borrow.indices();
        }

        public List<BasicWmoData.Batch> batches() {
            check();
            return commands;
        }

        public List<BasicWmoData.Source> sources() {
            check();
            return identities;
        }

        public int materialCount() {
            return borrow.materials().size();
        }

        public long materialWord(int material, int word) {
            return borrow.materials().get(material).word(word);
        }

        public int imageCount() {
            return borrow.imageCount();
        }

        public BasicWmoData.Image image(int index) {
            return new BasicWmoData.Image(
                    borrow.imageId(index),
                    borrow.imageWidth(index),
                    borrow.imageHeight(index),
                    borrow.rgba(index));
        }
    }
}
