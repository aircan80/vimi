package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.BuildModels;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStore;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.MeshAppearanceCodec;
import com.vibeminer.rendering.models.TextureComposer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Reviewed current character composition profiles; optional unsupported shapes remain explicit. */
final class RaceAppearance {
    private final RaceMedia media;
    private final BuildFiles files;
    private final GeneratedStore generated;
    private final RaceAppearancePlan plans;

    static final int MAX_REMEMBERED = 6;
    static final long MAX_REMEMBERED_BYTES = 160L << 20;

    /** A complete result and the content keys of the model and layer files it was composed from. */
    private record Remembered(
            RaceAppearanceTypes.Result result, java.util.Map<Long, String> sources) {}

    /**
     * Complete composed appearances by request, most recently used last. Only results whose every
     * layer was read (or that were restored from such a derivative) are kept. Their other inputs
     * are this handle's build-scoped tables, and a hit is used only while the model and every layer
     * file still have the content keys that named its derivative; incomplete or failed results are
     * prepared again on the next request. Guarded by itself: prepare runs on the query worker and
     * render on the appearance worker.
     */
    private final java.util.LinkedHashMap<String, Remembered> remembered =
            new java.util.LinkedHashMap<>(16, 0.75f, true);

    private long rememberedBytes;

    RaceAppearance(
            RacesService owner,
            RaceMedia media,
            BuildTables tables,
            BuildFiles files,
            GeneratedStore generated) {
        this.media = media;
        this.files = files;
        this.generated = generated;
        plans = new RaceAppearancePlan(owner, media, tables);
    }

    boolean available() {
        return files != null && generated != null;
    }

    record Prepared(
            RaceMediaTypes.Preview base,
            RaceAppearancePlan.Plan plan,
            GeneratedKey key,
            List<String> issues,
            RaceAppearanceTypes.Result finished,
            String request,
            java.util.Map<Long, String> sources) {
        Prepared {
            issues = List.copyOf(issues);
            sources = sources == null ? java.util.Map.of() : java.util.Map.copyOf(sources);
        }
    }

    /** The remembered-result key of one selection; picks are ordered by option ID. */
    static String request(
            long raceId, long classId, long modelId, java.util.Map<Long, Long> picks) {
        return raceId + ":" + classId + ":" + modelId + ":" + new java.util.TreeMap<>(picks);
    }

    /** A remembered result whose source files are unchanged, or null. */
    private RaceAppearanceTypes.Result remembered(String request, Cancellation token) {
        Remembered entry;
        synchronized (remembered) {
            entry = remembered.get(request);
        }
        if (entry == null) {
            return null;
        }
        for (var source : entry.sources().entrySet()) {
            var stat = files.stat(FileTypes.FileRef.id(source.getKey()), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || !source.getValue().equals(stat.file().contentKey())) {
                forget(request, entry);
                return null;
            }
        }
        return entry.result();
    }

    private void forget(String request, Remembered entry) {
        synchronized (remembered) {
            if (remembered.remove(request, entry)) {
                rememberedBytes -= bytes(entry.result().appearance());
            }
        }
    }

    void remember(Prepared prepared, RaceAppearanceTypes.Result result) {
        long bytes = bytes(result.appearance());
        if (prepared.request() == null
                || prepared.sources().isEmpty()
                || bytes > MAX_REMEMBERED_BYTES) {
            return;
        }
        var entry = new Remembered(result, prepared.sources());
        synchronized (remembered) {
            var replaced = remembered.put(prepared.request(), entry);
            if (replaced != null) {
                rememberedBytes -= bytes(replaced.result().appearance());
            }
            rememberedBytes += bytes;
            var eldest = remembered.entrySet().iterator();
            while ((remembered.size() > MAX_REMEMBERED || rememberedBytes > MAX_REMEMBERED_BYTES)
                    && eldest.hasNext()) {
                var next = eldest.next();
                if (next.getValue() == entry) {
                    continue;
                }
                rememberedBytes -= bytes(next.getValue().result().appearance());
                eldest.remove();
            }
        }
    }

