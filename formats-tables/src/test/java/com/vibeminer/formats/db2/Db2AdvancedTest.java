package com.vibeminer.formats.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.definitions.DbdDocument;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
final class Db2AdvancedTest {
    private static final String BUILD = "1.2.3.4";
    private static final DbdDocument NUMERIC =
            Db2Fixture.definition("int ID\nint Value", "$noninline,id$ID<u32>\nValue<u32>");
    private static final DbdDocument RELATION =
            Db2Fixture.definition(
                    "int ID\nint Before\nint Parent\nint After",
                    "$noninline,id$ID<u32>\nBefore<u32>\n$noninline,relation$Parent<u32>\nAfter<u32>");

    private record Projection(byte[] bytes, int hiddenStart, int hiddenEnd) {}

    private static ByteBuffer header(
            int records,
            int fields,
            int recordBytes,
            int strings,
            int sections,
            int flags,
            int lookup) {
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.put("WDC5".getBytes(StandardCharsets.US_ASCII)).putInt(5);
        b.putInt(136, records)
                .putInt(140, fields)
                .putInt(144, recordBytes)
                .putInt(148, strings)
                .putInt(152, 0x11223344)
                .putInt(156, 0x01020304);
        b.putShort(172, (short) flags)
                .putInt(176, fields)
                .putInt(184, lookup)
                .putInt(188, fields * 24)
                .putInt(200, sections);
        b.position(204);
        return b;
    }

    private static void section(
            ByteBuffer b,
            long key,
            int start,
            int rows,
            int strings,
            int relationBytes,
            int copies) {
        b.putLong(key)
                .putInt(start)
                .putInt(rows)
                .putInt(strings)
                .putInt(0)
                .putInt(rows * 4)
                .putInt(relationBytes)
                .putInt(0)
                .putInt(copies);
    }

