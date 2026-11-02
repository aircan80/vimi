package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.RelationIndexes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One detail's verified appearance/display/model/material/component chain; caches only within this
 * read.
 */
final class ItemAppearanceReader {
    static final String PROFILE_UNSUPPORTED_CODE = "ITEM_APPEARANCE_PROFILE_UNSUPPORTED",
            VALUE_INVALID_CODE = "ITEM_APPEARANCE_VALUE_INVALID",
            ROW_LIMIT_CODE = "ITEM_APPEARANCE_ROW_LIMIT";
    static final String MODIFIED = "ItemModifiedAppearance",
            APPEARANCE = "ItemAppearance",
            DISPLAY = "ItemDisplayInfo",
            ARMOR = "ItemDisplayInfoMaterialRes",
            MODEL = "ModelFileData",
            TEXTURE = "TextureFileData",
            COMPONENT_MODEL = "ComponentModelFileData",
            COMPONENT_TEXTURE = "ComponentTextureFileData",
            ICON_COLUMN = "DefaultIconFileDataID";
    private final BuildTables tables;
    private final RelationIndexes relations;
    private final ItemsOptions options;
    private final TableTypes.Priority priority;
    private final Cancellation token;
    private final Map<String, TableTypes.SchemaResult> schemas = new HashMap<>();
    private final Map<Long, ItemAppearanceRef> appearances = new HashMap<>();
    private final Map<Long, ItemDisplayRef> displays = new HashMap<>();
    private final Map<String, ItemResourceRef> resources = new HashMap<>();
    private final Map<String, ItemComponentKey> components = new HashMap<>();

    ItemAppearanceReader(
            BuildTables tables,
            RelationIndexes relations,
            ItemsOptions options,
            TableTypes.Priority priority,
            Cancellation token) {
        this.tables = tables;
        this.relations = relations;
        this.options = options;
        this.priority = priority;
        this.token = token;
    }

    private TableTypes.SchemaResult schema(String table) {
        return schemas.computeIfAbsent(
                table, name -> tables.schema(TableTypes.Ref.forName(name), priority, token).join());
    }

    private static List<Db2Types.Column> columns(TableTypes.SchemaResult schema) {
        return TableQueries.isReadable(schema.table().state())
                ? (schema.columns() == null ? List.of() : schema.columns())
                : null;
    }

