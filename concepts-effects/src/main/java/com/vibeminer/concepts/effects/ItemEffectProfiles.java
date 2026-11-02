package com.vibeminer.concepts.effects;

import com.vibeminer.concepts.effects.EffectsTypes.DefinitionProvenance;
import com.vibeminer.concepts.effects.EffectsTypes.DefinitionWarning;
import com.vibeminer.concepts.effects.EffectsTypes.Evidence;
import com.vibeminer.concepts.effects.EffectsTypes.Field;
import com.vibeminer.concepts.effects.EffectsTypes.Link;
import com.vibeminer.concepts.effects.EffectsTypes.Problem;
import com.vibeminer.concepts.effects.EffectsTypes.Role;
import com.vibeminer.concepts.effects.EffectsTypes.Source;
import com.vibeminer.concepts.effects.EffectsTypes.SourceState;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.TableSchema.ColumnKind;
import com.vibeminer.formats.definitions.TableSchema.KeyStorage;
import com.vibeminer.formats.definitions.TableSchema.SchemaColumn;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reviewed physical profiles and input widths. No trigger/effect meaning or texture inference. */
public final class ItemEffectProfiles {
    public static final String ITEM_TABLE = "Item",
            BRIDGE_TABLE = "ItemXItemEffect",
            EFFECT_TABLE = "ItemEffect",
            NAME_TABLE = "SpellName",
            FIELD_UNAVAILABLE_CODE = "ITEM_EFFECTS_FIELD_UNAVAILABLE",
            TEXT_INVALID_CODE = "DB2_TEXT_ENCODING_INVALID",
            LAYOUT_MATCH_WARNING = "ITEM_EFFECTS_DEFINITION_MATCHED_BY_LAYOUT";
    private static final List<String> INPUTS =
            List.of(
                    "ID",
                    "LegacySlotIndex",
                    "TriggerType",
                    "Charges",
                    "CoolDownMSec",
                    "CategoryCoolDownMSec",
                    "SpellCategoryID",
                    "SpellID",
                    "ChrSpecializationID",
                    "PlayerConditionID");

    public record Profile(
            String table,
            long fileDataId,
            String layout,
            List<String> columns,
            List<Integer> rawColumns) {
        public Profile {
            columns = List.copyOf(columns);
            rawColumns = List.copyOf(rawColumns);
        }

        public boolean isEffect() {
            return table.equals(EFFECT_TABLE);
        }

        public boolean isName() {
            return table.equals(NAME_TABLE);
        }
    }

    public static final Profile
            ITEM_X_ITEM_EFFECT =
                    new Profile(
                            BRIDGE_TABLE,
                            3177687,
                            "96F083AD",
                            List.of("ID", "ItemEffectID", "ItemID"),
                            List.of()),
            RETAIL_ITEM_EFFECT =
                    new Profile(EFFECT_TABLE, 969941, "4CA77678", INPUTS, List.of(3, 6, 8)),
            CLASSIC_ITEM_EFFECT =
                    new Profile(
                            EFFECT_TABLE,
                            969941,
                            "1BF9CF3A",
                            java.util.stream.Stream.concat(
                                            INPUTS.stream(),
                                            java.util.stream.Stream.of("ParentItemID"))
                                    .toList(),
                            List.of()),
            SPELL_NAME =
                    new Profile(
                            NAME_TABLE, 1990283, "782EE721", List.of("ID", "Name_lang"), List.of());

    private ItemEffectProfiles() {}

    public static int parentColumn(Link link) {
        return link == Link.ITEM_X_ITEM_EFFECT ? 2 : 10;
    }

    public static String parentColumnName(Link link) {
        return link == Link.ITEM_X_ITEM_EFFECT ? "ItemID" : "ParentItemID";
    }

    public static Profile bridge(Link link) {
        return link == Link.ITEM_X_ITEM_EFFECT ? ITEM_X_ITEM_EFFECT : CLASSIC_ITEM_EFFECT;
    }

    public static Profile effect(Link link) {
        return link == Link.ITEM_X_ITEM_EFFECT ? RETAIL_ITEM_EFFECT : CLASSIC_ITEM_EFFECT;
    }

    public static Link choose(TableSchema effect, TableSchema bridge) {
        if (effect != null) {
            return effect.columns().stream()
                            .anyMatch(
                                    c ->
                                            "ParentItemID".equals(c.name())
                                                    && c.relation() != KeyStorage.NONE)
                    ? Link.PARENT_ITEM_ID
                    : Link.ITEM_X_ITEM_EFFECT;
        }
        return bridge != null ? Link.ITEM_X_ITEM_EFFECT : Link.PARENT_ITEM_ID;
    }

