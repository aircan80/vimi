package com.vibeminer.rendering.bgfx;

import java.io.IOException;

/**
 * The GPU or backend lacks something the interior renderer needs (a hardware context, reversed D32F
 * depth, RGBA8/BGRA8 targets, readback or 32-bit indices). The child reports it as {@code
 * GLOBAL_UNAVAILABLE CAPABILITY} and exits cleanly; nothing about the scene is wrong.
 */
final class GlobalWmoCapabilityException extends IOException {
    private static final long serialVersionUID = 1L;

    GlobalWmoCapabilityException(String message) {
        super(message);
    }
}
