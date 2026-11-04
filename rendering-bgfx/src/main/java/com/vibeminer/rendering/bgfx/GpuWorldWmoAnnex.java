package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded structural IPC, never game-source authentication, native ownership or GPU admission. */
public final class GpuWorldWmoAnnex {
    public static final int MAX_BYTES = 2 << 20, MAX_VALID_BYTES = 1_594_400, HEADER_BYTES = 512;
    private static final byte[] MAGIC = {'V', 'I', 'M', 'I', 'W', 'M', '0', '1'};
    private static final int[] LIMITS = {1 << 20, 2 << 20, 8 << 20, 1024, 32768, 65536};

    private GpuWorldWmoAnnex() {}

    /** Privately owned decoded arrays. Safe shape says nothing about source provenance. */
    public static final class Decoded implements BasicWmoData.View {
        private final BasicWmoData.Metadata metadata;
        private final float[] positions, normals, uv;
        private final int[] indices;
        private final long[][] materials;
        private final List<BasicWmoData.Batch> batches;
        private final List<BasicWmoData.Source> sources;
        private final List<BasicWmoData.Image> images;

        private Decoded(
                BasicWmoData.Metadata metadata,
                float[] positions,
                float[] normals,
                float[] uv,
                int[] indices,
                long[][] materials,
                List<BasicWmoData.Batch> batches,
                List<BasicWmoData.Source> sources,
                List<BasicWmoData.Image> images) {
            this.metadata = metadata;
            this.positions = positions;
            this.normals = normals;
            this.uv = uv;
            this.indices = indices;
            this.materials = materials;
            this.batches = List.copyOf(batches);
            this.sources = List.copyOf(sources);
            this.images = List.copyOf(images);
        }

        public BasicWmoData.Metadata metadata() {
            return metadata;
        }

        public FloatBuffer positions() {
            return FloatBuffer.wrap(positions).asReadOnlyBuffer();
        }

        public FloatBuffer normals() {
            return FloatBuffer.wrap(normals).asReadOnlyBuffer();
        }

        public FloatBuffer uv() {
            return FloatBuffer.wrap(uv).asReadOnlyBuffer();
        }

        public IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        public List<BasicWmoData.Batch> batches() {
            return batches;
        }

        public List<BasicWmoData.Source> sources() {
            return sources;
        }

        public int materialCount() {
            return materials.length;
        }

        public long materialWord(int material, int word) {
            return materials[material][word];
        }

        public int imageCount() {
            return images.size();
        }

        public BasicWmoData.Image image(int index) {
            return images.get(index);
        }
    }

    private record Layout(
            int vertices,
            int indices,
            int batches,
            int materials,
            int images,
            int sources,
            long bindingsAt,
            long materialsAt,
            long batchesAt,
            long imagesAt,
            long verticesAt,
            long indicesAt,
            long pixelsAt,
            long length) {}

    private record ImageDeclaration(long id, int width, int height, long offset, byte[] sha) {}

    private record Snapshot(
            BasicWmoData.Metadata meta,
            FloatBuffer positions,
            FloatBuffer normals,
            FloatBuffer uv,
            IntBuffer indices,
            List<BasicWmoData.Batch> batches,
            List<BasicWmoData.Source> sources,
            long[][] materials,
            List<BasicWmoData.Image> images,
            Layout layout) {}

