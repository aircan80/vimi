package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.bounds;
import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;
import static com.vibeminer.formats.models.M2Input.v2;
import static com.vibeminer.formats.models.M2Input.v3;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Input.Budget;
import com.vibeminer.formats.models.M2Input.Span;
import com.vibeminer.formats.models.M2Types.AnimationFile;
import com.vibeminer.formats.models.M2Types.ArrayDeclaration;
import com.vibeminer.formats.models.M2Types.Bone;
import com.vibeminer.formats.models.M2Types.Bounds;
import com.vibeminer.formats.models.M2Types.Chunk;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Material;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Texture;
import com.vibeminer.formats.models.M2Types.Vec3;
import com.vibeminer.formats.models.M2Types.Vertex;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Native static M2 reader: unchunked264 and chunked272/274. Dependency discovery is descriptive.
 */
public final class M2ModelReader {
    public static final String REVISION = "m2-static-native-v1";

    private M2ModelReader() {}

    public static CompletableFuture<Model> read(
            AssetSource source, Limits limits, Cancellation token) {
        return AssetTasks.run(
                () -> readCore(source, limits == null ? new Limits() : limits, token));
    }

    public static CompletableFuture<Model> read(AssetSource source) {
        return read(source, new Limits(), Cancellation.NONE);
    }

