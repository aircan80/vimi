package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The bgfx D3D11 runtime binds per-instance data i_dataN to TEXCOORD(31-N)
 * (BGFX_CONFIG_INSTANCE_DATA_FIRST_TEXCOORD). Shaders compiled with the older TEXCOORD7-N
 * convention still load, but D3D11 then feeds those inputs from the per-vertex stream, so every
 * instanced terrain triangle collapses and only the sky remains visible.
 */
final class Dx11InstanceInputSignatureTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/com/vibeminer/rendering/bgfx/shaders/terrain/dx11/vs_model.bin",
                "/com/vibeminer/rendering/bgfx/terrain-spike/dx11/vs_model.bin",
                "/com/vibeminer/rendering/bgfx/world-stream-spike/dx11/vs_model.bin"
            })
    void instancedTerrainVertexShadersReadInstanceDataFromBgfxD3dSemantics(String resource)
            throws IOException {
        Set<String> inputs = dx11VertexInputs(resource);
        assertEquals(
                Set.of("POSITION0", "TEXCOORD31", "TEXCOORD30", "TEXCOORD29"),
                inputs,
                "D3D11 vertex inputs for " + resource);
    }

    /** The production World stream terrain is an indexed mesh (issue #373): no instance data. */
    @org.junit.jupiter.api.Test
    void worldStreamTerrainReadsPerVertexPositionAndColour() throws IOException {
        assertEquals(
                Set.of("POSITION0", "COLOR0"),
                dx11VertexInputs(
                        "/com/vibeminer/rendering/bgfx/shaders/world-stream/dx11/vs_model.bin"));
    }

    /** The World object shader reads per-instance transforms (#382) on bgfx's semantics. */
    @org.junit.jupiter.api.Test
    void worldObjectsReadInstanceTransformsFromBgfxD3dSemantics() throws IOException {
        assertEquals(
                Set.of(
                        "POSITION0",
                        "NORMAL0",
                        "TEXCOORD0",
                        "TEXCOORD31",
                        "TEXCOORD30",
                        "TEXCOORD29"),
                dx11VertexInputs(
                        "/com/vibeminer/rendering/bgfx/shaders/world-wmo/dx11/vs_model.bin"));
    }

    static Set<String> dx11VertexInputs(String resource) throws IOException {
        byte[] bytes;
        try (var input = Dx11InstanceInputSignatureTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "Missing packaged DX11 shader " + resource);
            bytes = input.readAllBytes();
        }
        assertTrue(bytes.length > 4 && bytes[0] == 'V' && bytes[1] == 'S' && bytes[2] == 'H');
        int container = indexOf(bytes, "DXBC".getBytes(StandardCharsets.US_ASCII));
        assertTrue(container > 0, "bgfx DX11 shader must embed a DXBC container");
        var dxbc =
                ByteBuffer.wrap(bytes, container, bytes.length - container)
                        .slice()
                        .order(ByteOrder.LITTLE_ENDIAN);
        int chunks = dxbc.getInt(28);
        for (int i = 0; i < chunks; i++) {
            int offset = dxbc.getInt(32 + 4 * i);
            if (dxbc.getInt(offset) == fourCc("ISGN")) {
                int base = offset + 8;
                int count = dxbc.getInt(base);
                var result = new LinkedHashSet<String>();
                for (int element = 0; element < count; element++) {
                    int at = base + 8 + 24 * element;
                    result.add(cString(dxbc, base + dxbc.getInt(at)) + dxbc.getInt(at + 4));
                }
                return result;
            }
        }
        throw new AssertionError("DX11 vertex shader lacks an input signature: " + resource);
    }

    private static int fourCc(String tag) {
        byte[] b = tag.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    private static String cString(ByteBuffer buffer, int at) {
        var text = new StringBuilder();
        for (int i = at; buffer.get(i) != 0; i++) {
            text.append((char) buffer.get(i));
        }
        return text.toString();
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
