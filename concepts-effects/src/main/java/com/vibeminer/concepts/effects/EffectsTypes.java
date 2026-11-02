package com.vibeminer.concepts.effects;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenState;
import com.vibeminer.formats.db2.Db2Types.Interpretation;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.definitions.TableSchema.ForeignKey;
import com.vibeminer.formats.definitions.TableSchema.SchemaMatch;
import com.vibeminer.formats.tables.TableTypes;
import java.time.Instant;
import java.util.List;

/**
 * Immutable facts, raw input evidence and source outcomes; IDs are unsigned32 represented by long.
 */
public final class EffectsTypes {
    private EffectsTypes() {}

    public record OpenResult(
            BuildRef build,
            String locale,
            FilesOpenState state,
            BuildItemEffects effects,
            String code,
            String message,
            Instant observedAt) {}

    public record Query(int limit, String after) {
        public Query() {
            this(ItemEffectQueries.MAX_LIMIT, null);
        }

        public Query(int limit) {
            this(limit, null);
        }
    }

    public enum Link {
        ITEM_X_ITEM_EFFECT,
        PARENT_ITEM_ID;

        public String cursorName() {
            return this == ITEM_X_ITEM_EFFECT ? "ItemXItemEffect" : "ParentItemId";
        }
    }

    public enum PageState {
        OK,
        INDEX_LIMIT,
        ITEM_NOT_READABLE,
        MISSING_ITEM,
        ITEM_UNKNOWN,
        ITEM_PROTECTED,
        ITEM_KEY_OUT_OF_RANGE,
        BRIDGE_NOT_READY,
        STALE,
        INVALID_CURSOR
    }

    public enum Role {
        BRIDGE,
        EFFECT,
        NAME
    }

    public enum SourceState {
        NOT_CHECKED,
        READY,
        NOT_IN_BUILD,
        NOT_IN_LOCALE,
        UNAVAILABLE,
        PROTECTED,
        UNSUPPORTED,
        CORRUPT,
        RESOURCE_LIMIT
    }

    public record Problem(
            String code, String message, boolean retryable, TableDefinitions.State definition) {}

    /**
     * Additive issue94 provenance. A layout match is a definition selection fact, not new game-fact
     * authority.
     */
    public record DefinitionWarning(String code, String message) {}

    public record DefinitionProvenance(
            SchemaMatch match,
            String observedLayoutHash,
            List<String> declaredLayoutHashes,
            List<DefinitionWarning> warnings) {
        public DefinitionProvenance {
            declaredLayoutHashes = List.copyOf(declaredLayoutHashes);
            warnings = List.copyOf(warnings);
        }
    }

    public record Source(
            Role role,
            SourceState state,
            TableTypes.Entry table,
            Problem problem,
            DefinitionProvenance definition) {
        public Source(Role role, SourceState state) {
            this(role, state, null, null, null);
        }

        public Source(Role role, SourceState state, TableTypes.Entry table, Problem problem) {
            this(role, state, table, problem, null);
        }
    }

    public enum Absence {
        ABSENT_FROM_COMPLETE_SOURCE,
        UNKNOWN_OUTSIDE_READABLE_SCOPE
    }

    public enum EffectState {
        OBSERVED_READY,
        EFFECT_KEY_OUT_OF_RANGE,
        FIELD_UNAVAILABLE,
        NOT_FOUND_IN_ADMITTED_SCOPE,
        PROTECTED,
        SOURCE_NOT_READY
    }

    public enum Evidence {
        STRICT_NUMERIC,
        RAW_PHYSICAL_INTEGER,
        UNVERIFIED_CANDIDATE
    }

    public record Field(
            String name,
            int columnIndex,
            String value,
            Evidence evidence,
            Interpretation interpretation,
            ForeignKey candidateReference) {}

    public record Effect(
            EffectState state,
            TableTypes.Row row,
            List<Field> fields,
            String spellId,
            Absence absence,
            String protectedKey) {
        public Effect {
            fields = fields == null ? null : List.copyOf(fields);
        }

        public Effect(EffectState state) {
            this(state, null, null, null, null, null);
        }
    }

    public enum NameState {
        EFFECT_NOT_READY,
        SPELL_KEY_OUT_OF_RANGE,
        NOT_FOUND_IN_ADMITTED_SCOPE,
        PROTECTED,
        SOURCE_NOT_READY,
        INVALID_TEXT,
        TEXT_TOO_LARGE,
        EMPTY_TEXT,
        READY
    }

    public record Name(
            NameState state,
            TableTypes.Row row,
            String text,
            Integer textUnits,
            String issueCode,
            Absence absence,
            String protectedKey) {
        public Name(NameState state) {
            this(state, null, null, null, null, null, null);
        }
    }

    public record Entry(
            TableTypes.Row link,
            String parentItemId,
            String itemEffectId,
            Effect effect,
            Name name) {}

    public record Page(
            PageState state,
            long itemId,
            Link link,
            List<Entry> entries,
            String next,
            int matches,
            TableTypes.Entry item,
            Source bridge,
            Source effect,
            Source name,
            String protectedKey,
            String code,
            String message) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    public record Options(int maxRelationIndexEntries) {
        public Options() {
            this(4_000_000);
        }

        public Options {
            if (maxRelationIndexEntries < 1) {
                throw new IllegalArgumentException("Positive relation index bound required");
            }
        }
    }
}
