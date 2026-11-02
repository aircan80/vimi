package com.vibeminer.formats.generated;

import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Row-shaping field identity; comments, layout and build selectors do not count. */
public final class ResolvedDefinition {
    public static final String FORMAT = "vibeminer-resolved-definition v1";

    private ResolvedDefinition() {}

    public static boolean hasLocstrings(Block block) {
        return block.fields().stream().anyMatch(f -> f.column().family().equals("locstring"));
    }

    public static String hash(Block block) {
        var text = new StringBuilder(FORMAT).append('\n');
        for (var f : block.fields()) {
            var c = f.column();
            text.append(
                            Json.canonical(
                                    Arrays.asList(
                                            c.name(),
                                            c.family(),
                                            c.reference() == null ? null : c.reference().table(),
                                            c.reference() == null ? null : c.reference().column(),
                                            c.verifiedName(),
                                            f.integerBits(),
                                            f.unsigned(),
                                            f.arrayLength(),
                                            f.isId(),
                                            f.nonInline(),
                                            f.relationStorage())))
                    .append('\n');
        }
        return Json.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
