package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.f;
import static com.vibeminer.formats.models.M2Input.i16;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;
import static com.vibeminer.formats.models.M2Input.v3;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Input.Budget;
import com.vibeminer.formats.models.M2Input.Span;
import com.vibeminer.formats.models.M2Types.Batch;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Section;
import com.vibeminer.formats.models.M2Types.ShadowBatch;
import com.vibeminer.formats.models.M2Types.Skin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Paired SKIN reader for unchunked264 and chunked272/274 static profiles. */
public final class M2SkinReader {
    public static final String REVISION = "m2-skin-native-v3";

    private M2SkinReader() {}

    public static CompletableFuture<Skin> read(
            AssetSource source, Model model, Limits limits, Cancellation token) {
        Objects.requireNonNull(model);
        return AssetTasks.run(
                () -> readCore(source, model, limits == null ? model.limits() : limits, token));
    }

    public static CompletableFuture<Skin> read(AssetSource source, Model model) {
        return read(source, model, null, Cancellation.NONE);
    }

    private static Skin readCore(
            AssetSource source, Model model, Limits limits, Cancellation token) {
        return readCore(source, model, limits, true, token);
    }

    static Skin readCore(
            AssetSource source,
            Model model,
            Limits limits,
            boolean validateRig,
            Cancellation token) {
        Core core = core(source, model, limits, true, validateRig, token);
        List<Batch> batches = new ArrayList<>();
        List<ShadowBatch> shadows = new ArrayList<>();
        long colors = count(model, "colors"),
                weights = count(model, "weights"),
                transforms = count(model, "transforms");
        boolean shaderPicks =
                model.isChunked()
                        && (model.version() == 272 || model.version() == 274)
                        && model.textureCoordinates().isEmpty();
        core.input.records(
                core.batch,
                (row, i) -> {
                    Batch b =
                            new Batch(
                                    row[0] & 255,
                                    row[1],
                                    u16(row, 2),
                                    u16(row, 4),
                                    u16(row, 6),
                                    i16(row, 8),
                                    u16(row, 10),
                                    u16(row, 12),
                                    u16(row, 14),
                                    u16(row, 16),
                                    u16(row, 18),
                                    u16(row, 20),
                                    u16(row, 22));
                    if (b.sectionIndex() >= core.sections.size()
                            || b.materialIndex() >= model.materials().size()
                            || b.colorIndex() < -1
                            || b.colorIndex() >= colors && b.colorIndex() != -1) {
                        throw error(
                                "INVALID",
                                "A SKIN batch has an invalid section/material/color reference.");
                    }
                    if (b.textureCount() > limits.maxTextures()) {
                        throw error("LIMIT", "SKIN batch texture count exceeds policy.");
                    }
                    if (b.textureCount() > 0) {
                        if ((long) b.textureComboIndex() + b.textureCount()
                                        > model.textureCombos().size()
                                || !shaderPicks
                                        && (long) b.textureCoordinateComboIndex() + b.textureCount()
                                                > model.textureCoordinates().size()
                                || (long) b.textureTransformComboIndex() + b.textureCount()
                                        > model.textureTransforms().size()
                                || b.textureWeightComboIndex() >= model.textureWeights().size()) {
                            throw error(
                                    "INVALID",
                                    "A SKIN batch combo range is outside its model lookup.");
                        }
                        for (int n = 0; n < b.textureCount(); n++) {
                            if (model.textureCombos().get(b.textureComboIndex() + n)
                                    >= model.textures().size()) {
                                throw error(
                                        "INVALID",
                                        "A texture combo identifies no declared model texture.");
                            }
                            int transform =
                                    model.textureTransforms()
                                            .get(b.textureTransformComboIndex() + n);
                            if (transform != 65535 && transform >= transforms) {
                                throw error(
                                        "INVALID",
                                        "A UV-transform combo identifies no declared channel.");
                            }
                        }
                        int weight = model.textureWeights().get(b.textureWeightComboIndex());
                        if (weight != 65535 && weight >= weights) {
                            throw error(
                                    "INVALID", "An opacity combo identifies no declared channel.");
                        }
                    }
                    batches.add(b);
                });
        core.input.records(
                core.shadow,
                (row, i) ->
                        shadows.add(
                                new ShadowBatch(
                                        row[0] & 255,
                                        row[1] & 255,
                                        u16(row, 2),
                                        u16(row, 4),
                                        u16(row, 6),
                                        u16(row, 8),
                                        u16(row, 10))));
        core.input.finish();
        return new Skin(
                model,
                core.lookup,
                core.triangles,
                core.properties,
                core.sections,
                batches,
                shadows,
                core.maximumBones,
                core.input.length,
                core.retainedBytes,
                limits);
    }

    static long count(Model model, String name) {
        return model.declarations().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow()
                .count();
    }

    record Core(
            M2Input input,
            List<Integer> lookup,
            List<Integer> triangles,
            List<Long> properties,
            List<Section> sections,
            Span batch,
            Span shadow,
            long maximumBones,
            int headerBytes,
            long retainedBytes) {}

    static Core core(
            AssetSource source, Model model, Limits limits, boolean material, Cancellation token) {
        return core(source, model, limits, material, true, token);
    }

