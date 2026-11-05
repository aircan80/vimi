package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.world.WdtSceneFacts;
import com.vibeminer.formats.world.WmoFormats;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Closed, service-owned global scene source. All IO, payload callbacks and close belong off FX. */
public final class WorldGlobalScenes {
    public record Source(
            String kind,
            long id,
            String key,
            long length,
            Long declaredLength,
            int sourceLimit,
            String sha256) {}

    public record Material(int ordinal, long texture, List<Long> rawWords) {
        public Material {
            rawWords = List.copyOf(rawWords);
        }
    }

    public record Group(
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
            String name) {
        /**
         * Recorded MOGP bounds in the drawn mesh's native space (min xyz, max xyz). Descriptive
         * inspection framing only, never an entrance, floor or navigation claim.
         */
        public float[] nativeBounds() {
            return WorldGlobalAssetFactory.nativeBounds(header);
        }

        /** The MOGN name at the recorded offset, when readable; never inferred. */
        public java.util.Optional<String> recordedName() {
            return java.util.Optional.ofNullable(name);
        }
    }

    public record Image(
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

    public record Span(int group, int firstIndex, int indexCount, int material) {}

    /** Detached descriptions, never accepted as source authority. */
    public static final class Facts {
        private final WorldGlobalScopes.Facts declaration;
        private final WmoFormats.RootHeader root;
        private final List<Material> materials;
        private final List<Group> groups;
        private final List<Image> images;
        private final List<Source> sources;
        private final byte[] modf;
        private final int skippedGroups;
        private final java.util.SortedMap<String, Integer> skippedReasons;

        private Facts(WorldGlobalAssetFactory.Manifest manifest) {
            declaration = manifest.declaration();
            skippedGroups = manifest.skippedGroups();
            skippedReasons = manifest.skippedReasons();
            root = manifest.root().header();
            materials =
                    manifest.root().materials().stream()
                            .map(m -> new Material(m.ordinal(), m.texture(), m.rawWords()))
                            .toList();
            groups =
                    manifest.groups().stream()
                            .map(
                                    g ->
                                            new Group(
                                                    g.ordinal(),
                                                    g.id(),
                                                    g.flags(),
                                                    g.vertices(),
                                                    g.rawTriangles(),
                                                    g.drawnTriangles(),
                                                    g.batches(),
                                                    g.colors(),
                                                    g.liquid(),
                                                    g.liquidBytes(),
                                                    g.canonicalMeshSha256(),
                                                    g.header(),
                                                    g.name()))
                            .toList();
            images =
                    manifest.images().stream()
                            .map(
                                    i ->
                                            new Image(
                                                    i.id(),
                                                    i.width(),
                                                    i.height(),
                                                    i.encoding(),
                                                    i.alphaDepth(),
                                                    i.alphaEncoding(),
                                                    i.mipFlags(),
                                                    i.mipCount(),
                                                    i.alphaMin(),
                                                    i.alphaMax(),
                                                    i.nonopaque(),
                                                    i.sourceRgbaSha256(),
                                                    i.opaqueRgbaSha256()))
                            .toList();
            var sourceList = new java.util.ArrayList<Source>();
            sourceList.add(
                    new Source(
                            "WDT",
                            declaration.wdtId(),
                            declaration.wdtKey(),
                            declaration.consumedLength(),
                            declaration.declaredLength(),
                            declaration.sourceLimit(),
                            declaration.bodySha256()));
            for (var r : manifest.receipts()) {
                sourceList.add(
                        new Source(
                                r.kind().name(),
                                r.id(),
                                r.key(),
                                r.length(),
                                r.declared(),
                                r.limit(),
                                r.sha256()));
            }
            sources = List.copyOf(sourceList);
            modf = modf(declaration.declaration());
            try {
                if (!HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(modf))
                        .equals(declaration.declarationSha256())) {
                    throw new IllegalStateException("Recorded global declaration bits changed");
                }
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        public BuildRef build() {
            return declaration.build();
        }

        public String locale() {
            return declaration.locale();
        }

        public long mapId() {
            return declaration.mapId();
        }

        public String scopeSha256() {
            return declaration.scopeDigest();
        }

        public WdtSceneFacts.GlobalObject declaration() {
            return declaration.declaration();
        }

        public String declarationSha256() {
            return declaration.declarationSha256();
        }

        public int modfByte(int offset) {
            return modf[Objects.checkIndex(offset, 64)] & 255;
        }

        public WmoFormats.RootHeader root() {
            return root;
        }

        public List<Material> materials() {
            return materials;
        }

        public List<Group> groups() {
            return groups;
        }

        public List<Image> images() {
            return images;
        }

        public List<Source> sources() {
            return sources;
        }

        /**
         * Root groups left out of the drawn interior because their file is missing or their layout
         * is outside the profile; the drawn groups are complete.
         */
        public int skippedGroups() {
            return skippedGroups;
        }

        /** {@link #skippedGroups()} by fixed reason text (counts only; no names or paths). */
        public java.util.SortedMap<String, Integer> skippedGroupReasons() {
            return skippedReasons;
        }
    }

    public static final class Scope {
        private final WorldGlobalScenes owner;
        private final WorldGlobalScopes.GlobalScope token;

        private Scope(WorldGlobalScenes owner, WorldGlobalScopes.GlobalScope token) {
            this.owner = owner;
            this.token = token;
        }

        public long mapId() {
            return token.mapId();
        }

        public String scopeSha256() {
            return token.digest();
        }
    }

    public static final class CheckedBorrow {
        private WorldGlobalAssetFactory.AssetBorrow borrow;
        private final Facts facts;
        private final long thread = Thread.currentThread().threadId();

        private CheckedBorrow(WorldGlobalAssetFactory.AssetBorrow borrow, Facts facts) {
            this.borrow = borrow;
            this.facts = facts;
        }

        private WorldGlobalAssetFactory.AssetBorrow checked() {
            if (borrow == null || thread != Thread.currentThread().threadId()) {
                throw new IllegalStateException("Expired or foreign global scene borrow");
            }
            return borrow;
        }

        public Facts facts() {
            checked();
            return facts;
        }

        public int vertices() {
            return checked().vertices();
        }

        public int indices() {
            return checked().indices();
        }

        public float vertex(int vertex, int component) {
            return checked().vertex(vertex, component);
        }

        public int index(int index) {
            return checked().index(index);
        }

        public int imageCount() {
            return checked().imageCount();
        }

        public int imageBytes(int image) {
            return checked().imageBytes(image);
        }

        public int imageByte(int image, int offset) {
            return checked().imageByte(image, offset);
        }

        public List<Span> spans() {
            return checked().spans().stream()
                    .map(s -> new Span(s.group(), s.firstIndex(), s.indexCount(), s.material()))
                    .toList();
        }

        private void expire() {
            borrow = null;
        }
    }

    @FunctionalInterface
    public interface Output<R extends AutoCloseable> {
        R writeUnadvertised(CheckedBorrow borrow, Cancellation actualAdmissionCancellation);
    }

    public static final class WrittenScene<R extends AutoCloseable> implements AutoCloseable {
        private final WorldGlobalScenes owner;
        private final Scope scope;
        private final WorldGlobalAssetFactory.WrittenAsset<R> asset;
        private final Facts facts;

        private WrittenScene(
                WorldGlobalScenes owner,
                Scope scope,
                WorldGlobalAssetFactory.WrittenAsset<R> asset,
                Facts facts) {
            this.owner = owner;
            this.scope = scope;
            this.asset = asset;
            this.facts = facts;
        }

        public Facts facts() {
            return facts;
        }

        public boolean isOpen() {
            return owner.open && owner.core.isKnown(scope.token) && asset.isOpen();
        }

        public <T> T withPayload(Function<CheckedBorrow, T> read) {
            Objects.requireNonNull(read);
            owner.check(scope);
            return asset.withBorrow(
                    b -> {
                        var checked = new CheckedBorrow(b, facts);
                        try {
                            return read.apply(checked);
                        } finally {
                            checked.expire();
                        }
                    });
        }

        /**
         * MOVE only: the resource must become inert after transfer; this callback pins retirement.
         */
        public void withWrittenResource(Consumer<R> moveOnly) {
            owner.check(scope);
            asset.withWrittenResource(moveOnly);
        }

        public CompletableFuture<Void> closeAsync() {
            return asset.closeAsync();
        }

        @Override
        public void close() {
            asset.close();
        }
    }

    private final WorldGlobalScopes core;
    private final WorldGlobalAssetFactory factory;
    private volatile boolean open = true;

    WorldGlobalScenes(WorldGlobalScopes core) {
        this.core = Objects.requireNonNull(core);
        factory = new WorldGlobalAssetFactory(core);
    }

    void invalidate() {
        open = false;
    }

    private void check(Scope scope) {
        if (scope == null || scope.owner != this) {
            throw new IllegalArgumentException("Foreign global scope");
        }
        core.requireHealthy();
        if (!open || !core.isKnown(scope.token)) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.SCOPE_RETIRED);
        }
    }

