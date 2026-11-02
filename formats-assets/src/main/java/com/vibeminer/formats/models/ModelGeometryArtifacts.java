package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.GeometryTypes.Artifact;
import com.vibeminer.formats.models.GeometryTypes.Bounds;
import com.vibeminer.formats.models.GeometryTypes.BufferRegion;
import com.vibeminer.formats.models.GeometryTypes.Evaluations;
import com.vibeminer.formats.models.GeometryTypes.Manifest;
import com.vibeminer.formats.models.GeometryTypes.OriginalSource;
import com.vibeminer.formats.models.GeometryTypes.SourceBinding;
import com.vibeminer.formats.models.M2Types.GeometrySkin;
import com.vibeminer.formats.models.M2Types.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** VMG1 derivatives bind exact receipts but never acquire or authorize original sources. */
@SuppressWarnings("try")
public final class ModelGeometryArtifacts {
    public static final String REVISION = "model-geometry-vmg1-v1",
            LINE_POLICY = "all-sections-triangle-edges-v1";
    public static final int HEADER_BYTES = 128, MAXIMUM_BYTES = 2 << 20;
    private static final int BUFFER_BYTES = 65536,
            MAXIMUM_VERTICES = 65536,
            MAXIMUM_INDICES = 131070,
            MAXIMUM_SECTIONS = 256;

    private ModelGeometryArtifacts() {}

    private static ModelFormatException invalid(String text) {
        return new ModelFormatException("GEOMETRY_ARTIFACT_INVALID", text);
    }

    private static ModelFormatException limit(String text) {
        return new ModelFormatException("GEOMETRY_ARTIFACT_LIMIT", text);
    }

    private static ModelFormatException unsupported(String text) {
        return new ModelFormatException("GEOMETRY_ARTIFACT_UNSUPPORTED", text);
    }

    public static CompletableFuture<Artifact> build(
            GeometrySkin skin,
            SourceBinding binding,
            SeekableByteChannel output,
            Cancellation token) {
        return build(skin, binding, output, GeometryArtifactWorkBudget.UNBUDGETED, token);
    }

