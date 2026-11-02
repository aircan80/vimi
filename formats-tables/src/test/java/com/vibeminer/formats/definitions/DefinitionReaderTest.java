package com.vibeminer.formats.definitions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.Db2FormatException;
import com.vibeminer.formats.definitions.DbdTypes.Limits;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DefinitionReaderTest {
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void exactBuildAndLayoutFallbackKeepDifferentProvenance() {
        var document =
                DbdDefinitionReader.parse(
                        text(
                                "COLUMNS\nint ID\nlocstring Name_lang? // name\n\nLAYOUT AABBCCDD\nBUILD 11.0.0.100\n$id,noninline$ID<32>\nName_lang // field\n"));
        var exact = document.selectFile("11.0.0.100", "aabbccdd", Cancellation.NONE);
        assertEquals("build", exact.match());
        assertEquals(
                "missing",
                document.selectFile("11.0.0.100", "11111111", Cancellation.NONE).status());
        var fallback = document.selectFile("11.0.0.101", "aabbccdd", Cancellation.NONE);
        assertEquals("layout", fallback.match());
        var schema =
                TableSchema.fromBlock(
                        exact.blocks().getFirst(), TableSchema.SchemaMatch.BUILD, WowLocale.EN_US);
        assertEquals(TableSchema.KeyStorage.NONINLINE, schema.columns().getFirst().id());
        assertEquals("field", schema.columns().get(1).fieldComment());
        assertFalse(schema.columns().get(1).verifiedName());
        assertEquals(WowLocale.EN_US, schema.columns().get(1).locale());
        assertEquals(document.selectionCost(), DbdDefinitionReader.estimateSelection(document));
        assertEquals(1, document.layoutCandidates("11.0.0.101", Cancellation.NONE).size());
    }

    @Test
    void columnsOnlyDoesNotInterpretBlocks() {
        assertEquals(
                1,
                DbdDefinitionReader.parseColumns(
                                text("COLUMNS\nint ID\n\nunsupported!!!"),
                                new Limits(),
                                Cancellation.NONE)
                        .size());
    }

    @Test
    void invalidUtf8ControlsDuplicateNamesAndEmptyDefinitionsRefuse() {
        for (byte[] value :
                new byte[][] {
                    {(byte) 0xff},
                    text("COLUMNS\nint ID\nint ID"),
                    text("COLUMNS\n"),
                    text("COLUMNS\rint ID")
                }) assertThrows(Db2FormatException.class, () -> DbdDefinitionReader.parse(value));
    }

    @Test
    void ambiguousNearestLayoutsAndVersionLinesRemainDistinct() {
        var d =
                DbdDefinitionReader.parse(
                        text(
                                "COLUMNS\nint A\nint B\n\nLAYOUT 12345678\nBUILD 11.0.0.99\nA<32>\n\nLAYOUT 12345678\nBUILD 11.0.0.101\nB<32>"));
        assertEquals(
                "ambiguous", d.selectFile("11.0.0.100", "12345678", Cancellation.NONE).status());
        assertEquals("missing", d.selectFile("12.0.0.100", "12345678", Cancellation.NONE).status());
        assertTrue(d.layoutCandidates("11.0.0.100", Cancellation.NONE).isEmpty());
    }

    @Test
    void allLocalesAreExactAndVersionComponentsAreUnsigned() {
        assertEquals(15, WowLocale.ALL.size());
        for (var locale : WowLocale.ALL) assertSame(locale, WowLocale.parse(locale.code()));
        assertThrows(DefinitionException.class, () -> WowLocale.parse("enus"));
        assertEquals(4294967295L, DbdTypes.BuildVersion.parse("1.2.3.4294967295").build());
        assertThrows(Db2FormatException.class, () -> DbdTypes.BuildVersion.parse("1.02.3.4"));
    }
}
