package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpFormatException;
import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldTextureFormats;
import com.vibeminer.rendering.world.TerrainAtlasComposer;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainTileAtlas;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional authenticated-source basic-coverage batches; files and the application pool are
 * borrowed.
 */
public final class WorldTerrainTextureSource {
    public static final String REVISION = "recorded-terrain-basic-source-v1";

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        POOL_BUSY
    }

    public enum Reason {
        UNSUPPORTED,
        ABSENT,
        UNBOUND,
        LIMIT,
        PROTECTED,
        INCOMPLETE,
        CHANGED,
        INVENTORY_PROFILE,
        ROOT_PROFILE,
        TEXTURE_PROFILE,
        DIFFUSE_PROFILE,
        CHUNK,
        POOL_BUSY
    }

    public enum Stage {
        INVENTORY,
        GRID,
        TEXTURES,
        DIFFUSE,
        COMPOSING,
        REVALIDATING
    }

    public record Progress(Stage stage, int tileIndex, int tileCount, int diffuseIndex) {
        public Progress {
            Objects.requireNonNull(stage);
            if (tileCount < 1
                    || tileCount > 9
                    || tileIndex < 0
                    || tileIndex > tileCount
                    || diffuseIndex < 0
                    || diffuseIndex > 64) {
                throw new IllegalArgumentException("Invalid texture progress bounds");
            }
        }
    }

    public record Issue(
            Reason reason,
            int ordinal,
            long fileId,
            TerrainAtlasComposer.Reason chunkReason,
            WorldTextureFormats.ChunkIssue maskIssue) {
        public Issue(Reason reason, int ordinal, long fileId) {
            this(reason, ordinal, fileId, null, WorldTextureFormats.ChunkIssue.NONE);
        }

        public Issue {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(maskIssue);
            if (ordinal < -1
                    || ordinal >= 256
                    || fileId < 0
                    || fileId > 0xffff_ffffL
                    || (reason == Reason.CHUNK) != (chunkReason != null)) {
                throw new IllegalArgumentException("Invalid texture issue bounds");
            }
        }
    }

    public record FileBinding(
            long fileId, String contentKey, long length, Long declaredSize, int sourceLimit) {
        public FileBinding {
            Objects.requireNonNull(contentKey);
            if (fileId <= 0
                    || fileId > 0xffff_ffffL
                    || contentKey.length() != 32
                    || !contentKey.matches("[0-9a-f]{32}")
                    || (sourceLimit != 1 << 20 && sourceLimit != 2 << 20 && sourceLimit != 8 << 20)
                    || length < -1
                    || length > sourceLimit
                    || (declaredSize != null
                            && (declaredSize < 12 || declaredSize > sourceLimit))) {
                throw new IllegalArgumentException("Invalid texture file binding");
            }
        }
    }

    public record MipBinding(
            FileBinding file,
            int originalSide,
            int level,
            int mipFlags,
            String encoding,
            int alphaDepth,
            int alphaEncoding) {
        public MipBinding {
            Objects.requireNonNull(file);
            Objects.requireNonNull(encoding);
            if (level < 0
                    || level > 12
                    || originalSide != (4 << level)
                    || mipFlags < 0
                    || mipFlags > 255
                    || encoding.isEmpty()
                    || encoding.length() > 16
                    || alphaDepth < 0
                    || alphaDepth > 255
                    || alphaEncoding < 0
                    || alphaEncoding > 255) {
                throw new IllegalArgumentException("Invalid texture mip binding");
            }
        }
    }

    /** Descriptive positive receipt; only a successfully checked source can construct it. */
    public static final class Receipt {
        private final BuildRef build;
        private final String locale;
        private final long mapId;
        private final WorldFormats.Tile tile;
        private final FileBinding wdt, root, textures;
        private final List<MipBinding> diffuse;
        private final String geometrySha256, cacheKey;

        private Receipt(
                BuildRef build,
                String locale,
                long mapId,
                WorldFormats.Tile tile,
                FileBinding wdt,
                FileBinding root,
                FileBinding textures,
                List<MipBinding> diffuse,
                String geometrySha256,
                String cacheKey) {
            this.build = build;
            this.locale = locale;
            this.mapId = mapId;
            this.tile = tile;
            this.wdt = wdt;
            this.root = root;
            this.textures = textures;
            if (diffuse.size() > 64) {
                throw new IllegalArgumentException("Too many diffuse bindings");
            }
            this.diffuse = List.copyOf(diffuse);
            this.geometrySha256 = geometrySha256;
            this.cacheKey = cacheKey;
        }

        public BuildRef build() {
            return build;
        }

        public String locale() {
            return locale;
        }

        public long mapId() {
            return mapId;
        }

        public WorldFormats.Tile tile() {
            return tile;
        }

        public FileBinding wdt() {
            return wdt;
        }

        public FileBinding root() {
            return root;
        }

        public FileBinding textures() {
            return textures;
        }

        public List<MipBinding> diffuse() {
            return diffuse;
        }

        public String geometrySha256() {
            return geometrySha256;
        }

        public String cacheKey() {
            return cacheKey;
        }

        long metadataBytes() {
            // Logical conservative per-receipt allowance: bounded keys/IDs/strings/records/lists.
            return 2048L + diffuse.size() * 512L;
        }

        List<FileBinding> bindings() {
            var result = new ArrayList<FileBinding>(3 + diffuse.size());
            result.add(wdt);
            result.add(root);
            result.add(textures);
            for (var mip : diffuse) {
                result.add(mip.file());
            }
            return result;
        }
    }

    public record TileResult(
            WorldFormats.Tile tile,
            State state,
            Receipt receipt,
            int supportedChunks,
            List<Issue> issues) {
        public TileResult {
            Objects.requireNonNull(tile);
            Objects.requireNonNull(state);
            Objects.requireNonNull(issues);
            if (issues.size() > 320
                    || supportedChunks < 0
                    || supportedChunks > 256
                    || ((state == State.READY || state == State.PARTIAL) != (receipt != null))
                    || (state == State.READY && supportedChunks != 256)
                    || (state == State.PARTIAL && (supportedChunks == 0 || supportedChunks == 256))
                    || ((state == State.UNAVAILABLE || state == State.POOL_BUSY)
                            && supportedChunks != 0)) {
                throw new IllegalArgumentException("Invalid texture tile result");
            }
            issues = List.copyOf(issues);
        }
    }

    /** Owns result claims until closed. Retained consumer leases survive result closure. */
    public static final class BatchResult implements AutoCloseable {
        private final List<TileResult> tiles;
        private TerrainAtlasPool.Lease[] leases;

        private BatchResult(List<TileResult> tiles, TerrainAtlasPool.Lease[] leases) {
            this.tiles = List.copyOf(tiles);
            this.leases = leases;
        }

        public static BatchResult unsupported(List<WorldFormats.Tile> tiles) {
            Objects.requireNonNull(tiles);
            if (tiles.isEmpty() || tiles.size() > 9) {
                throw new IllegalArgumentException("Expected1..9texturetiles");
            }
            var rows = new ArrayList<TileResult>(tiles.size());
            for (var tile : List.copyOf(tiles)) {
                rows.add(unavailable(tile, Reason.UNSUPPORTED));
            }
            return new BatchResult(rows, new TerrainAtlasPool.Lease[rows.size()]);
        }

        public List<TileResult> tiles() {
            return tiles;
        }

        public synchronized Optional<TerrainAtlasPool.Lease> retainAtlas(int x, int y) {
            if (leases == null) {
                throw new IllegalStateException("Texture batch closed");
            }
            for (int i = 0; i < tiles.size(); i++) {
                if (tiles.get(i).tile().x() == x && tiles.get(i).tile().y() == y) {
                    return leases[i] == null ? Optional.empty() : Optional.of(leases[i].retain());
                }
            }
            return Optional.empty();
        }

        @Override
        public synchronized void close() {
            if (leases != null) {
                var retired = leases;
                leases = null;
                for (var lease : retired) {
                    if (lease != null) {
                        lease.close();
                    }
                }
            }
        }
    }

    enum ObservationKind {
        FOUND,
        NOT_FOUND,
        UNBOUND
    }

    enum UnboundKind {
        NONE,
        MISSING_FILE,
        WRONG_ID,
        MISSING_KEY,
        INVALID_KEY
    }

    /** Actual lookup/read observations only: never a positive file/ancestry/material receipt. */
    static final class Observation {
        private final long fileId, reportedId;
        private final int sourceLimit;
        private final FileTypes.FileLookupState lookup;
        private final ObservationKind kind;
        private final UnboundKind unbound;
        private final String key;
        private final Long initialDeclaredLength, knownLength, streamLength;
        private final FileTypes.FileReadState readState;
        private final Boolean holes;

        private Observation(
                long fileId,
                long reportedId,
                int sourceLimit,
                FileTypes.FileLookupState lookup,
                ObservationKind kind,
                UnboundKind unbound,
                String key,
                Long initialDeclaredLength,
                Long knownLength,
                FileTypes.FileReadState readState,
                Long streamLength,
                Boolean holes) {
            this.fileId = fileId;
            this.reportedId = reportedId;
            this.sourceLimit = sourceLimit;
            this.lookup = lookup;
            this.kind = kind;
            this.unbound = unbound;
            this.key = key;
            this.initialDeclaredLength = initialDeclaredLength;
            this.knownLength = knownLength;
            this.readState = readState;
            this.streamLength = streamLength;
            this.holes = holes;
        }

        private static Observation lookup(long id, int limit, FileTypes.FileStat result) {
            var state = Objects.requireNonNull(result.state());
            var file = result.file();
            ObservationKind kind;
            UnboundKind unbound = UnboundKind.NONE;
            String key = null;
            long reported = 0;
            Long declared = null;
            if (state != FileTypes.FileLookupState.FOUND) {
                kind = ObservationKind.NOT_FOUND;
            } else if (file == null) {
                kind = ObservationKind.UNBOUND;
                unbound = UnboundKind.MISSING_FILE;
            } else {
                reported = file.fileDataId();
                declared = file.size();
                String observed = file.contentKey();
                if (observed != null
                        && observed.length() == 32
                        && observed.matches("[0-9a-fA-F]{32}")) {
                    key = observed.toLowerCase(Locale.ROOT);
                }
                if (reported != id) {
                    unbound = UnboundKind.WRONG_ID;
                } else if (observed == null) {
                    unbound = UnboundKind.MISSING_KEY;
                } else if (key == null) {
                    unbound = UnboundKind.INVALID_KEY;
                }
                kind =
                        unbound == UnboundKind.NONE
                                ? ObservationKind.FOUND
                                : ObservationKind.UNBOUND;
            }
            return new Observation(
                    id, reported, limit, state, kind, unbound, key, declared, declared, null, null,
                    null);
        }

        private boolean sameLookupIdentity(Observation other) {
            return lookup == other.lookup
                    && kind == other.kind
                    && unbound == other.unbound
                    && reportedId == other.reportedId
                    && Objects.equals(key, other.key);
        }

        private Observation mergeLookup(Observation other) {
            if (!sameLookupIdentity(other)
                    || knownLength != null
                            && other.knownLength != null
                            && !knownLength.equals(other.knownLength)) {
                throw new SourceFailure(Reason.CHANGED);
            }
            return new Observation(
                    fileId,
                    reportedId,
                    Math.min(sourceLimit, other.sourceLimit),
                    lookup,
                    kind,
                    unbound,
                    key,
                    initialDeclaredLength,
                    knownLength == null ? other.knownLength : knownLength,
                    readState,
                    streamLength,
                    holes);
        }

        private Observation opened(FileTypes.FileOpen opened) {
            var stream = opened.stream();
            Long length = stream == null ? null : stream.length();
            Boolean protectedBytes = stream == null ? null : !stream.holes().isEmpty();
            if (readState != null
                            && (readState != opened.state()
                                    || !Objects.equals(streamLength, length)
                                    || !Objects.equals(holes, protectedBytes))
                    || knownLength != null && length != null && !knownLength.equals(length)) {
                throw new SourceFailure(Reason.CHANGED);
            }
            return new Observation(
                    fileId,
                    reportedId,
                    sourceLimit,
                    lookup,
                    kind,
                    unbound,
                    key,
                    initialDeclaredLength,
                    knownLength == null ? length : knownLength,
                    Objects.requireNonNull(opened.state()),
                    length,
                    protectedBytes);
        }

        boolean matches(FileTypes.FileStat fresh) {
            var other = lookup(fileId, sourceLimit, fresh);
            return sameLookupIdentity(other)
                    && (knownLength == null
                            || other.knownLength == null
                            || knownLength.equals(other.knownLength));
        }

        long fileId() {
            return fileId;
        }

        ObservationKind kind() {
            return kind;
        }

        FileTypes.FileLookupState lookupState() {
            return lookup;
        }

        String contentKey() {
            return key;
        }

        Long initialDeclaredLength() {
            return initialDeclaredLength;
        }

        Long observedLength() {
            return knownLength;
        }

        Long streamLength() {
            return streamLength;
        }

        FileTypes.FileReadState readState() {
            return readState;
        }

        Boolean hasHoles() {
            return holes;
        }

        int sourceLimit() {
            return sourceLimit;
        }
    }

    static final class Request {
        final WorldTerrainTextureSource source;
        final WorldService.MapInfo map;
        final List<WorldFormats.Tile> tiles;
        final Cancellation caller, token = new Cancellation();
        final Consumer<Progress> progress;
        final CompletableFuture<BatchResult> future = new CompletableFuture<>();
        final CompletableFuture<Void> drained = new CompletableFuture<>();
        final CompletableFuture<List<Observation>> observations = new CompletableFuture<>();
        private final ArrayList<Observation> observing;
        final boolean strictFailures;
        Cancellation.Registration registration;
        volatile boolean canceled;
        volatile Throwable cancellationFailure;
        boolean cancelQueued;
        boolean activityTracked, activityFinished;

        Request(
                WorldTerrainTextureSource source,
                WorldService.MapInfo map,
                List<WorldFormats.Tile> tiles,
                Cancellation caller,
                Consumer<Progress> progress,
                boolean strictFailures) {
            this.source = source;
            this.map = map;
            this.tiles = tiles;
            this.caller = caller;
            this.progress = progress;
            this.strictFailures = strictFailures;
            observing = strictFailures ? new ArrayList<>() : null;
        }

        void observeLookup(long id, int limit, FileTypes.FileStat result) {
            if (!strictFailures) {
                return;
            }
            var value = Observation.lookup(id, limit, result);
            for (int i = 0; i < observing.size(); i++) {
                if (observing.get(i).fileId == id) {
                    observing.set(i, observing.get(i).mergeLookup(value));
                    return;
                }
            }
            if (observing.size() >= 67) {
                throw new IllegalStateException("Owned source observation overflow");
            }
            observing.add(value);
        }

        void observeOpen(long id, FileTypes.FileOpen opened) {
            if (!strictFailures) {
                return;
            }
            for (int i = 0; i < observing.size(); i++) {
                if (observing.get(i).fileId == id) {
                    observing.set(i, observing.get(i).opened(opened));
                    return;
                }
            }
            throw new IllegalStateException("Owned read lacks lookup observation");
        }

        void completeObservations() {
            observations.complete(observing == null ? List.of() : List.copyOf(observing));
            if (observing != null) {
                observing.clear();
            }
        }

        void check() {
            if (canceled || source.closed || source.pool.isClosing() || caller.isCanceled()) {
                throw new CancellationException();
            }
            token.check();
        }

        boolean canceled() {
            return canceled
                    || source.closed
                    || source.pool.isClosing()
                    || caller.isCanceled()
                    || token.isCanceled();
        }

        void report(Stage stage, int tileIndex, int diffuseIndex) {
            check();
            progress.accept(new Progress(stage, tileIndex, tiles.size(), diffuseIndex));
            check();
        }
    }

    private record ReadSource(FileBinding binding, byte[] bytes) {}

    private record Outcome(TileResult metadata, TerrainAtlasPool.Lease lease) {}

    private static final class SourceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Reason reason;

        SourceFailure(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    private final BuildFiles files;
    private final TerrainAtlasPool pool;
    private final TerrainTextureTasks tasks;
    private final BuildRef build;
    private final String locale;
    volatile boolean closed;
    volatile Throwable cancellationFailure;
    final CompletableFuture<Void> closing = new CompletableFuture<>();
    // Guarded by the shared task owner, including the post-active-finally interval.
    int activityRequests;
    CompletableFuture<Void> activityIdle = CompletableFuture.completedFuture(null);

    public WorldTerrainTextureSource(BuildFiles files, TerrainAtlasPool pool) {
        this.files = Objects.requireNonNull(files);
        this.pool = Objects.requireNonNull(pool);
        build = Objects.requireNonNull(files.build());
        locale = Objects.requireNonNull(files.locale());
        if (locale.isBlank() || locale.length() > 16) {
            throw new IllegalArgumentException("Invalid locale");
        }
        tasks = pool.tasks();
    }

    boolean matchesNamespace(BuildRef expectedBuild, String expectedLocale) {
        return build.equals(expectedBuild) && locale.equals(expectedLocale);
    }

    /** Owned result and actual worker/cancellation retirement are deliberately separate. */
    static final class Operation {
        private final CompletableFuture<BatchResult> result;
        private final CompletableFuture<Void> drained;
        private final CompletableFuture<List<Observation>> observations;

        private Operation(Request request) {
            result = request.future;
            drained = request.drained;
            observations = request.observations;
        }

        List<Observation> observations() {
            if (!drained.isDone()) {
                throw new IllegalStateException("Owned source observations not drained");
            }
            drained.join();
            return observations.join();
        }

        CompletableFuture<BatchResult> result() {
            return result;
        }

        BatchResult awaitResult() {
            return WorldSourceFutures.await(result);
        }

        boolean isDrained() {
            return drained.isDone();
        }

        void awaitDrained() {
            drained.join();
        }
    }

    Operation readOwned(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress) {
        Objects.requireNonNull(tiles);
        if (tiles.size() != 1) {
            throw new IllegalArgumentException("Owned material operation requires one tile");
        }
        return beginRead(map, tiles, token, progress, true);
    }

    public CompletableFuture<BatchResult> read(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress) {
        return beginRead(map, tiles, token, progress, false).result();
    }

    private Operation beginRead(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress,
            boolean strictFailures) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(tiles);
        Objects.requireNonNull(token);
        Objects.requireNonNull(progress);
        if (tiles.isEmpty() || tiles.size() > 9) {
            throw new IllegalArgumentException("Expected1..9texturetiles");
        }
        var snapshot = List.copyOf(tiles);
        boolean[] seen = new boolean[4096];
        for (var tile : snapshot) {
            int key = tile.y() * 64 + tile.x();
            if (seen[key]) {
                throw new IllegalArgumentException("Duplicate texture tile");
            }
            seen[key] = true;
        }
        var request = new Request(this, map, snapshot, token, progress, strictFailures);
        request.registration = token.onCancel(() -> tasks.cancel(request));
        request.future.whenComplete(
                (result, failure) -> {
                    if (request.future.isCancelled()) {
                        tasks.cancel(request);
                    }
                });
        tasks.submit(request);
        return new Operation(request);
    }

    /**
     * Actual current request/cancellation/observation retirement; does not close this shared
     * source. Callers must stop their request producers before taking this epoch fence: later
     * requests begin a new epoch and are not covered by an already completed fence.
     */
    public CompletableFuture<Void> activityDrained() {
        return tasks.whenSourceIdle(this);
    }

    public CompletableFuture<Void> closeAsync() {
        closed = true;
        tasks.closeSource(this);
        return closing.copy();
    }

    BatchResult load(Request request) {
        var results = new ArrayList<TileResult>(request.tiles.size());
        var leases = new TerrainAtlasPool.Lease[request.tiles.size()];
        boolean transferred = false;
        try {
            request.report(Stage.INVENTORY, 0, 0);
            ReadSource wdt;
            WorldTextureFormats.Inventory inventory;
            try {
                if (request.map.wdtId() == null || request.map.wdtId() <= 0) {
                    throw new SourceFailure(Reason.ABSENT);
                }
                wdt = readBytes(request.map.wdtId(), 1 << 20, request);
                try {
                    inventory = WorldTextureFormats.inventory(wdt.bytes(), request::canceled);
                } catch (IllegalArgumentException invalid) {
                    throw new SourceFailure(Reason.INVENTORY_PROFILE);
                }
            } catch (RuntimeException unavailable) {
                propagateCancellation(request, unavailable);
                Reason reason = reason(request, unavailable, Reason.INVENTORY_PROFILE);
                for (var tile : request.tiles) {
                    results.add(unavailable(tile, reason));
                }
                transferred = true;
                return new BatchResult(results, leases);
            }
            var recorded = new HashMap<Integer, WorldFormats.Tile>();
            for (var tile : inventory.world().tiles()) {
                recorded.put(tile.y() * 64 + tile.x(), tile);
            }
            for (int i = 0; i < request.tiles.size(); i++) {
                var tile = request.tiles.get(i);
                if (!tile.equals(recorded.get(tile.y() * 64 + tile.x()))) {
                    results.add(unavailable(tile, Reason.INVENTORY_PROFILE));
                    continue;
                }
                Outcome loaded;
                try {
                    loaded = loadTile(request, i, tile, wdt.binding());
                } catch (RuntimeException unavailable) {
                    propagateCancellation(request, unavailable);
                    loaded =
                            new Outcome(
                                    unavailable(
                                            tile,
                                            reason(request, unavailable, Reason.TEXTURE_PROFILE)),
                                    null);
                }
                results.add(loaded.metadata());
                leases[i] = loaded.lease();
            }
            // Earlier tiles are revalidated after the whole batch, not just their own decode.
            request.report(Stage.REVALIDATING, request.tiles.size(), 0);
            for (int i = 0; i < results.size(); i++) {
                var receipt = results.get(i).receipt();
                if (receipt != null) {
                    try {
                        revalidate(receipt.bindings(), request);
                    } catch (RuntimeException changed) {
                        propagateCancellation(request, changed);
                        if (request.strictFailures) {
                            throw changed;
                        }
                        if (leases[i] != null) {
                            leases[i].close();
                            leases[i] = null;
                        }
                        results.set(i, unavailable(results.get(i).tile(), Reason.CHANGED));
                    }
                }
            }
            request.check();
            transferred = true;
            return new BatchResult(results, leases);
        } finally {
            if (!transferred) {
                for (var lease : leases) {
                    if (lease != null) {
                        lease.close();
                    }
                }
            }
        }
    }

    private Outcome loadTile(
            Request request, int tileIndex, WorldFormats.Tile tile, FileBinding wdt) {
        if (tile.rootId() <= 0 || tile.tex0Id() <= 0) {
            throw new SourceFailure(Reason.ABSENT);
        }
        request.report(Stage.GRID, tileIndex, 0);
        var root = readBytes(tile.rootId(), 2 << 20, request);
        TerrainGrid grid;
        String geometry;
        try {
            grid = WorldFormats.adtGrid(root.bytes(), request::canceled);
            geometry = TerrainGeometryFingerprint.sha256(tile.x(), tile.y(), grid);
        } catch (IllegalArgumentException invalid) {
            throw new SourceFailure(Reason.ROOT_PROFILE);
        }
        request.report(Stage.TEXTURES, tileIndex, 0);
        var texture = readBytes(tile.tex0Id(), WorldTextureFormats.MAX_SOURCE_BYTES, request);
        try (var data = readTextureData(texture.bytes(), request)) {
            boolean[] used = new boolean[data.diffuseIds().size()];
            for (var chunk : data.chunks()) {
                if (chunk.issue() == WorldTextureFormats.ChunkIssue.NONE) {
                    for (var layer : chunk.layers()) {
                        used[(int) layer.textureIndex()] = true;
                    }
                }
            }
            var stats = new LinkedHashMap<Long, FileBinding>();
            var issues = new ArrayList<Issue>();
            for (int i = 0; i < used.length; i++) {
                if (used[i]) {
                    long id = data.diffuseIds().get(i);
                    if (stats.containsKey(id)) {
                        continue;
                    }
                    try {
                        stats.put(id, stat(id, 1 << 20, request));
                    } catch (RuntimeException missing) {
                        propagateCancellation(request, missing);
                        issues.add(
                                new Issue(
                                        reason(request, missing, Reason.DIFFUSE_PROFILE), -1, id));
                    }
                }
            }
            String key =
                    issues.isEmpty()
                            ? cacheKey(
                                    request.map.id(),
                                    tile,
                                    wdt,
                                    root.binding(),
                                    texture.binding(),
                                    stats.values().stream().toList(),
                                    geometry)
                            : null;
            if (key != null) {
                var cached = pool.cachedReceipt(key);
                if (cached.isPresent()) {
                    var hit = cached.get();
                    try {
                        revalidate(hit.receipt().bindings(), request);
                    } catch (RuntimeException changed) {
                        hit.lease().close();
                        throw changed;
                    }
                    return new Outcome(
                            new TileResult(tile, State.READY, hit.receipt(), 256, List.of()),
                            hit.lease());
                }
            }
            var reservation = pool.reserve();
            if (reservation.isEmpty()) {
                return new Outcome(
                        new TileResult(
                                tile,
                                State.POOL_BUSY,
                                null,
                                0,
                                List.of(new Issue(Reason.POOL_BUSY, -1, 0))),
                        null);
            }
            try (var reserved = reservation.get()) {
                var diffuse = new HashMap<Long, TerrainAtlasComposer.DiffuseMip>();
                var mipBindings = new ArrayList<MipBinding>();
                int index = 0;
                for (var binding : stats.values()) {
                    request.report(Stage.DIFFUSE, tileIndex, index++);
                    try {
                        var mip = decode(binding, request);
                        diffuse.put(binding.fileId(), mip.pixels());
                        mipBindings.add(mip.binding());
                    } catch (RuntimeException missing) {
                        propagateCancellation(request, missing);
                        issues.add(
                                new Issue(
                                        reason(request, missing, Reason.DIFFUSE_PROFILE),
                                        -1,
                                        binding.fileId()));
                    }
                }
                request.report(Stage.COMPOSING, tileIndex, 0);
                var composition =
                        TerrainAtlasComposer.compose(tile, grid, data, diffuse, request::canceled);
                for (var issue : composition.diagnostics()) {
                    issues.add(
                            new Issue(
                                    Reason.CHUNK,
                                    issue.ordinal(),
                                    0,
                                    issue.reason(),
                                    issue.maskIssue()));
                }
                if (composition.supportedChunks() == 0) {
                    return new Outcome(
                            new TileResult(tile, State.UNAVAILABLE, null, 0, issues), null);
                }
                var receipt =
                        new Receipt(
                                build,
                                locale,
                                request.map.id(),
                                tile,
                                wdt,
                                root.binding(),
                                texture.binding(),
                                mipBindings,
                                geometry,
                                key);
                revalidate(receipt.bindings(), request);
                request.check();
                boolean complete = composition.supportedChunks() == 256;
                var lease =
                        reserved.publish(
                                composition.atlas(),
                                complete ? key : null,
                                complete ? receipt : null);
                return new Outcome(
                        new TileResult(
                                tile,
                                complete ? State.READY : State.PARTIAL,
                                receipt,
                                composition.supportedChunks(),
                                issues),
                        lease);
            }
        }
    }

    private static WorldTextureFormats.TextureData readTextureData(byte[] bytes, Request request) {
        try {
            return WorldTextureFormats.read(bytes, request::canceled);
        } catch (IllegalArgumentException invalid) {
            throw new SourceFailure(Reason.TEXTURE_PROFILE);
        }
    }

    private record Decoded(TerrainAtlasComposer.DiffuseMip pixels, MipBinding binding) {}

    private Decoded decode(FileBinding before, Request request) {
        var opened = files.openRead(FileTypes.FileRef.id(before.fileId()), request.token).join();
        try (var stream = opened.stream()) {
            request.observeOpen(before.fileId(), opened);
            if (stream == null) {
                throw new SourceFailure(Reason.ABSENT);
            }
            validateStream(opened, stream.length(), before, 1 << 20, !stream.holes().isEmpty());
            var image =
                    Blp2Decoder.decodeLargestWithin(
                                    AssetSources.file(stream),
                                    4,
                                    new Blp2Types.Limits(1 << 20, 16384, 16, 1 << 20, 64),
                                    request.token,
                                    Blp2Types.MipProfile.DECLARED_HANDMADE_V1)
                            .join();
            request.check();
            if (image.width() != 4
                    || image.height() != 4
                    || image.baseWidth() != image.baseHeight()) {
                throw new SourceFailure(Reason.DIFFUSE_PROFILE);
            }
            FileBinding actual =
                    new FileBinding(
                            before.fileId(),
                            before.contentKey(),
                            stream.length(),
                            before.declaredSize(),
                            before.sourceLimit());
            revalidate(List.of(actual), request);
            var profile =
                    image.mipFlags() == 2
                            ? TerrainAtlasComposer.MipProfile.DECLARED_HANDMADE_V1
                            : TerrainAtlasComposer.MipProfile.STRICT;
            return new Decoded(
                    new TerrainAtlasComposer.DiffuseMip(
                            before.fileId(),
                            before.contentKey(),
                            profile,
                            image.baseWidth(),
                            image.mipLevel(),
                            image.rgba()),
                    new MipBinding(
                            actual,
                            image.baseWidth(),
                            image.mipLevel(),
                            image.mipFlags(),
                            image.encoding().name(),
                            image.alphaDepth(),
                            image.alphaEncoding()));
        }
    }

    private ReadSource readBytes(long id, int limit, Request request) {
        var before = stat(id, limit, request);
        var opened = files.openRead(FileTypes.FileRef.id(id), request.token).join();
        try (var stream = opened.stream()) {
            request.observeOpen(id, opened);
            if (stream == null) {
                throw new SourceFailure(Reason.ABSENT);
            }
            validateStream(opened, stream.length(), before, limit, !stream.holes().isEmpty());
            var target = ByteBuffer.allocate((int) stream.length());
            while (target.hasRemaining()) {
                request.check();
                int remaining = target.remaining();
                int n = stream.read(target, request.token).join();
                if (n <= 0 || remaining - target.remaining() != n) {
                    throw new SourceFailure(Reason.INCOMPLETE);
                }
            }
            var binding =
                    new FileBinding(
                            id,
                            before.contentKey(),
                            stream.length(),
                            before.declaredSize(),
                            before.sourceLimit());
            revalidate(List.of(binding), request);
            return new ReadSource(binding, target.array());
        }
    }

    private static void validateStream(
            FileTypes.FileOpen opened, long length, FileBinding before, int limit, boolean holes) {
        if (holes) {
            throw new SourceFailure(Reason.PROTECTED);
        }
        if (opened.state() != FileTypes.FileReadState.COMPLETE) {
            throw new SourceFailure(Reason.INCOMPLETE);
        }
        if (length < 12
                || length > limit
                || before.declaredSize() != null && before.declaredSize() != length) {
            throw new SourceFailure(Reason.LIMIT);
        }
    }

    private FileBinding stat(long id, int limit, Request request) {
        request.check();
        if (id <= 0 || id > 0xffff_ffffL) {
            throw new SourceFailure(Reason.UNBOUND);
        }
        var result = files.stat(FileTypes.FileRef.id(id), request.token).join();
        request.check();
        request.observeLookup(id, limit, result);
        if (result.state() != FileTypes.FileLookupState.FOUND || result.file() == null) {
            throw new SourceFailure(Reason.ABSENT);
        }
        var file = result.file();
        if (file.fileDataId() != id
                || file.contentKey() == null
                || !file.contentKey().matches("[0-9a-fA-F]{32}")) {
            throw new SourceFailure(Reason.UNBOUND);
        }
        if (file.size() != null && (file.size() < 12 || file.size() > limit)) {
            throw new SourceFailure(Reason.LIMIT);
        }
        if (!build.equals(files.build()) || !locale.equals(files.locale())) {
            throw new SourceFailure(Reason.CHANGED);
        }
        return new FileBinding(
                id,
                file.contentKey().toLowerCase(Locale.ROOT),
                file.size() == null ? -1 : file.size(),
                file.size(),
                limit);
    }

    private void revalidate(List<FileBinding> bindings, Request request) {
        for (var binding : bindings) {
            var current = stat(binding.fileId(), binding.sourceLimit(), request);
            if (!binding.contentKey().equals(current.contentKey())
                    || !Objects.equals(binding.declaredSize(), current.declaredSize())) {
                throw new SourceFailure(Reason.CHANGED);
            }
        }
    }

    private String cacheKey(
            long mapId,
            WorldFormats.Tile tile,
            FileBinding wdt,
            FileBinding root,
            FileBinding textures,
            List<FileBinding> diffuse,
            String geometry) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String text :
                    List.of(
                            REVISION,
                            TerrainTileAtlas.RECIPE,
                            TerrainGrid.REVISION,
                            WorldTextureFormats.REVISION,
                            Blp2Decoder.REVISION,
                            "DECLARED_HANDMADE_V1",
                            build.toString(),
                            locale,
                            Long.toString(mapId),
                            tile.toString(),
                            geometry)) {
                text(hash, text);
            }
            var all = new ArrayList<FileBinding>(3 + diffuse.size());
            all.add(wdt);
            all.add(root);
            all.add(textures);
            all.addAll(diffuse);
            for (var binding : all) {
                text(hash, Long.toString(binding.fileId()));
                text(hash, binding.contentKey());
                text(hash, String.valueOf(binding.declaredSize()));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(MessageDigest hash, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int shift = 0; shift < 32; shift += 8) {
            hash.update((byte) (bytes.length >>> shift));
        }
        hash.update(bytes);
    }

    private static TileResult unavailable(WorldFormats.Tile tile, Reason reason) {
        return new TileResult(tile, State.UNAVAILABLE, null, 0, List.of(new Issue(reason, -1, 0)));
    }

    private static Reason reason(Request request, RuntimeException failure, Reason fallback) {
        Throwable value = failure;
        for (int i = 0; i < 16 && value.getCause() != null; i++) {
            value = value.getCause();
        }
        if (value instanceof SourceFailure known) {
            if (request.strictFailures && known.reason == Reason.CHANGED) {
                throw failure;
            }
            return known.reason;
        }
        if (request.strictFailures && !(value instanceof BlpFormatException)) {
            throw failure;
        }
        return fallback;
    }

    static boolean ownedSourceChanged(Throwable failure) {
        return failure instanceof SourceFailure known && known.reason == Reason.CHANGED;
    }

    /** Rethrows a pure cancellation; a real failure, even one caused by a stop, stays itself. */
    private static void propagateCancellation(Request request, RuntimeException failure) {
        request.check();
        var stop = WorldSourceRefusals.cleanCancellation(failure);
        if (stop != null) {
            throw stop;
        }
    }
}
