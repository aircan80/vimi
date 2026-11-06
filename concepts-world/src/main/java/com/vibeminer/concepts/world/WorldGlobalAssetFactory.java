package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WmoGroupSpans;
import com.vibeminer.rendering.world.GlobalInteriorLimits;
import com.vibeminer.rendering.world.WmoMaterialClass;
import com.vibeminer.rendering.world.WorldObjectPicking;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** G1 source/ancestry/material inspection only. No mesh, image payload, packet or public facade. */
final class WorldGlobalAssetFactory {
    static final String PROFILE = "GLOBAL_UNLIT_MATERIALS_V3";
    private static final WmoFormats.Limits ROOT_LIMITS =
            new WmoFormats.Limits(
                    GlobalInteriorLimits.ROOT_BYTES,
                    GlobalInteriorLimits.GROUP_BYTES,
                    GlobalInteriorLimits.GROUPS,
                    GlobalInteriorLimits.MATERIALS,
                    4096,
                    GlobalInteriorLimits.GROUP_VERTICES,
                    GlobalInteriorLimits.GROUP_TRIANGLES,
                    GlobalInteriorLimits.GROUP_BATCHES,
                    1,
                    GlobalInteriorLimits.ROOT_BYTES);

    /** Source images up to 1024 on a side; anything over 256 is drawn from a smaller mip. */
    private static final int SOURCE_IMAGE_SIDE = 1024;

    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(
                    GlobalInteriorLimits.IMAGE_BYTES,
                    SOURCE_IMAGE_SIDE,
                    (long) SOURCE_IMAGE_SIDE * SOURCE_IMAGE_SIDE,
                    GlobalInteriorLimits.IMAGE_BYTES,
                    GlobalInteriorLimits.IMAGE_PIXELS * 4L);

    record Material(int ordinal, long texture, List<Long> rawWords) {
        Material {
            rawWords = List.copyOf(rawWords);
        }
    }

    record RootFacts(
            WmoFormats.RootHeader header,
            List<Material> materials,
            List<Long> groups,
            List<WmoFormats.Chunk> chunks,
            WmoFormats.GroupNames groupNames) {
        RootFacts {
            Objects.requireNonNull(groupNames);
            materials = List.copyOf(materials);
            groups = List.copyOf(groups);
            chunks = List.copyOf(chunks);
        }
    }

    record GroupFacts(
            int ordinal,
            long id,
            long flags,
            int vertices,
            int rawTriangles,
            int drawnTriangles,
            int batches,
            boolean colors,
            boolean liquid,
            int liquidBytes,
            String canonicalMeshSha256,
            WmoFormats.GroupHeader header,
            String name,
            List<Integer> materials,
            List<Integer> drawnBatches,
            int blendedBatches) {
        GroupFacts {
            materials = List.copyOf(materials);
            drawnBatches = List.copyOf(drawnBatches);
        }
    }

    /**
     * Whether a batch's material is left out (issue #297): its blend mode is above additive
     * (modulate and its variants, not pinned; {@link WmoMaterialClass}), or it is an alpha-blended
     * or additive surface without a texture. Opaque, alpha-key, alpha-blend and additive materials
     * draw with the same mapping as the outdoor objects; left-out batches are counted.
     */
    static boolean leftOut(Material material) {
        var words = material.rawWords();
        int rangeClass = WmoMaterialClass.of(words.get(0), words.get(2));
        return rangeClass == WmoMaterialClass.UNSUPPORTED
                || WorldObjectPicking.kind(rangeClass) >= WorldObjectPicking.BLENDED
                        && material.texture() <= 0;
    }

    /**
     * The recorded MOGP bounds in the same native space as the copied mesh (x and y mirrored, z
     * kept), as min xyz then max xyz. Qualification already proved every group vertex is inside.
     */
    static float[] nativeBounds(WmoFormats.GroupHeader header) {
        var lower = header.lower();
        var upper = header.upper();
        return new float[] {
            nativeAxis(0, upper.x()),
            nativeAxis(1, upper.y()),
            lower.z(),
            nativeAxis(0, lower.x()),
            nativeAxis(1, lower.y()),
            upper.z()
        };
    }

    /** WMO-local to native axis: x and y are mirrored and z is kept; -0 is normalized. */
    static float nativeAxis(int axis, float value) {
        float nativeValue = axis == 2 ? value : -value;
        return nativeValue == 0 ? 0f : nativeValue;
    }

    record ImageFacts(
            long id,
            int width,
            int height,
            Blp2Types.Encoding encoding,
            int alphaDepth,
            int alphaEncoding,
            int mipFlags,
            int mipCount,
            int alphaMin,
            int alphaMax,
            int nonopaque,
            String sourceRgbaSha256,
            String opaqueRgbaSha256) {}