    /** Drops every remembered result; called once the workers have terminated. */
    void forget() {
        synchronized (remembered) {
            remembered.clear();
            rememberedBytes = 0;
        }
    }

    /** Remembered results, for tests. */
    int rememberedCount() {
        synchronized (remembered) {
            return remembered.size();
        }
    }

    /** Approximate retained heap of one composed appearance: texels and vertex arrays. */
    static long bytes(MeshAppearance appearance) {
        long total = 0;
        for (var texture : appearance.textures()) {
            total += 4L * texture.width() * texture.height();
        }
        var mesh = appearance.mesh();
        // Positions, normals, UVs, and indices.
        return total + 32L * mesh.vertexCount() + 12L * mesh.triangleCount();
    }

    /** The LOD0 draw SKIN and, when it was read, the textured sample's rest surface. */
    record DrawSurface(
            BuildModelTypes.SkinRead<M2Types.RestDrawSkin> skin, RaceRestSurface surface) {}

    /**
     * Derives the textured sample's surface. Package-visible so tests prove a race idle matches the
     * surface this sample shows.
     */
    static DrawSurface drawSurface(
            BuildModels models, BuildModelTypes.ModelRead model, Cancellation token) {
        var skin = models.readRestDrawSkin(model, 0, token).join();
        if (skin.state() != BuildModelTypes.ReadState.READ || skin.skin() == null) {
            return new DrawSurface(skin, null);
        }
        return new DrawSurface(
                skin, RaceRestSurface.of(model.model(), skin.skin().surface(), token));
    }

    Prepared prepare(long raceId, long classId, long modelId, Cancellation token) {
        return prepare(raceId, classId, modelId, token, java.util.Map.of());
    }

