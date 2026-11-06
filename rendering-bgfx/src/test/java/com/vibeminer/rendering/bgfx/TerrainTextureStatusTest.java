package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerrainTextureStatusTest {
    @Test
    void exactStatesRoundTripIncludingHonestPartialAndUnavailable() {
        for (var status :
                List.of(
                        TerrainTextureStatus.of(9, 9, 2304, TerrainTextureStatus.Code.NONE),
                        TerrainTextureStatus.of(1, 9, 1, TerrainTextureStatus.Code.NONE),
                        TerrainTextureStatus.of(2, 9, 512, TerrainTextureStatus.Code.UPLOAD),
                        TerrainTextureStatus.of(0, 9, 0, TerrainTextureStatus.Code.CHECKSUM))) {
            assertEquals(status, TerrainTextureStatus.parse(status.protocolLine()));
        }
    }

    @Test
    void incompleteNoncanonicalOrContradictoryAcknowledgementsCannotClaimSuccess() {
        for (var line :
                List.of(
                        "VIMI_GPU TEXTURES 2 READY 9 9 2304 2304 NONE",
                        "VIMI_GPU TEXTURES 1 READY 8 9 2304 2304 NONE",
                        "VIMI_GPU TEXTURES 1 READY 9 9 2304 2304 UPLOAD",
                        "VIMI_GPU TEXTURES 1 PARTIAL 0 9 0 2304 NONE",
                        "VIMI_GPU TEXTURES 1 UNAVAILABLE 0 9 1 2304 CHECKSUM",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 257 2304 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 01 9 1 2304 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL +1 9 1 2304 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 10 1 2560 NONE",
                        // One atlas can mark at most 255 rectangles as having nothing to draw.
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2048 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2305 NONE",
                        "VIMI_GPU TEXTURES 1 READY 1 1 240 256 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 1 241 240 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 GUESSED",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 NONE ",
                        " VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 NONE")) {
            assertThrows(
                    IllegalArgumentException.class, () -> TerrainTextureStatus.parse(line), line);
        }
    }

    @Test
    void rectanglesWithNothingToDrawLeaveACompleteSceneReady() {
        var edge = TerrainTextureStatus.of(1, 1, 240, 16, TerrainTextureStatus.Code.NONE);
        assertEquals(TerrainTextureStatus.State.READY, edge.state());
        assertEquals(240, edge.totalRects());
        assertEquals("VIMI_GPU TEXTURES 1 READY 1 1 240 240 NONE", edge.protocolLine());
        assertEquals(edge, TerrainTextureStatus.parse(edge.protocolLine()));
        var mixed = TerrainTextureStatus.of(2, 2, 400, 16, TerrainTextureStatus.Code.NONE);
        assertEquals(TerrainTextureStatus.State.PARTIAL, mixed.state());
        assertEquals(496, mixed.totalRects());
        assertEquals(mixed, TerrainTextureStatus.parse(mixed.protocolLine()));
    }
}
