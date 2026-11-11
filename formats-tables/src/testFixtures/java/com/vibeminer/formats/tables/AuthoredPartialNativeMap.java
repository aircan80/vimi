package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.AsyncCursor;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2ByteSource;
import com.vibeminer.formats.db2.Db2Fixture;
import com.vibeminer.formats.db2.Db2Reader;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.BundledDefinitions;
import com.vibeminer.formats.definitions.TableDefinitions;
import com.vibeminer.formats.generated.GeneratedTableContent;
import com.vibeminer.formats.generated.GeneratedTableSource;
import com.vibeminer.formats.generated.GeneratedTables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual two-section WDC5 native Map graph, with explicit protected source bytes and ID index. */
public final class AuthoredPartialNativeMap implements AutoCloseable {
    public static final String KEY = "000000000000007B";
    private final byte[] bytes;
    private final int hidden;
    private final BuildFiles files;
    private final BuildTables tables;
    public final AtomicInteger opens = new AtomicInteger(), closedStreams = new AtomicInteger();
    public final AtomicReference<String> contentKey = new AtomicReference<>(),
            keyFingerprint = new AtomicReference<>();
    public volatile Long declaredSize, streamLength;
    public volatile RuntimeException readFailure;
    public volatile boolean unknownSize;
    public volatile FileTypes.FileReadState readState = FileTypes.FileReadState.PARTIAL;
    public volatile boolean exposeProtected, holdReads, holdCancellation, throwCancellation;
    public final CompletableFuture<Void> reading = new CompletableFuture<>(),
            releaseRead = new CompletableFuture<>(),
            cancellationEntered = new CompletableFuture<>(),
            releaseCancellation = new CompletableFuture<>(),
            streamClosed = new CompletableFuture<>();
    public final AtomicInteger cancellationCalls = new AtomicInteger();
    public final TableDefinitions definitions;

    public AuthoredPartialNativeMap(BuildFiles borrowed) {
        this(borrowed, true, false);
    }

    public AuthoredPartialNativeMap(BuildFiles borrowed, boolean completeHiddenIds, boolean copy) {
        this(borrowed, completeHiddenIds, copy, 1);
    }