    Prepared prepare(
            long raceId,
            long classId,
            long modelId,
            Cancellation token,
            java.util.Map<Long, Long> requested) {
        var issues = new ArrayList<String>();
        String request = request(raceId, classId, modelId, requested);
        try {
            if (files == null || generated == null) {
                return prepareFailure(
                        issues, "Texture preparation is unavailable for this workspace.");
            }
            if (!RaceAppearancePlan.reviewedPair(raceId, modelId)) {
                // Refused before any read, so an unreviewed race's GPU preview is not held back.
                return prepareFailure(issues, RaceAppearancePlan.UNREVIEWED_PAIR);
            }
            var known = remembered(request, token);
            if (known != null) {
                return new Prepared(null, null, null, issues, known, request, null);
            }
            var selection =
                    new RaceCustomization(media)
                            .select(raceId, classId, modelId, token, issues, requested);
            var base = media.preview(raceId, classId, modelId, token, selection);
            boolean unread = media.unreadParts();
            base.issues().stream()
                    .filter(issue -> !issue.startsWith("Static native rest positions"))
                    .filter(issue -> requested.isEmpty() || !issue.startsWith("Sample look uses"))
                    .forEach(issues::add);
            if (!requested.isEmpty()) {
                issues.add(
                        "Requested known eligible choices and recorded defaults are shown; unknown unlock requirements are omitted.");
            }
            if (base.mesh() == null || base.variant() == null) {
                return prepareFailure(issues, base.message());
            }
            var plan = plans.build(raceId, classId, base.variant(), token, issues, selection);
            // A plan or sample that skipped a source only because it could not be read now is
            // shown but not remembered: without recorded sources the next request prepares again.
            unread |= plans.unsettled();
            base =
                    new RaceMediaTypes.Preview(
                            base.state(),
                            base.variant(),
                            base.mesh(),
                            RaceClothingGeometry.apply(
                                    base.mesh(), base.visibleParts(), plan.items()),
                            base.issues(),
                            base.restored(),
                            base.message());
            var stat =
                    files.stat(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            if (stat.state() != FileTypes.FileLookupState.FOUND || stat.file() == null) {
                return prepareFailure(issues, "The model root entry is unavailable.");
            }
            var sources = new java.util.TreeMap<Long, String>();
            sources.put(base.variant().modelFileDataId(), stat.file().contentKey());
            StringBuilder identity =
                    new StringBuilder("race-static-appearance-v1\n")
                            .append(MeshAppearanceCodec.REVISION)
                            .append('\n')
                            .append(M2RestDrawSkinReader.REVISION)
                            .append('\n')
                            .append(Blp2Decoder.REVISION)
                            .append(
                                    "\nstraight-alpha-source-over-nearest-v1\nhuman-orc-dwarf-night-elf-tauren-clothing-geosets-v6\n")
                            .append(files.build())
                            .append('\n')
                            .append(files.locale())
                            .append('\n')
                            .append(stat.file().contentKey())
                            .append('\n')
                            .append("choice-vector-v1\n")
                            .append(selection.byOption())
                            .append('\n')
                            .append(plan)
                            .append('\n')
                            .append(base.visibleParts().stream().sorted().toList());
            for (long fid :
                    plan.layers().stream()
                            .map(RaceAppearancePlan.Layer::fileDataId)
                            .distinct()
                            .sorted()
                            .toList()) {
                token.check();
                var source = files.stat(FileTypes.FileRef.id(fid), token).join();
                if (source.state() == FileTypes.FileLookupState.FOUND && source.file() != null) {
                    sources.put(fid, source.file().contentKey());
                }
                identity.append('\n')
                        .append(fid)
                        .append(':')
                        .append(source.state())
                        .append(':')
                        .append(source.file() == null ? "" : source.file().contentKey());
            }
            var key = GeneratedKey.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
            return new Prepared(base, plan, key, issues, null, request, unread ? null : sources);
        } catch (RacesService.Projection unavailable) {
            return prepareFailure(issues, unavailable.getMessage());
        }
    }

    private static Prepared prepareFailure(List<String> issues, String message) {
        return new Prepared(null, null, null, issues, failed(issues, message), null, null);
    }

    RaceAppearanceTypes.Result render(Prepared prepared, Cancellation token) {
        if (prepared.finished() != null) {
            return prepared.finished();
        }
        var issues = new ArrayList<>(prepared.issues());
        var base = prepared.base();
        var plan = prepared.plan();
        var key = prepared.key();
        try {
            var restored = restore(key, base.variant().modelFileDataId(), token);
            if (restored != null) {
                var result = result(restored, base.visibleParts(), plan, issues, true);
                token.check();
                remember(prepared, result);
                return result;
            }
            var images = new BlpImages(generated);
            var decoded = new HashMap<Long, MeshAppearance.Texture>();
            boolean complete = true;
            long decodedPixels = 0;
            for (long fid :
                    plan.layers().stream()
                            .map(RaceAppearancePlan.Layer::fileDataId)
                            .distinct()
                            .toList()) {
                token.check();
                var read =
                        images.decodeLargestWithin(files, FileTypes.FileRef.id(fid), 2048, token)
                                .join();
                if (read.image() == null) {
                    complete = false;
                    note(
                            issues,
                            "Texture #"
                                    + fid
                                    + " is "
                                    + read.state()
                                    + "; available layers are retained.");
                    continue;
                }
                var image = read.image();
                long imagePixels = (long) image.width() * image.height();
                if (imagePixels > 8_388_608L - decodedPixels) {
                    complete = false;
                    note(
                            issues,
                            "Additional diffuse images exceed the bounded texture preview budget.");
                    continue;
                }
                decodedPixels += imagePixels;
                byte[] rgba = image.rgba();
                int[] argb = new int[image.width() * image.height()];
                for (int i = 0; i < argb.length; i++) {
                    if ((i & 16383) == 0) {
                        token.check();
                    }
                    argb[i] =
                            (rgba[i * 4 + 3] & 255) << 24
                                    | (rgba[i * 4] & 255) << 16
                                    | (rgba[i * 4 + 1] & 255) << 8
                                    | (rgba[i * 4 + 2] & 255);
                }
                decoded.put(fid, new MeshAppearance.Texture(image.width(), image.height(), argb));
            }
            if (RaceAppearanceProfiles.nightElf(plan.profile())
                    && plan.layers().stream()
                            .noneMatch(
                                    layer ->
                                            RaceAppearancePlan.wholeBodyBase(layer, plan.canvases())
                                                    && decoded.containsKey(layer.fileDataId()))) {
                return failed(
                        issues,
                        "The base skin texture is unavailable; no incomplete body is presented.");
            }
            var textures = new ArrayList<MeshAppearance.Texture>();
            var textureTypes = new HashMap<Integer, Integer>();
            for (var canvas : plan.canvases()) {
                var layers = new ArrayList<TextureComposer.Layer>();
                for (var layer : plan.layers()) {
                    if (layer.type() == canvas.type() && decoded.containsKey(layer.fileDataId())) {
                        layers.add(
                                new TextureComposer.Layer(
                                        decoded.get(layer.fileDataId()),
                                        layer.x(),
                                        layer.y(),
                                        layer.width(),
                                        layer.height(),
                                        layer.blend() == 1
                                                ? TextureComposer.Blend.COPY
                                                : TextureComposer.Blend.SOURCE_OVER));
                    }
                }
                if (!layers.isEmpty()) {
                    textureTypes.put(canvas.type(), textures.size());
                    textures.add(
                            TextureComposer.compose(
                                    canvas.width(), canvas.height(), layers, token::isCanceled));
                }
            }
            if (textures.isEmpty()) {
                return failed(issues, "No reviewed diffuse texture layers are currently readable.");
            }
            var models = new BuildModels(files);
            var model =
                    models.readModel(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            if (model.state() != BuildModelTypes.ReadState.READ || model.model() == null) {
                return failed(issues, "The model could not be read for texture coordinates.");
            }
            var drawn = drawSurface(models, model, token);
            var skin = drawn.skin();
            if (drawn.surface() == null) {
                return failed(
                        issues, "The model draw declarations are unavailable: " + skin.message());
            }
            var surface = drawn.surface();
            var mesh = surface.mesh();
            float[] uv = surface.uv(), normals = surface.normals();
            M2Appearance.ModelFacts facts = null;
            var open =
                    files.openRead(FileTypes.FileRef.id(base.variant().modelFileDataId()), token)
                            .join();
            if (open.stream() != null) {
                try (var stream = open.stream()) {
                    if (open.state() == FileTypes.FileReadState.COMPLETE
                            && stream.holes().isEmpty()) {
                        facts =
                                M2Appearance.readModel(
                                                AssetSources.file(stream), model.model(), token)
                                        .join();
                    }
                }
            }
            var bindings = new ArrayList<MeshAppearance.Surface>();
            var seen = new java.util.HashSet<Integer>();
            var sectionBatches = new HashMap<Integer, Integer>();
            for (var batch : skin.skin().batches()) {
                sectionBatches.merge(batch.sectionIndex(), 1, Integer::sum);
            }
            for (var batch : skin.skin().batches()) {
                boolean contextual = plan.profile() != RaceAppearanceProfiles.Profile.LEGACY;
                if (contextual
                        && !RaceAppearanceProfiles.reviewedDraw(
                                plan.profile(),
                                model.model(),
                                batch,
                                facts,
                                sectionBatches.get(batch.sectionIndex()))) {
                    continue;
                }
                if (batch.textureCount() != 1
                        || !contextual && batch.shaderId() != 0
                        || batch.colorIndex() != -1) {
                    continue;
                }
                var m = model.model();
                var material = m.materials().get(batch.materialIndex());
                if (material.blendMode() > 1 || material.flags() != 0 && material.flags() != 4) {
                    continue;
                }
                int transform = m.textureTransforms().get(batch.textureTransformComboIndex());
                if (transform != 65535) {
                    continue;
                }
                int weight = m.textureWeights().get(batch.textureWeightComboIndex());
                if (weight != 65535
                        && (facts == null
                                || weight >= facts.staticWeights().size()
                                || !facts.staticWeights().get(weight))) {
                    continue;
                }
                var texture = m.textures().get(m.textureCombos().get(batch.textureComboIndex()));
                Integer mapped = textureTypes.get((int) texture.type());
                if (mapped == null || !seen.add(batch.sectionIndex())) {
                    continue;
                }
                bindings.add(RaceAppearanceProfiles.surface(batch, material, texture, mapped));
            }
            if (bindings.isEmpty()) {
                return failed(
                        issues,
                        "No reviewed static one-stage diffuse draw bindings are available.");
            }
            var appearance = new MeshAppearance(mesh, uv, normals, bindings, textures);
            if (complete) {
                save(
                        key,
                        base.variant().modelFileDataId(),
                        skin.fileDataId(),
                        skin.file().contentKey(),
                        appearance,
                        token,
                        issues);
            }
            var result = result(appearance, base.visibleParts(), plan, issues, false);
            if (complete) {
                token.check();
                remember(prepared, result);
            }
            return result;
        } catch (RacesService.Projection unavailable) {
            return failed(issues, unavailable.getMessage());
        }
    }

    private static RaceAppearanceTypes.Result result(
            MeshAppearance appearance,
            Set<Integer> visible,
            RaceAppearancePlan.Plan plan,
            List<String> issues,
            boolean restored) {
        var boundSections =
                appearance.surfaces().stream()
                        .map(MeshAppearance.Surface::sectionIndex)
                        .collect(java.util.stream.Collectors.toSet());
        var parts = appearance.mesh().parts();
        if (java.util.stream.IntStream.range(0, parts.size())
                .anyMatch(
                        index ->
                                visible.contains(parts.get(index).id())
                                        && !boundSections.contains(index))) {
            note(
                    issues,
                    "Some surfaces retain neutral shading because their draw bindings are outside the reviewed diffuse profile.");
        }
        note(
                issues,
                "Diffuse source files: "
                        + plan.layers().stream()
                                .map(RaceAppearancePlan.Layer::fileDataId)
                                .distinct()
                                .sorted()
                                .toList());
        note(
                issues,
                "Static diffuse sample and recorded new-character clothing only; animation, lighting shaders and separate equipment attachments are not evaluated.");
        return new RaceAppearanceTypes.Result(
                RaceTypes.State.PARTIAL,
                appearance,
                visible,
                plan.items().stream()
                        .map(
                                item ->
                                        new RaceAppearanceTypes.Item(
                                                item.itemId(),
                                                item.inventoryType(),
                                                item.displayId()))
                        .toList(),
                issues,
                restored,
                null);
    }

    private static RaceAppearanceTypes.Result failed(List<String> issues, String message) {
        return new RaceAppearanceTypes.Result(
                RaceTypes.State.UNAVAILABLE, null, Set.of(), List.of(), issues, false, message);
    }

    @SuppressWarnings("try")
    private MeshAppearance restore(GeneratedKey key, long modelId, Cancellation token) {
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            var size = generated.readRange(key, 0, 0, lower).join();
            if (size.state() != GeneratedTypes.ReadState.PRESENT
                    || size.length() < 40
                    || size.length() > 40L + MeshAppearanceCodec.MAX_BYTES) {
                return null;
            }
            var saved = generated.read(key, lower).join();
            token.check();
            if (saved.state() != GeneratedTypes.ReadState.PRESENT) {
                return null;
            }
            var bytes = ByteBuffer.wrap(saved.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.getInt() != 0x56414331 || bytes.getLong() != modelId) {
                return null;
            }
            long skin = bytes.getLong();
            byte[] content = new byte[16];
            bytes.get(content);
            int length = bytes.getInt();
            if (length != bytes.remaining() || skin < 1 || skin > 0xffffffffL) {
                return null;
            }
            var stat = files.stat(FileTypes.FileRef.id(skin), token).join();
            if (stat.state() != FileTypes.FileLookupState.FOUND
                    || stat.file() == null
                    || !stat.file().contentKey().equals(HexFormat.of().formatHex(content))) {
                return null;
            }
            byte[] payload = new byte[length];
            bytes.get(payload);
            return MeshAppearanceCodec.decode(payload);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException invalid) {
            token.check();
            return null;
        }
    }

    @SuppressWarnings("try")
    private void save(
            GeneratedKey key,
            long modelId,
            long skinId,
            String content,
            MeshAppearance appearance,
            Cancellation token,
            List<String> issues) {
        var lower = new com.vibeminer.generated.Cancellation();
        try (var registration = token.onCancel(lower::cancel)) {
            byte[] payload = MeshAppearanceCodec.encode(appearance);
            var bytes = ByteBuffer.allocate(40 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(0x56414331)
                    .putLong(modelId)
                    .putLong(skinId)
                    .put(HexFormat.of().parseHex(content))
                    .putInt(payload.length)
                    .put(payload);
            generated.delete(List.of(key), lower).join();
            generated.put(List.of(new GeneratedTypes.Put(key, bytes.array())), lower).join();
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            token.check();
            note(issues, "The composed appearance derivative could not be saved.");
        }
    }

    private static void note(List<String> issues, String text) {
        if (issues.size() < 32 && !issues.contains(text)) {
            issues.add(text);
        }
    }

    static boolean supports(String table, List<String> layouts) {
        String expected =
                switch (table) {
                    case "ChrRaces" -> "4F44C796";
                    case "ChrModel" -> "03FAB755";
                    case "ChrCustomizationOption" -> "DCC2A86E";
                    case "ChrCustomizationChoice" -> "9559C358";
                    case "ChrCustomizationElement" -> "6483C37E";
                    case "ChrCustomizationGeoset" -> "BF55F2FB";
                    case "ChrCustomizationReq" -> "CA154412";
                    case "ChrCustomizationReqChoice" -> "F925BC6F";
                    case "ChrCustomizationMaterial" -> "BE9767E9";
                    case "ChrModelMaterial" -> "22469480";
                    case "ChrModelTextureLayer" -> "D0583FB4";
                    case "CharComponentTextureSections" -> "2173BA71";
                    case "TextureFileData" -> "BD7C74C2";
                    case "ComponentTextureFileData" -> "B32B030A";
                    case "CharacterLoadout" -> "713CE8BB";
                    case "CharacterLoadoutItem" -> "0C7A1862";
                    case "Item" -> "9A2A4834";
                    case "ItemModifiedAppearance" -> "03A6C979";
                    case "ItemAppearance" -> "481C4281";
                    case "ItemDisplayInfo" -> "9F3AB8A9";
                    case "ItemDisplayInfoMaterialRes" -> "AA462C0E";
                    default -> null;
                };
        return expected != null && layouts.contains(expected);
    }

    static void requireProfile(RacesService.Data data) {
        if (!supports(data.name, data.schema.layoutHashes())) {
            throw data.unsupported("Unreviewed static appearance layout");
        }
    }

    static boolean reviewedPhysicalInteger(
            String table,
            List<String> layouts,
            String name,
            com.vibeminer.formats.definitions.TableSchema.SchemaColumn field,
            com.vibeminer.formats.db2.Db2Types.Column column) {
        var raw = column.interpretation();
        return table.equals("ChrCustomizationGeoset")
                && layouts.contains("BF55F2FB")
                && name.equals("GeosetType")
                && field.arrayLength() == 1
                && java.util.Objects.equals(field.bits(), 8)
                && Boolean.FALSE.equals(field.unsigned())
                && column.narrowing() == null
                && raw != null
                && raw.policy().equals("raw-physical-integer-v1")
                && raw.physicalBits() == 32
                && !raw.physicalSigned()
                && raw.candidateBits() == 8
                && !raw.candidateUnsigned();
    }

    static boolean reviewedField(String table, List<String> layouts, String field) {
        return switch (table) {
            case "ChrRaces" -> layouts.contains("4F44C796") && field.equals("PlayableRaceBit");
            case "ChrCustomizationOption" ->
                    layouts.contains("DCC2A86E") && field.equals("Requirement");
            case "ChrCustomizationChoice" ->
                    layouts.contains("9559C358")
                            && Set.of("ChrCustomizationReqID", "ChrCustomizationVisReqID")
                                    .contains(field);
            case "ChrCustomizationReq" ->
                    layouts.contains("CA154412")
                            && Set.of(
                                            "ReqType",
                                            "ClassMask",
                                            "RaceMasks",
                                            "RegionGroupMask",
                                            "ReqAchievementID",
                                            "ReqQuestID",
                                            "OverrideArchive",
                                            "ReqItemModifiedAppearanceID")
                                    .contains(field);
            case "ChrCustomizationElement" ->
                    layouts.contains("6483C37E") && field.equals("ChrCustomizationMaterialID");
            case "ChrCustomizationReqChoice" ->
                    layouts.contains("F925BC6F") && field.equals("ChrCustomizationReqID");
            case "ChrModel" ->
                    layouts.contains("03FAB755") && field.equals("CharComponentTextureLayoutID");
            case "ChrModelMaterial" ->
                    layouts.contains("22469480")
                            && Set.of(
                                            "CharComponentTextureLayoutsID",
                                            "TextureType",
                                            "Width",
                                            "Height",
                                            "Flags")
                                    .contains(field);
            case "ChrModelTextureLayer" ->
                    layouts.contains("D0583FB4")
                            && Set.of(
                                            "CharComponentTextureLayoutsID",
                                            "ChrModelTextureTargetID",
                                            "TextureType",
                                            "Layer",
                                            "BlendMode",
                                            "TextureSectionTypeBitMask",
                                            "TextureSectionTypeBitMask2",
                                            "Flags")
                                    .contains(field);
            case "CharComponentTextureSections" ->
                    layouts.contains("2173BA71")
                            && Set.of(
                                            "CharComponentTextureLayoutID",
                                            "SectionType",
                                            "X",
                                            "Y",
                                            "Width",
                                            "Height")
                                    .contains(field);
            case "ChrCustomizationMaterial" ->
                    layouts.contains("BE9767E9")
                            && Set.of("ChrModelTextureTargetID", "MaterialResourcesID")
                                    .contains(field);
            case "TextureFileData" ->
                    layouts.contains("BD7C74C2")
                            && Set.of("UsageType", "MaterialResourcesID").contains(field);
            case "ComponentTextureFileData" ->
                    layouts.contains("B32B030A")
                            && Set.of("GenderIndex", "ClassID", "RaceID").contains(field);
            case "CharacterLoadout" ->
                    layouts.contains("713CE8BB")
                            && Set.of("ChrClassID", "Purpose", "Racemask").contains(field);
            case "CharacterLoadoutItem" ->
                    layouts.contains("0C7A1862")
                            && Set.of("CharacterLoadoutID", "ItemID").contains(field);
            case "Item" -> layouts.contains("9A2A4834") && field.equals("InventoryType");
            case "ItemModifiedAppearance" ->
                    layouts.contains("03A6C979")
                            && Set.of("ItemID", "ItemAppearanceModifierID", "ItemAppearanceID")
                                    .contains(field);
            case "ItemAppearance" ->
                    layouts.contains("481C4281") && field.equals("ItemDisplayInfoID");
            case "ItemDisplayInfo" ->
                    layouts.contains("9F3AB8A9")
                            && Set.of("GeosetGroup", "GeosetGroupOverride", "ModelResourcesID")
                                    .contains(field);
            case "ItemDisplayInfoMaterialRes" ->
                    layouts.contains("AA462C0E")
                            && Set.of(
                                            "ComponentSection",
                                            "MaterialResourcesID",
                                            "ItemDisplayInfoID")
                                    .contains(field);
            default -> false;
        };
    }
}