    private static Model readCore(AssetSource source, Limits limits, Cancellation token) {
        M2Input input = new M2Input(source, limits, token);
        Budget budget = new Budget(limits.maxOutputBytes());
        boolean chunked = !ascii(input.at(0, 4)).equals("MD20");
        List<Chunk> chunks = new ArrayList<>();
        Map<String, Chunk> known = new HashMap<>();
        long origin = 0, bytes = input.length;
        if (chunked) {
            for (long at = 0; at < input.length; ) {
                token.check();
                if (chunks.size() == limits.maxChunks()) {
                    throw error("LIMIT", "Model chunk count exceeds policy.");
                }
                byte[] header = input.at(at, 8);
                for (int i = 0; i < 4; i++) {
                    if (header[i] < '0' || header[i] > 'Z') {
                        throw error("UNSUPPORTED", "Unsupported M2 container/chunk identifier.");
                    }
                }
                String id = ascii(java.util.Arrays.copyOf(header, 4));
                long count = u32(header, 4);
                if (count > input.length - at - 8) {
                    throw error("INVALID", "An M2 chunk exceeds its source.");
                }
                Chunk chunk = new Chunk(id, at + 8, count);
                chunks.add(chunk);
                budget.add(1, 128);
                if (known.putIfAbsent(id, chunk) != null) {
                    throw error("INVALID", "Duplicate M2 chunk " + id + " is not admitted.");
                }
                at += 8 + count;
            }
            Chunk body = known.get("MD21");
            if (body == null) {
                throw error("UNSUPPORTED", "The M2 container has no admitted MD21 model payload.");
            }
            origin = body.offset();
            bytes = body.byteLength();
        }
        if (bytes < 304) {
            throw error("INVALID", "The MD20 base header is truncated.");
        }
        byte[] core = input.at(origin, 304);
        if (!ascii(java.util.Arrays.copyOf(core, 4)).equals("MD20")) {
            throw error("INVALID", "MD21 must contain a nested MD20 model.");
        }
        long version = u32(core, 4);
        if (!chunked && version != 264 || chunked && version != 272 && version != 274) {
            throw error(
                    "UNSUPPORTED",
                    "M2 version "
                            + version
                            + (chunked ? " in a chunked MD21" : " in an unchunked MD20")
                            + " model is outside the admitted profiles (unchunked 264, chunked 272 and 274).");
        }
        long flags = u32(core, 16);
        int headerBytes = (flags & 8) != 0 ? 312 : 304;
        if (bytes < headerBytes) {
            throw error("INVALID", "The flagged texture-combiner descriptor is truncated.");
        }
        List<ArrayDeclaration> declarations = new ArrayList<>();
        Map<String, Span> spans = new HashMap<>();
        String[] names = {
            "name",
            "globalSequences",
            "sequences",
            "sequenceLookup",
            "bones",
            "keyBones",
            "vertices",
            "colors",
            "textures",
            "weights",
            "transforms",
            "replacements",
            "materials",
            "boneLookup",
            "textureCombos",
            "textureCoordinates",
            "weightLookup",
            "transformLookup",
            "collisionTriangles",
            "collisionVertices",
            "collisionNormals",
            "attachments",
            "attachmentLookup",
            "events",
            "lights",
            "cameras",
            "cameraLookup",
            "ribbons",
            "particles"
        };
        int[] ats = {
            8, 20, 28, 36, 44, 52, 60, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152, 216, 224,
            232, 240, 248, 256, 264, 272, 280, 288, 296
        };
        int[] strides = {
            1, 4, 64, 2, 88, 2, 48, 40, 16, 20, 60, 2, 4, 2, 2, 2, 2, 2, 2, 12, 12, 40, 2, 0, 0, 0,
            2, 0, 0
        };
        long viewCount = u32(core, 68);
        Bounds declaredBounds = null;
        for (int i = 0; i < names.length; i++) {
            if (i == 7 && viewCount > limits.maxSkinProfiles()) {
                throw error(
                        "LIMIT",
                        "The model has "
                                + M2Input.number(viewCount)
                                + " SKIN profiles; geometry reads at most "
                                + M2Input.number(limits.maxSkinProfiles())
                                + ".");
            }
            if (i == 18) {
                declaredBounds = bounds(core, 160, false);
                bounds(core, 188, u32(core, 216) == 0 && u32(core, 224) == 0);
            }
            int maximum =
                    switch (names[i]) {
                        case "name" -> limits.maxNameBytes();
                        case "vertices", "collisionVertices", "collisionNormals" ->
                                limits.maxVertices();
                        case "textures" -> limits.maxTextures();
                        case "materials" -> limits.maxMaterials();
                        case "collisionTriangles" -> limits.maxTriangleIndices();
                        default -> limits.maxMetadataEntries();
                    };
            long count = u32(core, ats[i]), offset = u32(core, ats[i] + 4);
            spans.put(
                    names[i],
                    input.span(
                            names[i],
                            count,
                            offset,
                            strides[i],
                            maximum,
                            origin,
                            bytes,
                            headerBytes));
            declarations.add(new ArrayDeclaration(names[i], count, offset, strides[i]));
            budget.add(1, 128);
        }
        if (headerBytes == 312) {
            byte[] combiner = input.at(origin + 304, 8);
            long count = u32(combiner), offset = u32(combiner, 4);
            spans.put(
                    "combiners",
                    input.span(
                            "combiners",
                            count,
                            offset,
                            2,
                            limits.maxMetadataEntries(),
                            origin,
                            bytes,
                            headerBytes));
            declarations.add(new ArrayDeclaration("combiners", count, offset, 2));
            budget.add(1, 128);
        }
        String name = name(input, spans.get("name"), budget);
        Span vertexSpan = spans.get("vertices");
        budget.add(vertexSpan.count(), 48);
        List<Vertex> vertices = new ArrayList<>(vertexSpan.count());
        input.records(
                vertexSpan,
                (row, i) ->
                        vertices.add(
                                new Vertex(
                                        v3(row, 0),
                                        u32(row, 12),
                                        u32(row, 16),
                                        v3(row, 20),
                                        v2(row, 32),
                                        v2(row, 40))));
        Bounds computed = null;
        if (!vertices.isEmpty()) {
            float minX = Float.POSITIVE_INFINITY,
                    minY = minX,
                    minZ = minX,
                    maxX = Float.NEGATIVE_INFINITY,
                    maxY = maxX,
                    maxZ = maxX;
            for (Vertex vertex : vertices) {
                token.check();
                Vec3 p = vertex.position();
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
            double dx = (double) maxX - minX,
                    dy = (double) maxY - minY,
                    dz = (double) maxZ - minZ,
                    radius = Math.sqrt(dx * dx + dy * dy + dz * dz) / 2;
            if (radius > Float.MAX_VALUE) {
                throw error(
                        "LIMIT", "Computed geometry bounds exceed finite output representation.");
            }
            computed =
                    new Bounds(
                            new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ), (float) radius);
        }
        Span textureSpan = spans.get("textures");
        budget.add(textureSpan.count(), 64);
        List<long[]> textureRows = new ArrayList<>();
        input.records(
                textureSpan,
                (row, i) ->
                        textureRows.add(
                                new long[] {u32(row), u32(row, 4), u32(row, 8), u32(row, 12)}));
        List<Long> txids =
                chunkIds("TXID", textureSpan.count(), false, input, known, limits, budget);
        if (chunked && textureSpan.count() > 0 && txids == null) {
            throw error(
                    "UNSUPPORTED",
                    "Modern M2 profile needs an exact TXID texture-ordinal declaration.");
        }
        List<Texture> textures = new ArrayList<>();
        for (int i = 0; i < textureRows.size(); i++) {
            token.check();
            long[] row = textureRows.get(i);
            String filename =
                    name(
                            input,
                            input.span(
                                    "texture filename",
                                    row[2],
                                    row[3],
                                    1,
                                    limits.maxNameBytes(),
                                    origin,
                                    bytes,
                                    headerBytes),
                            budget);
            textures.add(
                    new Texture(i, row[0], row[1], filename, txids == null ? null : txids.get(i)));
        }
        budget.add(spans.get("materials").count(), 4);
        List<Material> materials = new ArrayList<>();
        input.records(
                spans.get("materials"),
                (row, i) -> materials.add(new Material(u16(row), u16(row, 2))));
        int boneCount = spans.get("bones").count();
        budget.add(boneCount, 32);
        List<Bone> bones = new ArrayList<>();
        input.records(
                spans.get("bones"),
                (row, i) -> bones.add(M2Input.bone(row, i, boneCount, "model")));
        List<Integer> combos = words("textureCombos", input, spans, budget),
                coordWords = words("textureCoordinates", input, spans, budget);
        budget.add(coordWords.size(), 2);
        List<Integer> coordinates = new ArrayList<>(coordWords.size());
        for (int word : coordWords) {
            token.check();
            coordinates.add((int) (short) word);
        }
        List<Integer> weights = words("weightLookup", input, spans, budget),
                transforms = words("transformLookup", input, spans, budget),
                boneLookup = words("boneLookup", input, spans, budget);
        List<Long> allSkins = chunkIds("SFID", (int) viewCount, true, input, known, limits, budget);
        if (chunked && viewCount > 0 && allSkins == null) {
            throw error(
                    "UNSUPPORTED", "Modern model has no exact main-SKIN dependency declaration.");
        }
        if (allSkins == null) {
            allSkins = List.of();
        }
        budget.add(allSkins.size(), 4);
        List<Long> skins = allSkins.subList(0, Math.min((int) viewCount, allSkins.size())),
                lodSkins =
                        allSkins.subList(
                                Math.min((int) viewCount, allSkins.size()), allSkins.size());
        long skeleton = 0;
        Chunk sk = known.get("SKID");
        if (sk != null) {
            if (sk.byteLength() != 4) {
                throw error("INVALID", "SKID must contain exactly one declared skeleton ID.");
            }
            skeleton = u32(input.at(sk.offset(), 4));
        }
        List<Long> boneFiles = chunkIds("BFID", null, true, input, known, limits, budget);
        if (boneFiles == null) {
            boneFiles = List.of();
        }
        List<AnimationFile> animations = new ArrayList<>();
        Chunk af = known.get("AFID");
        if (af != null) {
            if (af.byteLength() % 8 != 0) {
                throw error("INVALID", "AFID has a partial animation dependency record.");
            }
            long count = af.byteLength() / 8;
            if (count > limits.maxMetadataEntries()) {
                throw error("LIMIT", "Animation file count exceeds policy.");
            }
            budget.add(count, 8);
            input.records(
                    new Span("AFID", af.offset(), (int) count, 8),
                    (row, i) ->
                            animations.add(new AnimationFile(u16(row), u16(row, 2), u32(row, 4))));
        }
        input.finish();
        return new Model(
                version,
                chunked,
                flags,
                name,
                input.length,
                origin,
                bytes,
                viewCount,
                vertices,
                textures,
                materials,
                bones,
                combos,
                coordinates,
                weights,
                transforms,
                boneLookup,
                skins,
                lodSkins,
                skeleton,
                boneFiles,
                animations,
                declarations,
                chunks,
                declaredBounds,
                computed,
                budget.used,
                limits);
    }

