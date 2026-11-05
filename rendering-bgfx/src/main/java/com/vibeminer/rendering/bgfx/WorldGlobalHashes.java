package com.vibeminer.rendering.bgfx;

import static com.vibeminer.rendering.bgfx.WorldGlobalLayout.require;

import com.vibeminer.rendering.world.GlobalInteriorLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

/**
 * Pure bounded structural hash/CRC primitives. No file or descriptor authenticates game sources.
 */
public final class WorldGlobalHashes {
    private WorldGlobalHashes() {}

    private static final HexFormat HEX = HexFormat.of();

    public enum Role {
        WDT(1, 1 << 20, 1),
        ROOT(2, GlobalInteriorLimits.ROOT_BYTES, 1),
        GROUP(3, GlobalInteriorLimits.GROUP_BYTES, GlobalInteriorLimits.GROUPS),
        IMAGE(4, GlobalInteriorLimits.IMAGE_BYTES, GlobalInteriorLimits.IMAGES);
        private final int wire, limit, ordinals;

        Role(int wire, int limit, int ordinals) {
            this.wire = wire;
            this.limit = limit;
            this.ordinals = ordinals;
        }

        public int wire() {
            return wire;
        }
    }

    public record Source(
            long id,
            Role role,
            int ordinal,
            long length,
            Long declaredLength,
            String key,
            String bodySha256) {
        public Source {
            WorldGlobalLayout.unsigned(id);
            require(
                    id > 0
                            && role != null
                            && ordinal >= 0
                            && ordinal < role.ordinals
                            && length >= 12
                            && length <= role.limit
                            && (declaredLength == null || declaredLength == length));
            WorldGlobalLayout.hex(key, 32);
            WorldGlobalLayout.hex(bodySha256, 64);
        }
    }

    /** Copies only the bounded sixteen raw words; never an ownership-taking material API. */
    public record Material(long[] words) {
        public Material {
            require(words != null && words.length == 16);
            words = words.clone();
            for (long word : words) {
                WorldGlobalLayout.unsigned(word);
            }
            // Flags, shader and blend words are carried but not applied (unlit opaque profile). A
            // material without a texture is allowed only while no batch draws it.
        }

        @Override
        public long[] words() {
            return words.clone();
        }
    }

    public record Image(
            long id,
            int width,
            int height,
            int encoding,
            int alphaDepth,
            int alphaEncoding,
            int mipCount,
            int alphaMin,
            int alphaMax,
            int nonopaque,
            String originalRgbaSha256,
            String opaqueRgbaSha256) {
        public Image {
            WorldGlobalLayout.unsigned(id);
            require(
                    id > 0
                            && width >= 1
                            && width <= GlobalInteriorLimits.IMAGE_SIDE
                            && height >= 1
                            && height <= GlobalInteriorLimits.IMAGE_SIDE
                            && encoding >= 1
                            && encoding <= 3
                            && (alphaDepth == 0
                                    || alphaDepth == 1
                                    || alphaDepth == 4
                                    || alphaDepth == 8)
                            && alphaEncoding >= 0
                            && alphaEncoding <= 255
                            && mipCount >= 1
                            && mipCount
                                    <= 32 - Integer.numberOfLeadingZeros(Math.max(width, height))
                            && alphaMin >= 0
                            && alphaMin <= alphaMax
                            && alphaMax <= 255
                            && nonopaque >= 0
                            && nonopaque <= width * height);
            require(
                    nonopaque == 0
                            ? alphaMin == 255 && alphaMax == 255
                            : alphaMin < 255
                                    && (nonopaque == width * height
                                            ? alphaMax < 255
                                            : alphaMax == 255));
            WorldGlobalLayout.hex(originalRgbaSha256, 64);
            WorldGlobalLayout.hex(opaqueRgbaSha256, 64);
        }
    }

    /** Literal raw MODF bits, deliberately separate from G2 native positive-zero positions. */
    public static final class RawModf {
        private final byte[] bytes;

