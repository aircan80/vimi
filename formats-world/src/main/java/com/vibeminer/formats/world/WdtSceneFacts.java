package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Raw WDT18 scene declarations. No file resolution, source authority or placement transform. */
public final class WdtSceneFacts {
    public static final int MAX_BYTES = 1 << 20, MAX_CHUNKS = 256, MAX_NAME_BYTES = 64 << 10;
    public static final int MAX_GLOBAL_RECORDS = 16;

    public enum Mode {
        TERRAIN_ONLY,
        GLOBAL_WMO_ONLY,
        MIXED,
        UNRESOLVED
    }

    public enum ReferenceKind {
        FILE_DATA_ID,
        UNRESOLVED_UNSIGNED32
    }

    public record GlobalObject(
            int ordinal,
            ReferenceKind referenceKind,
            long reference,
            long uniqueId,
            WorldPlacements.Vec3 position,
            WorldPlacements.Vec3 rotation,
            WorldPlacements.Vec3 lowerBounds,
            WorldPlacements.Vec3 upperBounds,
            int flags,
            int doodadSet,
            int nameSet,
            int scale) {
        public GlobalObject {
            Objects.requireNonNull(referenceKind);
            Objects.requireNonNull(position);
            Objects.requireNonNull(rotation);
            Objects.requireNonNull(lowerBounds);
            Objects.requireNonNull(upperBounds);
            if (ordinal < 0
                    || ordinal >= MAX_GLOBAL_RECORDS
                    || reference < 0
                    || reference > 0xffff_ffffL
                    || uniqueId < 0
                    || uniqueId > 0xffff_ffffL
                    || (flags | doodadSet | nameSet | scale) < 0
                    || flags > 65535
                    || doodadSet > 65535
                    || nameSet > 65535
                    || scale > 65535
                    || referenceKind
                            != ((flags & 8) != 0
                                    ? ReferenceKind.FILE_DATA_ID
                                    : ReferenceKind.UNRESOLVED_UNSIGNED32)) {
                throw invalid("Invalid raw global declaration");
            }
        }
    }

    /** Descriptor records presence, not understanding or an absence of render-affecting data. */
    public record Unparsed(int tag, int bytes) {
        public Unparsed {
            if (bytes < 0 || bytes > MAX_BYTES) {
                throw invalid("Invalid unparsed span");
            }
        }
    }

    public static final class Facts {
        private final long flags;
        private final boolean mainPresent;
        private final int terrainTiles;
        private final List<GlobalObject> objects;
        private final byte[] names;
        private final boolean namesPresent, modfPresent;
        private final List<Unparsed> unparsed;

        private Facts(
                long flags,
                boolean mainPresent,
                int terrainTiles,
                List<GlobalObject> objects,
                byte[] names,
                boolean namesPresent,
                boolean modfPresent,
                List<Unparsed> unparsed) {
            this.flags = flags;
            this.mainPresent = mainPresent;
            this.terrainTiles = terrainTiles;
            this.objects = List.copyOf(objects);
            this.names = names;
            this.namesPresent = namesPresent;
            this.modfPresent = modfPresent;
            this.unparsed = List.copyOf(unparsed);
        }

        public long flags() {
            return flags;
        }

        public boolean mainPresent() {
            return mainPresent;
        }

        public int terrainTiles() {
            return terrainTiles;
        }

        public List<GlobalObject> globalObjects() {
            return objects;
        }

        public boolean nameBlockPresent() {
            return namesPresent;
        }

        public boolean globalBlockPresent() {
            return modfPresent;
        }

        public ByteBuffer nameBlock() {
            return ByteBuffer.wrap(names).asReadOnlyBuffer();
        }

        public List<Unparsed> unparsed() {
            return unparsed;
        }

        /**
         * Structural declaration only: does not assert readable geometry or a supported profile.
         */
        public Mode mode() {
            if (!mainPresent
                    || (flags & 1) == 0 && !objects.isEmpty()
                    || (flags & 1) != 0 && objects.size() != 1) {
                return Mode.UNRESOLVED;
            }
            if ((flags & 1) != 0) {
                return terrainTiles == 0 ? Mode.GLOBAL_WMO_ONLY : Mode.MIXED;
            }
            return terrainTiles == 0 ? Mode.UNRESOLVED : Mode.TERRAIN_ONLY;
        }
    }

