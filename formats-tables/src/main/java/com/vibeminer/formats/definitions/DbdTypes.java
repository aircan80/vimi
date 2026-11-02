package com.vibeminer.formats.definitions;

import com.vibeminer.formats.Db2FormatException;
import java.util.List;

/** Immutable bounded DBD syntax values. Unsigned32 components are represented by long. */
public final class DbdTypes {
    private DbdTypes() {}

    public record Limits(
            int maxBytes,
            int maxLines,
            int maxLineCharacters,
            int maxColumns,
            int maxBlocks,
            int maxFieldsPerBlock,
            int maxSelectors,
            int maxArrayLength,
            int maxCommentCharacters,
            int maxLayouts,
            long maxSelectionBytes) {
        public Limits() {
            this(1 << 20, 16384, 16384, 512, 4096, 256, 100000, 4096, 2000, 8192, 4L << 20);
        }

        public Limits {
            if (maxBytes < 1
                    || maxLines < 1
                    || maxLineCharacters < 1
                    || maxColumns < 1
                    || maxBlocks < 1
                    || maxFieldsPerBlock < 1
                    || maxSelectors < 1
                    || maxArrayLength < 1
                    || maxCommentCharacters < 1
                    || maxLayouts < 1
                    || maxSelectionBytes < 1) {
                throw new IllegalArgumentException("Positive DBD limits required");
            }
        }
    }

    public record Reference(String table, String column) {}

    public record Column(
            String name,
            String family,
            boolean verifiedName,
            String comment,
            Reference reference) {}

    public record Field(
            Column column,
            Integer integerBits,
            Boolean unsigned,
            int arrayLength,
            boolean isId,
            boolean nonInline,
            boolean relationStorage,
            String comment) {}

    public record BuildVersion(long major, long minor, long patch, long build)
            implements Comparable<BuildVersion> {
        public BuildVersion {
            for (long value : new long[] {major, minor, patch, build}) {
                if (value < 0 || value > 0xffffffffL) {
                    throw new IllegalArgumentException("unsigned32 component required");
                }
            }
        }

        public static BuildVersion parse(String value) {
            if (value == null
                    || value.length() < 7
                    || value.length() > 43
                    || !value.matches(
                            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) {
                throw new Db2FormatException(
                        "DBD_INVALID", "Exact four-component numeric build required");
            }
            try {
                var p = value.split("\\.");
                return new BuildVersion(
                        Long.parseLong(p[0]),
                        Long.parseLong(p[1]),
                        Long.parseLong(p[2]),
                        Long.parseLong(p[3]));
            } catch (IllegalArgumentException e) {
                throw new Db2FormatException("DBD_INVALID", "Build component exceeds unsigned32");
            }
        }

        @Override
        public int compareTo(BuildVersion other) {
            int c = Long.compare(major, other.major);
            if (c != 0) {
                return c;
            }
            c = Long.compare(minor, other.minor);
            if (c != 0) {
                return c;
            }
            c = Long.compare(patch, other.patch);
            return c != 0 ? c : Long.compare(build, other.build);
        }
    }

    public record BuildRange(BuildVersion first, BuildVersion last) {
        public boolean contains(BuildVersion value) {
            return first.compareTo(value) <= 0 && last.compareTo(value) >= 0;
        }
    }

    public record Block(
            List<String> layoutHashes,
            List<BuildRange> builds,
            List<Field> fields,
            String comment) {
        public Block {
            layoutHashes = List.copyOf(layoutHashes);
            builds = List.copyOf(builds);
            fields = List.copyOf(fields);
        }
    }

    public record Selection(String status, List<Block> blocks, String match) {
        public Selection {
            blocks = List.copyOf(blocks);
        }
    }

    public record LayoutCandidate(String layoutHash, Block block) {}
}