    public AuthoredPartialNativeMap(
            BuildFiles borrowed, boolean completeHiddenIds, boolean copy, int visibleRows) {
        if (visibleRows < 1 || visibleRows > 2) {
            throw new IllegalArgumentException("Authored visible rows");
        }
        int prefix = 204 + 80 + 28 + 4 + (completeHiddenIds ? 4 : 0);
        hidden = prefix + visibleRows * 12 + (copy ? 8 : 0);
        var out = ByteBuffer.allocate(hidden + 12).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'W', 'D', 'C', '5'}).putInt(5);
        out.putInt(136, visibleRows + 1)
                .putInt(140, 1)
                .putInt(144, 8)
                .putInt(152, 0x11223344)
                .putInt(156, 0x01020304)
                .putShort(172, (short) 4)
                .putInt(176, 1)
                .putInt(188, 24)
                .putInt(200, 2);
        out.position(204);
        section(out, 0, prefix, visibleRows, copy ? 1 : 0);
        section(out, 123, hidden, 1, 0);
        out.putShort((short) 0).putShort((short) 0);
        out.putShort((short) 0)
                .putShort((short) 32)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        out.putInt(completeHiddenIds ? 1 : 0);
        if (completeHiddenIds) {
            out.putInt(99);
        }
        for (int i = 0; i < visibleRows; i++) {
            out.putLong(1);
        }
        for (int i = 0; i < visibleRows; i++) {
            out.putInt(7 + i);
        }
        if (copy) {
            out.putInt(50).putInt(7);
        }
        out.putLong(777).putInt(99);
        bytes = out.array();
        try {
            contentKey.set(
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("MD5").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        var document =
                Db2Fixture.definition(
                        "int ID\nint WdtFileDataID", "$id,noninline$ID<u32>\nWdtFileDataID<u32>");
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
        var projected =
                new Db2ByteSource() {
                    public long length() {
                        return bytes.length;
                    }

                    public List<Db2Types.Hole> holes() {
                        return List.of(new Db2Types.Hole(hidden, 12, KEY));
                    }

                    public CompletableFuture<Void> readRange(
                            long offset, ByteBuffer target, Cancellation token) {
                        token.check();
                        if (offset < hidden + 12 && offset + target.remaining() > hidden) {
                            throw new AssertionError(
                                    "Native reader may not consume protected row values");
                        }
                        target.put(bytes, (int) offset, target.remaining());
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var decoded =
                Db2Reader.open(projected, document, "1.2.3.4", limits, Cancellation.NONE).join();
        var block =
                document.selectFile("1.2.3.4", decoded.header().layoutHash(), Cancellation.NONE)
                        .blocks()
                        .getFirst();
        var build = borrowed.build();
        var source =
                GeneratedTableSource.forNativeBlock(
                        block,
                        borrowed.locale(),
                        contentKey.get(),
                        limits,
                        build.product() + " " + build.buildConfig() + " " + build.cdnConfig());
        var generated = new GeneratedTables(new MemoryGeneratedStore());
        generated
                .store(
                        source,
                        name -> keyFingerprint.get(),
                        GeneratedTableContent.nativeDb2(
                                source,
                                decoded,
                                List.of(KEY),
                                () -> {},
                                List.of(KEY),
                                List.of(KEY)),
                        Cancellation.NONE)
                .join();
        definitions =
                new TableDefinitions(
                        BundledDefinitions.of(
                                List.of(
                                        new BundledDefinitions.DefinitionTable(
                                                "11223344", "Map", "100", true)),
                                Map.of(
                                        "11223344",
                                        new BundledDefinitions.Definition(
                                                "a".repeat(64), "authored", document))));
        String inventoryKey = contentKey.get();
        files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("close")) {
                                        return null;
                                    }
                                    if (method.getName().equals("listContentInventory")) {
                                        return new AsyncCursor<FileTypes.ContentFile>() {
                                            boolean ended;

                                            public CompletableFuture<List<FileTypes.ContentFile>>
                                                    next(int limit, Cancellation token) {
                                                token.check();
                                                if (ended) {
                                                    return CompletableFuture.completedFuture(
                                                            List.of());
                                                }
                                                ended = true;
                                                return CompletableFuture.completedFuture(
                                                        List.of(
                                                                new FileTypes.ContentFile(
                                                                        100,
                                                                        (long) bytes.length,
                                                                        inventoryKey)));
                                            }

                                            public void close() {
                                                ended = true;
                                            }
                                        };
                                    }
                                    if (((method.getName().equals("stat")
                                                            || method.getName()
                                                                    .equals("statContent"))
                                                    || method.getName().equals("openRead"))
                                            && ((FileTypes.FileRef) args[0]).fileDataId() == 100) {
                                        var token = (Cancellation) args[1];
                                        token.check();
                                        if (method.getName().equals("stat")
                                                || method.getName().equals("statContent")) {
                                            return CompletableFuture.completedFuture(
                                                    new FileTypes.FileStat(
                                                            FileTypes.FileLookupState.FOUND,
                                                            new FileTypes.BuildFile(
                                                                    100,
                                                                    null,
                                                                    FileTypes.FilePathSource.NONE,
                                                                    unknownSize
                                                                            ? null
                                                                            : declaredSize == null
                                                                                    ? (long)
                                                                                            bytes.length
                                                                                    : declaredSize,
                                                                    contentKey.get(),
                                                                    FileTypes.FileFormat.DB2,
                                                                    FileTypes.FileFormatSource.NONE,
                                                                    FileTypes.FileStorage.STORED,
                                                                    false)));
                                        }
                                        opens.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new FileTypes.FileOpen(
                                                        readState, stream(), null, null, null));
                                    }
                                    try {
                                        return method.invoke(borrowed, args);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                });
        var options =
                new TablesOptions(
                        (ignored, token) -> CompletableFuture.completedFuture("1.2.3.4"),
                        base,
                        ignored -> name -> keyFingerprint.get(),
                        new TableWorkScheduler(),
                        10000,
                        256,
                        1000000,
                        true);
        tables =
                new ClientBuildTables(
                        null,
                        files,
                        "1.2.3.4",
                        definitions,
                        generated,
                        options,
                        new BackgroundBuilds(),
                        new ProtectedIds());
    }

    private static void section(ByteBuffer out, long key, int start, int rows, int copies) {
        out.putLong(key)
                .putInt(start)
                .putInt(rows)
                .putInt(0)
                .putInt(0)
                .putInt(rows * 4)
                .putInt(0)
                .putInt(0)
                .putInt(copies);
    }

    private FileReadStream stream() {
        return new FileReadStream() {
            int position;
            boolean closed;
            Cancellation.Registration cancelHook;

            public long length() {
                return streamLength == null ? bytes.length : streamLength;
            }

            public long position() {
                return position;
            }

            public void seek(long offset) {
                position = Math.toIntExact(offset);
            }

            public List<FileTypes.FileHole> holes() {
                return exposeProtected
                        ? List.of()
                        : List.of(new FileTypes.FileHole(hidden, 12, KEY));
            }

            public CompletableFuture<Integer> read(ByteBuffer target, Cancellation token) {
                token.check();
                if (holdCancellation && cancelHook == null) {
                    cancelHook =
                            token.onCancel(
                                    () -> {
                                        cancellationCalls.incrementAndGet();
                                        cancellationEntered.complete(null);
                                        releaseCancellation.join();
                                        if (throwCancellation) {
                                            throw new IllegalStateException(
                                                    "Authored native ID cancellation failure");
                                        }
                                    });
                }
                reading.complete(null);
                if (holdReads) {
                    return releaseRead.thenApply(ignored -> transfer(target, token));
                }
                return CompletableFuture.completedFuture(transfer(target, token));
            }

            private int transfer(ByteBuffer target, Cancellation token) {
                if (readFailure != null) {
                    throw readFailure;
                }
                token.check();
                int count = Math.min(target.remaining(), bytes.length - position);
                if (!exposeProtected && position < hidden + 12 && position + count > hidden) {
                    throw new AssertionError("Inspector must not read protected row bytes");
                }
                target.put(bytes, position, count);
                position += count;
                return count;
            }

            public void close() {
                if (!closed) {
                    closed = true;
                    closedStreams.incrementAndGet();
                    streamClosed.complete(null);
                    if (cancelHook != null) {
                        cancelHook.close();
                        cancelHook = null;
                    }
                }
            }
        };
    }

    public BuildTables tables() {
        return tables;
    }

    public BuildFiles files() {
        return files;
    }

    @Override
    public void close() {
        releaseRead.complete(null);
        releaseCancellation.complete(null);
        tables.close();
    }
}