    public static CompletableFuture<Artifact> build(
            GeometrySkin skin,
            SourceBinding binding,
            SeekableByteChannel output,
            GeometryArtifactWorkBudget budget,
            Cancellation token) {
        return AssetTasks.run(
                () -> {
                    try {
                        return buildCore(skin, binding, output, budget, token);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    public static CompletableFuture<Void> verify(
            AssetSource source, Artifact descriptor, Cancellation token) {
        return verify(source, descriptor, GeometryArtifactWorkBudget.UNBUDGETED, token);
    }

    public static CompletableFuture<Void> verify(
            AssetSource source,
            Artifact descriptor,
            GeometryArtifactWorkBudget budget,
            Cancellation token) {
        return AssetTasks.run(
                () -> {
                    verifyCore(source, descriptor, budget, token);
                    return null;
                });
    }

    public static String computeBindingSha256(SourceBinding binding, Cancellation token) {
        token.check();
        String hash = hex(bindingHash(binding));
        token.check();
        return hash;
    }

    private static GeometryArtifactWorkBudget.Scope enter(
            GeometryArtifactWorkBudget budget, Cancellation token) {
        token.check();
        var scope = Objects.requireNonNull(budget.enter(token).join(), "work scope");
        try {
            token.check();
            return scope;
        } catch (RuntimeException | Error e) {
            scope.close();
            throw e;
        }
    }

    private static Artifact buildCore(
            GeometrySkin skin,
            SourceBinding binding,
            SeekableByteChannel output,
            GeometryArtifactWorkBudget budget,
            Cancellation token)
            throws IOException {
        Objects.requireNonNull(skin);
        Objects.requireNonNull(output);
        Objects.requireNonNull(budget);
        token.check();
        if (output.position() != 0 || output.size() != 0) {
            throw invalid(
                    "Caller-owned output must be writable, seekable and empty at position zero.");
        }
        var model = skin.model();
        byte[] bindingHash;
        long sum = 0;
        try (var ignored = enter(budget, token)) {
            bindingHash = bindingHash(binding);
            if (skin.retainedBytes() > 8L << 20) {
                throw limit("Typed model/SKIN output exceeds initial eight-MiB parser allowance.");
            }
            for (var d : model.declarations()) {
                token.check();
                if (!Set.of(
                                        "vertices",
                                        "collisionTriangles",
                                        "collisionVertices",
                                        "collisionNormals",
                                        "name")
                                .contains(d.name())
                        && d.count() > 8192) {
                    throw limit("Model metadata exceeds initial delivery profile.");
                }
            }
            if (model.boneFileDataIds().size() > 8192
                    || model.animationFiles().size() > 8192
                    || model.skinFileDataIds().size() + model.lodSkinFileDataIds().size() > 32) {
                throw limit("Model dependency metadata exceeds initial delivery profile.");
            }
            if (M2GeometrySkinReader.profileRefusal(model) != null) {
                throw unsupported("Artifact requires finite geometry-only model profile.");
            }
            if (model.skinFileDataIds().isEmpty()
                    || model.skinFileDataIds().getFirst() != binding.skinFileDataId()
                    || binding.model().decodedBytes() != model.sourceBytes()
                    || binding.skin().decodedBytes() != skin.sourceBytes()) {
                throw invalid(
                        "Exact main-SKIN identity or decoded source length differs from typed input.");
            }
            for (var section : skin.sections()) {
                token.check();
                sum = Math.addExact(sum, section.triangleCount());
            }
            if (sum == 0) {
                throw unsupported("Main SKIN has no declared triangles.");
            }
            if (sum > MAXIMUM_INDICES) {
                throw limit("Summed section expansion exceeds artifact profile.");
            }
            counts(
                    model.vertices().size(),
                    skin.vertexLookup().size(),
                    skin.triangleLookup().size(),
                    (int) sum,
                    skin.sections().size());
        }
        M2StaticGeometry geometry;
        try (var ignored = enter(budget, token)) {
            geometry = M2StaticGeometry.create(model, skin, token);
        }
        Header h;
        byte[] header;
        try (var ignored = enter(budget, token)) {
            Bounds bounds =
                    new Bounds(
                            canonical(geometry.bounds().minimum()),
                            canonical(geometry.bounds().maximum()));
            boundsValid(bounds);
            h =
                    new Header(
                            model.vertices().size(),
                            skin.vertexLookup().size(),
                            skin.triangleLookup().size(),
                            (int) sum,
                            skin.sections().size(),
                            bounds);
            if (h.length() > MAXIMUM_BYTES) {
                throw limit("Artifact exceeds twoMiB.");
            }
            header = header(h, bindingHash);
        }
        MessageDigest hash = sha();
        byte[] buffer = new byte[BUFFER_BYTES];
        write(output, hash, header, header.length, budget, token);
        for (int first = 0; first < model.vertices().size(); ) {
            int count = Math.min(BUFFER_BYTES / 60, model.vertices().size() - first);
            try (var ignored = enter(budget, token)) {
                for (int i = 0; i < count; i++) {
                    if ((i & 4095) == 0) {
                        token.check();
                    }
                    Vec3 position = model.vertices().get(first + i).position();
                    if (!finite(position)) {
                        throw invalid("Source position is not finite.");
                    }
                    putFloat(buffer, i * 12, position.x());
                    putFloat(buffer, i * 12 + 4, position.y());
                    putFloat(buffer, i * 12 + 8, position.z());
                }
            }
            write(output, hash, buffer, count * 12, budget, token);
            first += count;
        }
        int ordinal = 0, triangle = 0;
        while (ordinal < geometry.sections().size()) {
            int used = 0, workBytes = 0;
            try (var ignored = enter(budget, token)) {
                while (ordinal < geometry.sections().size()
                        && used + 24 <= BUFFER_BYTES / 100 * 24
                        && workBytes + 100 <= BUFFER_BYTES) {
                    var section = geometry.sections().get(ordinal);
                    workBytes += 64;
                    if (triangle >= section.triangleCount()) {
                        ordinal++;
                        triangle = 0;
                        continue;
                    }
                    if ((triangle & 511) == 0) {
                        token.check();
                    }
                    int n = Math.toIntExact(section.triangleStart() + triangle);
                    int a = geometry.indices().get(n),
                            b = geometry.indices().get(n + 1),
                            c = geometry.indices().get(n + 2);
                    put(buffer, used, a);
                    put(buffer, used + 4, b);
                    put(buffer, used + 8, b);
                    put(buffer, used + 12, c);
                    put(buffer, used + 16, c);
                    put(buffer, used + 20, a);
                    used += 24;
                    triangle += 3;
                    workBytes += 36;
                }
            }
            if (used != 0) {
                write(output, hash, buffer, used, budget, token);
            }
        }
        int directoryBytes = 0, lineStart = 0;
        try (var ignored = enter(budget, token)) {
            for (int i = 0; i < geometry.sections().size(); i++) {
                token.check();
                var section = geometry.sections().get(i);
                put(buffer, directoryBytes, i);
                word(buffer, directoryBytes + 4, section.id());
                word(buffer, directoryBytes + 6, section.level());
                put(buffer, directoryBytes + 8, section.vertexStart());
                put(buffer, directoryBytes + 12, section.vertexCount());
                put(buffer, directoryBytes + 16, section.triangleStart());
                put(buffer, directoryBytes + 20, section.triangleCount());
                put(buffer, directoryBytes + 24, lineStart);
                put(buffer, directoryBytes + 28, section.triangleCount() * 2L);
                directoryBytes += 32;
                lineStart += section.triangleCount() * 2;
            }
        }
        write(output, hash, buffer, directoryBytes, budget, token);
        token.check();
        if (output.position() != h.length() || output.size() != h.length()) {
            throw invalid("Output length changed during derivative writing.");
        }
        try (var ignored = enter(budget, token)) {
            byte[] digest = hash.digest();
            String bound = hex(bindingHash),
                    sha = hex(digest),
                    id = artifactId(bindingHash, digest, h.length());
            return new Artifact(
                    binding, bound, id, sha, h.length(), manifest(binding, bound, id, sha, h));
        }
    }

    private static void write(
            SeekableByteChannel output,
            MessageDigest hash,
            byte[] bytes,
            int count,
            GeometryArtifactWorkBudget budget,
            Cancellation token)
            throws IOException {
        try (var ignored = enter(budget, token)) {
            hash.update(bytes, 0, count);
        }
        token.check();
        ByteBuffer b = ByteBuffer.wrap(bytes, 0, count);
        while (b.hasRemaining()) {
            token.check();
            output.write(b);
        }
        token.check();
    }

    private static void verifyCore(
            AssetSource source,
            Artifact descriptor,
            GeometryArtifactWorkBudget budget,
            Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(budget);
        token.check();
        Header h;
        byte[] binding, expected;
        try (var ignored = enter(budget, token)) {
            h = validateDescriptor(descriptor);
            binding = bindingHash(descriptor.binding());
            expected = parseHex(descriptor.sha256(), 32);
        }
        if (source.length() != h.length()) {
            throw invalid("Caller-owned artifact input must be complete and have exact length.");
        }
        MessageDigest hash = sha();
        byte[] actualHeader = read(source, 0, HEADER_BYTES, hash, budget, token);
        try (var ignored = enter(budget, token)) {
            if (!Arrays.equals(actualHeader, header(h, binding))) {
                throw invalid("VMG1 header differs from exact descriptor/framing.");
            }
        }
        byte[] positions = new byte[h.vertices * 12];
        for (int at = 0; at < positions.length; ) {
            int count = Math.min(BUFFER_BYTES, positions.length - at);
            byte[] part = read(source, HEADER_BYTES + at, count, hash, budget, token);
            System.arraycopy(part, 0, positions, at, count);
            at += count;
        }
        for (int first = 0; first < h.vertices; ) {
            int count = Math.min(BUFFER_BYTES / 12, h.vertices - first);
            try (var ignored = enter(budget, token)) {
                for (int i = 0; i < count; i++) {
                    if ((i & 4095) == 0) {
                        token.check();
                    }
                    if (!finite(vector(positions, (first + i) * 12))) {
                        throw invalid("Artifact position is not finite.");
                    }
                }
            }
            first += count;
        }
        float[] bounds = {
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        };
        for (int first = 0; first < h.lines(); ) {
            int count = Math.min(BUFFER_BYTES / 24 * 6, h.lines() - first);
            byte[] buffer =
                    read(source, h.lineOffset() + (long) first * 4, count * 4, hash, budget, token);
            for (int start = 0; start < buffer.length; ) {
                int end = Math.min(buffer.length, start + BUFFER_BYTES / 60 * 24);
                try (var ignored = enter(budget, token)) {
                    for (int at = start; at < end; at += 24) {
                        if ((at / 24 & 511) == 0) {
                            token.check();
                        }
                        long a = u(buffer, at), b = u(buffer, at + 4), c = u(buffer, at + 12);
                        if (a >= h.vertices
                                || b >= h.vertices
                                || c >= h.vertices
                                || u(buffer, at + 8) != b
                                || u(buffer, at + 16) != c
                                || u(buffer, at + 20) != a) {
                            throw invalid(
                                    "App edge group differs from bounded triangle-pair pattern.");
                        }
                        include(bounds, vector(positions, (int) a * 12));
                        include(bounds, vector(positions, (int) b * 12));
                        include(bounds, vector(positions, (int) c * 12));
                    }
                }
                start = end;
            }
            first += count;
        }
        byte[] directory = read(source, h.directoryOffset(), h.sections * 32, hash, budget, token);
        long cumulative = 0;
        try (var ignored = enter(budget, token)) {
            for (int i = 0; i < h.sections; i++) {
                token.check();
                int at = i * 32;
                long vertexStart = u(directory, at + 8),
                        vertexCount = u(directory, at + 12),
                        start = u(directory, at + 16),
                        count = u(directory, at + 20);
                if (u(directory, at) != i
                        || vertexStart > 65535
                        || vertexCount > 65535
                        || count > 65535
                        || start >>> 16 != w(directory, at + 6)
                        || start % 3 != 0
                        || count % 3 != 0
                        || vertexStart + vertexCount > h.lookup
                        || start + count > h.globalIndices
                        || count != 0 && vertexCount == 0
                        || u(directory, at + 24) != cumulative
                        || u(directory, at + 28) != count * 2) {
                    throw invalid(
                            "Directory violates section provenance or exact line partitioning.");
                }
                cumulative += count * 2;
            }
            Bounds actual =
                    new Bounds(
                            canonical(new Vec3(bounds[0], bounds[1], bounds[2])),
                            canonical(new Vec3(bounds[3], bounds[4], bounds[5])));
            if (cumulative != h.lines() || !actual.equals(h.bounds)) {
                throw invalid(
                        "Section partition or tight referenced bounds differ from declaration.");
            }
        }
        token.check();
        if (source.length() != h.length()) {
            throw invalid("Artifact length changed during verification.");
        }
        try (var ignored = enter(budget, token)) {
            if (!MessageDigest.isEqual(hash.digest(), expected)) {
                throw invalid("Complete artifact SHA256 differs from descriptor.");
            }
        }
    }

    private static Header validateDescriptor(Artifact d) {
        if (d == null || d.manifest() == null) {
            throw invalid("Engine artifact descriptor required.");
        }
        byte[] binding = bindingHash(d.binding());
        String bound = hex(binding);
        parseHex(d.bindingSha256(), 32);
        parseHex(d.artifactId(), 32);
        byte[] hash = parseHex(d.sha256(), 32);
        if (d.byteLength() > MAXIMUM_BYTES) {
            throw limit("Artifact exceeds twoMiB.");
        }
        Manifest m = d.manifest();
        counts(
                m.vertexCount(),
                m.vertexLookupCount(),
                m.sourceTriangleIndexCount(),
                m.sectionIndexCount(),
                m.sectionCount());
        boundsValid(m.bounds());
        Header h =
                new Header(
                        m.vertexCount(),
                        m.vertexLookupCount(),
                        m.sourceTriangleIndexCount(),
                        m.sectionIndexCount(),
                        m.sectionCount(),
                        m.bounds());
        if (!d.bindingSha256().equals(bound)
                || d.byteLength() != h.length()
                || !d.artifactId().equals(artifactId(binding, hash, h.length()))
                || !m.equals(manifest(d.binding(), bound, d.artifactId(), d.sha256(), h))) {
            throw invalid("Canonical descriptor, revisions or manifest fields differ.");
        }
        return h;
    }

    private static byte[] read(
            AssetSource source,
            long offset,
            int count,
            MessageDigest hash,
            GeometryArtifactWorkBudget budget,
            Cancellation token) {
        token.check();
        byte[] bytes;
        try {
            bytes = source.readAt(offset, count, token).join();
        } catch (RuntimeException e) {
            if (AssetTasks.unwrap(e) instanceof java.io.EOFException) {
                throw invalid("VMG1 artifact is truncated.");
            }
            throw e;
        }
        if (bytes.length != count) {
            throw invalid("VMG1 artifact is truncated.");
        }
        try (var ignored = enter(budget, token)) {
            hash.update(bytes);
        }
        return bytes;
    }

    private record Header(
            int vertices,
            int lookup,
            int globalIndices,
            int sectionIndices,
            int sections,
            Bounds bounds) {
        int lines() {
            return sectionIndices * 2;
        }

        int lineOffset() {
            return HEADER_BYTES + vertices * 12;
        }

        int directoryOffset() {
            return lineOffset() + lines() * 4;
        }

        int length() {
            return directoryOffset() + sections * 32;
        }
    }

    private static Manifest manifest(
            SourceBinding b, String bound, String id, String sha, Header h) {
        return new Manifest(
                id,
                bound,
                sha,
                h.length(),
                1,
                HEADER_BYTES,
                "unfiltered-declared-sections",
                M2StaticGeometry.COORDINATE_POLICY,
                new Evaluations("notEvaluated", "notEvaluated", "notEvaluated", "notEvaluated"),
                h.vertices,
                h.lookup,
                h.globalIndices,
                h.sectionIndices,
                h.lines(),
                h.sections,
                new BufferRegion(HEADER_BYTES, h.vertices * 12),
                new BufferRegion(h.lineOffset(), h.lines() * 4),
                new BufferRegion(h.directoryOffset(), h.sections * 32),
                h.bounds,
                b.modelFileDataId(),
                b.skinFileDataId(),
                0,
                M2ModelReader.REVISION,
                M2GeometrySkinReader.REVISION,
                GeometrySkin.INTERPRETATION_POLICY,
                REVISION,
                LINE_POLICY);
    }

    private static byte[] header(Header h, byte[] binding) {
        byte[] b = new byte[HEADER_BYTES];
        b[0] = 'V';
        b[1] = 'M';
        b[2] = 'G';
        b[3] = '1';
        word(b, 4, 1);
        word(b, 6, HEADER_BYTES);
        put(b, 8, h.length());
        put(b, 12, 1);
        System.arraycopy(binding, 0, b, 16, 32);
        put(b, 48, h.vertices);
        put(b, 52, h.lines());
        put(b, 56, h.sections);
        put(b, 60, h.lookup);
        put(b, 64, HEADER_BYTES);
        put(b, 68, h.vertices * 12L);
        put(b, 72, h.lineOffset());
        put(b, 76, h.lines() * 4L);
        put(b, 80, h.directoryOffset());
        put(b, 84, h.sections * 32L);
        for (int i = 0; i < 3; i++) {
            putFloat(b, 88 + i * 4, h.bounds.min().get(i));
            putFloat(b, 100 + i * 4, h.bounds.max().get(i));
        }
        put(b, 112, h.sectionIndices);
        put(b, 116, 1);
        put(b, 120, h.globalIndices);
        return b;
    }

    private static byte[] bindingHash(SourceBinding b) {
        if (b == null
                || b.model() == null
                || b.skin() == null
                || b.modelFileDataId() < 1
                || b.modelFileDataId() > 0xffffffffL
                || b.skinFileDataId() < 1
                || b.skinFileDataId() > 0xffffffffL
                || b.mainSkinOrdinal() != 0) {
            throw invalid("Binding requires positive exact identities and main ordinal zero.");
        }
        MessageDigest hash = sha();
        hash.update("vibeminer-model-geometry-binding-v1\0".getBytes(StandardCharsets.US_ASCII));
        sourceHash(hash, b.model());
        sourceHash(hash, b.skin());
        byte[] ids = new byte[12];
        put(ids, 0, b.modelFileDataId());
        put(ids, 4, b.skinFileDataId());
        hash.update(ids);
        for (String text :
                List.of(
                        M2ModelReader.REVISION,
                        M2GeometrySkinReader.REVISION,
                        GeometrySkin.INTERPRETATION_POLICY,
                        M2StaticGeometry.COORDINATE_POLICY,
                        REVISION,
                        LINE_POLICY)) {
            text(hash, text);
        }
        byte[] codes = new byte[10];
        put(codes, 0, 1);
        put(codes, 4, 1);
        word(codes, 8, 1);
        hash.update(codes);
        return hash.digest();
    }

    private static void sourceHash(MessageDigest hash, OriginalSource source) {
        hash.update(parseHex(source.contentKey(), 16));
        hash.update(parseHex(source.encodingKey(), 16));
        hash.update(parseHex(source.encodedSha256(), 32));
        if (source.encodedBytes() <= 0
                || source.decodedBytes() <= 0
                || !"content-blte-v1".equals(source.verifierRevision())) {
            throw invalid(
                    "Source binding needs positive lengths and original full verifier revision.");
        }
        if (source.encodedBytes() > 4L << 20 || source.decodedBytes() > 8L << 20) {
            throw limit("Source exceeds initial delivery profile.");
        }
        byte[] sizes =
                ByteBuffer.allocate(16)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(source.encodedBytes())
                        .putLong(source.decodedBytes())
                        .array();
        hash.update(sizes);
        text(hash, source.verifierRevision());
    }

    private static void text(MessageDigest hash, String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 128
                || value.chars().anyMatch(c -> c < ' ' || c > '~')) {
            throw invalid("Binding revision must be bounded printable ASCII.");
        }
        byte[] length = new byte[2];
        word(length, 0, value.length());
        hash.update(length);
        hash.update(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String artifactId(byte[] binding, byte[] fileHash, long length) {
        MessageDigest hash = sha();
        hash.update("vibeminer-model-geometry-artifact-v1\0".getBytes(StandardCharsets.US_ASCII));
        hash.update(binding);
        hash.update(fileHash);
        hash.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(length).array());
        return hex(hash.digest());
    }

    private static byte[] parseHex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2 || !value.matches("[0-9a-f]+")) {
            throw invalid("Artifact identities require canonical lowercase hexadecimal.");
        }
        return HexFormat.of().parseHex(value);
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static void counts(int vertices, int lookup, int global, int sum, int sections) {
        if (vertices < 1
                || lookup < 1
                || global < 1
                || sum < 1
                || sections < 1
                || global % 3 != 0
                || sum % 3 != 0) {
            throw invalid("Geometry counts are empty or not triangle-aligned.");
        }
        if (vertices > MAXIMUM_VERTICES
                || lookup > MAXIMUM_VERTICES
                || global > MAXIMUM_INDICES
                || sum > MAXIMUM_INDICES
                || sections > MAXIMUM_SECTIONS) {
            throw limit("Geometry exceeds finite initial artifact profile.");
        }
    }

    private static void boundsValid(Bounds bounds) {
        if (bounds == null || bounds.min().size() != 3 || bounds.max().size() != 3) {
            throw invalid("Bounds are absent or malformed.");
        }
        for (int i = 0; i < 3; i++) {
            float a = bounds.min().get(i), b = bounds.max().get(i);
            if (!Float.isFinite(a)
                    || !Float.isFinite(b)
                    || a > b
                    || Float.floatToIntBits(a) != Float.floatToIntBits(zero(a))
                    || Float.floatToIntBits(b) != Float.floatToIntBits(zero(b))) {
                throw invalid("Bounds must be finite, ordered and canonical zero bits.");
            }
        }
    }

    private static float zero(float f) {
        return f == 0 ? 0 : f;
    }

    private static Vec3 canonical(Vec3 v) {
        return new Vec3(zero(v.x()), zero(v.y()), zero(v.z()));
    }

    private static boolean finite(Vec3 v) {
        return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z());
    }

    private static void include(float[] bounds, Vec3 p) {
        bounds[0] = Math.min(bounds[0], p.x());
        bounds[1] = Math.min(bounds[1], p.y());
        bounds[2] = Math.min(bounds[2], p.z());
        bounds[3] = Math.max(bounds[3], p.x());
        bounds[4] = Math.max(bounds[4], p.y());
        bounds[5] = Math.max(bounds[5], p.z());
    }

    private static Vec3 vector(byte[] b, int at) {
        return new Vec3(
                Float.intBitsToFloat((int) u(b, at)),
                Float.intBitsToFloat((int) u(b, at + 4)),
                Float.intBitsToFloat((int) u(b, at + 8)));
    }

    private static long u(byte[] b, int at) {
        return M2Input.u32(b, at);
    }

    private static int w(byte[] b, int at) {
        return M2Input.u16(b, at);
    }

    private static void put(byte[] b, int at, long value) {
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value);
    }

    private static void word(byte[] b, int at, int value) {
        ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value);
    }

    private static void putFloat(byte[] b, int at, float value) {
        put(b, at, Float.floatToRawIntBits(value));
    }
}
