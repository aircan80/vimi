package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.DbdTypes;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTableContent;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.formats.generated.TactKeyStatus;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Authored WDC5, pinned DBD, real native decoder/certificate/capture; no fabricated receipts. */
public final class AuthoredNativeLiquids implements AutoCloseable {
    public static final String TYPE_KEY = "1".repeat(32), MATERIAL_KEY = "2".repeat(32);
    private final BuildTables tables;
    private final Map<Long, byte[]> raw = new java.util.HashMap<>();

    public AuthoredNativeLiquids(BuildFiles borrowed) {
        this(borrowed, 130, true);
    }

    public AuthoredNativeLiquids(BuildFiles borrowed, int materialId, boolean includeCopy) {
        this(borrowed, materialId, includeCopy, false);
    }

    /**
     * {@code oceanRow} adds a LiquidType 2 row with the same material as the others, so it resolves
     * a height-and-depth material instead of the depth-only convention.
     */
    public AuthoredNativeLiquids(
            BuildFiles borrowed, int materialId, boolean includeCopy, boolean oceanRow) {
        this(borrowed, materialId, includeCopy, oceanRow, 0);
    }

    /** As above, with every LiquidType row's MinimapStaticCol set to {@code minimapColour}. */
    public AuthoredNativeLiquids(
            BuildFiles borrowed,
            int materialId,
            boolean includeCopy,
            boolean oceanRow,
            int minimapColour) {
        if (materialId < 0 || materialId > 255) {
            throw new IllegalArgumentException("Authored material ID");
        }
        var bundled = BundledDefinitions.open(Cancellation.NONE).join();
        var generated = new GeneratedTables(new MemoryGeneratedStore());
        var base = new Db2Types.Limits();
        var limits =
                new Db2Types.Limits(
                        base.maxDecodedBytes(),
                        base.maxRows(),
                        base.maxFields(),
                        base.maxSections(),
                        base.maxRecordBytes(),
                        base.maxAuxiliaryBytes(),
                        base.maxStringBytes(),
                        base.maxIndexBytes(),
                        base.maxColumns(),
                        base.maxCopyDepth(),
                        true,
                        true,
                        base.maxRawTextEvidenceBytes(),
                        base.maxRelationshipBytes(),
                        base.maxRelationshipEntries(),
                        base.definitionSha256(),
                        base.verifiedRawIntegerRules());
        for (String name : List.of("LiquidType", "LiquidMaterial")) {
            boolean type = name.equals("LiquidType");
            String hash = type ? "6613bed3" : "8c379329", layout = type ? "D1ECEEC9" : "98E5D7AA";
            var definition = bundled.require(hash);
            var block =
                    definition
                            .document()
                            .selectFile("1.60.1.70755", layout, Cancellation.NONE)
                            .blocks()
                            .getFirst();
            byte[] bytes =
                    encode(
                            block.fields(),
                            type,
                            Long.parseUnsignedLong(hash, 16),
                            Long.parseUnsignedLong(layout, 16),
                            materialId,
                            includeCopy,
                            oceanRow,
                            minimapColour);
            raw.put(type ? 1371380L : 1132538L, bytes);
            var decoded =
                    Db2Reader.open(
                                    Db2ByteSource.memory(bytes),
                                    definition.document(),
                                    "1.60.1.70755",
                                    limits,
                                    Cancellation.NONE)
                            .join();
            var source =
                    GeneratedTableSource.forNativeBlock(
                            block,
                            borrowed.locale(),
                            type ? TYPE_KEY : MATERIAL_KEY,
                            limits,
                            borrowed.build().product()
                                    + " "
                                    + borrowed.build().buildConfig()
                                    + " "
                                    + borrowed.build().cdnConfig());
            generated
                    .store(
                            source,
                            TactKeyStatus.NONE,
                            GeneratedTableContent.nativeDb2(
                                    source, decoded, List.of(), () -> {}, List.of(), List.of()),
                            Cancellation.NONE)
                    .join();
        }
        var files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" -> borrowed.build();
                                            case "locale" -> borrowed.locale();
                                            case "listContentInventory" ->
                                                    new AsyncCursor<FileTypes.ContentFile>() {
                                                        boolean done;

                                                        public CompletableFuture<
                                                                        List<FileTypes.ContentFile>>
                                                                next(
                                                                        int limit,
                                                                        Cancellation token) {
                                                            token.check();
                                                            if (done) {
                                                                return CompletableFuture
                                                                        .completedFuture(List.of());
                                                            }
                                                            done = true;
                                                            return CompletableFuture
                                                                    .completedFuture(
                                                                            List.of(
                                                                                            new FileTypes
                                                                                                    .ContentFile(
                                                                                                    1371380,
                                                                                                    null,
                                                                                                    TYPE_KEY),
                                                                                            new FileTypes
                                                                                                    .ContentFile(
                                                                                                    1132538,
                                                                                                    null,
                                                                                                    MATERIAL_KEY))
                                                                                    .stream()
                                                                                    .filter(
                                                                                            file ->
                                                                                                    file
                                                                                                                            .fileDataId()
                                                                                                                    >= ((FileTypes
                                                                                                                                            .FileIdRange)
                                                                                                                                    args[
                                                                                                                                            0])
                                                                                                                            .fromId()
                                                                                                            && file
                                                                                                                            .fileDataId()
                                                                                                                    <= ((FileTypes
                                                                                                                                            .FileIdRange)
                                                                                                                                    args[
                                                                                                                                            0])
                                                                                                                            .toId())
                                                                                    .toList());
                                                        }

                                                        public void close() {
                                                            done = true;
                                                        }
                                                    };
                                            case "close" -> null;
                                            default ->
                                                    throw new AssertionError(
                                                            "Prepared fixture must not acquire "
                                                                    + method.getName());
                                        });
        var options =
                new TablesOptions(
                                (ignored, token) ->
                                        CompletableFuture.completedFuture("1.60.1.70755"))
                        .withLocalDefinitionsOnly();
        tables =
                new ClientBuildTables(
                        null,
                        files,
                        "1.60.1.70755",
                        new TableDefinitions(bundled),
                        generated,
                        options,
                        new BackgroundBuilds(),
                        new ProtectedIds());
    }

    public BuildTables tables() {
        return tables;
    }

    public int sourceBytes(long id) {
        return raw.get(id).length;
    }

    @Override
    public void close() {
        tables.close();
    }

    private record Field(
            DbdTypes.Field definition, int offset, int width, int kind, int packedBit) {}

    private static byte[] encode(
            List<DbdTypes.Field> definitions,
            boolean type,
            long hash,
            long layout,
            int materialId,
            boolean includeCopy,
            boolean oceanRow,
            int minimapColour) {
        var fields = new ArrayList<Field>();
        int prefix = 0, packedBits = 0;
        for (var f : definitions) {
            if (f.nonInline()) {
                continue;
            }
            String name = f.column().name();
            int kind =
                    name.equals("Flags")
                            ? 5
                            : name.equals("MaterialID") ? 1 : name.equals("LVF") ? 5 : 0;
            int width =
                    kind == 0
                            ? (f.integerBits() == null ? 32 : f.integerBits())
                            : name.equals("Flags") ? (type ? 15 : 2) : type ? 8 : 3;
            fields.add(new Field(f, prefix, width, kind, packedBits));
            if (kind == 0) {
                prefix += width / 8 * f.arrayLength();
            } else {
                packedBits += width;
            }
        }
        int[] typeIds = oceanRow ? new int[] {2, 1240, 1250, 1288} : new int[] {1240, 1250, 1288};
        int records = type ? typeIds.length : 4,
                copies = type || !includeCopy ? 0 : 1,
                recordSize = prefix + (packedBits + 7) / 8;
        int start = 204 + 40 + fields.size() * 28;
        var b =
                ByteBuffer.allocate(start + records * recordSize + 1 + records * 4 + copies * 8)
                        .order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'W', 'D', 'C', '5'}).putInt(5);
        b.putInt(136, records).putInt(140, fields.size()).putInt(144, recordSize).putInt(148, 1);
        b.putInt(152, (int) hash)
                .putInt(156, (int) layout)
                .putShort(172, (short) 4)
                .putShort(174, (short) 65535);
        b.putInt(176, fields.size())
                .putInt(180, prefix)
                .putInt(188, fields.size() * 24)
                .putInt(200, 1);
        b.position(204);
        b.putLong(0)
                .putInt(start)
                .putInt(records)
                .putInt(1)
                .putInt(0)
                .putInt(records * 4)
                .putInt(0)
                .putInt(0)
                .putInt(copies);
        for (var f : fields) {
            b.putShort((short) (f.kind() == 0 ? 32 - f.width() : 32))
                    .putShort((short) (f.kind() == 0 ? f.offset() : prefix));
        }
        for (var f : fields) {
            int offset = f.kind() == 0 ? f.offset() * 8 : prefix * 8 + f.packedBit();
            b.putShort((short) offset)
                    .putShort(
                            (short)
                                    (f.kind() == 0
                                            ? f.width() * f.definition().arrayLength()
                                            : f.width()))
                    .putInt(0)
                    .putInt(f.kind())
                    .putInt(f.kind() == 0 ? 0 : f.packedBit())
                    .putInt(f.kind() == 0 ? 0 : f.width())
                    .putInt(0);
        }
        for (int row = 0; row < records; row++) {
            long packed = 0;
            for (var f : fields) {
                if (f.kind() == 0) {
                    continue;
                }
                int value =
                        f.definition().column().name().equals("Flags")
                                ? (type ? 15 : row == 1 ? 0 : 1)
                                : type ? materialId : new int[] {0, 1, 3, 1}[row];
                packed |= (long) value << f.packedBit();
            }
            for (int octet = 0; octet < (packedBits + 7) / 8; octet++) {
                b.put(start + row * recordSize + prefix + octet, (byte) (packed >>> (octet * 8)));
            }
            for (var f : fields) {
                if (type
                        && f.kind() == 0
                        && f.definition().column().name().equals("MinimapStaticCol")) {
                    b.putInt(start + row * recordSize + f.offset(), minimapColour);
                }
            }
        }
        b.position(start + records * recordSize + 1);
        for (int id : type ? typeIds : new int[] {1, 2, 13, 146}) {
            b.putInt(id);
        }
        if (copies != 0) {
            b.putInt(130).putInt(1);
        }
        return b.array();
    }
}
