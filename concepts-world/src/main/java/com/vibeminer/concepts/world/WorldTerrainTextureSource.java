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
import com.vibeminer.formats.world.WorldStageTimings;
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

    /** Why a diffuse was refused as {@link Reason#DIFFUSE_PROFILE}. */
    public enum DiffuseRefusal {
        NONE,
        /** The BLP decoder does not support the container, encoding, alpha or mip flags. */
        UNSUPPORTED,
        /** The BLP's header or mip descriptors are malformed. */
        INVALID,
        /** A decoder byte or pixel bound refused it. */
        LIMIT,
        /** No declared, non-compact mip is 4 pixels or smaller. */
        MIP_UNAVAILABLE,
        /** The base image is not square. */
        NON_SQUARE,
        /** The selected small mip is not 4 x 4. */
        MIP_SHAPE,
        /** A decoder refusal with a code this source does not name. */
        OTHER
    }

    public record Issue(
            Reason reason,
            int ordinal,
            long fileId,
            TerrainAtlasComposer.Reason chunkReason,
            WorldTextureFormats.ChunkIssue maskIssue,
            DiffuseRefusal diffuseRefusal) {
        public Issue(Reason reason, int ordinal, long fileId) {
            this(reason, ordinal, fileId, null, WorldTextureFormats.ChunkIssue.NONE);
        }

        public Issue(
                Reason reason,
                int ordinal,
                long fileId,
                TerrainAtlasComposer.Reason chunkReason,
                WorldTextureFormats.ChunkIssue maskIssue) {
            this(reason, ordinal, fileId, chunkReason, maskIssue, DiffuseRefusal.NONE);
        }

        public Issue {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(maskIssue);
            Objects.requireNonNull(diffuseRefusal);
            if (ordinal < -1
                    || ordinal >= 256
                    || fileId < 0
                    || fileId > 0xffff_ffffL
                    || (reason == Reason.CHUNK) != (chunkReason != null)
                    || diffuseRefusal != DiffuseRefusal.NONE && reason != Reason.DIFFUSE_PROFILE) {
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

    /**
     * {@code emptyChunks} have no texture layers, so nothing to draw. READY means every chunk is
     * drawn or empty, with at least one drawn.
     */
    public record TileResult(
            WorldFormats.Tile tile,
            State state,
            Receipt receipt,
            int supportedChunks,
            int emptyChunks,
            List<Issue> issues) {
        public TileResult(
                WorldFormats.Tile tile,
                State state,
                Receipt receipt,
                int supportedChunks,
                List<Issue> issues) {
            this(tile, state, receipt, supportedChunks, 0, issues);
        }

        public TileResult {
            Objects.requireNonNull(tile);
            Objects.requireNonNull(state);
            Objects.requireNonNull(issues);
            if (issues.size() > 320
                    || supportedChunks < 0
                    || supportedChunks > 256
                    || emptyChunks < 0
                    || supportedChunks + emptyChunks > 256
                    || ((state == State.READY || state == State.PARTIAL) != (receipt != null))
                    || (state == State.READY
                            && (supportedChunks == 0 || supportedChunks + emptyChunks != 256))
                    || (state == State.PARTIAL
                            && (supportedChunks == 0 || supportedChunks + emptyChunks == 256))
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
        final PreparedRoot preparedRoot;
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
            this(source, map, tiles, caller, progress, strictFailures, null);
        }

        Request(
                WorldTerrainTextureSource source,
                WorldService.MapInfo map,
                List<WorldFormats.Tile> tiles,
                Cancellation caller,
                Consumer<Progress> progress,
                boolean strictFailures,
                PreparedRoot preparedRoot) {
            this.source = source;
            this.map = map;
            this.tiles = tiles;
            this.caller = caller;
            this.progress = progress;
            this.strictFailures = strictFailures;
            this.preparedRoot = preparedRoot;
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

    /**
     * A root ADT the caller already read, parsed and fingerprinted from one checked admission. The
     * material read uses it instead of reading, parsing and hashing the same root again; the root
     * binding is still revalidated with every other source before publication.
     */
    record PreparedRoot(FileBinding binding, TerrainGrid grid, String geometrySha256) {
        PreparedRoot {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(grid);
            Objects.requireNonNull(geometrySha256);
            if (grid.chunks().size() != 256 || !geometrySha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid prepared root");
            }
        }
    }

    /**
     * Largest root ADT accepted, the same as the streaming geometry admission, so a root the
     * geometry draws is never refused for textures only because the material read it itself.
     */
    static final int ROOT_SOURCE_LIMIT = 8 << 20;

    private record CachedInventory(FileBinding binding, WorldTextureFormats.Inventory inventory) {}

    private static final int INVENTORY_CACHE = 4;

    // WDT inventories by map and WDT file, reused while the WDT's content key is unchanged.
    private final java.util.LinkedHashMap<String, CachedInventory> inventories =
            new java.util.LinkedHashMap<>(8, .75f, true);

    private record Outcome(TileResult metadata, TerrainAtlasPool.Lease lease) {}

    private static final class SourceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        final DiffuseRefusal diffuse;

        SourceFailure(Reason reason) {
            this(reason, DiffuseRefusal.NONE);
        }

        SourceFailure(Reason reason, DiffuseRefusal diffuse) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.diffuse = diffuse;
        }
    }

    /** The specific refusal behind a diffuse decode failure reported as DIFFUSE_PROFILE. */
    static DiffuseRefusal diffuseRefusal(Throwable failure) {
        Throwable value = failure;
        for (int i = 0; i < 16 && value != null; i++) {
            if (value instanceof SourceFailure known) {
                return known.diffuse;
            }
            if (value instanceof BlpFormatException blp) {
                return switch (String.valueOf(blp.code())) {
                    case "BLP_UNSUPPORTED" -> DiffuseRefusal.UNSUPPORTED;
                    case "BLP_INVALID" -> DiffuseRefusal.INVALID;
                    case "BLP_LIMIT" -> DiffuseRefusal.LIMIT;
                    case "BLP_MIP_UNAVAILABLE" -> DiffuseRefusal.MIP_UNAVAILABLE;
                    default -> DiffuseRefusal.OTHER;
                };
            }
            value = value.getCause();
        }
        return DiffuseRefusal.NONE;
    }

    private final BuildFiles files;
    private final TerrainAtlasPool pool;

    /**
     * Shader-side terrain splatting (issue #174 step 2), off until its renderer path ships: only
     * {@code -Dvimi.world.splat=true} composes the splatting inputs beside each atlas.
     */
    static volatile boolean splat = Boolean.getBoolean("vimi.world.splat");

    /**
     * Side of each tile's splat mask map, from the texture quality tier (issue #174 step 2b-2):
     * STANDARD 512 (every other alpha-map texel), HIGH 1024 (the native 64 x 64 per chunk). It
     * applies to tiles composed after it changes; each tile carries its own side.
     */
    static volatile int splatMask = com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;

    /**
     * Whole-file bound of a layer diffuse. The decoder reads only the header and the selected mip's
     * byte range (each mip read is bounded by {@code maxMipBytes}), so a large diffuse with a full
     * mip chain (a 1024 x 1024 BC3 chain is about 1.4 MB) no longer fails a 1 MiB file cap. The
     * decoded stream holds the BLTE chunk the selected mip lies in, so the transient cost per
     * diffuse is one decoded chunk: at most the file size, so at most this bound. Diffuses are
     * decoded one at a time.
     */
    static final int DIFFUSE_SOURCE_LIMIT = 8 << 20;

    // Layer diffuses decoded for shader-side splatting, shared by every tile of this source.
    final TerrainDiffuseDetails details = new TerrainDiffuseDetails();
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
        return beginRead(map, tiles, token, progress, true, null);
    }

    /** As above, reusing the caller's checked root parse and fingerprint for this tile. */
    Operation readOwned(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress,
            PreparedRoot root) {
        Objects.requireNonNull(tiles);
        Objects.requireNonNull(root);
        if (tiles.size() != 1 || tiles.getFirst().rootId() != root.binding().fileId()) {
            throw new IllegalArgumentException("Owned material operation requires its one root");
        }
        return beginRead(map, tiles, token, progress, true, root);
    }

    public CompletableFuture<BatchResult> read(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress) {
        return beginRead(map, tiles, token, progress, false, null).result();
    }

    private Operation beginRead(
            WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles,
            Cancellation token,
            Consumer<Progress> progress,
            boolean strictFailures,
            PreparedRoot preparedRoot) {
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
        var request =
                new Request(this, map, snapshot, token, progress, strictFailures, preparedRoot);
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
            FileBinding wdt;
            WorldTextureFormats.Inventory inventory;
            try {
                if (request.map.wdtId() == null || request.map.wdtId() <= 0) {
                    throw new SourceFailure(Reason.ABSENT);
                }
                var cached = inventory(request);
                wdt = cached.binding();
                inventory = cached.inventory();
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
                    loaded = loadTile(request, i, tile, wdt, inventory.alphaFormat());
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
            Request request,
            int tileIndex,
            WorldFormats.Tile tile,
            FileBinding wdt,
            WorldTextureFormats.AlphaFormat alphaFormat) {
        if (tile.rootId() <= 0 || tile.tex0Id() <= 0) {
            throw new SourceFailure(Reason.ABSENT);
        }
        request.report(Stage.GRID, tileIndex, 0);
        FileBinding root;
        TerrainGrid grid;
        String geometry;
        var prepared = request.preparedRoot;
        if (prepared != null && prepared.binding().fileId() == tile.rootId()) {
            root = prepared.binding();
            grid = prepared.grid();
            geometry = prepared.geometrySha256();
        } else {
            var read = readBytes(tile.rootId(), ROOT_SOURCE_LIMIT, request);
            root = read.binding();
            try {
                grid = WorldFormats.adtGrid(read.bytes(), request::canceled);
                geometry = TerrainGeometryFingerprint.sha256(tile.x(), tile.y(), grid);
            } catch (IllegalArgumentException invalid) {
                throw profileFailure(root, Reason.ROOT_PROFILE, request);
            }
        }
        request.report(Stage.TEXTURES, tileIndex, 0);
        var texture = readBytes(tile.tex0Id(), WorldTextureFormats.MAX_SOURCE_BYTES, request);
        try (var data = readTextureData(texture, alphaFormat, request)) {
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
                        stats.put(id, stat(id, DIFFUSE_SOURCE_LIMIT, request));
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
                                    root,
                                    texture.binding(),
                                    stats.values().stream().toList(),
                                    geometry)
                            : null;
            if (key != null) {
                var cached = pool.cachedReceipt(key);
                if (cached.isPresent()) {
                    WorldStageTimings.add("terrain.material.receiptCacheHits", 1);
                    var hit = cached.get();
                    try {
                        revalidate(hit.receipt().bindings(), request);
                    } catch (RuntimeException changed) {
                        hit.lease().close();
                        throw changed;
                    }
                    int[] counts = {0, 0};
                    hit.lease()
                            .withAtlas(
                                    atlas -> {
                                        counts[0] = atlas.supportedChunks();
                                        counts[1] = atlas.emptyChunks();
                                    });
                    // A complete cached atlas has no refusals, only chunks with no layers; name
                    // them again from this read's tex0 so diagnostics match a fresh composition.
                    var empty = new ArrayList<Issue>();
                    for (var chunk : data.chunks()) {
                        if (chunk.issue() == WorldTextureFormats.ChunkIssue.NO_LAYERS) {
                            empty.add(
                                    new Issue(
                                            Reason.CHUNK,
                                            chunk.ordinal(),
                                            0,
                                            TerrainAtlasComposer.Reason.TEXTURE_METADATA,
                                            WorldTextureFormats.ChunkIssue.NO_LAYERS));
                        }
                    }
                    return new Outcome(
                            new TileResult(
                                    tile, State.READY, hit.receipt(), counts[0], counts[1], empty),
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
                        var reason = reason(request, missing, Reason.DIFFUSE_PROFILE);
                        issues.add(
                                new Issue(
                                        reason,
                                        -1,
                                        binding.fileId(),
                                        null,
                                        WorldTextureFormats.ChunkIssue.NONE,
                                        reason == Reason.DIFFUSE_PROFILE
                                                ? diffuseRefusal(missing)
                                                : DiffuseRefusal.NONE));
                    }
                }
                request.report(Stage.COMPOSING, tileIndex, 0);
                long composing = WorldStageTimings.start();
                var composition =
                        TerrainAtlasComposer.compose(
                                tile, grid, data, diffuse, splat, splatMask, request::canceled);
                WorldStageTimings.stop("terrain.material.atlasCompose", composing);
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
                                root,
                                texture.binding(),
                                mipBindings,
                                geometry,
                                key);
                // Every source this tile read, checked once before publication; reads and
                // decodes no longer revalidate one by one.
                revalidate(receipt.bindings(), request);
                request.check();
                boolean complete = composition.atlas().complete();
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
                                composition.atlas().emptyChunks(),
                                issues),
                        lease);
            }
        }
    }

    private WorldTextureFormats.TextureData readTextureData(
            ReadSource texture, WorldTextureFormats.AlphaFormat alphaFormat, Request request) {
        long started = WorldStageTimings.start();
        try {
            return WorldTextureFormats.adopt(texture.bytes(), alphaFormat, request::canceled);
        } catch (IllegalArgumentException invalid) {
            throw profileFailure(texture.binding(), Reason.TEXTURE_PROFILE, request);
        } finally {
            WorldStageTimings.stop("terrain.material.tex0Parse", started);
        }
    }

    /**
     * Reads no longer revalidate one by one, so bytes that fail to parse may come from a source
     * that changed after its stat. Report that as CHANGED, not as a profile refusal.
     */
    private SourceFailure profileFailure(FileBinding binding, Reason profile, Request request) {
        revalidate(List.of(binding), request);
        return new SourceFailure(profile);
    }

    private record Decoded(TerrainAtlasComposer.DiffuseMip pixels, MipBinding binding) {}

    private Decoded decode(FileBinding before, Request request) {
        long started = WorldStageTimings.start();
        try {
            return decodeTimed(before, request);
        } finally {
            WorldStageTimings.stop("terrain.material.diffuseReadDecode", started);
        }
    }

    private Decoded decodeTimed(FileBinding before, Request request) {
        var opened = files.openRead(FileTypes.FileRef.id(before.fileId()), request.token).join();
        try (var stream = opened.stream()) {
            request.observeOpen(before.fileId(), opened);
            if (stream == null) {
                throw new SourceFailure(Reason.ABSENT);
            }
            validateStream(
                    opened,
                    stream.length(),
                    before,
                    DIFFUSE_SOURCE_LIMIT,
                    !stream.holes().isEmpty());
            var image =
                    Blp2Decoder.decodeLargestWithin(
                                    AssetSources.file(stream),
                                    4,
                                    new Blp2Types.Limits(
                                            DIFFUSE_SOURCE_LIMIT, 16384, 16, 1 << 20, 64),
                                    request.token,
                                    Blp2Types.MipProfile.DECLARED_HANDMADE_V1)
                            .join();
            request.check();
            if (image.baseWidth() != image.baseHeight()) {
                throw new SourceFailure(Reason.DIFFUSE_PROFILE, DiffuseRefusal.NON_SQUARE);
            }
            if (image.width() != 4 || image.height() != 4) {
                throw new SourceFailure(Reason.DIFFUSE_PROFILE, DiffuseRefusal.MIP_SHAPE);
            }
            // Revalidated with the tile's other sources before publication.
            FileBinding actual =
                    new FileBinding(
                            before.fileId(),
                            before.contentKey(),
                            stream.length(),
                            before.declaredSize(),
                            before.sourceLimit());
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

    /**
     * One layer diffuse as its {@link com.vibeminer.rendering.world.TerrainLayerBlocks} BC1 chain,
     * for shader-side splatting (issue #174 step 2). A DXT file of side at least 256 that records
     * its levels from 256 down to 4 x 4 is carried as its own blocks; any other diffuse is decoded
     * at its largest declared mip within 256 and encoded. Same file bounds and revalidation as the
     * coverage preview's 4 x 4 mip; the result is cached by file id and content key.
     */
    TerrainDiffuseDetails.Detail detail(FileBinding before, Request request) {
        var cached = details.get(before.fileId(), before.contentKey());
        if (cached != null) {
            WorldStageTimings.add("terrain.material.detailCacheHits", 1);
            return cached;
        }
        long started = WorldStageTimings.start();
        try {
            return detailTimed(before, request);
        } finally {
            WorldStageTimings.stop("terrain.material.detailReadDecode", started);
        }
    }

    private TerrainDiffuseDetails.Detail detailTimed(FileBinding before, Request request) {
        var opened = files.openRead(FileTypes.FileRef.id(before.fileId()), request.token).join();
        try (var stream = opened.stream()) {
            request.observeOpen(before.fileId(), opened);
            if (stream == null) {
                throw new SourceFailure(Reason.ABSENT);
            }
            validateStream(
                    opened,
                    stream.length(),
                    before,
                    DIFFUSE_SOURCE_LIMIT,
                    !stream.holes().isEmpty());
            var source = AssetSources.file(stream);
            String[] encoding = new String[1];
            byte[] chain = layerBlocks(source, request, encoding);
            boolean fileBlocks = chain != null;
            if (chain == null) {
                int side = com.vibeminer.rendering.world.TerrainLayerBlocks.SIDE;
                var image =
                        Blp2Decoder.decodeLargestWithin(
                                        source,
                                        side,
                                        // Decoded pixels and bytes bounded by one 256 x 256 layer.
                                        new Blp2Types.Limits(
                                                DIFFUSE_SOURCE_LIMIT,
                                                16384,
                                                side * side,
                                                1 << 20,
                                                side * side * 4L),
                                        request.token,
                                        Blp2Types.MipProfile.DECLARED_HANDMADE_V1)
                                .join();
                request.check();
                int decoded = image.width();
                if (decoded != image.height()
                        || decoded > side
                        || Integer.bitCount(decoded) != 1
                        || image.baseWidth() != image.baseHeight()
                        || image.rowStride() != decoded * 4) {
                    throw new SourceFailure(Reason.DIFFUSE_PROFILE);
                }
                chain =
                        com.vibeminer.rendering.world.TerrainLayerBlocks.encode(
                                java.nio.ByteBuffer.wrap(image.rgba()), decoded);
                encoding[0] = image.encoding().name();
            }
            request.check();
            revalidate(
                    List.of(
                            new FileBinding(
                                    before.fileId(),
                                    before.contentKey(),
                                    stream.length(),
                                    before.declaredSize(),
                                    before.sourceLimit())),
                    request);
            return details.put(
                    new TerrainDiffuseDetails.Detail(
                            before.fileId(), before.contentKey(), chain, fileBlocks, encoding[0]));
        }
    }

    /**
     * The layer chain from the file's own DXT blocks, or null when the file has none to carry: not
     * DXT, not square, smaller than 256, or not recording every level from 256 down to 4 x 4 in
     * exact storage. Reads only the header, the mip table and those levels' byte ranges.
     */
    private static byte[] layerBlocks(
            com.vibeminer.formats.AssetSource source, Request request, String[] encoding) {
        int side = com.vibeminer.rendering.world.TerrainLayerBlocks.SIDE;
        try {
            byte[] head = source.readAt(0, Blp2Decoder.HEADER_BYTES, request.token).join();
            request.check();
            var header = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (head.length != Blp2Decoder.HEADER_BYTES
                    || header.getInt(0) != 0x32504c42
                    || header.getInt(4) != 1
                    || (head[8] & 255) != 2) {
                return null;
            }
            int format =
                    switch (head[10] & 255) {
                        case 0 -> com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
                        case 1 -> com.vibeminer.rendering.world.ObjectTextureFormats.BC2;
                        case 7 -> com.vibeminer.rendering.world.ObjectTextureFormats.BC3;
                        default -> -1;
                    };
            long width = Integer.toUnsignedLong(header.getInt(12));
            long height = Integer.toUnsignedLong(header.getInt(16));
            if (format < 0
                    || width != height
                    || width < side
                    || width > 16384
                    || Long.bitCount(width) != 1) {
                return null;
            }
            int top = Long.numberOfTrailingZeros(width / side);
            var mips =
                    Blp2Decoder.readMipsSynchronous(
                            source,
                            new Blp2Types.Limits(
                                    DIFFUSE_SOURCE_LIMIT, 16384, 16384L * 16384, 1 << 20, 64),
                            request.token,
                            Blp2Types.MipProfile.DECLARED_HANDMADE_V1);
            var levels = new java.util.ArrayList<byte[]>();
            for (int k = 0; k < com.vibeminer.rendering.world.TerrainLayerBlocks.LEVELS; k++) {
                int levelSide = com.vibeminer.rendering.world.TerrainLayerBlocks.side(k);
                int bytes =
                        com.vibeminer.rendering.world.ObjectTextureFormats.levelBytes(
                                format, levelSide, levelSide, 0);
                Blp2Types.Mip mip = null;
                for (var candidate : mips) {
                    if (candidate.level() == top + k) {
                        mip = candidate;
                    }
                }
                if (mip == null
                        || mip.width() != levelSide
                        || mip.height() != levelSide
                        || mip.storage() != Blp2Types.MipStorage.EXACT
                        || mip.byteLength() != bytes) {
                    if (k < 7) {
                        return null;
                    }
                    break;
                }
                request.check();
                byte[] level = source.readAt(mip.offset(), bytes, request.token).join();
                if (level.length != bytes) {
                    return null;
                }
                levels.add(level);
            }
            var chain =
                    com.vibeminer.rendering.world.TerrainLayerBlocks.fromBlocks(
                            format, levels.toArray(byte[][]::new));
            encoding[0] = new String[] {"RGBA8", "BC1", "BC2", "BC3"}[format];
            return chain;
        } catch (com.vibeminer.formats.images.BlpFormatException
                | IllegalArgumentException
                | java.util.concurrent.CompletionException unusable) {
            propagateCancellation(request, unusable);
            return null;
        }
    }

    private ReadSource readBytes(long id, int limit, Request request) {
        return readBytes(stat(id, limit, request), request);
    }

    /** Reads one stat'ed source; the caller revalidates it with the tile's other sources. */
    private ReadSource readBytes(FileBinding before, Request request) {
        long started = WorldStageTimings.start();
        try {
            return readBytesTimed(before, request);
        } finally {
            WorldStageTimings.stop("terrain.material.read", started);
        }
    }

    private ReadSource readBytesTimed(FileBinding before, Request request) {
        long id = before.fileId();
        int limit = before.sourceLimit();
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
            return new ReadSource(binding, target.array());
        }
    }

    /**
     * The map's WDT inventory. A cached inventory is reused only while a fresh stat reports the
     * same content key and declared size; otherwise the WDT is read and parsed again.
     */
    private CachedInventory inventory(Request request) {
        long id = request.map.wdtId();
        String key = request.map.id() + ":" + id;
        var before = stat(id, 1 << 20, request);
        synchronized (inventories) {
            var cached = inventories.get(key);
            if (cached != null
                    && cached.binding().contentKey().equals(before.contentKey())
                    && Objects.equals(cached.binding().declaredSize(), before.declaredSize())) {
                return cached;
            }
        }
        var wdt = readBytes(before, request);
        WorldTextureFormats.Inventory parsed;
        try {
            parsed = WorldTextureFormats.inventory(wdt.bytes(), request::canceled);
        } catch (IllegalArgumentException invalid) {
            throw profileFailure(wdt.binding(), Reason.INVENTORY_PROFILE, request);
        }
        // Checked like every other source before this request uses or caches it.
        revalidate(List.of(wdt.binding()), request);
        var fresh = new CachedInventory(wdt.binding(), parsed);
        synchronized (inventories) {
            inventories.put(key, fresh);
            if (inventories.size() > INVENTORY_CACHE) {
                var eldest = inventories.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
        return fresh;
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
        // Index facts only (state, id, size, content key): no listfile names or format sniff.
        var result = files.statContent(FileTypes.FileRef.id(id), request.token).join();
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