    private WdtSceneFacts() {}

    public static Facts read(byte[] bytes, BooleanSupplier canceled) {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (bytes.length < 12 || bytes.length > MAX_BYTES) {
            throw invalid("WDT source size");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean version = false, mphd = false, main = false, mwmo = false, modf = false;
        long flags = 0;
        int terrainTiles = 0, chunks = 0;
        byte[] names = new byte[0];
        var objects = new ArrayList<GlobalObject>();
        var unparsed = new ArrayList<Unparsed>();
        while (input.hasRemaining()) {
            check(canceled);
            if (++chunks > MAX_CHUNKS || input.remaining() < 8) {
                throw invalid("WDT chunk header/count");
            }
            int tag = input.getInt(), size = input.getInt();
            if (size < 0 || size > input.remaining()) {
                throw invalid("WDT chunk span");
            }
            int end = input.position() + size;
            switch (tag) {
                case 0x4d564552 -> {
                    if (version || size != 4 || input.getInt() != 18) {
                        throw invalid("WDT version");
                    }
                    version = true;
                }
                case 0x4d504844 -> {
                    if (mphd || size < 4 || size > 128 || size % 4 != 0) {
                        throw invalid("WDT MPHD");
                    }
                    mphd = true;
                    flags = u32(input);
                    if (size > 4) {
                        unparsed.add(new Unparsed(tag, size - 4));
                    }
                }
                case 0x4d41494e -> {
                    if (main || size != 4096 * 8) {
                        throw invalid("WDT MAIN");
                    }
                    main = true;
                    for (int i = 0; i < 4096; i++) {
                        check(canceled);
                        if ((input.getInt() & 1) != 0) {
                            terrainTiles++;
                        }
                        input.getInt();
                    }
                }
                case 0x4d574d4f -> {
                    if (mwmo || size > MAX_NAME_BYTES) {
                        throw invalid("WDT MWMO");
                    }
                    mwmo = true;
                    names = new byte[size];
                    input.get(names);
                }
                case 0x4d4f4446 -> {
                    if (modf || size % 64 != 0 || size / 64 > MAX_GLOBAL_RECORDS) {
                        throw invalid("WDT MODF");
                    }
                    modf = true;
                    for (int i = 0; i < size / 64; i++) {
                        check(canceled);
                        long reference = u32(input), uid = u32(input);
                        var position = vector(input);
                        var rotation = vector(input);
                        var lower = vector(input);
                        var upper = vector(input);
                        int objectFlags = u16(input),
                                doodadSet = u16(input),
                                nameSet = u16(input),
                                scale = u16(input);
                        objects.add(
                                new GlobalObject(
                                        i,
                                        (objectFlags & 8) != 0
                                                ? ReferenceKind.FILE_DATA_ID
                                                : ReferenceKind.UNRESOLVED_UNSIGNED32,
                                        reference,
                                        uid,
                                        position,
                                        rotation,
                                        lower,
                                        upper,
                                        objectFlags,
                                        doodadSet,
                                        nameSet,
                                        scale));
                    }
                }
                default -> unparsed.add(new Unparsed(tag, size));
            }
            input.position(end);
        }
        if (!version || !mphd) {
            throw invalid("WDT requires version and flags");
        }
        check(canceled);
        return new Facts(flags, main, terrainTiles, objects, names, mwmo, modf, unparsed);
    }

    private static WorldPlacements.Vec3 vector(ByteBuffer input) {
        return new WorldPlacements.Vec3(input.getFloat(), input.getFloat(), input.getFloat());
    }

    private static long u32(ByteBuffer input) {
        return Integer.toUnsignedLong(input.getInt());
    }

    private static int u16(ByteBuffer input) {
        return Short.toUnsignedInt(input.getShort());
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
