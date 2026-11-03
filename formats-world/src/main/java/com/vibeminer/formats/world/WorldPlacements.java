package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Raw ADT18 object declarations only; no resolution, placement matrix or appearance policy. */
public final class WorldPlacements {
    public static final int MAX_SOURCE_BYTES = 8 << 20;
    public static final int MAX_RECORDS_PER_KIND = 4096;
    public static final int MAX_CHUNKS = 4096;
    public static final String REVISION = "adt18-obj0-placement-facts-v1";

    private WorldPlacements() {}

    public enum ReferenceKind {
        FILE_DATA_ID,
        NAME_TABLE_INDEX
    }

    /** Value is unsigned; a raw zero direct ID does not assert a readable file. */
    public record Reference(ReferenceKind kind, long value) {
        public Reference {
            Objects.requireNonNull(kind);
            unsigned32(value);
        }
    }

    /** Source axes/units are deliberately unchanged, including Euler angles in rotation fields. */
    public record Vec3(float x, float y, float z) {
        public Vec3 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw invalid("Nonfinite placement vector");
            }
        }
    }

    public record Doodad(
            int ordinal,
            Reference reference,
            long uniqueId,
            Vec3 position,
            Vec3 rotation,
            int scale,
            int flags) {
        public Doodad {
            common(ordinal, reference, uniqueId, position, rotation, scale, flags);
        }
    }

    public record WorldObject(
            int ordinal,
            Reference reference,
            long uniqueId,
            Vec3 position,
            Vec3 rotation,
            Vec3 lowerBounds,
            Vec3 upperBounds,
            int flags,
            int doodadSet,
            int nameSet,
            int scale) {
        public WorldObject {
            common(ordinal, reference, uniqueId, position, rotation, scale, flags);
            Objects.requireNonNull(lowerBounds);
            Objects.requireNonNull(upperBounds);
            unsigned16(doodadSet);
            unsigned16(nameSet);
        }
    }

    public record Metadata(List<Doodad> doodads, List<WorldObject> worldObjects) {
        public Metadata {
            Objects.requireNonNull(doodads);
            Objects.requireNonNull(worldObjects);
            if (doodads.size() > MAX_RECORDS_PER_KIND
                    || worldObjects.size() > MAX_RECORDS_PER_KIND) {
                throw invalid("Placement record count exceeds bounds");
            }
            doodads = List.copyOf(doodads);
            worldObjects = List.copyOf(worldObjects);
            for (int i = 0; i < doodads.size(); i++) {
                if (doodads.get(i).ordinal() != i) {
                    throw invalid("Doodad ordinal differs from source order");
                }
            }
            for (int i = 0; i < worldObjects.size(); i++) {
                if (worldObjects.get(i).ordinal() != i) {
                    throw invalid("World object ordinal differs from source order");
                }
            }
        }
    }

    /** Caller authenticates/binds the source and owns bytes; parsing retains no input storage. */
    public static Metadata readObj0(byte[] bytes, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > MAX_SOURCE_BYTES) {
            throw invalid("Object source size outside bounds");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean version = false, mddf = false, modf = false;
        var doodads = new ArrayList<Doodad>();
        var worldObjects = new ArrayList<WorldObject>();
        int chunks = 0;
        while (input.hasRemaining()) {
            check(canceled);
            if (++chunks > MAX_CHUNKS || input.remaining() < 8) {
                throw invalid("Object chunk header/count outside bounds");
            }
            int tag = input.getInt(), size = input.getInt();
            if (size < 0 || size > input.remaining()) {
                throw invalid("Object chunk extends outside source");
            }
            int end = input.position() + size;
            switch (tag) {
                case 0x4d564552 -> {
                    if (version || size != 4 || input.getInt() != 18) {
                        throw invalid("Object source requires exactly one version 18 declaration");
                    }
                    version = true;
                }
                case 0x4d444446 -> {
                    if (mddf || size % 36 != 0 || size / 36 > MAX_RECORDS_PER_KIND) {
                        throw invalid("MDDF size, count or duplicate declaration");
                    }
                    mddf = true;
                    for (int i = 0; i < size / 36; i++) {
                        check(canceled);
                        long id = u32(input), uniqueId = u32(input);
                        var position = vector(input);
                        var rotation = vector(input);
                        int scale = u16(input), flags = u16(input);
                        doodads.add(
                                new Doodad(
                                        i,
                                        reference(id, flags, 0x40),
                                        uniqueId,
                                        position,
                                        rotation,
                                        scale,
                                        flags));
                    }
                }
                case 0x4d4f4446 -> {
                    if (modf || size % 64 != 0 || size / 64 > MAX_RECORDS_PER_KIND) {
                        throw invalid("MODF size, count or duplicate declaration");
                    }
                    modf = true;
                    for (int i = 0; i < size / 64; i++) {
                        check(canceled);
                        long id = u32(input), uniqueId = u32(input);
                        var position = vector(input);
                        var rotation = vector(input);
                        var lower = vector(input);
                        var upper = vector(input);
                        int flags = u16(input),
                                doodadSet = u16(input),
                                nameSet = u16(input),
                                scale = u16(input);
                        worldObjects.add(
                                new WorldObject(
                                        i,
                                        reference(id, flags, 0x8),
                                        uniqueId,
                                        position,
                                        rotation,
                                        lower,
                                        upper,
                                        flags,
                                        doodadSet,
                                        nameSet,
                                        scale));
                    }
                }
                default -> {
                    // Other chunks (including legacy name blocks) do not establish reference kind.
                }
            }
            input.position(end);
        }
        if (!version) {
            throw invalid("Object source has no version 18 declaration");
        }
        check(canceled);
        return new Metadata(doodads, worldObjects);
    }

    private static Reference reference(long id, int flags, int directFlag) {
        return new Reference(
                (flags & directFlag) != 0
                        ? ReferenceKind.FILE_DATA_ID
                        : ReferenceKind.NAME_TABLE_INDEX,
                id);
    }

    private static void common(
            int ordinal,
            Reference reference,
            long uniqueId,
            Vec3 position,
            Vec3 rotation,
            int scale,
            int flags) {
        if (ordinal < 0 || ordinal >= MAX_RECORDS_PER_KIND) {
            throw invalid("Invalid source ordinal");
        }
        Objects.requireNonNull(reference);
        Objects.requireNonNull(position);
        Objects.requireNonNull(rotation);
        unsigned32(uniqueId);
        unsigned16(scale);
        unsigned16(flags);
    }

    private static Vec3 vector(ByteBuffer input) {
        return new Vec3(input.getFloat(), input.getFloat(), input.getFloat());
    }

    private static long u32(ByteBuffer input) {
        return Integer.toUnsignedLong(input.getInt());
    }

    private static int u16(ByteBuffer input) {
        return Short.toUnsignedInt(input.getShort());
    }

    private static void unsigned32(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw invalid("Value is not an unsigned 32-bit source field");
        }
    }

    private static void unsigned16(int value) {
        if (value < 0 || value > 0xffff) {
            throw invalid("Value is not an unsigned 16-bit source field");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Object metadata request canceled");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
