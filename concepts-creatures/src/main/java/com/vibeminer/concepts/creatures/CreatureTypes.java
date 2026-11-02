package com.vibeminer.concepts.creatures;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.models.BuildModelTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.time.Instant;
import java.util.List;

/** Immutable layer six creature facts, provenance and explicit partial outcomes. */
public final class CreatureTypes {
    private CreatureTypes() {}

    public enum CreaturesPageState {
        OK,
        CREATURE_NOT_READABLE,
        SCHEMA_UNSUPPORTED,
        SOURCE_INVALID,
        STALE,
        INVALID_CURSOR
    }

    public enum CreatureNameState {
        VALID,
        EMPTY,
        INVALID
    }

    public enum CreatureSlotState {
        DECLARED,
        NO_DECLARED_DISPLAY,
        INVALID_DISPLAY_REFERENCE
    }

    public enum CreatureResultState {
        FOUND,
        ABSENT,
        UNKNOWN,
        PROTECTED,
        CREATURE_NOT_READABLE,
        SCHEMA_UNSUPPORTED,
        SOURCE_INVALID
    }

    public enum CreatureDisplayChainState {
        NO_DECLARED_DISPLAY,
        INVALID_DISPLAY_REFERENCE,
        DISPLAY_NOT_READABLE,
        DISPLAY_UNSUPPORTED,
        DISPLAY_ABSENT,
        DISPLAY_UNKNOWN,
        DISPLAY_PROTECTED,
        DISPLAY_INVALID,
        NO_DECLARED_MODEL,
        MODEL_NOT_READABLE,
        MODEL_UNSUPPORTED,
        MODEL_ABSENT,
        MODEL_UNKNOWN,
        MODEL_PROTECTED,
        MODEL_INVALID,
        NO_DECLARED_MODEL_FILE,
        METADATA_RESOLVED
    }

    public enum CreatureFactsState {
        READY,
        ROW_NOT_WHOLE,
        LIMIT_EXCEEDED
    }

    public enum CreatureFactState {
        VALUE,
        UNSUPPORTED
    }

    public enum CreatureModelState {
        READ,
        FILES_NOT_OPEN,
        READ_FAILED
    }

    public enum CreatureTextureSource {
        TXID,
        VARIATION,
        OTHER
    }

    public enum CreatureTextureState {
        RESOLVED,
        UNRESOLVED,
        RESOLVED_ELSEWHERE
    }

    public enum CreatureAppearanceState {
        READY,
        MODEL_NOT_READ,
        GEOMETRY_UNSUPPORTED,
        NO_MAIN_SKIN,
        SKIN_NOT_READ,
        TEXTURE_UNRESOLVED,
        CHARACTER_MODEL,
        BLEND_UNSUPPORTED,
        MATERIAL_UNSUPPORTED,
        TEXTURE_PROTECTED,
        TEXTURE_UNSUPPORTED,
        TEXTURE_LIMIT,
        TEXTURE_CORRUPT,
        TEXTURE_UNAVAILABLE
    }

    public record CreaturesOpenResult(
            BuildRef build,
            String locale,
            FileTypes.FilesOpenState state,
            BuildCreatures creatures,
            String code,
            String message,
            Instant observedAt) {}

    public record CreatureQuery(String query, Long exactId, int limit, String after) {
        public CreatureQuery() {
            this("", null, 50, null);
        }

        public CreatureQuery(String query) {
            this(query, null, 50, null);
        }
    }

    public record CreaturesPage(
            CreaturesPageState state,
            List<CreatureSummary> entries,
            String next,
            long scanned,
            TableTypes.Entry creature,
            TableTypes.RowOutcome exact,
            String protectedKey,
            String code,
            String message) {
        public CreaturesPage {
            entries = entries == null ? null : List.copyOf(entries);
        }
    }

    public record CreatureName(CreatureNameState state, String value, String issue) {}

    public record CreatureSourceValue(String column, int columnIndex, String value) {}

    public record CreatureDisplaySlot(
            int slot, int displayId, float probability, CreatureSlotState state) {}

    public record CreatureSummary(
            long id,
            boolean isCopy,
            Long copySourceId,
            CreatureName name,
            List<CreatureSourceValue> facts,
            List<CreatureDisplaySlot> slots) {
        public CreatureSummary {
            facts = facts == null ? null : List.copyOf(facts);
            slots = slots == null ? null : List.copyOf(slots);
        }
    }

    public record CreatureResult(
            CreatureResultState state,
            CreatureDetail detail,
            TableTypes.Entry creature,
            String protectedKey,
            String code,
            String message) {}

    public record CreatureDetail(
            CreatureSummary summary,
            TableTypes.Row row,
            String classificationLabel,
            List<CreatureDisplayChain> displays) {
        public CreatureDetail {
            displays = displays == null ? null : List.copyOf(displays);
        }
    }

    public record CreatureDisplayChain(
            CreatureDisplaySlot slot,
            CreatureDisplayChainState state,
            TableTypes.Entry display,
            TableTypes.Row displayRow,
            Long modelId,
            TableTypes.Entry modelData,
            TableTypes.Row modelRow,
            Long modelFileDataId,
            String protectedKey,
            String code,
            String message,
            CreatureRecordFacts displayFacts,
            CreatureRecordFacts modelFacts,
            CreatureTextureEvidence textures,
            CreatureModel model) {}

    public record CreatureFactCell(String column, int columnIndex, String value) {}

    public record CreatureFact(String key, CreatureFactState state, List<CreatureFactCell> cells) {
        public CreatureFact {
            cells = cells == null ? null : List.copyOf(cells);
        }
    }

    public record CreatureRecordFacts(
            CreatureFactsState state,
            List<CreatureFact> facts,
            List<CreatureFactCell> raw,
            String code) {
        public CreatureRecordFacts {
            facts = facts == null ? null : List.copyOf(facts);
            raw = raw == null ? null : List.copyOf(raw);
        }
    }

    public record CreatureTextureEvidence(
            int extendedDisplayInfoId, List<Integer> textureVariations) {
        public CreatureTextureEvidence {
            textureVariations = textureVariations == null ? null : List.copyOf(textureVariations);
        }
    }

    public record CreatureTexture(
            int ordinal,
            long type,
            long flags,
            String filename,
            CreatureTextureSource source,
            Integer variation,
            Long fileDataId,
            CreatureTextureState state,
            boolean drawn,
            BuildModelTypes.Reference reference,
            BlpImageTypes.State image,
            FileTypes.FileReadState fileState,
            List<String> holeKeys,
            String code) {
        public CreatureTexture {
            holeKeys = holeKeys == null ? null : List.copyOf(holeKeys);
        }
    }

    public record CreatureAppearance(
            CreatureAppearanceState state,
            String code,
            String message,
            Integer textureOrdinal,
            Integer batchOrdinal,
            Integer blendedBatches) {}

    public record CreatureModel(
            CreatureModelState state,
            long fileDataId,
            BuildModelTypes.ReadState readState,
            CreatureAppearance appearance,
            List<CreatureTexture> textures,
            Long mainSkinFileDataId,
            String code,
            String message,
            Instant observedAt,
            List<String> keyNames,
            BuildModelTypes.ReadState skinState,
            List<String> skinKeyNames) {
        public CreatureModel {
            textures = textures == null ? null : List.copyOf(textures);
            keyNames = keyNames == null ? null : List.copyOf(keyNames);
            skinKeyNames = skinKeyNames == null ? null : List.copyOf(skinKeyNames);
        }
    }
}