    public Scope createScope(WorldService.MapInfo comparison, Cancellation cancellation) {
        core.requireHealthy();
        if (!open) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.OWNER_RETIRED);
        }
        try {
            return new Scope(this, core.create(comparison, cancellation));
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    public void activate(Scope scope) {
        check(scope);
        core.activate(scope.token);
    }

    public <R extends AutoCloseable> WrittenScene<R> prepareAndWrite(
            Scope scope, Cancellation cancellation, Output<R> output) {
        Objects.requireNonNull(cancellation);
        Objects.requireNonNull(output);
        check(scope);
        try {
            var declaration = core.descriptiveFacts(scope.token).declaration();
            var selection =
                    new WorldGlobalScopes.Selection(
                            scope.mapId(), declaration.ordinal(), declaration.uniqueId());
            var facts = new Facts[1];
            var asset =
                    factory.createAndWrite(
                            scope.token,
                            selection,
                            cancellation,
                            (borrow, actualToken) -> {
                                facts[0] = new Facts(borrow.facts());
                                var checked = new CheckedBorrow(borrow, facts[0]);
                                try {
                                    return output.writeUnadvertised(checked, actualToken);
                                } finally {
                                    checked.expire();
                                }
                            });
            try {
                check(scope);
                return new WrittenScene<>(this, scope, asset, Objects.requireNonNull(facts[0]));
            } catch (RuntimeException | Error failure) {
                try {
                    asset.close();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        } catch (RuntimeException failure) {
            throw WorldSourceRefusals.boundary(failure);
        }
    }

    private static byte[] modf(WdtSceneFacts.GlobalObject d) {
        var bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt((int) d.reference()).putInt((int) d.uniqueId());
        for (var v : List.of(d.position(), d.rotation(), d.lowerBounds(), d.upperBounds())) {
            bytes.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());
        }
        bytes.putShort((short) d.flags())
                .putShort((short) d.doodadSet())
                .putShort((short) d.nameSet())
                .putShort((short) d.scale());
        return bytes.array();
    }
}