        private RawModf(byte[] raw, long root, long uid) {
            require(raw != null && raw.length == 64);
            WorldGlobalLayout.unsigned(root);
            WorldGlobalLayout.unsigned(uid);
            require(root > 0);
            bytes = raw.clone();
            var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(
                    Integer.toUnsignedLong(b.getInt(0)) == root
                            && Integer.toUnsignedLong(b.getInt(4)) == uid);
            for (int at = 8; at < 32; at += 4) {
                require(b.getFloat(at) == 0f);
            } // Both literal zero signs admitted.
            for (int axis = 0; axis < 3; axis++) {
                float lo = b.getFloat(32 + axis * 4), hi = b.getFloat(44 + axis * 4);
                require(
                        Float.isFinite(lo)
                                && Float.isFinite(hi)
                                && Math.abs(lo) <= 1_000_000
                                && Math.abs(hi) <= 1_000_000
                                && lo <= hi);
            }
            require(
                    Short.toUnsignedInt(b.getShort(56)) == 8
                            && b.getShort(58) == 0
                            && b.getShort(60) == 0
                            && Short.toUnsignedInt(b.getShort(62)) == 1024);
        }

        public static RawModf copyOf(byte[] raw, long root, long uid) {
            return new RawModf(raw, root, uid);
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        private long root() {
            return Integer.toUnsignedLong(
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        }
    }

    public static String buildLocale(
            String product, String buildConfig, String cdnConfig, String region, String locale) {
        ascii(product, 1, 32);
        WorldGlobalLayout.hex(buildConfig, 32);
        WorldGlobalLayout.hex(cdnConfig, 32);
        if (region != null) {
            ascii(region, 1, 32);
        }
        ascii(locale, 1, 16);
        var h = new Feed("vimi.global.build-locale.v1");
        h.text(product);
        h.hex(buildConfig);
        h.hex(cdnConfig);
        if (region == null) {
            h.integer(-1);
        } else {
            h.text(region);
        }
        h.text(locale);
        return h.finish();
    }

    /** Canonical Source64 descriptor; its roles/ordinal limits are fixed, not caller-widenable. */
    public static byte[] sourceBytes(Source s) {
        Objects.requireNonNull(s);
        var b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) s.id())
                .put((byte) s.role().wire)
                .put((byte) s.ordinal())
                .putShort((short) (s.declaredLength() == null ? 1 : 0))
                .putInt((int) s.length())
                .putInt(s.declaredLength() == null ? 0 : s.declaredLength().intValue())
                .put(HEX.parseHex(s.key()))
                .put(HEX.parseHex(s.bodySha256()));
        return b.array();
    }

    public static String recipe(
            long mapId,
            String scope,
            String buildLocale,
            RawModf modf,
            List<Source> sources,
            List<Material> materials,
            List<Image> images) {
        WorldGlobalLayout.unsigned(mapId);
        WorldGlobalLayout.hex(scope, 64);
        WorldGlobalLayout.hex(buildLocale, 64);
        Objects.requireNonNull(modf);
        var ss = snapshot(sources, 4, GlobalInteriorLimits.SOURCES);
        var mm = snapshot(materials, 1, GlobalInteriorLimits.MATERIALS);
        var ii = snapshot(images, 1, GlobalInteriorLimits.IMAGES);
        require(
                ss.get(0).role() == Role.WDT
                        && ss.get(1).role() == Role.ROOT
                        && ss.get(1).id() == modf.root());
        var ids = new HashSet<Long>();
        int groups = 0, slots = 0;
        boolean imagePhase = false;
        for (int n = 0; n < ss.size(); n++) {
            var s = ss.get(n);
            require(ids.add(s.id()));
            if (n < 2) {
                require(s.ordinal() == 0);
                continue;
            }
            if (s.role() == Role.GROUP && !imagePhase) {
                require(s.ordinal() == groups++);
            } else {
                require(s.role() == Role.IMAGE && s.ordinal() == slots++);
                imagePhase = true;
            }
        }
        require(
                groups >= 1
                        && groups <= GlobalInteriorLimits.GROUPS
                        && slots == ii.size()
                        && slots <= GlobalInteriorLimits.IMAGES
                        && ss.size() == 2 + groups + slots);
        var imageIds = new HashSet<Long>();
        int pixels = 0;
        for (int n = 0; n < ii.size(); n++) {
            var im = ii.get(n);
            require(imageIds.add(im.id()) && ss.get(2 + groups + n).id() == im.id());
            pixels += im.width() * im.height();
        }
        require(pixels <= GlobalInteriorLimits.PIXELS);
        // Every image is some material's texture. Materials whose texture is not among them must
        // stay undrawn, which the renderer enforces per batch.
        var used = new HashSet<Long>();
        for (var material : mm) {
            long id = material.words[3];
            if (imageIds.contains(id)) {
                used.add(id);
            }
        }
        require(used.size() == ii.size());
        var h = new Feed("vimi.global.recipe.v1");
        h.integer(1);
        h.integer(WorldGlobalLayout.OMISSIONS);
        h.longValue(mapId);
        h.hex(scope);
        h.hex(buildLocale);
        h.bytes(modf.bytes);
        h.integer(ss.size());
        for (var source : ss) {
            h.bytes(sourceBytes(source));
        }
        h.integer(mm.size());
        for (var material : mm) {
            for (long word : material.words) {
                h.integer((int) word);
            }
        }
        h.integer(ii.size());
        for (var im : ii) {
            h.integer((int) im.id());
            h.integer(im.width());
            h.integer(im.height());
            h.integer(im.encoding());
            h.integer(im.alphaDepth());
            h.integer(im.alphaEncoding());
            h.integer(1);
            h.integer(0);
            h.integer(im.mipCount());
            h.integer(im.alphaMin());
            h.integer(im.alphaMax());
            h.integer(im.nonopaque());
            h.hex(im.originalRgbaSha256());
            h.hex(im.opaqueRgbaSha256());
        }
        return h.finish();
    }

