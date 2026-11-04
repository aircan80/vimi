package com.vibeminer.rendering.bgfx;

import java.util.Objects;

/** Actual immutable draw metadata, separate from terrain/model controls. */
record GlobalWmoFrameRequest(long revision, GlobalWmoCamera camera, int width, int height) {
    GlobalWmoFrameRequest {
        if (revision < 1 || width < 1 || width > 800 || height < 1 || height > 500) {
            throw new IllegalArgumentException("Invalid global frame request");
        }
        Objects.requireNonNull(camera);
    }
}