    /** Immutable descriptions only. This manifest is never accepted as input to a renderable. */
    static final class Manifest {
        private final WorldGlobalScopes.Facts declaration;
        private final RootFacts root;
        private final List<GroupFacts> groups;
        private final List<ImageFacts> images;
        private final List<WorldGlobalScopes.AssetReceipt> receipts;
        private final int vertices, rawTriangles, drawnTriangles, batches, pixels, skippedGroups;
        private final java.util.SortedMap<String, Integer> skippedReasons;

        private Manifest(
                WorldGlobalScopes.Facts declaration,
                RootFacts root,
                List<GroupFacts> groups,
                List<ImageFacts> images,
                List<WorldGlobalScopes.AssetReceipt> receipts,
                java.util.SortedMap<String, Integer> skippedReasons) {
            int skippedGroups = skippedReasons.values().stream().mapToInt(Integer::intValue).sum();
            require(
                    !groups.isEmpty()
                            && groups.size() <= GlobalInteriorLimits.GROUPS
                            && images.size() <= GlobalInteriorLimits.IMAGES
                            && receipts.size() <= GlobalInteriorLimits.ASSETS
                            && skippedGroups >= 0
                            && groups.size() + skippedGroups == root.groups().size());
            this.skippedGroups = skippedGroups;
            this.skippedReasons =
                    java.util.Collections.unmodifiableSortedMap(
                            new java.util.TreeMap<>(skippedReasons));
            this.declaration = declaration;
            this.root = root;
            this.groups = List.copyOf(groups);
            this.images = List.copyOf(images);
            this.receipts = List.copyOf(receipts);
            vertices = groups.stream().mapToInt(GroupFacts::vertices).sum();
            rawTriangles = groups.stream().mapToInt(GroupFacts::rawTriangles).sum();
            drawnTriangles = groups.stream().mapToInt(GroupFacts::drawnTriangles).sum();
            batches = groups.stream().mapToInt(GroupFacts::batches).sum();
            pixels = images.stream().mapToInt(value -> value.width() * value.height()).sum();
            require(
                    vertices <= GlobalInteriorLimits.VERTICES
                            && rawTriangles <= GlobalInteriorLimits.TRIANGLES
                            && batches <= GlobalInteriorLimits.BATCHES
                            && pixels <= GlobalInteriorLimits.PIXELS);
        }

        /** Root groups that could not be read or drawn and are left out of the interior. */
        int skippedGroups() {
            return skippedGroups;
        }

        /**
         * Batches of drawn groups left out ({@link #leftOut}): unsupported blend modes, or blended
         * surfaces without a texture.
         */
        int skippedBlendedBatches() {
            return groups.stream().mapToInt(GroupFacts::blendedBatches).sum();
        }

        /** Skipped groups by fixed reason text. */
        java.util.SortedMap<String, Integer> skippedReasons() {
            return skippedReasons;
        }

        WorldGlobalScopes.Facts declaration() {
            return declaration;
        }

        RootFacts root() {
            return root;
        }

        List<GroupFacts> groups() {
            return groups;
        }

        List<ImageFacts> images() {
            return images;
        }

        List<WorldGlobalScopes.AssetReceipt> receipts() {
            return receipts;
        }

        int vertices() {
            return vertices;
        }

        int rawTriangles() {
            return rawTriangles;
        }

        int drawnTriangles() {
            return drawnTriangles;
        }

        int batches() {
            return batches;
        }

        int pixels() {
            return pixels;
        }

        int consumedBindings() {
            return receipts.size() + 1;
        }
    }

    static final class Borrow {
        private Manifest manifest;
        private Cancellation token;
        private final long thread = Thread.currentThread().threadId();

        private Borrow(Manifest manifest, Cancellation token) {
            this.manifest = manifest;
            this.token = token;
        }

        Manifest manifest() {
            if (manifest == null || Thread.currentThread().threadId() != thread) {
                throw new IllegalStateException("Expired or foreign global manifest borrow");
            }
            return manifest;
        }

        private void expire() {
            manifest = null;
            token = null;
        }
    }

    @FunctionalInterface
    interface SourceManifestUse<R extends AutoCloseable> {
        R use(Borrow borrow);
    }

    record DrawSpan(int group, int firstIndex, int indexCount, int material) {}

    @FunctionalInterface
    interface AssetRead<T> {
        T read(AssetBorrow borrow);
    }

    @FunctionalInterface
    interface AssetWriter<R extends AutoCloseable> {
        R writeUnadvertised(AssetBorrow borrow, Cancellation token);
    }

    /** Worker-only owned resource, retained by the actual source payload through final gates. */
    static final class WrittenAsset<R extends AutoCloseable> implements AutoCloseable {
        private final OwnedAsset asset;

