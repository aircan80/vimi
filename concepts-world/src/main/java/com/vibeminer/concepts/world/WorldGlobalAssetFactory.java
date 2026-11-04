package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WmoGroupSpans;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** G1 source/ancestry/material inspection only. No mesh, image payload, packet or public facade. */
final class WorldGlobalAssetFactory {
    static final String PROFILE = "GLOBAL_UNLIT_OPAQUE_RGB_V1";
    private static final WmoFormats.Limits ROOT_LIMITS =
            new WmoFormats.Limits(64 << 10, 1 << 20, 8, 24, 8, 16384, 16384, 64, 1, 64 << 10);
    private static final Blp2Types.Limits IMAGE_LIMITS =
            new Blp2Types.Limits(64 << 10, 256, 65536, 64 << 10, 262144);
    private static final Set<Integer> ROOT_TAGS =
            tags(
                    "MVER", "MOHD", "MOMT", "MOGN", "MOGI", "MOSB", "MOPV", "MOPT", "MOPR", "MOVV",
                    "MOVB", "MOLT", "MODS", "MODD", "MFOG", "GFID", "MODI");
    private static final Set<Integer> GROUP_TAGS =
            tags(
                    "MVER", "MOGP", "MOPY", "MOVI", "MOVT", "MONR", "MOTV", "MOBA", "MOCV", "MLIQ",
                    "MOLR", "MODR", "MOBN", "MOBR");
    private static final Set<Long> GROUP_FLAGS =
            Set.of(0x0a09L, 0x3805L, 0x2a05L, 0x2005L, 0x2805L);

    record Material(int ordinal, long texture, List<Long> rawWords) {
        Material {
            rawWords = List.copyOf(rawWords);
        }
    }

    record RootFacts(
            WmoFormats.RootHeader header,
            List<Material> materials,
            List<Long> groups,
            List<WmoFormats.Chunk> chunks) {
        RootFacts {
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
            WmoFormats.GroupHeader header) {}

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
        private final int vertices, rawTriangles, drawnTriangles, batches, pixels;

        private Manifest(
                WorldGlobalScopes.Facts declaration,
                RootFacts root,
                List<GroupFacts> groups,
                List<ImageFacts> images,
                List<WorldGlobalScopes.AssetReceipt> receipts) {
            require(groups.size() <= 8 && images.size() <= 18 && receipts.size() <= 27);
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
                    vertices <= 32768
                            && rawTriangles <= 32768
                            && batches <= 64
                            && pixels <= 1179648);
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
        private final long thread = Thread.currentThread().threadId();

        private Borrow(Manifest manifest) {
            this.manifest = manifest;
        }

        Manifest manifest() {
            if (manifest == null || Thread.currentThread().threadId() != thread) {
                throw new IllegalStateException("Expired or foreign global manifest borrow");
            }
            return manifest;
        }

        private void expire() {
            manifest = null;
        }
    }

    @FunctionalInterface
    interface SourceManifestUse<R extends AutoCloseable> {
        R use(Borrow borrow);
    }

    private final WorldGlobalScopes scopes;

    WorldGlobalAssetFactory(WorldGlobalScopes scopes) {
        this.scopes = Objects.requireNonNull(scopes);
    }

