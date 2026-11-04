package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeLiquids;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WorldLiquidTablesTest {
    private static final class Fixture implements AutoCloseable {
        AuthoredNativeLiquids nativeRows;
        boolean changed;
        int captures, closes;
        boolean defaultBounded, foreignUnsupported, closeFailure;
        Cancellation cancelAfterCapture;
        java.util.function.UnaryOperator<CapturedRowsTypes.Page> alterPage = p -> p;
        java.util.function.UnaryOperator<CapturedRowsTypes.Receipt> alter = r -> r;
        final BuildFiles files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" ->
                                                    new BuildRef(
                                                            "wowf",
                                                            "a".repeat(32),
                                                            "b".repeat(32),
                                                            "us");
                                            case "locale" -> "enUS";
                                            case "stat", "statContent" -> {
                                                long id =
                                                        ((FileTypes.FileRef) args[0]).fileDataId();
                                                ((Cancellation) args[1]).check();
                                                String key =
                                                        changed
                                                                ? "c".repeat(32)
                                                                : id == 1371380
                                                                        ? AuthoredNativeLiquids
                                                                                .TYPE_KEY
                                                                        : AuthoredNativeLiquids
                                                                                .MATERIAL_KEY;
                                                yield CompletableFuture.completedFuture(
                                                        new FileTypes.FileStat(
                                                                FileTypes.FileLookupState.FOUND,
                                                                new FileTypes.BuildFile(
                                                                        id,
                                                                        null,
                                                                        FileTypes.FilePathSource
                                                                                .NONE,
                                                                        (long)
                                                                                nativeRows
                                                                                        .sourceBytes(
                                                                                                id),
                                                                        key,
                                                                        FileTypes.FileFormat
                                                                                .UNKNOWN,
                                                                        FileTypes.FileFormatSource
                                                                                .NONE,
                                                                        FileTypes.FileStorage
                                                                                .STORED,
                                                                        false)));
                                            }
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected source IO "
                                                                    + method.getName());
                                        });
        final BuildTables tables;

        Fixture() {
            nativeRows = new AuthoredNativeLiquids(files);
            tables =
                    (BuildTables)
                            Proxy.newProxyInstance(
                                    BuildTables.class.getClassLoader(),
                                    new Class<?>[] {BuildTables.class},
                                    (proxy, method, args) -> {
                                        if (!method.getName().equals("capturePreparedLocal")) {
                                            throw new AssertionError("Only local native capture");
                                        }
                                        captures++;
                                        var captured =
                                                nativeRows
                                                        .tables()
                                                        .capturePreparedLocal(
                                                                (TableTypes.Ref) args[0],
                                                                (Cancellation) args[1])
                                                        .join();
                                        if (captured.rows() == null) {
                                            throw new AssertionError(
                                                    "Genuine authored native capture missing: "
                                                            + captured);
                                        }
                                        var actual = captured.rows();
                                        var held =
                                                new CapturedRows() {
                                                    public CapturedRowsTypes.Receipt receipt() {
                                                        return alter.apply(actual.receipt());
                                                    }

                                                    public CompletableFuture<CapturedRowsTypes.Page>
                                                            readOrdinalPage(
                                                                    long first,
                                                                    int count,
                                                                    Cancellation token) {
                                                        throw new AssertionError(
                                                                "No legacy fallback");
                                                    }

                                                    public CompletableFuture<CapturedRowsTypes.Page>
                                                            readOrdinalPage(
                                                                    long first,
                                                                    int count,
                                                                    CapturedRowsTypes.ReadLimits
                                                                            limits,
                                                                    Cancellation token) {
                                                        if (defaultBounded) {
                                                            return CapturedRows.super
                                                                    .readOrdinalPage(
                                                                            first, count, limits,
                                                                            token);
                                                        }
                                                        if (foreignUnsupported) {
                                                            return CompletableFuture.failedFuture(
                                                                    new UnsupportedOperationException(
                                                                            "Foreign provider failure"));
                                                        }
                                                        return actual.readOrdinalPage(
                                                                        first, count, limits, token)
                                                                .thenApply(alterPage);
                                                    }

                                                    public CompletableFuture<Void> closeAsync() {
                                                        closes++;
                                                        return actual.closeAsync()
                                                                .thenApply(
                                                                        ignored -> {
                                                                            if (closeFailure) {
                                                                                throw new IllegalStateException(
                                                                                        "Actual capture cleanup failed");
                                                                            }
                                                                            return null;
                                                                        });
                                                    }
                                                };
                                        if (cancelAfterCapture != null) {
                                            cancelAfterCapture.cancel();
                                        }
                                        return CompletableFuture.completedFuture(
                                                new CapturedRowsTypes.Capture(
                                                        captured.state(),
                                                        held,
                                                        captured.code(),
                                                        captured.message()));
                                    });
        }

        WorldLiquidTables reader() {
            return new WorldLiquidTables(tables, files);
        }

        public void close() {
            nativeRows.close();
        }
    }

    private static Object replace(Object record, String field, Object replacement) {
        try {
            var parts = record.getClass().getRecordComponents();
            var types = new Class<?>[parts.length];
            var values = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                values[i] =
                        parts[i].getName().equals(field)
                                ? replacement
                                : parts[i].getAccessor().invoke(record);
            }
            return record.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void actualNativeRelationsIncludeMaterialCopy130AndFreshEof() {
        try (var f = new Fixture()) {
            var reader = f.reader();
            var types =
                    reader.observe(
                            WorldLiquidTables.Profile.TYPE,
                            Set.of(1240L, 1250L, 1288L),
                            Cancellation.NONE);
            assertEquals(130, types.value(1250));
            var material =
                    reader.observe(
                            WorldLiquidTables.Profile.MATERIAL, Set.of(130L), Cancellation.NONE);
            assertEquals(0, material.value(130));
            reader.revalidate(types, Cancellation.NONE);
            reader.revalidate(material, Cancellation.NONE);
            assertEquals(4, f.captures);
            assertEquals(4, f.closes);
        }
    }

    @Test
    void missingRowsAndChangedSourceStayOptionalAndCloseCaptures() {
        try (var f = new Fixture()) {
            var reader = f.reader();
            assertEquals(
                    WorldLiquidTables.Reason.ROW_NOT_OBSERVED,
                    assertThrows(
                                    WorldLiquidTables.Unavailable.class,
                                    () ->
                                            reader.observe(
                                                    WorldLiquidTables.Profile.TYPE,
                                                    Set.of(999L),
                                                    Cancellation.NONE))
                            .reason);
            var proof =
                    reader.observe(
                            WorldLiquidTables.Profile.TYPE, Set.of(1250L), Cancellation.NONE);
            f.changed = true;
            assertEquals(
                    WorldLiquidTables.Reason.SOURCE_CHANGED,
                    assertThrows(
                                    WorldLiquidTables.Unavailable.class,
                                    () -> reader.revalidate(proof, Cancellation.NONE))
                            .reason);
            assertEquals(f.captures, f.closes);
        }
    }

    @Test
    void changedReceiptAndUnknownIssueChannelCannotReuseAStamp() {
        try (var f = new Fixture()) {
            var reader = f.reader();
            var proof =
                    reader.observe(
                            WorldLiquidTables.Profile.TYPE, Set.of(1250L), Cancellation.NONE);
            f.alter = r -> (CapturedRowsTypes.Receipt) replace(r, "readerRevision", "different");
            assertThrows(RuntimeException.class, () -> reader.revalidate(proof, Cancellation.NONE));
            f.alter =
                    r ->
                            (CapturedRowsTypes.Receipt)
                                    replace(
                                            r,
                                            "issueChannelEvidence",
                                            CapturedRowsTypes.IssueChannelEvidence.unknown());
            assertEquals(
                    WorldLiquidTables.Reason.TABLE_PROFILE,
                    assertThrows(
                                    WorldLiquidTables.Unavailable.class,
                                    () -> reader.revalidate(proof, Cancellation.NONE))
                            .reason);
            assertEquals(f.captures, f.closes);
        }
    }

    @Test
    void freshEofMustStillSupportStrictReadsAndForeignUnsupportedIsFatal() {
        try (var f = new Fixture()) {
            var reader = f.reader();
            var proof =
                    reader.observe(
                            WorldLiquidTables.Profile.TYPE, Set.of(1250L), Cancellation.NONE);
            f.defaultBounded = true;
            assertEquals(
                    WorldLiquidTables.Reason.TABLE_UNAVAILABLE,
                    assertThrows(
                                    WorldLiquidTables.Unavailable.class,
                                    () -> reader.revalidate(proof, Cancellation.NONE))
                            .reason);
            f.defaultBounded = false;
            f.foreignUnsupported = true;
            var failure =
                    assertThrows(
                            RuntimeException.class,
                            () -> reader.revalidate(proof, Cancellation.NONE));
            assertTrue(!(failure instanceof WorldLiquidTables.Unavailable));
            assertEquals(f.captures, f.closes);
        }
    }

    @Test
    void skipDuplicateAndUnknownSelectedIssueCannotCertifyObservedId() {
        for (int variant = 0; variant < 3; variant++) {
            int mode = variant;
            try (var f = new Fixture()) {
                f.alterPage =
                        p -> {
                            if (p.rows().isEmpty()) {
                                return p;
                            }
                            if (mode == 0 && p.firstOrdinal() == 0) {
                                return (CapturedRowsTypes.Page) replace(p, "nextOrdinal", 2L);
                            }
                            if (mode == 1 && p.firstOrdinal() == 2) {
                                var row = p.rows().getFirst();
                                var changed = replace(row.row(), "id", 1250L);
                                var altered = (TableTypes.Row) replace(row, "row", changed);
                                return (CapturedRowsTypes.Page)
                                        replace(p, "rows", java.util.List.of(altered));
                            }
                            if (mode == 2 && p.firstOrdinal() == 1) {
                                var row = p.rows().getFirst();
                                var changed = replace(row.row(), "textIssues", null);
                                return (CapturedRowsTypes.Page)
                                        replace(
                                                p,
                                                "rows",
                                                java.util.List.of(
                                                        (TableTypes.Row)
                                                                replace(row, "row", changed)));
                            }
                            return p;
                        };
                var failure =
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        f.reader()
                                                .observe(
                                                        WorldLiquidTables.Profile.TYPE,
                                                        Set.of(1250L),
                                                        Cancellation.NONE));
                if (mode == 0) {
                    assertTrue(!(failure instanceof WorldLiquidTables.Unavailable));
                } else {
                    assertEquals(
                            WorldLiquidTables.Reason.ROW_ISSUE,
                            ((WorldLiquidTables.Unavailable) failure).reason);
                }
                assertEquals(f.captures, f.closes);
            }
        }
    }

    @Test
    void exactWidthSignScalarAndForeignRelationshipAreRequired() {
        for (String field : java.util.List.of("bits", "unsigned", "arrayLength", "foreignKey")) {
            try (var f = new Fixture()) {
                f.alter =
                        r -> {
                            var columns = new java.util.ArrayList<>(r.schema().columns());
                            for (int i = 0; i < columns.size(); i++) {
                                if (columns.get(i).name().equals("MaterialID")) {
                                    Object value =
                                            switch (field) {
                                                case "bits" -> 16;
                                                case "unsigned" -> false;
                                                case "arrayLength" -> 2;
                                                default -> null;
                                            };
                                    columns.set(
                                            i,
                                            (com.vibeminer.formats.definitions.TableSchema
                                                            .SchemaColumn)
                                                    replace(columns.get(i), field, value));
                                }
                            }
                            return (CapturedRowsTypes.Receipt)
                                    replace(r, "schema", replace(r.schema(), "columns", columns));
                        };
                assertEquals(
                        WorldLiquidTables.Reason.TABLE_PROFILE,
                        assertThrows(
                                        WorldLiquidTables.Unavailable.class,
                                        () ->
                                                f.reader()
                                                        .observe(
                                                                WorldLiquidTables.Profile.TYPE,
                                                                Set.of(1250L),
                                                                Cancellation.NONE))
                                .reason);
                assertEquals(f.captures, f.closes);
            }
        }
    }

    @Test
    void optionalMissingRowCannotHideActualCaptureCleanupFailure() {
        try (var f = new Fixture()) {
            f.closeFailure = true;
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    f.reader()
                                            .observe(
                                                    WorldLiquidTables.Profile.TYPE,
                                                    Set.of(999L),
                                                    Cancellation.NONE));
            assertTrue(failure.getCause() instanceof WorldLiquidTables.Unavailable);
            assertEquals(1, failure.getCause().getSuppressed().length);
            assertEquals(f.captures, f.closes);
        }
    }

    @Test
    void cancellationAtCaptureHandoffClosesTheUnreturnedActualHandle() {
        try (var f = new Fixture()) {
            var stop = new Cancellation();
            f.cancelAfterCapture = stop;
            var failure =
                    assertThrows(
                            java.util.concurrent.CancellationException.class,
                            () ->
                                    f.reader()
                                            .observe(
                                                    WorldLiquidTables.Profile.TYPE,
                                                    Set.of(1250L),
                                                    stop));
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(1, f.captures);
            assertEquals(1, f.closes);
        }
    }

    @Test
    void cancellationBeforeCaptureDoesNoWork() {
        try (var f = new Fixture()) {
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> f.reader().observe(WorldLiquidTables.Profile.TYPE, Set.of(1250L), token));
            assertEquals(0, f.captures);
            assertTrue(f.closes == 0);
        }
    }
}