    ItemAppearanceSet read(long itemId) {
        var schema = schema(MODIFIED);
        var columns = columns(schema);
        if (columns == null) {
            return set(ItemAppearancesState.TABLE_NOT_READABLE, schema.table(), null, null);
        }
        int[] fields =
                List.of("ItemID", "ItemAppearanceModifierID", "OrderIndex", "ItemAppearanceID")
                        .stream()
                        .mapToInt(name -> field(columns, schema.schema(), name, null))
                        .toArray();
        for (int i : fields) {
            if (i < 0) {
                return set(
                        ItemAppearancesState.UNSUPPORTED,
                        schema.table(),
                        PROFILE_UNSUPPORTED_CODE,
                        "Modified appearance fields are not verified plain integers");
            }
        }
        var scan =
                relations
                        .lookup(
                                MODIFIED,
                                "ItemID",
                                itemId,
                                options.maxAppearanceRows(),
                                priority,
                                token)
                        .join();
        var state =
                switch (scan.state()) {
                    case OK ->
                            scan.matches() > options.maxAppearanceRows()
                                    ? ItemAppearancesState.ROW_LIMIT
                                    : scan.complete()
                                            ? ItemAppearancesState.COMPLETE
                                            : ItemAppearancesState.INCOMPLETE;
                    case INDEX_LIMIT -> ItemAppearancesState.INDEX_LIMIT;
                    case STALE -> ItemAppearancesState.STALE;
                    case NOT_READABLE -> ItemAppearancesState.TABLE_NOT_READABLE;
                    default -> ItemAppearancesState.UNSUPPORTED;
                };
        if (state != ItemAppearancesState.COMPLETE
                && state != ItemAppearancesState.INCOMPLETE
                && state != ItemAppearancesState.ROW_LIMIT) {
            return set(
                    state,
                    scan.table() == null ? schema.table() : scan.table(),
                    scan.code(),
                    scan.message());
        }
        var rows = new ArrayList<ItemAppearanceEntry>();
        for (var row : scan.rows()) {
            token.check();
            Long modifier = integer(row.row(), fields[1]),
                    order = integer(row.row(), fields[2]),
                    appearance = integer(row.row(), fields[3]);
            var target =
                    uint(appearance)
                            ? appearance(appearance)
                            : new ItemAppearanceRef(
                                    ItemRefState.INVALID_VALUE,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    VALUE_INVALID_CODE,
                                    "Appearance key is not uint32");
            rows.add(new ItemAppearanceEntry(row, modifier, order, appearance, target));
        }
        var defaults = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            if (Long.valueOf(0).equals(rows.get(i).itemAppearanceModifierId())) {
                defaults.add(i);
            }
        }
        var kind =
                defaults.size() > 1
                        ? ItemDefaultAppearanceState.AMBIGUOUS
                        : state != ItemAppearancesState.COMPLETE
                                ? ItemDefaultAppearanceState.UNKNOWN
                                : defaults.size() == 1
                                        ? ItemDefaultAppearanceState.DEFAULT
                                        : ItemDefaultAppearanceState.NONE;
        Integer at = kind == ItemDefaultAppearanceState.DEFAULT ? defaults.getFirst() : null;
        return new ItemAppearanceSet(
                state,
                rows,
                kind,
                at,
                scan.matches(),
                scan.table(),
                ItemModelRefs.NONE,
                state == ItemAppearancesState.ROW_LIMIT ? ROW_LIMIT_CODE : null,
                state == ItemAppearancesState.ROW_LIMIT
                        ? "Appearance rows exceed detail bound"
                        : null);
    }

    private static ItemAppearanceSet set(
            ItemAppearancesState state, TableTypes.Entry table, String code, String message) {
        return new ItemAppearanceSet(
                state,
                List.of(),
                ItemDefaultAppearanceState.UNKNOWN,
                null,
                0,
                table,
                ItemModelRefs.NONE,
                code,
                message);
    }

    private ItemAppearanceRef appearance(long id) {
        var hit = appearances.get(id);
        if (hit != null) {
            return hit;
        }
        var s = schema(APPEARANCE);
        var columns = columns(s);
        ItemAppearanceRef result;
        if (columns == null) {
            result =
                    new ItemAppearanceRef(
                            ItemRefState.TABLE_NOT_READABLE,
                            id,
                            null,
                            null,
                            null,
                            null,
                            s.table(),
                            null,
                            null,
                            null);
        } else {
            int icon = field(columns, s.schema(), ICON_COLUMN, null),
                    display = field(columns, s.schema(), "ItemDisplayInfoID", null);
            if (icon < 0 || display < 0) {
                result =
                        new ItemAppearanceRef(
                                ItemRefState.UNSUPPORTED,
                                id,
                                null,
                                null,
                                null,
                                null,
                                s.table(),
                                null,
                                PROFILE_UNSUPPORTED_CODE,
                                "Appearance icon/display columns unsupported");
            } else {
                var found =
                        tables.get(TableTypes.Ref.forName(APPEARANCE), id, priority, token).join();
                if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                    result =
                            new ItemAppearanceRef(
                                    refState(found.outcome()),
                                    id,
                                    null,
                                    null,
                                    null,
                                    null,
                                    found.table(),
                                    found.protectedKey(),
                                    null,
                                    null);
                } else {
                    Long iconId = unsigned(found.row().row(), icon),
                            displayId = unsigned(found.row().row(), display);
                    var displayRef =
                            displayId != null
                                    ? display(displayId)
                                    : new ItemDisplayRef(
                                            ItemRefState.INVALID_VALUE,
                                            null,
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            VALUE_INVALID_CODE,
                                            "Display key is not uint32");
                    result =
                            new ItemAppearanceRef(
                                    ItemRefState.FOUND,
                                    id,
                                    found.row(),
                                    iconId,
                                    displayId,
                                    displayRef,
                                    found.table(),
                                    null,
                                    null,
                                    null);
                }
            }
        }
        appearances.put(id, result);
        return result;
    }

    private ItemDisplayRef display(long id) {
        var hit = displays.get(id);
        if (hit != null) {
            return hit;
        }
        var s = schema(DISPLAY);
        var columns = columns(s);
        ItemDisplayRef result;
        if (columns == null) {
            result =
                    new ItemDisplayRef(
                            ItemRefState.TABLE_NOT_READABLE,
                            id,
                            null,
                            List.of(),
                            s.table(),
                            null,
                            null,
                            null);
        } else {
            int[]
                    models =
                            {
                                field(columns, s.schema(), "ModelResourcesID", 0),
                                field(columns, s.schema(), "ModelResourcesID", 1)
                            },
                    materials =
                            {
                                field(columns, s.schema(), "ModelMaterialResourcesID", 0),
                                field(columns, s.schema(), "ModelMaterialResourcesID", 1)
                            };
            if (models[0] < 0 || models[1] < 0 || materials[0] < 0 || materials[1] < 0) {
                result =
                        new ItemDisplayRef(
                                ItemRefState.UNSUPPORTED,
                                id,
                                null,
                                List.of(),
                                s.table(),
                                null,
                                PROFILE_UNSUPPORTED_CODE,
                                "Display resource slots unsupported");
            } else {
                var found = tables.get(TableTypes.Ref.forName(DISPLAY), id, priority, token).join();
                if (found.outcome() != TableTypes.RowOutcome.FOUND) {
                    result =
                            new ItemDisplayRef(
                                    refState(found.outcome()),
                                    id,
                                    null,
                                    List.of(),
                                    found.table(),
                                    found.protectedKey(),
                                    null,
                                    null);
                } else {
                    var slots = new ArrayList<ItemDisplaySlot>();
                    for (int slot = 0; slot < 2; slot++) {
                        slots.add(
                                new ItemDisplaySlot(
                                        slot,
                                        resource(
                                                ItemResourceKind.MODEL,
                                                slot,
                                                integer(found.row().row(), models[slot])),
                                        resource(
                                                ItemResourceKind.MATERIAL,
                                                slot,
                                                integer(found.row().row(), materials[slot]))));
                    }
                    result =
                            new ItemDisplayRef(
                                    ItemRefState.FOUND,
                                    id,
                                    found.row(),
                                    slots,
                                    found.table(),
                                    null,
                                    null,
                                    null,
                                    armor(id));
                }
            }
        }
        displays.put(id, result);
        return result;
    }

    private static ItemResourceRef withSlot(ItemResourceRef value, int slot) {
        return new ItemResourceRef(
                value.kind(),
                slot,
                value.resourceId(),
                value.state(),
                value.complete(),
                value.files(),
                value.matches(),
                value.table(),
                value.keyNames(),
                value.code(),
                value.message());
    }

    private ItemResourceRef resource(ItemResourceKind kind, int slot, Long id) {
        if (!uint(id)) {
            return new ItemResourceRef(
                    kind,
                    slot,
                    id,
                    ItemResourceState.INVALID_VALUE,
                    false,
                    List.of(),
                    0,
                    null,
                    null,
                    VALUE_INVALID_CODE,
                    "Resource key is not uint32");
        }
        if (id == 0) {
            return new ItemResourceRef(
                    kind,
                    slot,
                    0L,
                    ItemResourceState.NO_RESOURCE,
                    true,
                    List.of(),
                    0,
                    null,
                    null,
                    null,
                    null);
        }
        String key = kind + ":" + id;
        var hit = resources.get(key);
        if (hit != null) {
            return withSlot(hit, slot);
        }
        String table = kind == ItemResourceKind.MODEL ? MODEL : TEXTURE,
                column =
                        kind == ItemResourceKind.MODEL ? "ModelResourcesID" : "MaterialResourcesID";
        var s = schema(table);
        var columns = columns(s);
        ItemResourceRef result;
        if (columns == null) {
            result =
                    new ItemResourceRef(
                            kind,
                            slot,
                            id,
                            ItemResourceState.TABLE_NOT_READABLE,
                            false,
                            List.of(),
                            0,
                            s.table(),
                            null,
                            null,
                            null);
        } else if (field(columns, s.schema(), column, null) < 0) {
            result =
                    new ItemResourceRef(
                            kind,
                            slot,
                            id,
                            ItemResourceState.UNSUPPORTED,
                            false,
                            List.of(),
                            0,
                            s.table(),
                            null,
                            PROFILE_UNSUPPORTED_CODE,
                            "Resource relation field unsupported");
        } else {
            var scan =
                    relations
                            .lookup(table, column, id, options.maxResourceFiles(), priority, token)
                            .join();
            var files = new ArrayList<ItemResourceFile>();
            for (var row : scan.rows()) {
                token.check();
                files.add(
                        new ItemResourceFile(row.row().id(), row, component(kind, row.row().id())));
            }
            var state =
                    switch (scan.state()) {
                        case OK ->
                                scan.matches() > options.maxResourceFiles()
                                        ? ItemResourceState.ROW_LIMIT
                                        : !files.isEmpty()
                                                ? ItemResourceState.FOUND
                                                : scan.complete()
                                                        ? ItemResourceState.ABSENT
                                                        : ItemResourceState.UNKNOWN;
                        case INDEX_LIMIT -> ItemResourceState.INDEX_LIMIT;
                        case STALE -> ItemResourceState.STALE;
                        case NOT_READABLE -> ItemResourceState.TABLE_NOT_READABLE;
                        default -> ItemResourceState.UNSUPPORTED;
                    };
            result =
                    new ItemResourceRef(
                            kind,
                            slot,
                            id,
                            state,
                            scan.complete() && state != ItemResourceState.ROW_LIMIT,
                            files,
                            scan.matches(),
                            scan.table() == null ? s.table() : scan.table(),
                            keys(scan.table()),
                            state == ItemResourceState.ROW_LIMIT ? ROW_LIMIT_CODE : scan.code(),
                            state == ItemResourceState.ROW_LIMIT
                                    ? "Resource rows exceed detail bound"
                                    : scan.message());
        }
        resources.put(key, result);
        return result;
    }

    private ItemComponentKey component(ItemResourceKind kind, long id) {
        String key = kind + ":" + id;
        var hit = components.get(key);
        if (hit != null) {
            return hit;
        }
        String table = kind == ItemResourceKind.MODEL ? COMPONENT_MODEL : COMPONENT_TEXTURE;
        var s = schema(table);
        var columns = columns(s);
        ItemComponentKey result;
        if (columns == null) {
            result =
                    new ItemComponentKey(
                            ItemRefState.TABLE_NOT_READABLE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            s.table(),
                            null,
                            null,
                            null);
        } else {
            int race = field(columns, s.schema(), "RaceID", null),
                    gender = field(columns, s.schema(), "GenderIndex", null),
                    cls = field(columns, s.schema(), "ClassID", null),
                    position =
                            kind == ItemResourceKind.MODEL
                                    ? field(columns, s.schema(), "PositionIndex", null)
                                    : -1;
            if (race < 0
                    || gender < 0
                    || cls < 0
                    || kind == ItemResourceKind.MODEL && position < 0) {
                result =
                        new ItemComponentKey(
                                ItemRefState.UNSUPPORTED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                s.table(),
                                null,
                                PROFILE_UNSUPPORTED_CODE,
                                "Component key columns unsupported");
            } else {
                var found = tables.get(TableTypes.Ref.forName(table), id, priority, token).join();
                result =
                        found.outcome() != TableTypes.RowOutcome.FOUND
                                ? new ItemComponentKey(
                                        refState(found.outcome()),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        found.table(),
                                        found.protectedKey(),
                                        null,
                                        null)
                                : new ItemComponentKey(
                                        ItemRefState.FOUND,
                                        integer(found.row().row(), race),
                                        integer(found.row().row(), gender),
                                        integer(found.row().row(), cls),
                                        position < 0 ? null : integer(found.row().row(), position),
                                        found.row(),
                                        found.table(),
                                        null,
                                        null,
                                        null);
            }
        }
        components.put(key, result);
        return result;
    }

    private ItemArmorMaterials armor(long display) {
        var s = schema(ARMOR);
        var columns = columns(s);
        if (columns == null) {
            return new ItemArmorMaterials(
                    ItemArmorMaterialsState.TABLE_NOT_READABLE,
                    List.of(),
                    0,
                    s.table(),
                    keys(s.table()),
                    null,
                    null);
        }
        int parent = field(columns, s.schema(), "ItemDisplayInfoID", null),
                section = field(columns, s.schema(), "ComponentSection", null),
                material = field(columns, s.schema(), "MaterialResourcesID", null);
        if (parent < 0 || section < 0 || material < 0 || !armorEvidence(s.schema())) {
            return new ItemArmorMaterials(
                    ItemArmorMaterialsState.UNSUPPORTED,
                    List.of(),
                    0,
                    s.table(),
                    keys(s.table()),
                    PROFILE_UNSUPPORTED_CODE,
                    "Armor material fields are not verified plain integers");
        }
        var scan =
                relations
                        .lookup(
                                ARMOR,
                                "ItemDisplayInfoID",
                                display,
                                options.maxResourceFiles(),
                                priority,
                                token)
                        .join();
        var state =
                switch (scan.state()) {
                    case OK ->
                            scan.matches() > options.maxResourceFiles()
                                    ? ItemArmorMaterialsState.ROW_LIMIT
                                    : scan.complete()
                                            ? ItemArmorMaterialsState.COMPLETE
                                            : ItemArmorMaterialsState.INCOMPLETE;
                    case INDEX_LIMIT -> ItemArmorMaterialsState.INDEX_LIMIT;
                    case STALE -> ItemArmorMaterialsState.STALE;
                    case NOT_READABLE -> ItemArmorMaterialsState.TABLE_NOT_READABLE;
                    default -> ItemArmorMaterialsState.UNSUPPORTED;
                };
        var rows = new ArrayList<ItemArmorMaterial>();
        for (var row : scan.rows()) {
            rows.add(
                    new ItemArmorMaterial(
                            row,
                            integer(row.row(), section),
                            integer(row.row(), material),
                            resource(ItemResourceKind.MATERIAL, -1, integer(row.row(), material))));
        }
        return new ItemArmorMaterials(
                state,
                rows,
                scan.matches(),
                scan.table() == null ? s.table() : scan.table(),
                keys(scan.table()),
                state == ItemArmorMaterialsState.ROW_LIMIT ? ROW_LIMIT_CODE : scan.code(),
                state == ItemArmorMaterialsState.ROW_LIMIT
                        ? "Armor material rows exceed detail bound"
                        : scan.message());
    }

    private static boolean armorEvidence(TableSchema schema) {
        if (schema == null) {
            return false;
        }
        var parent =
                schema.columns().stream()
                        .filter(column -> column.name().equals("ItemDisplayInfoID"))
                        .findFirst()
                        .orElse(null);
        var material =
                schema.columns().stream()
                        .filter(column -> column.name().equals("MaterialResourcesID"))
                        .findFirst()
                        .orElse(null);
        var section =
                schema.columns().stream()
                        .filter(column -> column.name().equals("ComponentSection"))
                        .findFirst()
                        .orElse(null);
        return parent != null
                && parent.foreignKey() != null
                && parent.foreignKey().table().equals(DISPLAY)
                && parent.foreignKey().column().equals("ID")
                && material != null
                && material.foreignKey() != null
                && material.foreignKey().table().equals(TEXTURE)
                && material.foreignKey().column().equals("MaterialResourcesID")
                && section != null
                && Integer.valueOf(8).equals(section.bits());
    }

    private static List<String> keys(TableTypes.Entry table) {
        return table != null && table.state() == TableTypes.State.PARTIAL && table.reason() != null
                ? table.reason().keyNames()
                : List.of();
    }

    private static ItemRefState refState(TableTypes.RowOutcome outcome) {
        return switch (outcome) {
            case ABSENT -> ItemRefState.ABSENT;
            case PROTECTED -> ItemRefState.PROTECTED;
            default -> ItemRefState.UNKNOWN;
        };
    }

    static int field(List<Db2Types.Column> columns, TableSchema schema, String name, Integer slot) {
        String header = slot == null ? name : name + "[" + slot + "]";
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            var c = columns.get(i);
            if (c.name().equals(header)
                    && c.kind().equals("integer")
                    && c.interpretation() == null) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        if (found < 0) {
            return -1;
        }
        var declared =
                schema == null
                        ? null
                        : schema.columns().stream()
                                .filter(c -> c.name().equals(name))
                                .findFirst()
                                .orElse(null);
        if (declared == null) {
            return found;
        }
        return declared.kind() == TableSchema.ColumnKind.INT
                        && declared.verifiedName()
                        && declared.bits() != null
                        && declared.bits() <= 32
                        && declared.arrayLength() == (slot == null ? 1 : 2)
                ? found
                : -1;
    }

    private static Long integer(Db2Types.Row row, int index) {
        if (index < 0 || index >= row.values().size() || ItemLayout.issue(row, index)) {
            return null;
        }
        BigInteger value = TableQueries.parseInteger(row.values().get(index));
        return value != null
                        && value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                        && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
                ? value.longValue()
                : null;
    }

    private static boolean uint(Long value) {
        return value != null && value >= 0 && value <= 0xffffffffL;
    }

    private static Long unsigned(Db2Types.Row row, int index) {
        var value = integer(row, index);
        return uint(value) ? value : null;
    }
}