    public static boolean fieldMatches(Profile profile, int index, SchemaColumn field) {
        String name = profile.columns().get(index);
        if (!Objects.equals(field.name(), name) || field.arrayLength() != 1) {
            return false;
        }
        if (profile.isName()) {
            return field.verifiedName()
                    && field.kind() == (index == 1 ? ColumnKind.LOCSTRING : ColumnKind.INT)
                    && field.id() == (index == 0 ? KeyStorage.NONINLINE : KeyStorage.NONE)
                    && field.relation() == KeyStorage.NONE
                    && (index == 1
                            || Integer.valueOf(32).equals(field.bits())
                                    && Boolean.FALSE.equals(field.unsigned()));
        }
        boolean effect = profile.isEffect(),
                relation = effect ? name.equals("ParentItemID") : index == 2;
        int bits =
                effect
                        ? (index == 1 || index == 2
                                ? 8
                                : index == 3 || index == 6 || index == 8 ? 16 : 32)
                        : 32;
        boolean unsigned = effect && (index == 1 || index == 2 || index == 6 || index == 8);
        return field.verifiedName() != (effect && index == 9)
                && field.kind() == ColumnKind.INT
                && field.id() == (index == 0 ? KeyStorage.NONINLINE : KeyStorage.NONE)
                && field.relation() == (relation ? KeyStorage.NONINLINE : KeyStorage.NONE)
                && Integer.valueOf(bits).equals(field.bits())
                && Boolean.valueOf(unsigned).equals(field.unsigned());
    }

    public static boolean qualifies(Profile profile, TableTypes.SchemaResult result) {
        var table = result.table();
        var counts = table.counts();
        if (counts == null
                || !"WDC5".equals(counts.format())
                || table.fileDataId() != profile.fileDataId()
                || !profile.layout().equalsIgnoreCase(counts.layoutHash())
                || result.schema() == null
                || result.columns() == null) {
            return false;
        }
        var schema = result.schema();
        var columns = result.columns();
        if (schema.columns().size() != profile.columns().size()
                || columns.size() != profile.columns().size()) {
            return false;
        }
        for (int i = 0; i < profile.columns().size(); i++) {
            var actual = columns.get(i);
            boolean text = profile.isName() && i == 1;
            if (!fieldMatches(profile, i, schema.columns().get(i))
                    || !Objects.equals(actual.name(), profile.columns().get(i))
                    || !Objects.equals(actual.kind(), text ? "string" : "integer")
                    || actual.isId() != (i == 0)) {
                return false;
            }
            var raw = actual.interpretation();
            if (raw != null
                    && (!profile.rawColumns().contains(i)
                            || !"raw-physical-integer-v1".equals(raw.policy())
                            || raw.physicalBits() != 32
                            || raw.physicalSigned()
                            || raw.candidateBits() != 16
                            || raw.candidateUnsigned() != (i != 3)
                            || raw.warning() == null
                            || raw.warning().isEmpty()
                            || raw.warning().length() > 512
                            || raw.warning().chars().anyMatch(Character::isISOControl))) {
                return false;
            }
        }
        return true;
    }

    public static Source source(Role role, TableTypes.Entry table) {
        TableDefinitions.State definition =
                table.state() == TableTypes.State.NO_DEFINITION
                                || table.state() == TableTypes.State.DEFINITION_UNUSABLE
                        ? table.reason() == null ? null : table.reason().definitionState()
                        : null;
        SourceState state =
                switch (table.state()) {
                    case READY, PARTIAL -> SourceState.READY;
                    case NOT_IN_BUILD -> SourceState.NOT_IN_BUILD;
                    case NOT_IN_LOCALE -> SourceState.NOT_IN_LOCALE;
                    case NEEDS_NETWORK,
                            MISSING,
                            UNAVAILABLE,
                            NOT_IN_ENCODING,
                            NOT_STORED,
                            NOT_CHECKED ->
                            SourceState.UNAVAILABLE;
                    case ENCRYPTED, WRONG_KEY -> SourceState.PROTECTED;
                    case TOO_LARGE -> SourceState.RESOURCE_LIMIT;
                    case CORRUPT, REJECTED, STORE_FAILED -> SourceState.CORRUPT;
                    case DEFINITION_UNUSABLE ->
                            table.reason() != null
                                            && ("DEFINITION_FETCH_OFFLINE"
                                                            .equals(table.reason().code())
                                                    || "DEFINITION_FETCH_FAILED"
                                                            .equals(table.reason().code()))
                                    ? SourceState.UNAVAILABLE
                                    : SourceState.UNSUPPORTED;
                    default -> SourceState.UNSUPPORTED;
                };
        return state == SourceState.READY
                ? new Source(role, state, table, null)
                : failed(role, state, table, definition);
    }

    public static Source failed(
            Role role,
            SourceState state,
            TableTypes.Entry table,
            TableDefinitions.State definition) {
        return new Source(role, state, table, problem(state, definition));
    }

    public static Source failed(Source previous, SourceState state, TableTypes.Entry table) {
        return new Source(
                previous.role(), state, table, problem(state, null), previous.definition());
    }

