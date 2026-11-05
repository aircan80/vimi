package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The renderer's objects stderr diagnostics: their wording and their rate limit. */
class BgfxWorldStreamRendererDiagnosticsTest {
    @Test
    void reasonNamesTheShaderFamilyProfileAndStep() {
        assertEquals(
                "World exterior objects build failed (world-wmo/spirv, textures):"
                        + " java.io.IOException: Native resource allocation failed",
                BgfxWorldStreamRenderer.objectsReason(
                        "build failed",
                        "world-wmo/spirv",
                        "textures",
                        new IOException("Native resource allocation failed")));
    }

    @Test
    void eachDistinctBuildFailureIsWrittenOnceThenEvery64th() {
        var log = new BgfxWorldStreamRenderer.ObjectsFailureLog();
        var allocation = new IOException("Native resource allocation failed");
        assertEquals(1, log.admit("textures", allocation));
        assertEquals(0, log.admit("textures", new IOException(allocation.getMessage())));
        // The same exception text at another step, or another type, is a distinct reason.
        assertEquals(3, log.admit("buffers", allocation));
        assertEquals(4, log.admit("textures", new IllegalStateException("x")));
        for (long count = 5; count < 64; count++) {
            assertEquals(0, log.admit("textures", allocation), "count " + count);
        }
        assertEquals(64, log.admit("textures", allocation));
        assertEquals(0, log.admit("textures", allocation));
    }

    @Test
    void distinctReasonsAreBounded() {
        var log = new BgfxWorldStreamRenderer.ObjectsFailureLog();
        int written = 0;
        for (int i = 0; i < 63; i++) {
            if (log.admit("textures", new IOException("reason " + i)) > 0) {
                written++;
            }
        }
        assertEquals(BgfxWorldStreamRenderer.ObjectsFailureLog.DISTINCT, written);
        assertEquals(64, log.admit("textures", new IOException("reason 63")));
    }
}