    public record Group(
            int vertexFirst,
            int vertexCount,
            int indexFirst,
            int indexCount,
            int batchFirst,
            int batchCount,
            long flags) {
        public Group {
            require(
                    vertexFirst >= 0
                            && vertexCount >= 1
                            && vertexCount <= GlobalInteriorLimits.GROUP_VERTICES
                            && indexFirst >= 0
                            && indexCount >= 3
                            && indexCount <= GlobalInteriorLimits.GROUP_INDICES
                            && indexCount % 3 == 0
                            && batchFirst >= 0
                            && batchCount >= 1
                            && batchCount <= GlobalInteriorLimits.GROUP_BATCHES);
            WorldGlobalLayout.unsigned(flags);
        }
    }

    public record Batch(int group, int material, int firstIndex, int indexCount) {
        public Batch {
            require(
                    group >= 0
                            && group < GlobalInteriorLimits.GROUPS
                            && material >= 0
                            && material < GlobalInteriorLimits.MATERIALS
                            && firstIndex >= 0
                            && firstIndex % 3 == 0
                            && indexCount >= 3
                            && indexCount <= GlobalInteriorLimits.INDICES
                            && indexCount % 3 == 0);
        }
    }

    /** Frozen synchronous scalar source; structural safety only, not authenticated ancestry. */
    public interface Mesh {
        WorldGlobalLayout.Counts counts();

        List<Group> groups();

        List<Batch> batches();

        float vertex(int vertex, int component);

        int index(int ordinal);
    }

    public record MeshDigests(String nativeWire, List<String> groups) {
        public MeshDigests {
            WorldGlobalLayout.hex(nativeWire, 64);
            var bounded = snapshot(groups, 1, GlobalInteriorLimits.GROUPS);
            for (String hash : bounded) {
                WorldGlobalLayout.hex(hash, 64);
            }
            groups = List.copyOf(bounded);
        }
    }