    public static Problem problem(SourceState state, TableDefinitions.State definition) {
        return switch (state) {
            case UNAVAILABLE ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_UNAVAILABLE",
                            "The selected source is unavailable.",
                            true,
                            definition);
            case PROTECTED ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_PROTECTED",
                            "The selected source is protected.",
                            false,
                            null);
            case UNSUPPORTED ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_UNSUPPORTED",
                            "The selected source profile is unsupported.",
                            false,
                            definition);
            case CORRUPT ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_CORRUPT",
                            "The selected source failed integrity validation.",
                            false,
                            null);
            case RESOURCE_LIMIT ->
                    new Problem(
                            "ITEM_EFFECTS_LIMIT",
                            "The effects resource limit was exceeded.",
                            false,
                            null);
            case NOT_IN_BUILD ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_NOT_IN_BUILD",
                            "This build does not include the selected source.",
                            false,
                            null);
            case NOT_IN_LOCALE ->
                    new Problem(
                            "ITEM_EFFECTS_SOURCE_NOT_IN_LOCALE",
                            "This build includes the selected source in other locales, not this one.",
                            false,
                            null);
            default -> throw new IllegalArgumentException("State has no problem");
        };
    }

    public static Source evaluate(Role role, Profile profile, TableTypes.SchemaResult result) {
        Source source = source(role, result.table());
        DefinitionProvenance provenance = null;
        if (result.schema() != null) {
            var schema = result.schema();
            List<DefinitionWarning> warnings =
                    schema.match() == SchemaMatch.LAYOUT
                            ? List.of(
                                    new DefinitionWarning(
                                            LAYOUT_MATCH_WARNING,
                                            "The definition was selected by layout hash rather than an exact build match."))
                            : List.of();
            provenance =
                    new DefinitionProvenance(
                            schema.match(),
                            result.table().counts() == null
                                    ? null
                                    : result.table().counts().layoutHash(),
                            schema.layoutHashes(),
                            warnings);
        }
        source =
                new Source(
                        source.role(),
                        source.state(),
                        source.table(),
                        source.problem(),
                        provenance);
        return source.state() == SourceState.READY && !qualifies(profile, result)
                ? failed(source, SourceState.UNSUPPORTED, result.table())
                : source;
    }

    public static List<Field> fields(
            Profile profile, List<Db2Types.Column> columns, Db2Types.Row row) {
        List<Field> fields = new ArrayList<>(9);
        for (int i = 1; i <= 9; i++) {
            String value = row.values().get(i);
            var interpretation = columns.get(i).interpretation();
            boolean raw = profile.rawColumns().contains(i) && interpretation != null;
            if (hasIssue(row, i)
                    || !(i == 1 || i == 2
                            ? signed(value, 0, 255)
                            : raw
                                    ? signed(value, 0, 0xffffffffL)
                                    : i == 6 || i == 8
                                            ? signed(value, 0, 65535)
                                            : i == 3
                                                    ? signed(
                                                            value, Short.MIN_VALUE, Short.MAX_VALUE)
                                                    : signed(
                                                            value,
                                                            Integer.MIN_VALUE,
                                                            Integer.MAX_VALUE))) {
                return null;
            }
            Evidence evidence =
                    raw
                            ? Evidence.RAW_PHYSICAL_INTEGER
                            : i == 9 ? Evidence.UNVERIFIED_CANDIDATE : Evidence.STRICT_NUMERIC;
            TableSchema.ForeignKey hint =
                    switch (i) {
                        case 6 -> new TableSchema.ForeignKey("SpellCategory", "ID");
                        case 8 -> new TableSchema.ForeignKey("ChrSpecialization", "ID");
                        case 9 -> new TableSchema.ForeignKey("PlayerCondition", "ID");
                        default -> null;
                    };
            fields.add(
                    new Field(
                            profile.columns().get(i),
                            i,
                            value,
                            evidence,
                            raw ? interpretation : null,
                            hint));
        }
        return List.copyOf(fields);
    }

    public static String spellId(Db2Types.Row row) {
        return !hasIssue(row, 7)
                        && signed(row.values().get(7), Integer.MIN_VALUE, Integer.MAX_VALUE)
                ? row.values().get(7)
                : null;
    }

    public static boolean hasIssue(Db2Types.Row row, int column) {
        return row.textIssues() != null
                && row.textIssues().stream().anyMatch(issue -> issue.columnIndex() == column);
    }

    public static Long signedNumber(String value, long min, long max) {
        BigInteger number = TableQueries.parseInteger(value);
        return number != null
                        && number.compareTo(BigInteger.valueOf(min)) >= 0
                        && number.compareTo(BigInteger.valueOf(max)) <= 0
                ? number.longValue()
                : null;
    }

    public static boolean signed(String value, long min, long max) {
        return signedNumber(value, min, max) != null;
    }

    public static boolean wellFormed(String value) {
        Objects.requireNonNull(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isSurrogate(c)) {
                continue;
            }
            if (!Character.isHighSurrogate(c)
                    || ++i == value.length()
                    || !Character.isLowSurrogate(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
