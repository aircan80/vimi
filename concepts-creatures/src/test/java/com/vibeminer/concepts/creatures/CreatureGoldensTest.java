package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.db2.SingleText;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2SkinReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class CreatureGoldensTest {
    static byte[] bytes(String name) throws Exception {
        try (var input = CreatureGoldensTest.class.getResourceAsStream("/golden/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException(name);
            }
            return input.readAllBytes();
        }
    }

    static List<String> lines(String name) throws Exception {
        return new String(bytes(name), StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void originalCsharpQueryBindingsAndCursorBytes() throws Exception {
        for (var line : lines("queries.tsv")) {
            var f = line.split("\t", -1);
            String query = new String(Base64.getDecoder().decode(f[0]), StandardCharsets.UTF_8);
            String binding =
                    CreatureQueries.binding(
                            CreatureWorld.BUILD, "enUS", new CreatureQuery(query, null, 2, null));
            assertEquals(f[1], binding);
            assertEquals(f[2], CreatureQueries.encodeCursor(binding, 17, "source-identity"));
            var cursor = CreatureQueries.decodeCursor(f[2], binding);
            assertEquals(17, cursor.a());
            assertEquals("source-identity", cursor.i());
        }
    }

    @Test
    void originalCsharpProfilesClassificationCopyAndSingleRoundtrip() throws Exception {
        var table = CreatureWorld.creatures(List.of());
        var layout = CreatureLayout.resolve(table.columns(), table.schema());
        for (var line : lines("profiles.tsv")) {
            var f = line.split("\t", -1);
            var values =
                    new ArrayList<>(
                            List.of(
                                    "7",
                                    "Hogger",
                                    "Alt",
                                    "Title",
                                    "",
                                    f[0],
                                    "255",
                                    "65535",
                                    "127",
                                    "100",
                                    "0",
                                    "-1",
                                    "2147483647",
                                    "1",
                                    "-0",
                                    "1E-05",
                                    "0.5"));
            var summary = layout.summary(new Db2Types.Row(7, true, 6L, values, null));
            assertEquals(
                    f[1], java.util.Objects.toString(CreatureLayout.classificationLabel(f[0]), ""));
            assertEquals(
                    f[2],
                    summary.facts().stream().map(v -> v.value()).collect(Collectors.joining(",")));
            assertEquals(
                    f[3],
                    summary.slots().stream()
                            .map(s -> Integer.toString(s.displayId()))
                            .collect(Collectors.joining(",")));
            assertEquals(
                    f[4],
                    summary.slots().stream()
                            .map(s -> SingleText.format(s.probability()))
                            .collect(Collectors.joining(",")));
        }
    }

    @Test
    void originalCsharpAppearanceDrawnBatchesAndRefusal() throws Exception {
        for (var line : lines("appearance.tsv")) {
            var f = line.split("\t", -1);
            var model =
                    M2ModelReader.read(AssetSource.bytes(bytes("appearance-" + f[0] + ".m2")))
                            .join();
            var skin =
                    M2SkinReader.read(
                                    AssetSource.bytes(bytes("appearance-" + f[0] + ".skin")), model)
                            .join();
            var geometry = CreatureDisplayRules.geometryRefusal(model);
            assertEquals(normal(f[1]), geometry == null ? "" : geometry.state().name());
            var drawn = CreatureDisplayRules.drawnTextures(model, skin);
            assertEquals(
                    f[2],
                    drawn.drawn().stream()
                            .sorted()
                            .map(String::valueOf)
                            .collect(Collectors.joining(",")));
            assertEquals(
                    normal(f[3]), drawn.refusal() == null ? "" : drawn.refusal().state().name());
            assertEquals(Integer.parseInt(f[4]), drawn.blended());
        }
    }

    static String normal(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }
}
