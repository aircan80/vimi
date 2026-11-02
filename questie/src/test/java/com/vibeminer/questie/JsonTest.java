package com.vibeminer.questie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.questie.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void preservesMetadataAndRejectsAmbiguousOrUnboundedInputs() {
        var value = Map.of("name", "Original's ✓", "array", List.of(1L, true, "nested"));
        var bytes = Json.bytes(value);
        assertEquals(value, Json.parse(bytes, bytes.length));
        assertThrows(IllegalArgumentException.class, () -> Json.parse(bytes, bytes.length - 1));
        for (String invalid : List.of("{\"id\":1,\"id\":2}", "{} []", "[1,]", "{", "NaN")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Json.parse(invalid.getBytes(StandardCharsets.UTF_8), 1024));
        }
        assertThrows(IllegalArgumentException.class, () -> Json.bytes(Double.NaN));
    }
}