    <R extends AutoCloseable> R inspectSources(
            WorldGlobalScopes.GlobalScope scope,
            WorldGlobalScopes.Selection selection,
            Cancellation external,
            SourceManifestUse<R> use) {
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
                    var groups = new ArrayList<GroupFacts>(root.groups().size());
                    int[] totals = new int[3];
                    for (int ordinal = 0; ordinal < root.groups().size(); ordinal++) {
                        token.check();
                        int index = ordinal;
                        long id = root.groups().get(ordinal);
                        admission.groupCandidate(
                                id,
                                input ->
                                        WmoGroupSpans.withGroup(
                                                input.bytes(),
                                                () ->
                                                        token.isCanceled()
                                                                || Thread.currentThread()
                                                                        .isInterrupted(),
                                                borrow -> {
                                                    var facts =
                                                            qualifyGroup(
                                                                    index,
                                                                    input.receipt().id(),
                                                                    root,
                                                                    borrow,
                                                                    token);
                                                    totals[0] += facts.vertices();
                                                    totals[1] += facts.rawTriangles();
                                                    totals[2] += facts.batches();
                                                    require(
                                                            totals[0] <= 32768
                                                                    && totals[1] <= 32768
                                                                    && totals[2] <= 64);
                                                    groups.add(facts);
                                                }));
                    }
                    var images = new ArrayList<ImageFacts>();
                    var seen = new HashSet<Long>();
                    int[] pixels = {0};
                    for (var material : root.materials()) {
                        if (!seen.add(material.texture())) {
                            continue;
                        }
                        require(seen.size() <= 18);
                        admission.imageCandidate(
                                material.texture(),
                                input -> {
                                    var facts = inspectImage(input, token, 1179648 - pixels[0]);
                                    pixels[0] += facts.width() * facts.height();
                                    images.add(facts);
                                });
                    }
                    var manifest =
                            new Manifest(
                                    admission.facts(), root, groups, images, admission.receipts());
                    var borrow = new Borrow(manifest);
                    try {
                        return Objects.requireNonNull(use.use(borrow));
                    } finally {
                        borrow.expire();
                    }
                });
    }

    private static RootFacts qualifyRoot(
            WorldGlobalScopes.AssetInput input,
            WorldGlobalScopes.Facts declaration,
            Cancellation token) {
        var root = WmoFormats.root(input.bytes(), ROOT_LIMITS, token::isCanceled);
        var h = root.header();
        require(
                h.flags() == 5
                        && h.lodCount() == 0
                        && h.groupCount() > 0
                        && h.groupCount() <= 8
                        && h.materialCount() > 0
                        && h.materialCount() <= 24
                        && root.gfidPresent()
                        && root.gfidCount() == h.groupCount()
                        && !root.textureNameBlockPresent());
        var chunks = root.chunks();
        require(chunks.size() <= 256);
        var chunkIds = new HashSet<Integer>();
        for (var chunk : chunks) {
            require(ROOT_TAGS.contains(chunk.tag()) && chunkIds.add(chunk.tag()));
        }
        var groups = new ArrayList<Long>(root.gfidCount());
        var references = new HashSet<Long>();
        references.add(input.receipt().id());
        references.add(declaration.wdtId());
        for (int ordinal = 0; ordinal < root.gfidCount(); ordinal++) {
            long id = root.gfid(ordinal);
            require(id > 0 && references.add(id));
            groups.add(id);
        }
        var materials = new ArrayList<Material>(root.materials().size());
        var imageIds = new HashSet<Long>();
        for (int ordinal = 0; ordinal < root.materials().size(); ordinal++) {
            token.check();
            var material = root.materials().get(ordinal);
            for (int word : new int[] {0, 1, 2, 6, 9, 11, 12, 13, 14, 15}) {
                require(material.word(word) == 0);
            }
            long id = material.word(3);
            require(id > 0 && !references.contains(id));
            imageIds.add(id);
            require(imageIds.size() <= 18);
            var words = new ArrayList<Long>(16);
            for (int word = 0; word < 16; word++) {
                words.add(material.word(word));
            }
            materials.add(new Material(ordinal, id, words));
        }
        return new RootFacts(h, materials, groups, chunks);
    }

    private static GroupFacts qualifyGroup(
            int ordinal, long id, RootFacts root, WmoGroupSpans.Borrow borrow, Cancellation token) {
        var h = borrow.header();
        require(
                GROUP_FLAGS.contains(h.flags())
                        && h.rawBatchPadding() == 0
                        && h.rawUnused() <= 1
                        && h.tail0() == 0
                        && h.tail1() == 0);
        int vertices = borrow.positions().size() / 3, triangles = borrow.indices().size() / 3;
        require(
                vertices > 0
                        && vertices <= 16384
                        && triangles > 0
                        && triangles <= 16384
                        && borrow.positions().size() == vertices * 3
                        && borrow.normals().size() == vertices * 3
                        && borrow.uv().size() == vertices * 2
                        && borrow.triangles().size() == triangles * 2
                        && borrow.batches().size() > 0
                        && borrow.batches().size() <= 64
                        && h.batchesA() + h.batchesB() + h.batchesC() == borrow.batches().size());
        require((borrow.colors() != null) == ((h.flags() & 4) != 0));
        require((borrow.liquid() != null) == ((h.flags() & 0x1000) != 0));
        var chunkIds = new HashSet<Integer>();
        for (var chunk : borrow.chunks()) {
            require(GROUP_TAGS.contains(chunk.tag()) && chunkIds.add(chunk.tag()));
        }
        var digest = new StreamHash();
        digest.integer(ordinal);
        digest.integer(vertices);
        digest.integer(triangles);
        for (int v = 0; v < vertices; v++) {
            token.check();
            for (int axis = 0; axis < 3; axis++) {
                float value = borrow.positions().get(v * 3 + axis),
                        normal = borrow.normals().get(v * 3 + axis);
                require(Float.isFinite(value) && Float.isFinite(normal));
                float low = axis == 0 ? h.lower().x() : axis == 1 ? h.lower().y() : h.lower().z();
                float high = axis == 0 ? h.upper().x() : axis == 1 ? h.upper().y() : h.upper().z();
                float rootLow =
                        axis == 0
                                ? root.header().lower().x()
                                : axis == 1 ? root.header().lower().y() : root.header().lower().z();
                float rootHigh =
                        axis == 0
                                ? root.header().upper().x()
                                : axis == 1 ? root.header().upper().y() : root.header().upper().z();
                require(
                        value >= low - .001
                                && value <= high + .001
                                && value >= rootLow - .001
                                && value <= rootHigh + .001);
                digest.integer(Float.floatToRawIntBits(value));
            }
            for (int axis = 0; axis < 2; axis++) {
                float uv = borrow.uv().get(v * 2 + axis);
                require(Float.isFinite(uv));
                digest.integer(Float.floatToRawIntBits(uv));
            }
        }
        for (int i = 0; i < borrow.indices().size(); i++) {
            token.check();
            int index = borrow.indices().get(i);
            require(index < vertices);
            digest.integer(index);
        }
        int drawn = 0;
        for (var batch : borrow.batches()) {
            require(
                    batch.flags() == 0
                            && batch.materialByte() < root.materials().size()
                            && batch.firstIndex() % 3 == 0
                            && batch.indexCount() > 0
                            && batch.indexCount() % 3 == 0
                            && batch.firstIndex() + batch.indexCount() <= borrow.indices().size()
                            && batch.firstVertex() <= batch.lastVertex()
                            && batch.lastVertex() < vertices);
            for (int i = (int) batch.firstIndex();
                    i < batch.firstIndex() + batch.indexCount();
                    i++) {
                int index = borrow.indices().get(i);
                require(index >= batch.firstVertex() && index <= batch.lastVertex());
            }
            drawn += batch.indexCount() / 3;
            digest.integer((int) batch.firstIndex());
            digest.integer(batch.indexCount());
            digest.integer(batch.materialByte());
        }
        var ordered = new ArrayList<>(borrow.batches());
        ordered.sort(Comparator.comparingLong(WmoFormats.Batch::firstIndex));
        long end = 0;
        for (var batch : ordered) {
            require(batch.firstIndex() >= end);
            end = batch.firstIndex() + batch.indexCount();
        }
        if (borrow.colors() != null) {
            require(borrow.colors().size() == vertices * 4);
            for (int i = 0; i < borrow.colors().size(); i++) {
                if ((i & 4095) == 0) {
                    token.check();
                }
                borrow.colors().get(i);
            }
        }
        if (borrow.liquid() != null) {
            for (int i = 0; i < borrow.liquid().size(); i++) {
                if ((i & 4095) == 0) {
                    token.check();
                }
                borrow.liquid().get(i);
            }
        }
        return new GroupFacts(
                ordinal,
                id,
                h.flags(),
                vertices,
                triangles,
                drawn,
                borrow.batches().size(),
                borrow.colors() != null,
                borrow.liquid() != null,
                borrow.liquid() == null ? 0 : borrow.liquid().size(),
                digest.finish(),
                h);
    }

    private static ImageFacts inspectImage(
            WorldGlobalScopes.AssetInput input, Cancellation token, int remainingPixels) {
        var raw = input.bytes();
        require(raw.length >= 148);
        var header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        require(header.getInt(0) == 0x32504c42 && header.getInt(4) == 1);
        int encoding = raw[8] & 255,
                depth = raw[9] & 255,
                alpha = raw[10] & 255,
                flags = raw[11] & 255;
        int width = header.getInt(12), height = header.getInt(16);
        require(
                encoding == 2
                        && flags == 1
                        && (depth == 0 && alpha == 0 || depth == 8 && alpha == 7)
                        && width > 0
                        && width <= 256
                        && height > 0
                        && height <= 256
                        && (long) width * height <= remainingPixels);
        // Synchronous core + immediate owned memory only: no completed decoder worker remains.
        var image =
                Blp2Decoder.decodeSynchronous(
                        AssetSource.bytes(raw),
                        0,
                        IMAGE_LIMITS,
                        token,
                        Blp2Types.MipProfile.STRICT);
        require(
                image.width() == width
                        && image.height() == height
                        && image.mipLevel() == 0
                        && image.mipFlags() == 1
                        && (image.encoding() == Blp2Types.Encoding.BC1 && depth == 0 && alpha == 0
                                || image.encoding() == Blp2Types.Encoding.BC3
                                        && depth == 8
                                        && alpha == 7));
        byte[] rgba = image.rgba(); // Adopt this one copy. Never retain Image/source/future.
        require(rgba.length == (long) width * height * 4);
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
            rgba[i] = (byte) 255;
        }
        require(image.encoding() != Blp2Types.Encoding.BC1 || nonopaque == 0);
        return new ImageFacts(
                input.receipt().id(),
                width,
                height,
                image.encoding(),
                depth,
                alpha,
                flags,
                image.mips().size(),
                min,
                max,
                nonopaque,
                sourceHash,
                hash(rgba));
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

    private static Set<Integer> tags(String... names) {
        var result = new HashSet<Integer>();
        for (String name : names) {
            int tag = 0;
            for (int i = 0; i < 4; i++) {
                tag = tag << 8 | name.charAt(i);
            }
            result.add(tag);
        }
        return Set.copyOf(result);
    }

    private static void require(boolean value) {
        if (!value) {
            throw WorldSourceRefusals.at(WorldSourceRefusals.Site.GLOBAL_ASSET_PROFILE);
        }
    }
}