    public static MeshDigests mesh(Mesh mesh, BooleanSupplier canceled) {
        Objects.requireNonNull(mesh);
        Objects.requireNonNull(canceled);
        var c = Objects.requireNonNull(mesh.counts());
        var groups = snapshot(mesh.groups(), c.groups(), c.groups());
        var batches = snapshot(mesh.batches(), c.batches(), c.batches());
        int v = 0, index = 0, batch = 0, drawn = 0;
        for (int ordinal = 0; ordinal < groups.size(); ordinal++) {
            var g = groups.get(ordinal);
            require(g.vertexFirst() == v && g.indexFirst() == index && g.batchFirst() == batch);
            v += g.vertexCount();
            index += g.indexCount();
            batch += g.batchCount();
            require(v <= c.vertices() && index <= c.indices() && batch <= c.batches());
            for (int n = g.batchFirst(); n < batch; n++) {
                var b = batches.get(n);
                require(
                        b.group() == ordinal
                                && b.material() < c.materials()
                                && b.firstIndex() >= g.indexFirst()
                                && (long) b.firstIndex() + b.indexCount() <= index);
                for (int prior = g.batchFirst(); prior < n; prior++) {
                    var old = batches.get(prior);
                    require(
                            (long) b.firstIndex() + b.indexCount() <= old.firstIndex()
                                    || (long) old.firstIndex() + old.indexCount()
                                            <= b.firstIndex());
                }
                drawn += b.indexCount() / 3;
            }
        }
        require(
                v == c.vertices()
                        && index == c.indices()
                        && batch == c.batches()
                        && drawn == c.drawnTriangles());
        var wire = new Feed("vimi.global.native-mesh.v2");
        nativePrefix(wire);
        for (int count :
                new int[] {
                    c.groups(),
                    c.batches(),
                    c.vertices(),
                    c.indices(),
                    c.rawTriangles(),
                    c.drawnTriangles()
                }) {
            wire.integer(count);
        }
        var hashes = new ArrayList<String>(c.groups());
        for (int ordinal = 0; ordinal < groups.size(); ordinal++) {
            check(canceled);
            var g = groups.get(ordinal);
            var group = new Feed("vimi.global.native-group.v2");
            nativePrefix(group);
            groupBlock(group, ordinal, g);
            groupBlock(wire, ordinal, g);
            for (int n = g.batchFirst(); n < g.batchFirst() + g.batchCount(); n++) {
                batchBlock(group, batches.get(n));
            }
            for (int n = g.vertexFirst(); n < g.vertexFirst() + g.vertexCount(); n++) {
                check(canceled);
                for (int component = 0; component < 5; component++) {
                    float value = mesh.vertex(n, component);
                    require(
                            Float.isFinite(value)
                                    && Math.abs(value)
                                            <= (component < 3
                                                    ? GlobalInteriorLimits.POSITION
                                                    : GlobalInteriorLimits.UV));
                    int bits = Float.floatToRawIntBits(value);
                    require(component >= 3 || value != 0 || bits == 0);
                    group.integer(bits);
                }
            }
            for (int n = g.indexFirst(); n < g.indexFirst() + g.indexCount(); n++) {
                if ((n & 1023) == 0) {
                    check(canceled);
                }
                int value = mesh.index(n);
                require(value >= g.vertexFirst() && value < g.vertexFirst() + g.vertexCount());
                group.integer(value);
            }
            String hash = group.finish();
            hashes.add(hash);
            wire.hex(hash);
        }
        check(canceled);
        return new MeshDigests(wire.finish(), hashes);
    }

    private static void nativePrefix(Feed h) {
        h.integer(1);
        h.integer(1);
        h.longValue(0);
        h.longValue(0);
        h.longValue(0);
    }

    private static void groupBlock(Feed h, int ordinal, Group g) {
        h.integer(ordinal);
        h.integer(g.vertexFirst());
        h.integer(g.vertexCount());
        h.integer(g.indexFirst());
        h.integer(g.indexCount());
        h.integer(g.batchFirst());
        h.integer(g.batchCount());
        h.integer((int) g.flags());
    }

    private static void batchBlock(Feed h, Batch b) {
        h.byteValue(b.group());
        h.byteValue(b.material());
        h.shortValue(0);
        h.integer(b.firstIndex());
        h.integer(b.indexCount());
        h.integer(0);
    }

    public record FileDigest(String sha256, long bodyCrc32c) {
        public FileDigest {
            WorldGlobalLayout.hex(sha256, 64);
            WorldGlobalLayout.unsigned(bodyCrc32c);
        }
    }

    /** One 4KiB scratch; accepts bounded chunks without retaining or moving their cursor. */
    public static final class FileHasher {
        private final MessageDigest sha = digest();
        private final CRC32C body = new CRC32C();
        private final byte[] scratch = new byte[4096];
        private final int expected;
        private int seen;
        private boolean finished;

        public FileHasher(WorldGlobalLayout.Counts counts) {
            expected = Objects.requireNonNull(counts).offsets().actualBytes();
        }

