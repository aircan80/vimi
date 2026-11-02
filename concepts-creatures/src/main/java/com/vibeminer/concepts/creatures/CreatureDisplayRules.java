package com.vibeminer.concepts.creatures;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearance;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureAppearanceState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureFact;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureFactCell;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureFactState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureFactsState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureRecordFacts;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTexture;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTextureEvidence;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTextureSource;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureTextureState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.models.M2GeometrySkinReader;
import com.vibeminer.formats.models.M2Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Reviewed display edges and source facts, without rendering/pose authority. */
final class CreatureDisplayRules {
    enum Role {
        DISPLAY,
        MODEL
    }

    static final String DISPLAY = "CreatureDisplayInfo",
            MODEL = "CreatureModelData",
            UNSUPPORTED = "CREATURE_DISPLAY_SCHEMA_UNSUPPORTED",
            INTEGRITY = "CREATURE_DISPLAY_INTEGRITY";

    private CreatureDisplayRules() {}

    static String table(Role role) {
        return role == Role.DISPLAY ? DISPLAY : MODEL;
    }

    static Integer edgeColumn(
            Role role, String layout, List<Db2Types.Column> columns, TableSchema schema) {
        if (layout == null
                || !(role == Role.DISPLAY
                                ? Set.of("7275F5F6", "F75F884C")
                                : Set.of("0F5449F8", "3FFEE408"))
                        .contains(layout.toUpperCase(java.util.Locale.ROOT))
                || schema == null) {
            return null;
        }
        String name = role == Role.DISPLAY ? "ModelID" : "FileDataID";
        var fields = schema.columns().stream().filter(f -> f.name().equals(name)).toList();
        if (fields.size() != 1) {
            return null;
        }
        var field = fields.getFirst();
        if (field.kind() != TableSchema.ColumnKind.INT
                || !Boolean.TRUE.equals(field.unsigned())
                || field.arrayLength() != 1
                || !field.verifiedName()
                || field.id() != TableSchema.KeyStorage.NONE
                || field.relation() != TableSchema.KeyStorage.NONE
                || !Integer.valueOf(role == Role.DISPLAY ? 16 : 32).equals(field.bits())) {
            return null;
        }
        int at = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                if (at >= 0) {
                    return null;
                }
                at = i;
            }
        }
        return at >= 0 && plain(columns.get(at)) ? at : null;
    }

    private static boolean plain(Db2Types.Column column) {
        return column.kind().equals("integer") && !column.isId() && column.interpretation() == null;
    }

    static Long edge(Db2Types.Row row, int column, Role role, long id) {
        if (row.id() != id
                || row.isCopy() != (row.copySourceId() != null)
                || column < 0
                || column >= row.values().size()
                || issues(row, column)) {
            return null;
        }
        Long n = CreatureLayout.integer(row.values().get(column));
        return n != null && n >= 0 && n <= (role == Role.DISPLAY ? 65535 : 0xffffffffL) ? n : null;
    }

    private static boolean issues(Db2Types.Row row, int column) {
        return row.textIssues() != null
                && row.textIssues().stream().anyMatch(i -> i.columnIndex() == column);
    }

    static CreatureRecordFacts facts(Role role, List<Db2Types.Column> columns, Db2Types.Row row) {
        if (columns.size() > 512) {
            return new CreatureRecordFacts(
                    CreatureFactsState.LIMIT_EXCEEDED,
                    List.of(),
                    List.of(),
                    "CREATURES_RESULT_LIMIT");
        }
        if (row.textIssues() != null && !row.textIssues().isEmpty()
                || row.values().size() != columns.size()) {
            return new CreatureRecordFacts(
                    CreatureFactsState.ROW_NOT_WHOLE, List.of(), List.of(), null);
        }
        var used = new HashSet<Integer>();
        List<CreatureFact> facts = new ArrayList<>();
        String[][] fields =
                role == Role.DISPLAY
                        ? new String[][] {
                            {"model", "ModelID"},
                            {"scale", "CreatureModelScale"},
                            {"textureVariations", "TextureVariationFileDataID"}
                        }
                        : new String[][] {{"file", "FileDataID"}, {"scale", "ModelScale"}};
        for (var field : fields) {
            List<CreatureFactCell> cells = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                if (base(columns.get(i).name()).equals(field[1]) && !columns.get(i).isId()) {
                    cells.add(new CreatureFactCell(columns.get(i).name(), i, row.values().get(i)));
                    used.add(i);
                }
            }
            if (cells.size() > 16) {
                return new CreatureRecordFacts(
                        CreatureFactsState.LIMIT_EXCEEDED,
                        List.of(),
                        List.of(),
                        "CREATURES_RESULT_LIMIT");
            }
            facts.add(
                    new CreatureFact(
                            field[0],
                            cells.isEmpty()
                                    ? CreatureFactState.UNSUPPORTED
                                    : CreatureFactState.VALUE,
                            cells));
        }
        List<CreatureFactCell> raw = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!used.contains(i) && !columns.get(i).isId()) {
                raw.add(new CreatureFactCell(columns.get(i).name(), i, row.values().get(i)));
            }
        }
        return new CreatureRecordFacts(CreatureFactsState.READY, facts, raw, null);
    }

    private static String base(String name) {
        int i = name.indexOf('[');
        return i < 0 ? name : name.substring(0, i);
    }

    static CreatureTextureEvidence textureEvidence(
            List<Db2Types.Column> columns, Db2Types.Row row) {
        Integer extended = value(columns, row, "ExtendedDisplayInfoID");
        if (extended == null) {
            return null;
        }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Integer v = value(columns, row, "TextureVariationFileDataID[" + i + "]");
            if (v == null) {
                return null;
            }
            slots.add(v);
        }
        return new CreatureTextureEvidence(extended, slots);
    }

    private static Integer value(List<Db2Types.Column> columns, Db2Types.Row row, String name) {
        int at = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                if (at >= 0) {
                    return null;
                }
                at = i;
            }
        }
        if (at < 0 || !plain(columns.get(at)) || at >= row.values().size() || issues(row, at)) {
            return null;
        }
        Long n = CreatureLayout.integer(row.values().get(at));
        return n != null && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? n.intValue() : null;
    }

    static List<CreatureTexture> textures(
            BuildModelTypes.ModelRead read, CreatureTextureEvidence evidence, Set<Integer> drawn) {
        List<CreatureTexture> out = new ArrayList<>();
        for (var texture : read.model().textures()) {
            var reference =
                    read.references().stream()
                            .filter(
                                    r ->
                                            r.kind() == BuildModelTypes.ReferenceKind.TEXTURE
                                                    && r.ordinal() == texture.ordinal())
                            .findFirst()
                            .orElse(null);
            boolean used = drawn != null && drawn.contains(texture.ordinal());
            CreatureTextureSource source;
            Integer variation = null;
            Long id = null;
            CreatureTextureState state;
            if (texture.type() == 0) {
                source = CreatureTextureSource.TXID;
                long raw =
                        texture.fileDataId() != null
                                ? texture.fileDataId()
                                : reference != null && reference.file() != null
                                        ? reference.file().fileDataId()
                                        : 0;
                id = raw > 0 ? raw : null;
                state =
                        id == null
                                ? CreatureTextureState.UNRESOLVED
                                : CreatureTextureState.RESOLVED;
            } else if (texture.type() >= 11 && texture.type() <= 13) {
                source = CreatureTextureSource.VARIATION;
                variation = (int) texture.type() - 11;
                int raw = evidence == null ? 0 : evidence.textureVariations().get(variation);
                id = raw > 0 ? (long) raw : null;
                state =
                        id == null
                                ? CreatureTextureState.UNRESOLVED
                                : CreatureTextureState.RESOLVED;
            } else {
                source = CreatureTextureSource.OTHER;
                state = CreatureTextureState.RESOLVED_ELSEWHERE;
            }
            out.add(
                    new CreatureTexture(
                            texture.ordinal(),
                            texture.type(),
                            texture.flags(),
                            texture.filename(),
                            source,
                            variation,
                            id,
                            state,
                            used,
                            reference,
                            null,
                            null,
                            null,
                            null));
        }
        return List.copyOf(out);
    }

    static CreatureAppearance geometryRefusal(M2Types.Model model) {
        String profile = M2GeometrySkinReader.profileRefusal(model);
        if (profile != null) {
            return appearance(
                    CreatureAppearanceState.GEOMETRY_UNSUPPORTED,
                    "MODEL_UNSUPPORTED",
                    profile,
                    null,
                    null,
                    null);
        }
        return model.skinFileDataIds().isEmpty() || model.skinFileDataIds().getFirst() == 0
                ? appearance(
                        CreatureAppearanceState.NO_MAIN_SKIN,
                        "MODEL_UNSUPPORTED",
                        "Model declares no positive main SKIN",
                        null,
                        null,
                        null)
                : null;
    }

    record SkinBatches(Set<Integer> drawn, int blended, CreatureAppearance refusal) {
        SkinBatches {
            drawn = Set.copyOf(drawn);
        }
    }

    static SkinBatches drawnTextures(M2Types.Model model, M2Types.Skin skin) {
        Set<Integer> drawn = new TreeSet<>();
        int blended = 0, draws = 0;
        Integer firstBlended = null;
        for (int i = 0; i < skin.batches().size(); i++) {
            var batch = skin.batches().get(i);
            if (batch.sectionIndex() < 0
                    || batch.materialIndex() < 0
                    || batch.sectionIndex() >= skin.sections().size()
                    || batch.materialIndex() >= model.materials().size()) {
                return new SkinBatches(
                        drawn,
                        blended,
                        material("Batch names no declared section/material", null, i));
            }
            int blend = model.materials().get(batch.materialIndex()).blendMode();
            if (blend > 7) {
                return new SkinBatches(
                        drawn,
                        blended,
                        appearance(
                                CreatureAppearanceState.BLEND_UNSUPPORTED,
                                "CREATURE_APPEARANCE_BLEND_UNSUPPORTED",
                                "Blend mode has no M2 meaning: " + blend,
                                null,
                                i,
                                null));
            }
            boolean triangles = skin.sections().get(batch.sectionIndex()).triangleCount() != 0;
            if (blend >= 2) {
                blended++;
                if (triangles && firstBlended == null) {
                    firstBlended = i;
                }
                continue;
            }
            if (batch.textureComboIndex() < 0
                    || batch.textureCount() < 0
                    || (long) batch.textureComboIndex() + batch.textureCount()
                            > model.textureCombos().size()) {
                return new SkinBatches(
                        drawn,
                        blended,
                        material("Batch texture lookup is outside declared textures", null, i));
            }
            for (int stage = 0; stage < batch.textureCount(); stage++) {
                int ordinal = model.textureCombos().get(batch.textureComboIndex() + stage);
                if (ordinal < 0 || ordinal >= model.textures().size()) {
                    return new SkinBatches(
                            drawn,
                            blended,
                            material("Batch texture lookup names no texture", null, i));
                }
                var texture = model.textures().get(ordinal);
                if ((texture.flags() & ~3L) != 0) {
                    return new SkinBatches(
                            drawn,
                            blended,
                            material("Texture flags exceed U/V wrap bits", ordinal, i));
                }
                if (texture.type() != 0 && (texture.type() < 11 || texture.type() > 13)) {
                    return new SkinBatches(
                            drawn,
                            blended,
                            material(
                                    "Texture type has no creature binding: " + texture.type(),
                                    ordinal,
                                    i));
                }
                drawn.add(ordinal);
            }
            if (triangles) {
                draws++;
            }
        }
        if (draws > 0) {
            return new SkinBatches(drawn, blended, null);
        }
        return new SkinBatches(
                drawn,
                blended,
                firstBlended != null
                        ? appearance(
                                CreatureAppearanceState.BLEND_UNSUPPORTED,
                                "CREATURE_APPEARANCE_BLEND_UNSUPPORTED",
                                "Every surface batch is blended; no opaque/alpha-key surface remains",
                                null,
                                firstBlended,
                                null)
                        : material("No batch draws declared surface triangles", null, null));
    }

    static CreatureAppearance refusal(
            CreatureTextureEvidence evidence,
            List<CreatureTexture> textures,
            CreatureAppearance batches) {
        if (evidence == null) {
            return appearance(
                    CreatureAppearanceState.TEXTURE_UNRESOLVED,
                    "CREATURE_DISPLAY_TEXTURES_UNREADABLE",
                    "Display texture evidence is unavailable",
                    null,
                    null,
                    null);
        }
        if (evidence.extendedDisplayInfoId() != 0) {
            return appearance(
                    CreatureAppearanceState.CHARACTER_MODEL,
                    "CREATURE_APPEARANCE_CHARACTER_MODEL",
                    "Character model baked textures are not resolved: "
                            + evidence.extendedDisplayInfoId(),
                    null,
                    null,
                    null);
        }
        if (batches != null) {
            return batches;
        }
        for (var texture : textures) {
            if (texture.drawn() && texture.state() == CreatureTextureState.UNRESOLVED) {
                return appearance(
                        CreatureAppearanceState.TEXTURE_UNRESOLVED,
                        "CREATURE_APPEARANCE_TEXTURE_UNRESOLVED",
                        "Declared texture has no positive TXID/display variation",
                        texture.ordinal(),
                        null,
                        null);
            }
        }
        return null;
    }

    static CreatureAppearanceState textureFailure(BlpImageTypes.State state) {
        return switch (state) {
            case PROTECTED -> CreatureAppearanceState.TEXTURE_PROTECTED;
            case UNSUPPORTED -> CreatureAppearanceState.TEXTURE_UNSUPPORTED;
            case LIMIT_EXCEEDED, MIP_UNAVAILABLE -> CreatureAppearanceState.TEXTURE_LIMIT;
            case INVALID -> CreatureAppearanceState.TEXTURE_CORRUPT;
            default -> CreatureAppearanceState.TEXTURE_UNAVAILABLE;
        };
    }

    static CreatureAppearance material(String why, Integer texture, Integer batch) {
        return appearance(
                CreatureAppearanceState.MATERIAL_UNSUPPORTED,
                "CREATURE_APPEARANCE_MATERIAL_UNSUPPORTED",
                why,
                texture,
                batch,
                null);
    }

    static CreatureAppearance appearance(
            CreatureAppearanceState state,
            String code,
            String why,
            Integer texture,
            Integer batch,
            Integer blended) {
        return new CreatureAppearance(state, code, why, texture, batch, blended);
    }

    static CreatureAppearance counted(CreatureAppearance a, int blended) {
        return new CreatureAppearance(
                a.state(), a.code(), a.message(), a.textureOrdinal(), a.batchOrdinal(), blended);
    }
}
