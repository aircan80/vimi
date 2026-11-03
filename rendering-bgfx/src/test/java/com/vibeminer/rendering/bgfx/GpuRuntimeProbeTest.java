package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_NOOP;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_OPENGL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;

import java.util.List;
import org.junit.jupiter.api.Test;

class GpuRuntimeProbeTest {
    @Test
    void requiresTheBackendUsedByEachSupportedOperatingSystem() {
        assertDoesNotThrow(
                () -> GpuRuntimeProbe.verifyBackends("Linux", List.of(BGFX_RENDERER_TYPE_VULKAN)));
        assertDoesNotThrow(
                () -> GpuRuntimeProbe.verifyBackends("Linux", List.of(BGFX_RENDERER_TYPE_OPENGL)));
        assertDoesNotThrow(
                () ->
                        GpuRuntimeProbe.verifyBackends(
                                "Mac OS X", List.of(BGFX_RENDERER_TYPE_METAL)));
        assertDoesNotThrow(
                () ->
                        GpuRuntimeProbe.verifyBackends(
                                "Windows 11", List.of(BGFX_RENDERER_TYPE_DIRECT3D11)));
        for (String os : List.of("Linux", "Mac OS X", "Windows 11"))
            assertThrows(
                    IllegalStateException.class,
                    () -> GpuRuntimeProbe.verifyBackends(os, List.of(BGFX_RENDERER_TYPE_NOOP)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        GpuRuntimeProbe.verifyBackends(
                                "Unknown", List.of(BGFX_RENDERER_TYPE_VULKAN)));
    }
}