        private WrittenAsset(OwnedAsset asset) {
            this.asset = asset;
        }

        boolean isOpen() {
            return asset.isOpen();
        }

        Manifest facts() {
            return asset.facts();
        }

        <T> T withBorrow(AssetRead<T> read) {
            return asset.withBorrow(read);
        }

        void withWrittenResource(java.util.function.Consumer<R> moveOnly) {
            Objects.requireNonNull(moveOnly);
            asset.withBorrow(
                    borrow -> {
                        @SuppressWarnings("unchecked")
                        R resource = (R) Objects.requireNonNull(asset.written);
                        moveOnly.accept(resource);
                        return null;
                    });
        }

        @Override
        public void close() {
            asset.close();
        }

        java.util.concurrent.CompletableFuture<Void> closeAsync() {
            return asset.closeAsync();
        }
    }

    /** Private source owner. All resource-bearing construction, callbacks and close run off FX. */
    static final class OwnedAsset implements AutoCloseable {
        private WorldGlobalScopes.PayloadCredit credit;
        private final java.util.concurrent.CompletableFuture<Void> drained =
                new java.util.concurrent.CompletableFuture<>();
        private float[] mesh;
        private int[] indices;
        private final ArrayList<byte[]> images = new ArrayList<>();
        private final ArrayList<DrawSpan> spans = new ArrayList<>();
        private Manifest manifest;
        private int vertices, indexCount, pixels, pins = 1; // Actual construction pin.
        private boolean closing, qualified, retiring;
        private AutoCloseable written;

        private OwnedAsset(WorldGlobalScopes.PayloadCredit credit) {
            this.credit = credit;
        }

        private void allocateMesh() {
            // Starts at 16Ki vertices and grows by doubling up to the profile bounds; most
            // interiors stay far below them.
            mesh = new float[16384 * 5];
            indices = new int[16384 * 3];
        }

        private void appendGroup(
                int ordinal, WmoGroupSpans.Borrow raw, GroupFacts facts, Cancellation token) {
            int count = facts.vertices(), added = facts.rawTriangles() * 3;
            require(
                    (long) vertices + count <= GlobalInteriorLimits.VERTICES
                            && (long) indexCount + added <= GlobalInteriorLimits.INDICES);
            if ((vertices + count) * 5 > mesh.length) {
                mesh =
                        java.util.Arrays.copyOf(
                                mesh,
                                Math.min(
                                        GlobalInteriorLimits.VERTICES * 5,
                                        Math.max(mesh.length * 2, (vertices + count) * 5)));
            }
            if (indexCount + added > indices.length) {
                indices =
                        java.util.Arrays.copyOf(
                                indices,
                                Math.min(
                                        GlobalInteriorLimits.INDICES,
                                        Math.max(indices.length * 2, indexCount + added)));
            }
            for (int v = 0; v < count; v++) {
                token.check();
                int target = (vertices + v) * 5;
                for (int axis = 0; axis < 3; axis++) {
                    float value = raw.positions().get(v * 3 + axis);
                    require(
                            Float.isFinite(value)
                                    && Math.abs(value) <= GlobalInteriorLimits.POSITION);
                    mesh[target + axis] = nativeAxis(axis, value);
                }
                for (int axis = 0; axis < 2; axis++) {
                    float uv = raw.uv().get(v * 2 + axis);
                    require(Float.isFinite(uv) && Math.abs(uv) <= GlobalInteriorLimits.UV);
                    mesh[target + 3 + axis] = uv;
                }
            }
            for (int i = 0; i < added; i++) {
                if ((i & 4095) == 0) {
                    token.check();
                }
                int local = raw.indices().get(i);
                require(local < count);
                indices[indexCount + i] = Math.addExact(vertices, local);
            }
            require(spans.size() + facts.batches() <= GlobalInteriorLimits.BATCHES);
            for (int drawn : facts.drawnBatches()) {
                var batch = raw.batches().get(drawn);
                spans.add(
                        new DrawSpan(
                                ordinal,
                                Math.addExact(indexCount, (int) batch.firstIndex()),
                                batch.indexCount(),
                                batch.materialIndex()));
            }
            vertices += count;
            indexCount += added;
        }

        private void adoptImage(byte[] rgba) {
            require(
                    images.size() < GlobalInteriorLimits.IMAGES
                            && rgba.length % 4 == 0
                            && (long) pixels + rgba.length / 4 <= GlobalInteriorLimits.PIXELS);
            images.add(rgba); // Exactly the synchronous decoder getter copy; no clone.
            pixels += rgba.length / 4;
        }