        public void update(ByteBuffer input) {
            Objects.requireNonNull(input);
            require(
                    !finished
                            && input.remaining() <= 262144
                            && input.remaining() <= expected - seen);
            int at = input.position(), end = input.limit();
            while (at < end) {
                int n = Math.min(scratch.length, end - at);
                input.get(at, scratch, 0, n);
                int bodyStart = Math.max(0, WorldGlobalLayout.HEADER_BYTES - seen);
                if (bodyStart < n) {
                    body.update(scratch, bodyStart, n - bodyStart);
                }
                for (int i = 0; i < n; i++) {
                    if (seen + i >= 160 && seen + i < 192) {
                        scratch[i] = 0;
                    }
                }
                sha.update(scratch, 0, n);
                seen += n;
                at += n;
            }
        }

        public FileDigest finish() {
            require(!finished && seen == expected);
            finished = true;
            Arrays.fill(scratch, (byte) 0);
            return new FileDigest(HEX.formatHex(sha.digest()), body.getValue());
        }
    }

    /** Header CRC masks only its own field and the whole-file SHA hole, never bodyCRC. */
    public static long headerCrc(byte[] header) {
        require(header != null && header.length == 1024);
        var crc = new CRC32C();
        for (int n = 0; n < header.length; n++) {
            crc.update(n >= 160 && n < 192 || n >= 428 && n < 432 ? 0 : header[n] & 255);
        }
        return crc.getValue();
    }

    /**
     * Integrity only: does not validate magic, layout, descriptors, nonce, scope or source
     * authority. The future closed reader must compute its digest from the same actual opened
     * bytes.
     */
    public static void verifyIntegrity(byte[] header, FileDigest computed, String ownedSha256) {
        require(header != null && header.length == 1024);
        Objects.requireNonNull(computed);
        WorldGlobalLayout.hex(ownedSha256, 64);
        var b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        String stored = HEX.formatHex(header, 160, 192);
        require(
                stored.equals(computed.sha256())
                        && stored.equals(ownedSha256)
                        && Integer.toUnsignedLong(b.getInt(424)) == computed.bodyCrc32c()
                        && Integer.toUnsignedLong(b.getInt(428)) == headerCrc(header));
    }

    private static <T> List<T> snapshot(List<T> source, int min, int max) {
        require(source != null);
        int size = source.size();
        require(size >= min && size <= max);
        var copy = new ArrayList<T>(size);
        for (int n = 0; n < size; n++) {
            copy.add(Objects.requireNonNull(source.get(n)));
        }
        return copy;
    }

    private static void ascii(String value, int min, int max) {
        require(value != null && value.length() >= min && value.length() <= max);
        for (int n = 0; n < value.length(); n++) {
            char c = value.charAt(n);
            require(
                    c >= 'A' && c <= 'Z'
                            || c >= 'a' && c <= 'z'
                            || c >= '0' && c <= '9'
                            || c == '_'
                            || c == '-');
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final class Feed {
        private final MessageDigest digest = digest();
        private final ByteBuffer scratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

        Feed(String domain) {
            bytes(domain.getBytes(StandardCharsets.US_ASCII));
            byteValue(0);
        }

        void byteValue(int value) {
            if (!scratch.hasRemaining()) {
                flush();
            }
            scratch.put((byte) value);
        }

        void shortValue(int value) {
            byteValue(value);
            byteValue(value >>> 8);
        }

        void integer(int value) {
            for (int n = 0; n < 4; n++) {
                byteValue(value >>> (n * 8));
            }
        }

        void longValue(long value) {
            for (int n = 0; n < 8; n++) {
                byteValue((int) (value >>> (n * 8)));
            }
        }

        void bytes(byte[] value) {
            for (byte b : value) {
                byteValue(b);
            }
        }

        void hex(String value) {
            bytes(HEX.parseHex(value));
        }

        void text(String value) {
            integer(value.length());
            bytes(value.getBytes(StandardCharsets.US_ASCII));
        }

        void flush() {
            digest.update(scratch.array(), 0, scratch.position());
            scratch.clear();
        }

        String finish() {
            flush();
            return HEX.formatHex(digest.digest());
        }
    }
}
