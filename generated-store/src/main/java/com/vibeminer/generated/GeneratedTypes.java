package com.vibeminer.generated;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Messages at the generated-store boundary contain no object-store implementation types. */
public final class GeneratedTypes {
    public static final int MAX_OBJECT_BYTES = 64 << 20, MAX_BATCH = 4096;
    public static final String DISK_FULL = "GENERATED_STORE_DISK_FULL",
            WRITE_FAILED = "GENERATED_STORE_WRITE_FAILED",
            CLOSED = "GENERATED_STORE_CLOSED",
            LIMIT = "GENERATED_STORE_LIMIT",
            CORRUPT = "GENERATED_CORRUPT",
            BUSY = "GENERATED_STORE_BUSY",
            UNUSABLE = "GENERATED_STORE_UNUSABLE",
            READ_ONLY = "GENERATED_STORE_READ_ONLY";

    private GeneratedTypes() {}

    public enum ReadState {
        PRESENT,
        ABSENT,
        CORRUPT
    }

    public enum PutOutcome {
        STORED,
        ALREADY_PRESENT
    }

    public record Put(GeneratedKey key, byte[] bytes) {
        public Put {
            Objects.requireNonNull(key);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        byte[] data() {
            return bytes;
        }
    }

    /** length is the whole object's length, including for a range read. */
    public record Read(ReadState state, long length, byte[] bytes, String problem) {
        public Read {
            if (bytes != null) {
                bytes = bytes.clone();
            }
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    public record Options(long maxPackBytes, Clock clock, int packHandles) {
        public Options {
            Objects.requireNonNull(clock);
            if (maxPackBytes < 64 || packHandles < 1) {
                throw new IllegalArgumentException("Invalid pack bounds");
            }
        }

        public Options() {
            this(256L << 20, Clock.systemUTC(), 64);
        }
    }

    public record Stats(
            long packs, long objects, long packBytes, long liveBytes, Instant createdAt) {}

    public record CompactReport(
            int packsCompacted,
            long bytesBefore,
            long bytesAfter,
            long moved,
            long skippedCorrupt) {}
}