        synchronized void publish() {
            if (closing || manifest == null || mesh == null || indices == null || pins != 1) {
                throw new IllegalStateException("Global payload not publishable");
            }
            if (vertices != manifest.vertices()
                    || indexCount != manifest.rawTriangles() * 3
                    || images.size() != manifest.images().size()
                    || pixels != manifest.pixels()
                    || spans.size() != manifest.batches()) {
                throw new IllegalStateException("Global payload counts differ");
            }
            qualified = true;
        }

        synchronized void pin() {
            if (closing || !qualified || credit == null) {
                throw new IllegalStateException("Global payload closed");
            }
            if (pins != 0) {
                throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_CAPACITY);
            }
            pins++;
        }

        synchronized boolean available() {
            return qualified && !closing && !retiring && credit != null;
        }

        boolean isOpen() {
            WorldGlobalScopes.PayloadCredit source;
            synchronized (this) {
                source = credit;
            }
            return source != null && source.isOpen(this);
        }

        Manifest facts() {
            synchronized (this) {
                if (!qualified) {
                    throw new IllegalStateException("Global payload never qualified");
                }
                return manifest;
            }
        }

        <T> T withBorrow(AssetRead<T> read) {
            Objects.requireNonNull(read);
            WorldGlobalScopes.PayloadCredit source;
            synchronized (this) {
                source = credit;
            }
            if (source == null) {
                throw new IllegalStateException("Global payload closed");
            }
            source.pin(this); // Source monitor first, then the fixed pin metadata method.
            AssetBorrow borrow = null;
            try {
                borrow = new AssetBorrow(this);
                return read.read(borrow);
            } finally {
                if (borrow != null) {
                    borrow.expire();
                }
                releasePin();
            }
        }

        private void releasePin() {
            synchronized (this) {
                if (pins <= 0) {
                    throw new IllegalStateException("Global payload pin underflow");
                }
                pins--;
            }
            retireIfReady();
        }

