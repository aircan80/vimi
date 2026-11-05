package com.vimi.gui;

import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Small application-arbiter adapter; readiness and actual ownership drain stay separate. */
final class WorldStreamGpuOwner {
    /**
     * BASIC_EXTERIOR_V1 outdoor buildings are on by default; {@code -Dvimi.world.exteriors=false}
     * turns them off. Their objects are delivered late, after terrain commits, and object
     * preparation runs only while terrain is idle (terrain preempts it), so terrain never waits
     * for buildings.
     */
    static final boolean EXTERIORS = exteriorsEnabled(System.getProperty("vimi.world.exteriors"));

    /** Only {@code false} (any letter case) selects the terrain-only session profile. */
    static boolean exteriorsEnabled(String property) {
        return !"false".equalsIgnoreCase(property);
    }

    /** The streaming session this owner opens; its profile follows {@link #EXTERIORS}. */
    static WorldStreamTransport.Spec spec(String scopeSha256) {
        return new WorldStreamTransport.Spec(
                scopeSha256, 1, configuredBackend(), EXTERIORS);
    }

    /**
     * Diagnostic override for comparing renderers on one machine, from the {@code
     * vimi.gpu.backend} system property or the {@code VIMI_GPU_BACKEND} environment variable.
     */
    static WorldStreamTransport.Backend configuredBackend() {
        String requested = System.getProperty("vimi.gpu.backend");
        return backend(requested != null ? requested : System.getenv("VIMI_GPU_BACKEND"));
    }

    static WorldStreamTransport.Backend backend(String requested) {
        if (requested == null || requested.isBlank()) return WorldStreamTransport.Backend.DEFAULT;
        return switch (requested.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "default" -> WorldStreamTransport.Backend.DEFAULT;
            case "vulkan" -> WorldStreamTransport.Backend.VULKAN;
            case "metal" -> WorldStreamTransport.Backend.METAL;
            case "d3d11", "direct3d11", "dx11" -> WorldStreamTransport.Backend.D3D11;
            default -> throw new IllegalArgumentException(
                    "vimi.gpu.backend must be default, vulkan, metal or d3d11");
        };
    }

    private final Object owner = new Object();
    private final GpuPreviewService service;
    private final CompletableFuture<WorldStreamTransport.Session> opened = new CompletableFuture<>();
    private final CompletableFuture<Void> lifetime;
    private CompletableFuture<Void> closing;

    WorldStreamGpuOwner(GpuPreviewService service, String scopeSha256) {
        this.service = Objects.requireNonNull(service);
        lifetime = service.openStreaming(owner,
                spec(scopeSha256),
                opened::complete,
                status -> {
                    if (status.state() == GpuPreviewService.State.FAILED) {
                        opened.completeExceptionally(new IOException("GPU World startup unavailable"));
                    }
                });
        lifetime.whenComplete((ignored, failure) -> {
                    // A queued owner can retire before any process exists. Never manufacture a
                    // child drain receipt or leave the controller waiting forever for its Session.
                    if (failure != null) {
                        opened.completeExceptionally(new IOException("GPU World owner ended"));
                    } else {
                        opened.completeExceptionally(new CancellationException("GPU World owner closed"));
                    }
                });
    }

    CompletableFuture<WorldStreamTransport.Session> session() {
        return opened.copy();
    }

    CompletableFuture<Void> closeAsync() {
        CompletableFuture<Void> result;
        synchronized (this) {
            if (closing != null) return closing.copy();
            result = new CompletableFuture<>();
            closing = result;
        }
        // Keep the original request outcome even if the service has forgotten this owner.
        // Repeated close must never turn unknown/failed retirement into a positive empty lookup.
        var actualDrain = service.closeOwner(owner);
        opened.completeExceptionally(new CancellationException("GPU World owner stopping"));
        CompletableFuture.allOf(lifetime, actualDrain).whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(null);
            else result.completeExceptionally(failure);
        });
        return result.copy();
    }
}
