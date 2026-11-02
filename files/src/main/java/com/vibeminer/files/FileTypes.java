package com.vibeminer.files;

import com.vibeminer.builds.BuildTypes.BuildRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Immutable layer four messages; byte payloads are owned. FileDataIDs use unsigned32 in long. */
public final class FileTypes {
    private FileTypes() {}

    public enum FileReadState {
        COMPLETE,
        PARTIAL,
        PROTECTED,
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
        TOO_LARGE
    }

    public enum FileLookupState {
        FOUND,
        UNKNOWN_FILE,
        NOT_IN_LOCALE,
        NOT_IN_ENCODING
    }

    public enum FilePathSource {
        NONE,
        LISTFILE,
        ROOT_HASH
    }

    public enum FileStorage {
        STORED,
        NOT_STORED,
        NO_ENCODED_COPY
    }

    public enum FileFormat {
        UNKNOWN,
        DB2,
        BLP,
        M2,
        M2_CHUNKED,
        M2_LEGACY,
        SKIN,
        ANIM,
        BONE,
        PHYS,
        SKEL,
        TEX,
        WMO_ROOT,
        WMO_GROUP,
        ADT_ROOT,
        ADT_TEX,
        ADT_OBJ,
        ADT_LOD,
        WDT,
        WDT_SPLIT,
        WDL,
        BLS,
        WWF,
        OGG,
        MP3,
        WAV,
        TTF,
        OTF,
        TTC,
        PNG,
        JPEG,
        LUA,
        XML,
        TOC,
        TXT,
        HTML
    }

    public enum FileFormatSource {
        NONE,
        MAGIC,
        EXTENSION
    }

    public enum FilesOpenState {
        OPEN,
        NEEDS_NETWORK,
        UNAVAILABLE,
        MISSING,
        ENCRYPTED,
        CORRUPT,
        UNSUPPORTED,
        STORE_FAILED,
        LOCALE_NOT_IN_BUILD
    }

    public record FileRef(Long fileDataId, String path) {
        public FileRef {
            if ((fileDataId == null) == (path == null)
                    || fileDataId != null && (fileDataId < 0 || fileDataId > 0xffffffffL)
                    || path != null && path.isBlank())
                throw new IllegalArgumentException("Invalid file reference");
        }

        public static FileRef id(long id) {
            return new FileRef(id, null);
        }

        public static FileRef forPath(String path) {
            return new FileRef(null, path);
        }
    }

    public record FileHole(long offset, long length, String keyName) {}

    public record FileSegment(long offset, byte[] bytes) {
        public FileSegment {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record FileRead(
            FileReadState state,
            long offset,
            long length,
            List<FileSegment> segments,
            List<FileHole> holes,
            String code,
            String message,
            Instant observedAt) {
        public FileRead {
            segments = List.copyOf(segments);
            holes = List.copyOf(holes);
        }

        public byte[] bytes() {
            return state != FileReadState.COMPLETE
                    ? null
                    : segments.isEmpty() ? new byte[0] : segments.getFirst().bytes();
        }

        public static FileRead failed(
                FileReadState state, long offset, String code, String message, Instant observedAt) {
            if (state == FileReadState.COMPLETE
                    || state == FileReadState.PARTIAL
                    || state == FileReadState.PROTECTED)
                throw new IllegalArgumentException("Failure carries no data");
            return new FileRead(state, offset, 0, List.of(), List.of(), code, message, observedAt);
        }
    }

    public record FileOpen(
            FileReadState state,
            FileReadStream stream,
            String code,
            String message,
            Instant observedAt) {}

    public record BuildFile(
            long fileDataId,
            String path,
            FilePathSource pathSource,
            Long size,
            String contentKey,
            FileFormat format,
            FileFormatSource formatSource,
            FileStorage storage,
            boolean encrypted) {}

    public record FileStat(FileLookupState state, BuildFile file) {}

    public record FileFilter(
            long fromId,
            long toId,
            String pathPrefix,
            Set<FileFormat> formats,
            Set<FileStorage> storage,
            Boolean encrypted) {
        public static final FileFilter ALL = new FileFilter(0, 0xffffffffL, null, null, null, null);

        public FileFilter {
            if (fromId < 0 || toId > 0xffffffffL)
                throw new IllegalArgumentException("Invalid id range");
            formats = formats == null ? null : Set.copyOf(formats);
            storage = storage == null ? null : Set.copyOf(storage);
        }
    }

    public record FilesOpenResult(
            BuildRef build,
            String locale,
            FilesOpenState state,
            BuildFiles files,
            String code,
            String message,
            Instant observedAt) {}

    public enum EnsureSelectionKind {
        ONE,
        MANY,
        MATCHING
    }

    public record EnsureSelection(
            EnsureSelectionKind kind, List<FileRef> files, FileFilter filter) {
        public EnsureSelection {
            if (kind == null) throw new NullPointerException("kind");
            if (kind == EnsureSelectionKind.MATCHING) {
                if (filter == null) throw new NullPointerException("filter");
                files = null;
            } else {
                files = List.copyOf(files);
                if (kind == EnsureSelectionKind.ONE && files.size() != 1)
                    throw new IllegalArgumentException("One requires one file");
                filter = null;
            }
        }

        public static EnsureSelection one(FileRef file) {
            return new EnsureSelection(EnsureSelectionKind.ONE, List.of(file), null);
        }

        public static EnsureSelection many(List<FileRef> files) {
            return new EnsureSelection(EnsureSelectionKind.MANY, files, null);
        }

        public static EnsureSelection matching(FileFilter filter) {
            return new EnsureSelection(EnsureSelectionKind.MATCHING, null, filter);
        }
    }

    public enum EnsurePriority {
        INTERACTIVE,
        BACKGROUND
    }

    public enum FileEnsureState {
        STORED,
        ALREADY_STORED,
        UNKNOWN_FILE,
        NOT_IN_LOCALE,
        NOT_IN_ENCODING,
        MISSING,
        NEEDS_NETWORK,
        UNAVAILABLE,
        REFUSED,
        UNSUPPORTED,
        STORE_FAILED,
        CANCELED
    }

    public enum EnsureOutcome {
        COMPLETED,
        INCOMPLETE,
        CANCELED
    }

    public record FileEnsured(FileRef file, Long fileDataId, FileEnsureState state, String code) {}

    public record EnsureProgress(
            long filesDone,
            long filesTotal,
            long bytesDone,
            long bytesTotal,
            long cdnBytes,
            boolean listed) {}

    public record EnsureResult(
            EnsureOutcome outcome, EnsureProgress progress, List<FileEnsured> files) {
        public EnsureResult {
            files = List.copyOf(files);
        }
    }
}