    /**
     * Trusted source composition invokes this only inside its live admission/unadvertised owner.
     */
    public static void write(
            Path destination,
            GpuTerrainSceneFile.FileDigest base,
            TerrainScene scene,
            BasicWmoData.View borrow,
            BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(base);
        Objects.requireNonNull(scene);
        Objects.requireNonNull(canceled);
        check(canceled);
        base.requireScene(scene, canceled);
        var s = snapshot(borrow, scene, canceled);
        byte[] header = header(s.meta(), s.layout(), base);
        boolean owned = false;
        try (var channel =
                FileChannel.open(
                        destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            owned = true;
            var raw = new BufferedOutputStream(Channels.newOutputStream(channel), 65536);
            var sha = digest();
            hashHeader(sha, header);
            raw.write(header);
            var out = new DataOutputStream(new DigestOutputStream(raw, sha));
            for (var source : s.sources()) {
                check(canceled);
                writeSource(out, source);
            }
            for (var material : s.materials()) {
                for (long word : material) {
                    integer(out, (int) word);
                }
            }
            for (var b : s.batches()) {
                integer(out, b.firstIndex());
                integer(out, b.indexCount());
                integer(out, b.material());
                integer(out, 0);
            }
            long pixelAt = s.layout().pixelsAt();
            for (var image : s.images()) {
                integer(out, (int) image.id());
                integer(out, image.width());
                integer(out, image.height());
                integer(out, image.rgba().remaining());
                number(out, pixelAt);
                out.write(pixelHash(image.rgba(), canceled));
                out.write(new byte[8]);
                pixelAt += image.rgba().remaining();
            }
            for (int v = 0; v < s.layout().vertices(); v++) {
                check(canceled);
                for (int i = 0; i < 3; i++) {
                    integer(out, Float.floatToRawIntBits(s.positions().get(v * 3 + i)));
                }
                for (int i = 0; i < 3; i++) {
                    integer(out, Float.floatToRawIntBits(s.normals().get(v * 3 + i)));
                }
                for (int i = 0; i < 2; i++) {
                    integer(out, Float.floatToRawIntBits(s.uv().get(v * 2 + i)));
                }
            }
            for (int i = 0; i < s.layout().indices(); i++) {
                out.writeShort(Short.reverseBytes((short) s.indices().get(i)));
            }
            byte[] staging = new byte[65536];
            for (var image : s.images()) {
                var pixels = image.rgba();
                while (pixels.hasRemaining()) {
                    check(canceled);
                    int count = Math.min(staging.length, pixels.remaining());
                    pixels.get(staging, 0, count);
                    out.write(staging, 0, count);
                }
            }
            check(canceled);
            out.flush();
            require(channel.size() == s.layout().length(), "Annex write length");
            channel.position(64);
            var checksum = ByteBuffer.wrap(sha.digest());
            while (checksum.hasRemaining()) {
                check(canceled);
                channel.write(checksum);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (owned) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /** Whole-byte checksum, EOF, actual base pairing and semantics precede returning any data. */
    public static Decoded read(
            Path source, GpuTerrainSceneFile.Decoded base, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(base);
        Objects.requireNonNull(canceled);
        check(canceled);
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "Annex is not regular");
        try (var channel =
                FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = channel.size();
            require(length >= HEADER_BYTES && length <= MAX_BYTES, "Annex source size");
            var raw =
                    new DataInputStream(
                            new BufferedInputStream(Channels.newInputStream(channel), 65536));
            byte[] header = new byte[HEADER_BYTES];
            raw.readFully(header);
            var h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            require(
                    Arrays.equals(Arrays.copyOf(header, 8), MAGIC)
                            && h.getInt(8) == 1
                            && h.getInt(12) == 0,
                    "Annex magic/version");
            require(
                    h.getLong(16) == length
                            && h.getLong(24) == base.file().length()
                            && Arrays.equals(
                                    Arrays.copyOfRange(header, 32, 64), base.file().sha256()),
                    "Annex base identity");
            require(
                    h.getInt(160) == 1
                            && h.getInt(164) == 1
                            && h.getInt(408) == 1
                            && h.getInt(412) == 1,
                    "Annex recipe");
            zero(header, 236, 240);
            zero(header, 416, 512);
            var placement =
                    new BasicWmoData.Placement(
                            h.getFloat(264),
                            h.getFloat(268),
                            h.getFloat(272),
                            h.getFloat(276),
                            h.getFloat(280),
                            h.getFloat(284),
                            h.getInt(288),
                            h.getInt(292),
                            h.getInt(296),
                            h.getInt(300));
            var meta =
                    new BasicWmoData.Metadata(
                            u(h.getInt(188)),
                            h.getInt(168),
                            h.getInt(172),
                            h.getInt(176),
                            h.getInt(180),
                            u(h.getInt(184)),
                            h.getLong(192),
                            h.getLong(200),
                            hex(header, 96, 128),
                            hex(header, 128, 160),
                            u(h.getInt(232)),
                            new TerrainScene.Origin(
                                    h.getDouble(240), h.getDouble(248), h.getDouble(256)),
                            placement);
            validateMetadata(meta, base.scene(), canceled);
            double[] computed = transform(placement);
            for (int i = 0; i < computed.length; i++) {
                require(
                        Double.doubleToRawLongBits(computed[i]) == h.getLong(304 + 8 * i),
                        "Annex matrix literal");
            }
            int imageCount = h.getInt(224);
            var layout =
                    layout(
                            h.getInt(208),
                            h.getInt(212),
                            h.getInt(216),
                            h.getInt(220),
                            imageCount,
                            h.getInt(228),
                            0);
            long[] offsets = {
                layout.bindingsAt(),
                layout.materialsAt(),
                layout.batchesAt(),
                layout.imagesAt(),
                layout.verticesAt(),
                layout.indicesAt(),
                layout.pixelsAt()
            };
            for (int i = 0; i < offsets.length; i++) {
                require(h.getLong(352 + i * 8) == offsets[i], "Annex section offset");
            }
            require(length >= layout.pixelsAt(), "Annex descriptor size");
            var sha = digest();
            hashHeader(sha, header);
            var in = new DataInputStream(new DigestInputStream(raw, sha));
            var sources = new ArrayList<BasicWmoData.Source>(layout.sources());
            for (int i = 0; i < layout.sources(); i++) {
                check(canceled);
                sources.add(readSource(in));
            }
            long[][] materials = new long[layout.materials()][16];
            for (var m : materials) {
                for (int i = 0; i < 16; i++) {
                    m[i] = u(integer(in));
                }
            }
            var batches = new ArrayList<BasicWmoData.Batch>(layout.batches());
            for (int i = 0; i < layout.batches(); i++) {
                batches.add(new BasicWmoData.Batch(integer(in), integer(in), integer(in)));
                require(integer(in) == 0, "Batch reserved");
            }
            var declarations = new ArrayList<ImageDeclaration>(imageCount);
            long pixels = 0, next = layout.pixelsAt();
            for (int i = 0; i < imageCount; i++) {
                check(canceled);
                long id = u(integer(in));
                int width = integer(in), height = integer(in), bytes = integer(in);
                long at = number(in);
                int required = imageBytes(width, height);
                require(bytes == required && at == next, "Image descriptor");
                byte[] pixelSha = new byte[32];
                in.readFully(pixelSha);
                byte[] reserved = new byte[8];
                in.readFully(reserved);
                zero(reserved, 0, 8);
                declarations.add(new ImageDeclaration(id, width, height, at, pixelSha));
                pixels += (long) width * height;
                next += bytes;
            }
            var actual =
                    layout(
                            layout.vertices(),
                            layout.indices(),
                            layout.batches(),
                            layout.materials(),
                            imageCount,
                            layout.sources(),
                            pixels);
            require(actual.length() == length, "Annex canonical length");
            float[] positions = new float[layout.vertices() * 3],
                    normals = new float[layout.vertices() * 3],
                    uv = new float[layout.vertices() * 2];
            for (int v = 0; v < layout.vertices(); v++) {
                check(canceled);
                for (int i = 0; i < 3; i++) {
                    positions[v * 3 + i] = Float.intBitsToFloat(integer(in));
                }
                for (int i = 0; i < 3; i++) {
                    normals[v * 3 + i] = Float.intBitsToFloat(integer(in));
                }
                for (int i = 0; i < 2; i++) {
                    uv[v * 2 + i] = Float.intBitsToFloat(integer(in));
                }
            }
            int[] indices = new int[layout.indices()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
            }
            var images = new ArrayList<BasicWmoData.Image>(imageCount);
            for (var d : declarations) {
                check(canceled);
                byte[] rgba = new byte[imageBytes(d.width(), d.height())];
                for (int at = 0; at < rgba.length; at += 65536) {
                    check(canceled);
                    in.readFully(rgba, at, Math.min(65536, rgba.length - at));
                }
                require(
                        Arrays.equals(pixelHash(ByteBuffer.wrap(rgba), canceled), d.sha()),
                        "Image checksum");
                images.add(
                        new BasicWmoData.Image(
                                d.id(), d.width(), d.height(), ByteBuffer.wrap(rgba)));
            }
            require(
                    in.read() == -1
                            && channel.size() == length
                            && Arrays.equals(sha.digest(), Arrays.copyOfRange(header, 64, 96)),
                    "Annex checksum/EOF");
            var decoded =
                    new Decoded(
                            meta, positions, normals, uv, indices, materials, batches, sources,
                            images);
            snapshot(
                    decoded,
                    base.scene(),
                    canceled); // Same bounded semantics as writer, no payload copies.
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid WMO structural data", invalid);
        }
    }

    private static Snapshot snapshot(
            BasicWmoData.View view, TerrainScene scene, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(view);
        check(canceled);
        var meta = Objects.requireNonNull(view.metadata());
        validateMetadata(meta, scene, canceled);
        var p = view.positions().slice().asReadOnlyBuffer();
        var n = view.normals().slice().asReadOnlyBuffer();
        var uv = view.uv().slice().asReadOnlyBuffer();
        var indices = view.indices().slice().asReadOnlyBuffer();
        require(
                p.remaining() % 3 == 0
                        && n.remaining() == p.remaining()
                        && uv.remaining() == p.remaining() / 3 * 2,
                "Mesh shape");
        var batchInput = view.batches();
        var sourceInput = view.sources();
        require(
                batchInput.size() >= 1
                        && batchInput.size() <= 6
                        && sourceInput.size() >= 6
                        && sourceInput.size() <= 11,
                "Descriptor count");
        int imageCount = view.imageCount(), materialCount = view.materialCount();
        var shape =
                layout(
                        p.remaining() / 3,
                        indices.remaining(),
                        batchInput.size(),
                        materialCount,
                        imageCount,
                        sourceInput.size(),
                        0);
        var batches = List.copyOf(batchInput);
        var sources = List.copyOf(sourceInput);
        var images = new ArrayList<BasicWmoData.Image>(imageCount);
        long pixels = 0;
        for (int i = 0; i < imageCount; i++) {
            check(canceled);
            var image = view.image(i);
            int size = imageBytes(image.width(), image.height());
            require(
                    image.rgba().remaining() == size && image.id() > 0 && image.id() <= 0xffffffffL,
                    "Image shape");
            for (var prior : images) {
                require(prior.id() != image.id(), "Duplicate image");
            }
            pixelHash(image.rgba(), canceled);
            images.add(image);
            pixels += (long) image.width() * image.height();
        }
        var actual =
                layout(
                        shape.vertices(),
                        shape.indices(),
                        shape.batches(),
                        materialCount,
                        imageCount,
                        shape.sources(),
                        pixels);
        for (int i = 0; i < sources.size(); i++) {
            var s = sources.get(i);
            int role = Math.min(i, 5);
            validateSource(s);
            require(s.role() == role && s.limit() == LIMITS[role], "Source role/limit");
            if (i >= 5) {
                require(s.id() == images.get(i - 5).id(), "Image source order");
            }
        }
        long[][] materials = new long[materialCount][16];
        for (int m = 0; m < materialCount; m++) {
            for (int word = 0; word < 16; word++) {
                long value = view.materialWord(m, word);
                require(value >= 0 && value <= 0xffffffffL, "Material word");
                materials[m][word] = value;
            }
            for (int word : new int[] {0, 1, 2, 6, 9, 11, 12, 13, 14, 15}) {
                require(materials[m][word] == 0, "Material profile");
            }
            int found = 0;
            for (var image : images) {
                if (image.id() == materials[m][3]) {
                    found++;
                }
            }
            require(found == 1, "Material texture");
        }
        // Canonical image order is first material use, including unused recorded materials.
        int nextImage = 0;
        var encountered = new ArrayList<Long>(6);
        for (var m : materials) {
            if (!encountered.contains(m[3])) {
                require(
                        nextImage < images.size() && images.get(nextImage++).id() == m[3],
                        "Material image order");
                encountered.add(m[3]);
            }
        }
        require(nextImage == imageCount, "Unreferenced image");
        boolean[] covered = new boolean[indices.remaining()];
        for (var b : batches) {
            require(
                    b.firstIndex() >= 0
                            && b.firstIndex() % 3 == 0
                            && b.indexCount() > 0
                            && b.indexCount() % 3 == 0
                            && b.indexCount() <= indices.remaining() - b.firstIndex()
                            && b.material() >= 0
                            && b.material() < materialCount,
                    "Batch profile");
            for (int i = b.firstIndex(); i < b.firstIndex() + b.indexCount(); i++) {
                require(!covered[i], "Batch overlap");
                covered[i] = true;
            }
        }
        for (boolean c : covered) {
            require(c, "Batch gap");
        }
        for (int i = 0; i < indices.remaining(); i++) {
            require(indices.get(i) >= 0 && indices.get(i) < shape.vertices(), "Mesh index");
        }
        double[] t = transform(meta.placement());
        var model = modelToOrigin(meta);
        for (int v = 0; v < shape.vertices(); v++) {
            check(canceled);
            double x = p.get(v * 3), y = p.get(v * 3 + 1), z = p.get(v * 3 + 2);
            require(
                    Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z),
                    "Mesh position");
            for (double q :
                    new double[] {
                        t[0] + t[3] * (-t[4] * x + t[5] * y),
                        t[1] + t[3] * (-t[5] * x - t[4] * y),
                        t[2] + t[3] * z
                    }) {
                require(
                        Double.isFinite(q) && Math.abs(q) <= TerrainScene.MAX_COORDINATE,
                        "Transformed coordinate");
            }
            for (int row = 0; row < 3; row++) {
                float relative =
                        model.get(row) * (float) x
                                + model.get(row + 4) * (float) y
                                + model.get(row + 8) * (float) z
                                + model.get(row + 12);
                require(
                        Float.isFinite(relative)
                                && Math.abs((double) relative) <= 2 * TerrainScene.MAX_COORDINATE,
                        "Float transformed coordinate");
            }
            double a = n.get(v * 3),
                    b = n.get(v * 3 + 1),
                    c = n.get(v * 3 + 2),
                    squared = a * a + b * b + c * c;
            require(
                    Double.isFinite(squared) && Math.abs(StrictMath.sqrt(squared) - 1) <= .00001,
                    "Mesh normal");
            require(Float.isFinite(uv.get(v * 2)) && Float.isFinite(uv.get(v * 2 + 1)), "Mesh UV");
        }
        return new Snapshot(meta, p, n, uv, indices, batches, sources, materials, images, actual);
    }

    private static void validateMetadata(
            BasicWmoData.Metadata m, TerrainScene scene, BooleanSupplier canceled)
            throws IOException {
        Objects.requireNonNull(m);
        Objects.requireNonNull(m.origin());
        Objects.requireNonNull(m.placement());
        hash(m.scopeDigest());
        hash(m.geometryDigest());
        require(
                m.mapId() >= 0
                        && m.mapId() <= 0xffffffffL
                        && m.uniqueId() >= 0
                        && m.uniqueId() <= 0xffffffffL
                        && m.ambientColor() >= 0
                        && m.ambientColor() <= 0xffffffffL
                        && m.owner() != 0
                        && m.generation() > 0
                        && m.modfOrdinal() >= 0
                        && m.modfOrdinal() < 4096
                        && m.tileOrdinal() >= 0
                        && m.tileOrdinal() < scene.tiles().size(),
                "Metadata scope");
        var tile = scene.tiles().get(m.tileOrdinal());
        require(
                tile.x() == m.tileX()
                        && tile.y() == m.tileY()
                        && m.geometryDigest().equals(TerrainGeometryFingerprint.sha256(tile)),
                "Selected geometry");
        require(
                same(m.origin().x(), scene.origin().x())
                        && same(m.origin().y(), scene.origin().y())
                        && same(m.origin().z(), scene.origin().z()),
                "Shared origin");
        var p = m.placement();
        for (float f : new float[] {p.x(), p.y(), p.z(), p.rx(), p.ry(), p.rz()}) {
            require(Float.isFinite(f), "Placement finite");
        }
        require(
                p.flags() == 12
                        && p.scale() > 0
                        && p.scale() <= 65535
                        && p.rx() == 0
                        && p.rz() == 0
                        && p.doodadSet() == 0
                        && p.nameSet() == 0,
                "Placement profile");
        check(canceled);
    }

    private static Layout layout(
            int vertices,
            int indices,
            int batches,
            int materials,
            int images,
            int sources,
            long pixels)
            throws IOException {
        require(
                vertices >= 1
                        && vertices <= 512
                        && indices >= 3
                        && indices <= 1536
                        && indices % 3 == 0
                        && batches >= 1
                        && batches <= 6
                        && materials >= 1
                        && materials <= 6
                        && images >= 1
                        && images <= 6
                        && sources == 5 + images
                        && pixels >= 0
                        && pixels <= 393216,
                "Count limit");
        long a = 512,
                b = a + sources * 64L,
                c = b + materials * 64L,
                d = c + batches * 16L,
                e = d + images * 64L,
                f = e + vertices * 32L,
                g = f + indices * 2L,
                length = g + pixels * 4;
        require(length <= MAX_VALID_BYTES && length <= MAX_BYTES, "Body limit");
        return new Layout(
                vertices, indices, batches, materials, images, sources, a, b, c, d, e, f, g,
                length);
    }

    private static byte[] header(
            BasicWmoData.Metadata m, Layout l, GpuTerrainSceneFile.FileDigest base) {
        var h = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        h.put(MAGIC)
                .putInt(1)
                .putInt(0)
                .putLong(l.length())
                .putLong(base.length())
                .put(base.sha256());
        h.position(96);
        h.put(HexFormat.of().parseHex(m.scopeDigest()))
                .put(HexFormat.of().parseHex(m.geometryDigest()));
        h.putInt(1)
                .putInt(1)
                .putInt(m.tileOrdinal())
                .putInt(m.tileX())
                .putInt(m.tileY())
                .putInt(m.modfOrdinal())
                .putInt((int) m.uniqueId())
                .putInt((int) m.mapId())
                .putLong(m.owner())
                .putLong(m.generation());
        h.putInt(l.vertices())
                .putInt(l.indices())
                .putInt(l.batches())
                .putInt(l.materials())
                .putInt(l.images())
                .putInt(l.sources())
                .putInt((int) m.ambientColor())
                .putInt(0);
        h.putDouble(m.origin().x()).putDouble(m.origin().y()).putDouble(m.origin().z());
        var p = m.placement();
        h.putFloat(p.x())
                .putFloat(p.y())
                .putFloat(p.z())
                .putFloat(p.rx())
                .putFloat(p.ry())
                .putFloat(p.rz())
                .putInt(p.scale())
                .putInt(p.flags())
                .putInt(p.doodadSet())
                .putInt(p.nameSet());
        for (double v : transform(p)) {
            h.putDouble(v);
        }
        for (long v :
                new long[] {
                    l.bindingsAt(),
                    l.materialsAt(),
                    l.batchesAt(),
                    l.imagesAt(),
                    l.verticesAt(),
                    l.indicesAt(),
                    l.pixelsAt()
                }) {
            h.putLong(v);
        }
        h.putInt(1).putInt(1);
        return h.array();
    }

    private static double[] transform(BasicWmoData.Placement p) {
        return new double[] {p.tx(), p.ty(), p.tz(), p.size(), p.cosine(), p.sine()};
    }

    private static boolean same(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    private static void validateSource(BasicWmoData.Source s) throws IOException {
        require(
                s.id() > 0
                        && s.id() <= 0xffffffffL
                        && s.role() >= 0
                        && s.role() <= 5
                        && s.length() > 0
                        && s.limit() == LIMITS[s.role()]
                        && s.length() <= s.limit()
                        && (s.declaredLength() == null || s.declaredLength() == s.length()),
                "Source descriptor");
        require(
                s.key() != null && s.key().length() == 32 && s.key().matches("[0-9a-f]{32}"),
                "Source key");
    }

    private static void writeSource(DataOutputStream out, BasicWmoData.Source s)
            throws IOException {
        integer(out, (int) s.id());
        integer(out, s.role());
        out.write(HexFormat.of().parseHex(s.key()));
        number(out, s.length());
        number(out, s.declaredLength() == null ? -1 : s.declaredLength());
        integer(out, s.limit());
        out.write(new byte[20]);
    }

    private static BasicWmoData.Source readSource(DataInputStream in) throws IOException {
        long id = u(integer(in));
        int role = integer(in);
        byte[] key = new byte[16];
        in.readFully(key);
        long length = number(in), declared = number(in);
        int limit = integer(in);
        byte[] reserved = new byte[20];
        in.readFully(reserved);
        zero(reserved, 0, 20);
        var s =
                new BasicWmoData.Source(
                        id,
                        role,
                        HexFormat.of().formatHex(key),
                        length,
                        declared == -1 ? null : declared,
                        limit);
        validateSource(s);
        return s;
    }

    private static int imageBytes(int width, int height) throws IOException {
        require(width >= 1 && width <= 256 && height >= 1 && height <= 256, "Image dimensions");
        return width * height * 4;
    }

    private static byte[] pixelHash(ByteBuffer pixels, BooleanSupplier canceled)
            throws IOException {
        var b = pixels.slice();
        var sha = digest();
        require(b.remaining() % 4 == 0, "Pixel alignment");
        for (int i = 3; i < b.remaining(); i += 4) {
            if ((i & 4095) == 3) {
                check(canceled);
            }
            require(b.get(i) == (byte) 255, "Pixel alpha");
        }
        check(canceled);
        sha.update(b);
        check(canceled);
        return sha.digest();
    }

    private static void hash(String value) throws IOException {
        require(
                value != null && value.length() == 64 && value.matches("[0-9a-f]{64}"),
                "Digest text");
    }

    private static String hex(byte[] bytes, int start, int end) {
        return HexFormat.of().formatHex(bytes, start, end);
    }

    private static void hashHeader(MessageDigest sha, byte[] header) {
        sha.update(header, 0, 64);
        sha.update(header, 96, 416);
    }

    private static void zero(byte[] bytes, int start, int end) throws IOException {
        for (int i = start; i < end; i++) {
            require(bytes[i] == 0, "Reserved bytes");
        }
    }

    private static long u(int n) {
        return Integer.toUnsignedLong(n);
    }

    private static int integer(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static long number(DataInputStream in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }

    private static void integer(DataOutputStream out, int n) throws IOException {
        out.writeInt(Integer.reverseBytes(n));
    }

    private static void number(DataOutputStream out, long n) throws IOException {
        out.writeLong(Long.reverseBytes(n));
    }

    private static void require(boolean test, String reason) throws IOException {
        if (!test) {
            throw new IOException(reason);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("WMO annex canceled");
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Shared-origin uniform oracle; no native context or source authority is created here. */
    public static FloatBuffer modelToOrigin(BasicWmoData.Metadata metadata) {
        var p = metadata.placement();
        double s = p.size(), co = p.cosine(), si = p.sine();
        var o = metadata.origin();
        return FloatBuffer.wrap(
                        new float[] {
                            (float) (-s * co),
                            (float) (-s * si),
                            0,
                            0,
                            (float) (s * si),
                            (float) (-s * co),
                            0,
                            0,
                            0,
                            0,
                            (float) s,
                            0,
                            (float) (p.tx() - o.x()),
                            (float) (p.ty() - o.y()),
                            (float) (p.tz() - o.z()),
                            1
                        })
                .asReadOnlyBuffer();
    }
}
