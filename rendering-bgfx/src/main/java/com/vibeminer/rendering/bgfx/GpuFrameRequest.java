package com.vibeminer.rendering.bgfx;

/** Typed private control metadata shared only by the child readback transport. */
sealed interface GpuFrameRequest permits GpuViewRequest, GpuWorldRequest {
    long revision();

    int width();

    int height();
}