    static Core core(
            AssetSource source,
            Model model,
            Limits limits,
            boolean material,
            boolean validateRig,
            Cancellation token) {
        M2Input input = new M2Input(source, limits, token);
        Budget budget = new Budget(limits.maxOutputBytes(), model.retainedBytes());
        budget.add(0, 1);
        int headerBytes =
                model.version() == 264
                        ? 48
                        : model.version() == 272 || model.version() == 274 ? 56 : 0;
        if (headerBytes == 0) {
            throw error("UNSUPPORTED", "Supplied model has no admitted SKIN profile.");
        }
        byte[] header = input.at(0, headerBytes);
        if (u32(header) != 0x4e494b53L) {
            throw error("UNSUPPORTED", "An external SKIN profile is required.");
        }
        Span
                lookup =
                        s(
                                input,
                                header,
                                "SKIN vertex lookup",
                                4,
                                2,
                                limits.maxVertexLookup(),
                                headerBytes),
                triangles =
                        s(
                                input,
                                header,
                                "SKIN triangle indices",
                                12,
                                2,
                                limits.maxTriangleIndices(),
                                headerBytes),
                properties =
                        s(
                                input,
                                header,
                                "SKIN bone properties",
                                20,
                                4,
                                limits.maxVertexLookup(),
                                headerBytes),
                sections =
                        s(
                                input,
                                header,
                                "SKIN sections",
                                28,
                                48,
                                limits.maxSections(),
                                headerBytes),
                batch = s(input, header, "SKIN batches", 36, 24, limits.maxBatches(), headerBytes),
                shadow =
                        headerBytes == 56
                                ? s(
                                        input,
                                        header,
                                        "SKIN shadow batches",
                                        48,
                                        12,
                                        limits.maxBatches(),
                                        headerBytes)
                                : new Span("SKIN shadow batches", 0, 0, 12);
        long maxBones = u32(header, 44);
        boolean rawZero =
                model.isChunked()
                        && (model.version() == 272 || model.version() == 274)
                        && (maxBones == 0 || maxBones == 0xcdcdcdcdL);
        if (!rawZero && maxBones > 256) {
            throw error("LIMIT", "SKIN bone palette exceeds 256 bones.");
        }
        if (triangles.count() % 3 != 0) {
            throw error("INVALID", "SKIN triangle index count is not divisible by three.");
        }
        if (properties.count() != lookup.count()) {
            throw error("INVALID", "SKIN needs one bone-property record per lookup vertex.");
        }
        budget.add(lookup.count(), 2);
        budget.add(triangles.count(), 6);
        budget.add(properties.count(), 4);
        budget.add(sections.count(), 64);
        if (material) {
            budget.add(batch.count(), 32);
            budget.add(shadow.count(), 16);
        } else {
            budget.add(1, 512);
        }
        List<Integer> lookupValues = new ArrayList<>(), triangleValues = new ArrayList<>();
        List<Long> propertyValues = new ArrayList<>();
        List<Section> sectionValues = new ArrayList<>();
        input.records(
                lookup,
                (row, i) -> {
                    int v = u16(row);
                    if (v >= model.vertices().size()) {
                        throw error("INVALID", "A SKIN lookup identifies no model vertex.");
                    }
                    lookupValues.add(v);
                });
        input.records(
                triangles,
                (row, i) -> {
                    int v = u16(row);
                    if (v >= lookupValues.size()) {
                        throw error("INVALID", "A SKIN triangle identifies no lookup vertex.");
                    }
                    triangleValues.add(v);
                });
        input.records(properties, (row, i) -> propertyValues.add(u32(row)));
        input.records(
                sections,
                (row, i) -> {
                    int vertexStart = u16(row, 4), vertexCount = u16(row, 6), count = u16(row, 10);
                    long start = u16(row, 8) + ((long) u16(row, 2) << 16);
                    if ((long) vertexStart + vertexCount > lookupValues.size()
                            || start > triangleValues.size()
                            || count > triangleValues.size() - start
                            || start % 3 != 0
                            || count % 3 != 0) {
                        throw error(
                                "INVALID", "A SKIN section has invalid vertex/triangle ranges.");
                    }
                    int boneCount = u16(row, 12),
                            boneStart = u16(row, 14),
                            influences = u16(row, 16);
                    if (rawZero && boneCount > 256) {
                        throw error("LIMIT", "SKIN section bone palette exceeds256 bones.");
                    }
                    if (validateRig
                            && (influences > 4
                                    || !rawZero && boneCount > maxBones
                                    || boneCount > 0
                                            && (long) boneStart + boneCount
                                                    > model.boneLookup().size())) {
                        throw error("INVALID", "A SKIN section has invalid bone-palette geometry.");
                    }
                    float radius = f(row, 44);
                    if (radius < 0) {
                        throw error("INVALID", "A SKIN section sort radius is negative.");
                    }
                    sectionValues.add(
                            new Section(
                                    u16(row),
                                    u16(row, 2),
                                    vertexStart,
                                    vertexCount,
                                    start,
                                    count,
                                    boneCount,
                                    boneStart,
                                    influences,
                                    u16(row, 18),
                                    v3(row, 20),
                                    v3(row, 32),
                                    radius));
                });
        for (Section section : sectionValues) {
            for (long n = section.triangleStart();
                    n < section.triangleStart() + section.triangleCount();
                    n++) {
                if ((n & 4095) == 0) {
                    token.check();
                }
                int v = triangleValues.get((int) n);
                if (v < section.vertexStart()
                        || v >= section.vertexStart() + section.vertexCount()) {
                    throw error("INVALID", "A section triangle escapes its declared vertex span.");
                }
            }
        }
        return new Core(
                input,
                lookupValues,
                triangleValues,
                propertyValues,
                sectionValues,
                batch,
                shadow,
                maxBones,
                headerBytes,
                budget.used);
    }

    private static Span s(
            M2Input input,
            byte[] header,
            String name,
            int at,
            int stride,
            int max,
            int headerBytes) {
        return input.span(
                name,
                u32(header, at),
                u32(header, at + 4),
                stride,
                max,
                0,
                input.length,
                headerBytes);
    }
}
