package com.vibeminer.formats.models;

import com.vibeminer.files.FileTypes.BuildFile;
import com.vibeminer.formats.models.M2Types.Model;
import java.time.Instant;
import java.util.List;

public final class BuildModelTypes {
    private BuildModelTypes() {}

    public enum ReadState {
        READ,
        UNKNOWN_FILE,
        NOT_IN_LOCALE,
        NOT_IN_ENCODING,
        MISSING,
        NEEDS_NETWORK,
        UNAVAILABLE,
        CORRUPT,
        UNSUPPORTED,
        WRONG_KEY,
        STORE_FAILED,
        TOO_LARGE,
        PROTECTED,
        PARTIAL,
        REJECTED,
        NOT_DECLARED
    }

    public enum ReferenceKind {
        SKIN,
        LOD_SKIN,
        TEXTURE,
        SKELETON,
        BONE_FILE,
        ANIMATION_FILE,
        PHYSICS_FILE,
        RECURSIVE_PARTICLE_MODEL,
        GEOMETRY_PARTICLE_MODEL
    }

    public enum ReferenceResolution {
        NONE,
        FILE_DATA_ID,
        ROOT_NAME,
        LISTFILE_NAME
    }

    public enum ReferenceState {
        FOUND,
        NOT_IN_ENCODING,
        UNKNOWN_FILE,
        NOT_IN_LOCALE,
        NO_EXTERNAL_FILE,
        NO_FILE_DECLARED,
        RESOLVED_ELSEWHERE,
        NOT_DECLARED,
        UNRESOLVED_BY_NAME,
        CHECK_FAILED
    }

    public record Reference(
            ReferenceKind kind,
            int ordinal,
            Long fileDataId,
            String name,
            ReferenceResolution resolution,
            ReferenceState state,
            BuildFile file,
            String message,
            Long textureType,
            Integer animationId,
            Integer variation) {}

    public record ModelRead(
            ReadState state,
            Long fileDataId,
            BuildFile file,
            Model model,
            List<Reference> references,
            String code,
            String message,
            Instant observedAt,
            List<String> keyNames,
            List<String> chunkKeys) {
        public ModelRead {
            references = List.copyOf(references);
            keyNames = keyNames == null ? List.of() : List.copyOf(keyNames);
            chunkKeys = chunkKeys == null ? List.of() : List.copyOf(chunkKeys);
        }

        public int mainSkinCount() {
            return model == null ? 0 : (int) model.viewCount();
        }

        public int lodSkinCount() {
            return model == null ? 0 : model.lodSkinFileDataIds().size();
        }
    }

    public record SkinRead<T>(
            ReadState state,
            int profile,
            Long fileDataId,
            BuildFile file,
            T skin,
            String code,
            String message,
            Instant observedAt,
            List<String> keyNames,
            List<String> chunkKeys) {
        public SkinRead {
            keyNames = keyNames == null ? List.of() : List.copyOf(keyNames);
            chunkKeys = chunkKeys == null ? List.of() : List.copyOf(chunkKeys);
        }
    }

    /** One BFID-declared bone file of a read model; {@code bones} is non-null only when READ. */
    public record BoneFileRead(
            ReadState state, long fileDataId, M2BoneFile.Bones bones, String code, String message) {
        public BoneFileRead {
            if (state == null || (state == ReadState.READ) != (bones != null)) {
                throw new IllegalArgumentException("Inconsistent bone file read");
            }
        }
    }

    /** Model-resident Stand facts of a read model; {@code stand} is non-null only when READ. */
    public record StandRead(
            ReadState state, M2IdleAnimation.StandFacts stand, String code, String message) {
        public StandRead {
            if (state == null || (state == ReadState.READ) != (stand != null)) {
                throw new IllegalArgumentException("Inconsistent Stand read");
            }
        }
    }
}
