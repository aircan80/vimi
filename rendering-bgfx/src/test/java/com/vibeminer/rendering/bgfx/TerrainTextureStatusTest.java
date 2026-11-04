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
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2303 NONE",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 GUESSED",
                        "VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 NONE ",
                        " VIMI_GPU TEXTURES 1 PARTIAL 1 9 1 2304 NONE")) {
            assertThrows(
                    IllegalArgumentException.class, () -> TerrainTextureStatus.parse(line), line);
        }
    }
}