        private void retireIfReady() {
            WorldGlobalScopes.PayloadCredit source;
            AutoCloseable resource;
            synchronized (this) {
                if (!closing || pins != 0 || credit == null || retiring) {
                    return;
                }
                retiring = true;
                source = credit;
                resource = written;
            }
            Throwable failure = null;
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Exception | Error cleanup) {
                failure = cleanup;
            }
            synchronized (this) {
                mesh = null;
                indices = null;
                images.clear();
                spans.clear();
                credit = null;
                if (failure == null) {
                    written = null;
                } // Failed resource stays retained in the source quarantine.
            }
            source.retired(failure);
            if (failure == null) {
                drained.complete(null);
            } else {
                drained.completeExceptionally(failure);
                throw resourceFailure(failure);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                closing = true;
            }
            retireIfReady();
        }

        java.util.concurrent.CompletableFuture<Void> closeAsync() {
            close();
            return drained.copy();
        }
    }

    /** Scalar accesses only. Saved borrows detach the asset on expiry. */
    static final class AssetBorrow {
        private OwnedAsset asset;
        private final long thread = Thread.currentThread().threadId();

        private AssetBorrow(OwnedAsset asset) {
            this.asset = asset;
        }

        private OwnedAsset checked() {
            if (asset == null || Thread.currentThread().threadId() != thread) {
                throw new IllegalStateException("Expired or foreign global payload borrow");
            }
            return asset;
        }

        int vertices() {
            return checked().vertices;
        }

        int indices() {
            return checked().indexCount;
        }

        int imageCount() {
            return checked().images.size();
        }

        float vertex(int vertex, int component) {
            var a = checked();
            Objects.checkIndex(vertex, a.vertices);
            Objects.checkIndex(component, 5);
            return a.mesh[vertex * 5 + component];
        }

        int index(int index) {
            var a = checked();
            return a.indices[Objects.checkIndex(index, a.indexCount)];
        }

        int imageByte(int image, int offset) {
            var a = checked();
            var bytes = a.images.get(Objects.checkIndex(image, a.images.size()));
            return bytes[Objects.checkIndex(offset, bytes.length)] & 255;
        }

        int imageBytes(int image) {
            var a = checked();
            return a.images.get(Objects.checkIndex(image, a.images.size())).length;
        }

        List<DrawSpan> spans() {
            return List.copyOf(checked().spans);
        }

        Manifest facts() {
            return checked().manifest;
        }

        private void expire() {
            asset = null;
        }
    }

    private final WorldGlobalScopes scopes;

    WorldGlobalAssetFactory(WorldGlobalScopes scopes) {
        this.scopes = Objects.requireNonNull(scopes);
    }

    OwnedAsset createOwned(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external) {
        return createOwned(scope, selection, external, null);
    }

    <R extends AutoCloseable> WrittenAsset<R> createAndWrite(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external,
            AssetWriter<R> writer) {
        Objects.requireNonNull(writer);
        var owned = createOwned(scope, selection, external, writer);
        try {
            return new WrittenAsset<>(owned);
        } catch (RuntimeException | Error failure) {
            try {
                owned.close();
            } catch (RuntimeException | Error cleanup) {
                throw cleanupFailure(failure, cleanup);
            }
            throw failure;
        }
    }

    private OwnedAsset createOwned(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external,
            AssetWriter<?> writer) {
        Objects.requireNonNull(selection);
        Objects.requireNonNull(external);
        var credit =
                scopes.reservePayload(scope); // Shared source credit BEFORE any build IO/arrays.
        OwnedAsset asset = null;
        boolean published = false;
        Throwable primary = null;
        try {
            asset = new OwnedAsset(credit);
            scopes.attachPayload(credit, asset);
            var building = asset;
            var checked =
                    inspect(
                            scope,
                            selection,
                            external,
                            borrow -> {
                                building.manifest = borrow.manifest();
                                if (writer != null) {
                                    var payload = new AssetBorrow(building);
                                    try {
                                        // Construction pin remains charged through all final IO.
                                        building.written =
                                                Objects.requireNonNull(
                                                        writer.writeUnadvertised(
                                                                payload, borrow.token));
                                    } finally {
                                        payload.expire();
                                    }
                                }
                                return building;
                            },
                            building);
            scopes.publishPayload(credit, checked);
            published = true;
            return checked;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (asset == null) {
                    credit.retired();
                } else {
                    if (!published) {
                        asset.close();
                    }
                    asset.releasePin(); // Final checks and actual lower cleanup precede retirement.
                }
            } catch (RuntimeException | Error cleanup) {
                if (primary != null) {
                    throw cleanupFailure(primary, cleanup);
                }
                throw cleanup;
            }
        }
    }

    private static RuntimeException resourceFailure(Throwable failure) {
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        return failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Global resource cleanup failed", failure);
    }

    private static IllegalStateException cleanupFailure(Throwable primary, Throwable cleanup) {
        var failure = new IllegalStateException("Global output cleanup failed", primary);
        if (primary != cleanup) {
            failure.addSuppressed(cleanup);
        }
        return failure;
    }

    <R extends AutoCloseable> R inspectSources(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external,
            SourceManifestUse<R> use) {
        return inspect(scope, selection, external, use, null);
    }

    private <R extends AutoCloseable> R inspect(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external,
            SourceManifestUse<R> use,
            OwnedAsset payload) {
        Objects.requireNonNull(use);
        return scopes.withAssetInputs(
                scope,
                selection,
                external,
                admission -> {
                    var token = admission.cancellation();
                    require(
                            admission.facts().declaration().reference()
                                    != admission.facts().wdtId());
                    // Only private parse results from this operation select descendant candidates.
                    var rootBox = new RootFacts[1];
                    admission.root(
                            input -> rootBox[0] = qualifyRoot(input, admission.facts(), token));
                    RootFacts root = rootBox[0];
                    rootBox[0] = null;
                    if (payload != null) {
                        payload.allocateMesh();
                    }
                    var groups = new ArrayList<GroupFacts>(root.groups().size());
                    long[] totals = new long[3];
                    var skipped = new java.util.TreeMap<String, Integer>();
                    for (int ordinal = 0; ordinal < root.groups().size(); ordinal++) {
                        token.check();
                        long id = root.groups().get(ordinal);
                        if (id == 0) {
                            skipped.merge("no group file is listed", 1, Integer::sum);
                            continue;
                        }
                        int drawn = groups.size();
                        try {
                            admission.groupCandidate(
                                    id,
                                    input -> {
                                        try {
                                            WmoGroupSpans.withGroup(
                                                    input.bytes(),
                                                    () ->
                                                            token.isCanceled()
                                                                    || Thread.currentThread()
                                                                            .isInterrupted(),
                                                    borrow -> {
                                                        var facts =
                                                                qualifyGroup(
                                                                        drawn,
                                                                        input.receipt().id(),
                                                                        root,
                                                                        borrow,
                                                                        token);
                                                        totals[0] += facts.vertices();
                                                        totals[1] += facts.rawTriangles();
                                                        totals[2] += facts.batches();
                                                        require(
                                                                totals[0]
                                                                        <= GlobalInteriorLimits
                                                                                .VERTICES,
                                                                "the interior has more than "
                                                                        + GlobalInteriorLimits
                                                                                .VERTICES
                                                                        + " vertices");
                                                        require(
                                                                totals[1]
                                                                        <= GlobalInteriorLimits
                                                                                .TRIANGLES,
                                                                "the interior has more than "
                                                                        + GlobalInteriorLimits
                                                                                .TRIANGLES
                                                                        + " triangles");
                                                        require(
                                                                totals[2]
                                                                        <= GlobalInteriorLimits
                                                                                .BATCHES,
                                                                "the interior has more than "
                                                                        + GlobalInteriorLimits
                                                                                .BATCHES
                                                                        + " draw batches");
                                                        if (payload != null) {
                                                            payload.appendGroup(
                                                                    drawn, borrow, facts, token);
                                                        }
                                                        groups.add(facts);
                                                    });
                                        } catch (IllegalArgumentException unreadable) {
                                            // The group span reader refused this group's layout.
                                            throw new SkippedGroup(
                                                    "unsupported group layout"
                                                            + parserReason(unreadable));
                                        }
                                    });
                        } catch (SkippedGroup unusable) {
                            skipped.merge(unusable.getMessage(), 1, Integer::sum);
                        } catch (WorldTerrainStreaming.Refusal refusal) {
                            if (!refusal.site()
                                    .equals(WorldSourceRefusals.Site.SOURCE_INPUT.name())) {
                                throw refusal;
                            }
                            // Missing, encrypted or oversized group file.
                            skipped.merge(
                                    refusal.detail().orElse("the group file is not readable"),
                                    1,
                                    Integer::sum);
                        }
                    }
                    require(!groups.isEmpty(), "none of the interior's WMO groups can be read");
                    var images = new ArrayList<ImageFacts>();
                    var seen = new HashSet<Long>();
                    int[] pixels = {0};
                    var used = new HashSet<Integer>();
                    for (var group : groups) {
                        used.addAll(group.materials());
                    }
                    for (var material : root.materials()) {
                        if (!used.contains(material.ordinal()) || !seen.add(material.texture())) {
                            continue;
                        }
                        require(
                                seen.size() <= GlobalInteriorLimits.IMAGES,
                                "the interior uses more than "
                                        + GlobalInteriorLimits.IMAGES
                                        + " textures");
                        admission.imageCandidate(
                                material.texture(),
                                input -> {
                                    var facts =
                                            inspectImage(
                                                    input,
                                                    token,
                                                    GlobalInteriorLimits.PIXELS - pixels[0],
                                                    payload);
                                    pixels[0] += facts.width() * facts.height();
                                    images.add(facts);
                                });
                    }
                    var manifest =
                            new Manifest(
                                    admission.facts(),
                                    root,
                                    groups,
                                    images,
                                    admission.receipts(),
                                    skipped);
                    var borrow = new Borrow(manifest, token);
                    try {
                        return Objects.requireNonNull(use.use(borrow));
                    } finally {
                        borrow.expire();
                    }
                });
    }

    /**
     * A group that cannot be drawn; it is left out and counted under its fixed reason, never a
     * whole-interior refusal.
     */
    private static final class SkippedGroup extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SkippedGroup(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The span reader's own fixed reason text, kept only when it is short plain words. */
    private static String parserReason(IllegalArgumentException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Za-z ]{1,48}")
                ? " (" + message.toLowerCase(java.util.Locale.ROOT) + ")"
                : "";
    }

    private static RootFacts qualifyRoot(
            WorldGlobalScopes.AssetInput input,
            WorldGlobalScopes.Facts declaration,
            Cancellation token) {
        WmoFormats.Root root;
        try {
            root = WmoFormats.root(input.bytes(), ROOT_LIMITS, token::isCanceled);
        } catch (IllegalArgumentException unreadable) {
            throw refused("the interior's WMO root has a layout this viewer cannot read");
        }
        var h = root.header();
        require(
                h.groupCount() > 0 && h.groupCount() <= GlobalInteriorLimits.GROUPS,
                "the interior has "
                        + h.groupCount()
                        + " WMO groups; at most "
                        + GlobalInteriorLimits.GROUPS
                        + " are supported");
        require(
                h.materialCount() > 0 && h.materialCount() <= GlobalInteriorLimits.MATERIALS,
                "the interior has "
                        + h.materialCount()
                        + " materials; at most "
                        + GlobalInteriorLimits.MATERIALS
                        + " are supported");
        require(
                root.gfidPresent()
                        && root.gfidCount() >= h.groupCount()
                        && !root.textureNameBlockPresent(),
                "the interior's WMO root names its files instead of listing file IDs");
        var chunks = root.chunks();
        var groups = new ArrayList<Long>((int) h.groupCount());
        var references = new HashSet<Long>();
        references.add(input.receipt().id());
        references.add(declaration.wdtId());
        // GFID lists the full-detail groups first; later LOD entries are not drawn.
        for (int ordinal = 0; ordinal < h.groupCount(); ordinal++) {
            long id = root.gfid(ordinal);
            require(id == 0 || references.add(id), "the WMO root lists a group file twice");
            groups.add(id);
        }
        var materials = new ArrayList<Material>(root.materials().size());
        for (int ordinal = 0; ordinal < root.materials().size(); ordinal++) {
            token.check();
            var material = root.materials().get(ordinal);
            long id = material.word(3);
            // A texture that names the WDT, root or a group file is a cross-role reference.
            require(
                    id == 0 || !references.contains(id),
                    "a material names a map or WMO file as its texture");
            var words = new ArrayList<Long>(16);
            for (int word = 0; word < 16; word++) {
                words.add(material.word(word));
            }
            // Flags, shader and blend words are recorded but not applied: everything draws as
            // unlit opaque diffuse (the profile's omissions).
            materials.add(new Material(ordinal, id, words));
        }
        return new RootFacts(h, materials, groups, chunks, root.groupNames());
    }

    private static GroupFacts qualifyGroup(
            int ordinal, long id, RootFacts root, WmoGroupSpans.Borrow borrow, Cancellation token) {
        var h = borrow.header();
        int vertices = borrow.positions().size() / 3, triangles = borrow.indices().size() / 3;
        skipUnless(
                vertices > 0
                        && vertices <= GlobalInteriorLimits.GROUP_VERTICES
                        && triangles > 0
                        && triangles <= GlobalInteriorLimits.GROUP_TRIANGLES
                        && borrow.positions().size() == vertices * 3
                        && borrow.indices().size() == triangles * 3
                        && borrow.uv().size() == vertices * 2,
                "empty or oversized group mesh");
        skipUnless(
                borrow.batches().size() > 0
                        && borrow.batches().size() <= GlobalInteriorLimits.GROUP_BATCHES,
                "no draw batches");
        var digest = new StreamHash();
        digest.integer(ordinal);
        digest.integer(vertices);
        digest.integer(triangles);
        for (int v = 0; v < vertices; v++) {
            token.check();
            for (int axis = 0; axis < 3; axis++) {
                float value = borrow.positions().get(v * 3 + axis);
                skipUnless(
                        Float.isFinite(value) && Math.abs(value) <= GlobalInteriorLimits.POSITION,
                        "positions beyond the supported range");
                digest.integer(Float.floatToRawIntBits(value));
            }
            for (int axis = 0; axis < 2; axis++) {
                float uv = borrow.uv().get(v * 2 + axis);
                skipUnless(
                        Float.isFinite(uv) && Math.abs(uv) <= GlobalInteriorLimits.UV,
                        "texture coordinates beyond the supported range");
                digest.integer(Float.floatToRawIntBits(uv));
            }
        }
        for (int i = 0; i < borrow.indices().size(); i++) {
            token.check();
            int index = borrow.indices().get(i);
            skipUnless(index < vertices, "indices outside the group's vertices");
            digest.integer(index);
        }
        int drawn = 0, blended = 0;
        var materials = new java.util.TreeSet<Integer>();
        var drawnBatches = new ArrayList<Integer>();
        for (int b = 0; b < borrow.batches().size(); b++) {
            var batch = borrow.batches().get(b);
            int material = batch.materialIndex();
            skipUnless(
                    material >= 0 && material < root.materials().size(),
                    "a batch without a textured material");
            skipUnless(
                    batch.firstIndex() % 3 == 0
                            && batch.indexCount() > 0
                            && batch.indexCount() % 3 == 0
                            && batch.firstIndex() + batch.indexCount() <= borrow.indices().size(),
                    "a batch outside the group's indices");
            if (leftOut(root.materials().get(material))) {
                blended++; // Left out before its texture matters, so it never skips the group.
                continue;
            }
            skipUnless(
                    root.materials().get(material).texture() > 0,
                    "a batch without a textured material");
            drawnBatches.add(b);
            materials.add(material);
            drawn += batch.indexCount() / 3;
            digest.integer((int) batch.firstIndex());
            digest.integer(batch.indexCount());
            digest.integer(material);
        }
        var ordered = new ArrayList<>(borrow.batches());
        ordered.sort(Comparator.comparingLong(WmoFormats.Batch::firstIndex));
        long end = 0;
        for (var batch : ordered) {
            skipUnless(batch.firstIndex() >= end, "overlapping batches");
            end = batch.firstIndex() + batch.indexCount();
        }
        skipUnless(!drawnBatches.isEmpty(), "only surfaces with unsupported blend modes");
        return new GroupFacts(
                ordinal,
                id,
                h.flags(),
                vertices,
                triangles,
                drawn,
                drawnBatches.size(),
                borrow.colors() != null,
                borrow.liquid() != null,
                borrow.liquid() == null ? 0 : borrow.liquid().size(),
                digest.finish(),
                h,
                root.groupNames().name(h.nameOffset()).orElse(null),
                List.copyOf(materials),
                drawnBatches,
                blended);
    }

    private static void skipUnless(boolean drawable, String reason) {
        if (!drawable) {
            throw new SkippedGroup(reason);
        }
    }

    private static ImageFacts inspectImage(
            WorldGlobalScopes.AssetInput input,
            Cancellation token,
            int remainingPixels,
            OwnedAsset payload) {
        var raw = input.bytes();
        require(raw.length >= 148, "a texture file is truncated");
        var header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        require(
                header.getInt(0) == 0x32504c42 && header.getInt(4) == 1,
                "a texture is not a BLP2 image");
        int encoding = raw[8] & 255,
                depth = raw[9] & 255,
                alpha = raw[10] & 255,
                flags = raw[11] & 255;
        int width = header.getInt(12), height = header.getInt(16);
        require(
                encoding == 2
                        && (depth == 0 || depth == 1 || depth == 4 || depth == 8)
                        && width > 0
                        && width <= SOURCE_IMAGE_SIDE
                        && height > 0
                        && height <= SOURCE_IMAGE_SIDE,
                "a texture uses an encoding or size this viewer does not draw");
        // Draw the first recorded mip no larger than the profile side.
        int mip = 0, w = width, h = height;
        while (w > GlobalInteriorLimits.IMAGE_SIDE || h > GlobalInteriorLimits.IMAGE_SIDE) {
            mip++;
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
        }
        require(
                mip == 0 || flags != 0,
                "a texture larger than "
                        + GlobalInteriorLimits.IMAGE_SIDE
                        + " pixels has no smaller mip");
        require((long) w * h <= remainingPixels, "the interior's textures exceed the pixel budget");
        // Synchronous core + immediate owned memory only: no completed decoder worker remains.
        Blp2Types.Image image;
        try {
            image =
                    Blp2Decoder.decodeSynchronous(
                            AssetSource.bytes(raw),
                            mip,
                            IMAGE_LIMITS,
                            token,
                            Blp2Types.MipProfile.STRICT);
        } catch (IllegalArgumentException undecodable) {
            throw refused("a texture cannot be decoded");
        }
        require(
                image.width() == w
                        && image.height() == h
                        && image.mipLevel() == mip
                        && (image.encoding() == Blp2Types.Encoding.BC1
                                || image.encoding() == Blp2Types.Encoding.BC2
                                || image.encoding() == Blp2Types.Encoding.BC3),
                "a texture decodes differently from its header");
        byte[] rgba = image.rgba(); // Adopt this one copy. Never retain Image/source/future.
        require(rgba.length == (long) w * h * 4);
        String sourceHash = hash(rgba);
        int min = 255, max = 0, nonopaque = 0;
        for (int i = 3; i < rgba.length; i += 4) {
            if ((i & 4095) == 3) {
                token.check();
            }
            int value = rgba[i] & 255;
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (value != 255) {
                nonopaque++;
            }
        }
        // The payload keeps its source alpha (#297): cutout and blended materials need it, and
        // opaque materials ignore it. The opaque digest stays a recorded fact.
        String opaqueHash = opaqueHash(rgba, token);
        if (payload != null) {
            payload.adoptImage(rgba);
        }
        return new ImageFacts(
                input.receipt().id(),
                w,
                h,
                image.encoding(),
                depth,
                alpha,
                flags,
                image.mips().size() - mip,
                min,
                max,
                nonopaque,
                sourceHash,
                opaqueHash);
    }

    /** SHA-256 of {@code rgba} with every alpha byte read as 255, without changing it. */
    private static String opaqueHash(byte[] rgba, Cancellation token) {
        var digest = digest();
        byte[] scratch = new byte[Math.min(rgba.length, 64 << 10)];
        for (int at = 0; at < rgba.length; at += scratch.length) {
            token.check();
            int count = Math.min(scratch.length, rgba.length - at);
            System.arraycopy(rgba, at, scratch, 0, count);
            for (int i = 3; i < count; i += 4) {
                scratch[i] = (byte) 255;
            }
            digest.update(scratch, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class StreamHash {
        private final MessageDigest digest = digest();
        private final ByteBuffer scratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

        void integer(int value) {
            if (scratch.remaining() < 4) {
                flush();
            }
            scratch.putInt(value);
        }

        private void flush() {
            digest.update(scratch.array(), 0, scratch.position());
            scratch.clear();
        }

        String finish() {
            flush();
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void require(boolean value) {
        if (!value) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_PROFILE);
        }
    }

    /** Refuses the whole interior and says which profile check failed. */
    private static void require(boolean value, String detail) {
        if (!value) {
            throw refused(detail);
        }
    }

    private static WorldTerrainStreaming.Refusal refused(String detail) {
        return WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_PROFILE, detail);
    }
}
