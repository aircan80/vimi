package com.vibeminer.store;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Immutable messages shared by the store interfaces; no UI or database implementation types. */
public final class StoreTypes {
    private StoreTypes() {}

    public enum Kind {
        CDN,
        GENERATED,
        LIBRARY
    }

    public enum Origin {
        UNKNOWN,
        CDN,
        SHARED_STORE
    }

    public enum ReadState {
        PRESENT,
        ABSENT,
        CORRUPT,
        LOST
    }

    public enum PutOutcome {
        STORED,
        ALREADY_PRESENT,
        REFUSED,
        CANCELED
    }

    public enum DeleteOutcome {
        DELETED,
        ABSENT,
        REFUSED
    }

    public record Refusal(String code, String message) {}

    public record PutObject(ObjectKey key, long declaredLength, byte[] bytes, Origin origin) {
        public PutObject {
            bytes = bytes.clone();
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(origin);
        }

        public PutObject(ObjectKey key, byte[] bytes) {
            this(key, bytes.length, bytes, Origin.CDN);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        byte[] data() {
            return bytes;
        }
    }

    public record PutResult(ObjectKey key, PutOutcome outcome, String code) {}

    public record DeleteResult(ObjectKey key, DeleteOutcome outcome, String code) {}

    public record ObjectInfo(ObjectKey key, long length, Origin origin, Instant storedAt) {}

    public record ObjectRead(ReadState state, ObjectInfo info, byte[] bytes, String problem) {
        public ObjectRead {
            if (bytes != null) {
                bytes = bytes.clone();
            }
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    public record OpenReport(
            long recoveredEntries,
            long truncatedBytes,
            boolean indexRebuilt,
            boolean fullIndexCheck,
            List<String> problems) {
        public OpenReport {
            problems = List.copyOf(problems);
        }
    }

    public record ScanReport(
            long packs,
            long entries,
            long live,
            long duplicates,
            long tombstones,
            long corrupt,
            long unverifiable,
            List<String> problems) {
        public ScanReport {
            problems = List.copyOf(problems);
        }
    }

    public record VerifyReport(
            long checked,
            long bytes,
            long corruptCount,
            long lostCount,
            List<ObjectKey> corrupt,
            List<ObjectKey> lost) {
        public VerifyReport {
            corrupt = List.copyOf(corrupt);
            lost = List.copyOf(lost);
        }
    }

    public record CompactReport(
            int packsCompacted,
            long bytesBefore,
            long bytesAfter,
            long moved,
            long skippedCorrupt) {}

    public record ScrubReport(String indexProblem, boolean indexRebuilt, VerifyReport verify) {}

    public record Stats(
            long packs,
            long objects,
            long historyRecords,
            long packBytes,
            long liveBytes,
            Instant createdAt) {}

    /** packHandles bounds retained read channels; existing constructors default to 64. */
    public record Options(Kind kind, long maxPackBytes, Clock clock, int packHandles) {
        public Options {
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(clock);
            if (packHandles < 1) {
                throw new IllegalArgumentException("Pack handle capacity must be positive");
            }
            if (maxPackBytes < 64) {
                throw new IllegalArgumentException("Pack limit must be at least 64 bytes");
            }
        }

        public Options(Kind kind, long maxPackBytes, Clock clock) {
            this(kind, maxPackBytes, clock, 64);
        }

        public Options() {
            this(Kind.CDN, 2L << 30, Clock.systemUTC(), 64);
        }
    }
}