    private static List<Integer> words(
            String field, M2Input input, Map<String, Span> spans, Budget budget) {
        Span span = spans.get(field);
        budget.add(span.count(), 2);
        List<Integer> out = new ArrayList<>();
        input.records(span, (row, i) -> out.add(u16(row)));
        return out;
    }

    private static List<Long> chunkIds(
            String id,
            Integer expected,
            boolean allowExtra,
            M2Input input,
            Map<String, Chunk> known,
            Limits limits,
            Budget budget) {
        Chunk chunk = known.get(id);
        if (chunk == null) {
            return null;
        }
        if (chunk.byteLength() % 4 != 0) {
            throw error("INVALID", "The " + id + " dependency chunk has a partial ID.");
        }
        long count = chunk.byteLength() / 4;
        int maximum =
                id.equals("TXID")
                        ? limits.maxTextures()
                        : id.equals("SFID")
                                ? limits.maxSkinProfiles()
                                : limits.maxMetadataEntries();
        if (count > maximum) {
            throw error("LIMIT", "The " + id + " dependency count exceeds policy.");
        }
        if (expected != null && (count < expected || !allowExtra && count != expected)) {
            throw error("INVALID", "The " + id + " count differs from model ordinals.");
        }
        budget.add(count, 4);
        List<Long> ids = new ArrayList<>();
        input.records(new Span(id, chunk.offset(), (int) count, 4), (row, i) -> ids.add(u32(row)));
        return ids;
    }

    private static String name(M2Input input, Span span, Budget budget) {
        if (span.count() == 0) {
            return "";
        }
        budget.add(span.count(), 2);
        byte[] bytes = input.at(span.offset(), span.count());
        if (bytes[bytes.length - 1] != 0) {
            throw error("INVALID", "A declared model name lacks its exact terminating NUL.");
        }
        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == 0) {
                throw error("INVALID", "A model name contains an embedded NUL.");
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1))
                    .toString();
        } catch (CharacterCodingException e) {
            throw error("UNSUPPORTED", "Declared model name is not strict UTF-8 text.");
        }
    }

    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }
}