    private static void fields(ByteBuffer b, int count) {
        for (int i = 0; i < count; i++) {
            b.putShort((short) 0).putShort((short) (i * 4));
        }
        for (int i = 0; i < count; i++) {
            b.putShort((short) (i * 32))
                    .putShort((short) 32)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0);
        }
    }

    private static byte[] finish(ByteBuffer b) {
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    private static Projection keyed() {
        int prefix = 204 + 80 + 28 + 8;
        int hidden = prefix + 4 + 1 + 4 + 3 * 8;
        ByteBuffer b = header(2, 1, 4, 2, 2, 4, 0);
        section(b, 0, prefix, 1, 1, 0, 3);
        section(b, 123, hidden, 1, 1, 0, 1);
        fields(b, 1);
        b.putInt(1).putInt(21); // Keyed IDs are clear, before either section body.
        assertEquals(prefix, b.position());
        b.putInt(99).put((byte) 0).putInt(7);
        b.putInt(8).putInt(7); // Readable copy.
        b.putInt(9).putInt(21); // Copy ending in hidden physical identity.
        b.putInt(10).putInt(9); // Copy of that protected copy.
        assertEquals(hidden, b.position());
        b.putInt(555).put((byte) 0).putInt(21).putInt(22).putInt(21);
        return new Projection(finish(b), hidden, b.position());
    }

    private static Db2Types.Limits partial() {
        var l = new Db2Types.Limits();
        return new Db2Types.Limits(
                l.maxDecodedBytes(),
                l.maxRows(),
                l.maxFields(),
                l.maxSections(),
                l.maxRecordBytes(),
                l.maxAuxiliaryBytes(),
                l.maxStringBytes(),
                l.maxIndexBytes(),
                l.maxColumns(),
                l.maxCopyDepth(),
                true,
                l.allowRawTextEvidence(),
                l.maxRawTextEvidenceBytes(),
                l.maxRelationshipBytes(),
                l.maxRelationshipEntries(),
                l.definitionSha256(),
                l.verifiedRawIntegerRules());
    }

    private static Db2Table open(
            Db2ByteSource source, DbdDocument document, Db2Types.Limits limits) {
        return Db2Reader.open(source, document, BUILD, limits, Cancellation.NONE).join();
    }

    private static Db2ByteSource projected(
            Projection p, List<Db2Types.Hole> holes, AtomicInteger forbiddenReads) {
        return new Db2ByteSource() {
            @Override
            public long length() {
                return p.bytes().length;
            }

            @Override
            public List<Db2Types.Hole> holes() {
                return holes;
            }

            @Override
            public CompletableFuture<Void> readRange(
                    long offset, ByteBuffer destination, Cancellation token) {
                token.check();
                long stop = offset + destination.remaining();
                for (var hole : holes) {
                    if (offset < hole.offset() + hole.length() && hole.offset() < stop) {
                        forbiddenReads.incrementAndGet();
                        return CompletableFuture.failedFuture(
                                new AssertionError("Touched hidden backing bytes"));
                    }
                }
                destination.put(p.bytes(), (int) offset, destination.remaining());
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Test
    void protectedPhysicalAndTransitiveCopyIdentitiesRemainDistinctFromPartialAbsence() {
        Projection p = keyed();
        AtomicInteger forbidden = new AtomicInteger();
        var source =
                projected(
                        p,
                        List.of(
                                new Db2Types.Hole(p.hiddenStart(), 5, "outer-key"),
                                new Db2Types.Hole(
                                        p.hiddenStart() + 5,
                                        p.hiddenEnd() - p.hiddenStart() - 5,
                                        "outer-key")),
                        forbidden);
        var table = open(source, NUMERIC, partial());
        assertEquals(2, table.header().declaredRecords());
        assertEquals(4, table.header().declaredCopyRecords());
        assertEquals(1, table.completion().excludedEncryptedSections());
        assertEquals(1, table.completion().excludedPhysicalRecords());
        assertEquals(3, table.completion().excludedCopyRecords());
        assertEquals("unencryptedSections", table.completion().scope());
        assertEquals(
                List.of(7L, 8L),
                table.readAll(Cancellation.NONE).join().stream().map(Db2Types.Row::id).toList());
        var copy = assertInstanceOf(Db2Types.Found.class, table.find(8, Cancellation.NONE).join());
        assertEquals(List.of("8", "99"), copy.row().values());
        assertEquals(7L, copy.row().copySourceId());
        for (long id : new long[] {21, 9, 10}) {
            var hidden =
                    assertInstanceOf(
                            Db2Types.Protected.class, table.find(id, Cancellation.NONE).join());
            assertEquals(21, hidden.protectedId());
            assertEquals(1, hidden.section());
            assertEquals("000000000000007B", hidden.key());
        }
        assertInstanceOf(
                Db2Types.NotInReadableSections.class, table.find(999, Cancellation.NONE).join());
        assertEquals(0, forbidden.get());
    }

    @Test
    void keyedSectionsWithoutHolesReadNormallyAndTrueAbsenceIsKnown() {
        Projection p = keyed();
        var table = open(Db2ByteSource.memory(p.bytes()), NUMERIC, new Db2Types.Limits());
        assertEquals("complete", table.completion().scope());
        assertEquals(
                List.of(7L, 21L, 8L, 9L, 10L, 22L),
                table.readAll(Cancellation.NONE).join().stream().map(Db2Types.Row::id).toList());
        assertTrue(table.sections().get(1).isDecrypted());
        assertInstanceOf(Db2Types.Absent.class, table.find(999, Cancellation.NONE).join());
    }

    @Test
    void hiddenBodiesRequireExplicitPolicyAndClearHeaderBytesCannotBeProjectedAway() {
        Projection p = keyed();
        var body =
                projected(
                        p,
                        List.of(new Db2Types.Hole(p.hiddenStart(), 1, "key")),
                        new AtomicInteger());
        assertEquals(
                "DB2_ENCRYPTED", failure(() -> open(body, NUMERIC, new Db2Types.Limits())).code());
        var prefix = projected(p, List.of(new Db2Types.Hole(312, 1, "key")), new AtomicInteger());
        assertEquals(
                "BLTE_RANGE_UNAVAILABLE", failure(() -> open(prefix, NUMERIC, partial())).code());
    }

    private static byte[] relationships(int flags, long[][] pairs, long[][] copies) {
        int rows = 3, fields = 2;
        int start = 204 + 40 + fields * 28;
        int relationBytes = 12 + pairs.length * 8;
        ByteBuffer b = header(rows, fields, 8, 0, 1, flags, 1);
        section(b, 0, start, rows, 0, relationBytes, copies.length);
        fields(b, fields);
        assertEquals(start, b.position());
        for (int row = 0; row < rows; row++) {
            b.putInt(1000 + row).putInt(2000 + row);
        }
        b.putInt(900).putInt(901).putInt(902);
        for (var copy : copies) {
            b.putInt((int) copy[0]).putInt((int) copy[1]);
        }
        b.putInt(pairs.length).putInt(0).putInt(0);
        for (var pair : pairs) {
            b.putInt((int) pair[0]).putInt((int) pair[1]);
        }
        return finish(b);
    }

    private static long key(int flags, int ordinal) {
        return (flags & 2) == 0 ? ordinal : 900 + ordinal;
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6})
    void absentExternalParentsAreIssuesWhileExplicitZeroAndCopyInheritanceRemainExact(int flags) {
        byte[] bytes =
                relationships(
                        flags,
                        new long[][] {{0, key(flags, 0)}, {5, key(flags, 2)}},
                        new long[][] {{950, 901}, {951, 950}});
        var table = open(Db2ByteSource.memory(bytes), RELATION, new Db2Types.Limits());
        var rows = table.readAll(Cancellation.NONE).join();
        assertEquals(
                List.of(900L, 901L, 902L, 950L, 951L),
                rows.stream().map(Db2Types.Row::id).toList());
        assertEquals(
                List.of("0", "", "5", "", ""), rows.stream().map(r -> r.values().get(2)).toList());
        var absent = new Db2Types.TextIssue(2, "DB2_RELATION_ABSENT", "");
        for (int ordinal : new int[] {1, 3, 4}) {
            assertEquals(List.of(absent), rows.get(ordinal).textIssues());
        }
        assertNull(rows.getFirst().textIssues());
        assertEquals(List.of("950", "1001", "", "2001"), rows.get(3).values());
        assertEquals(4, table.findWithOrdinal(951, Cancellation.NONE).join().ordinal());
        assertEquals(rows.get(4), table.readOrdinals(4, 1, Cancellation.NONE).join().getFirst());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 6})
    void equalRepeatedParentPairsAreAllowedOnlyForReadableTerminalCopies(int flags) {
        byte[] bytes =
                relationships(
                        flags,
                        new long[][] {
                            {7, key(flags, 0)},
                            {7, key(flags, 0)},
                            {7, key(flags, 0)},
                            {5, key(flags, 2)}
                        },
                        new long[][] {{950, 900}, {951, 950}});
        var table = open(Db2ByteSource.memory(bytes), RELATION, new Db2Types.Limits());
        assertEquals(
                List.of("7", "", "5", "7", "7"),
                table.readAll(Cancellation.NONE).join().stream()
                        .map(r -> r.values().get(2))
                        .toList());
        byte[] wrong =
                relationships(
                        flags,
                        new long[][] {{7, key(flags, 0)}, {9, key(flags, 0)}},
                        new long[][] {{950, 900}});
        assertEquals(
                "DB2_SCHEMA_MISMATCH",
                failure(() -> open(Db2ByteSource.memory(wrong), RELATION, new Db2Types.Limits()))
                        .code());
        byte[] extra =
                relationships(
                        flags,
                        new long[][] {{7, key(flags, 0)}, {7, key(flags, 0)}},
                        new long[0][]);
        assertEquals(
                "DB2_INVALID",
                failure(() -> open(Db2ByteSource.memory(extra), RELATION, new Db2Types.Limits()))
                        .code());
    }

    private static Db2FormatException failure(Runnable action) {
        return assertInstanceOf(
                Db2FormatException.class,
                assertThrows(CompletionException.class, action::run).getCause());
    }
}
