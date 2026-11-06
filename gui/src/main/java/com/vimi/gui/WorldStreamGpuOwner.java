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

    /**
     * Resident neighborhood (issue #173). NEAR (3 x 3) is the default, safe on integrated GPUs;
     * {@code -Dvimi.world.viewDistance=far} opts into the 5 x 5 neighborhood, which needs about
     * 2.6 times the terrain GPU memory. A settings control is a follow-up.
     */
    static final WorldStreamTransport.ViewDistance VIEW_DISTANCE =
            viewDistance(System.getProperty("vimi.world.viewDistance"));

    /** Only {@code far} (any letter case) selects FAR; anything else, or nothing, is NEAR. */
    static WorldStreamTransport.ViewDistance viewDistance(String property) {
        return "far".equalsIgnoreCase(property == null ? null : property.strip())
                ? WorldStreamTransport.ViewDistance.FAR
                : WorldStreamTransport.ViewDistance.NEAR;
    }

    /**
     * Static creature models at declared spawns ride in the same late tile objects, so they need
     * exteriors on. Opt-in until measured on real data: only {@code -Dvimi.world.creatures=true}
     * turns them on.
     */
    static final boolean CREATURES =
            EXTERIORS && creaturesEnabled(System.getProperty("vimi.world.creatures"));

    /**
     * Object texture budget tier (#267): STANDARD by default, the original per-tile pixel budgets;
     * {@code -Dvimi.world.objectTextures=high} starts with HIGH, twice the pixels per tile. The
     * World view can switch it at runtime.
     */
    static final com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail IMAGE_DETAIL =
            imageDetail(System.getProperty("vimi.world.objectTextures"));

    /** Only {@code high} (any letter case, surrounding whitespace ignored) selects HIGH. */
    static com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail imageDetail(
            String property) {
        return property != null && "high".equalsIgnoreCase(property.strip())
                ? com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH
                : com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.STANDARD;
    }

    /**
     * WMO vertex colours (MOCV, #375) added to the building light. Off by default until compared
     * with the client: only {@code -Dvimi.world.wmoVertexColours=true} turns them on. The colours
     * are always written; the World helper, which draws them, receives the switch on its command
     * line ({@link GpuPreviewService}).
     */
    static final boolean WMO_VERTEX_COLOURS =
            vertexColoursEnabled(System.getProperty("vimi.world.wmoVertexColours"));

    /** Only {@code true} (any letter case) turns WMO vertex colours on. */
    static boolean vertexColoursEnabled(String property) {
        return "true".equalsIgnoreCase(property);
    }

    /** Only {@code true} (any letter case) turns creature models on. */
    static boolean creaturesEnabled(String property) {
        return "true".equalsIgnoreCase(property);
    }

    /** Only {@code false} (any letter case) selects the terrain-only session profile. */
    static boolean exteriorsEnabled(String property) {
        return !"false".equalsIgnoreCase(property);
    }

    /** The streaming session this owner opens; its profile follows {@link #EXTERIORS}. */
    static WorldStreamTransport.Spec spec(String scopeSha256) {
        return spec(scopeSha256, IMAGE_DETAIL);
    }

    /** The session follows the runtime object texture choice, which selects its objects budget. */
    static WorldStreamTransport.Spec spec(
            String scopeSha256, com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail detail) {
        return new WorldStreamTransport.Spec(
                scopeSha256, 1, configuredBackend(), EXTERIORS, VIEW_DISTANCE, objectTextures(detail));
    }

    /** HIGH image detail selects the HIGH objects budget; anything else is STANDARD. */
    static WorldStreamTransport.ObjectTextures objectTextures(
            com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail detail) {
        return detail == com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH
                ? WorldStreamTransport.ObjectTextures.HIGH
                : WorldStreamTransport.ObjectTextures.STANDARD;
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
                    "vimi.gpu.backend '" + requested.strip()
                            + "' is not a GPU backend: use default, vulkan, metal or d3d11");
        };
    }

    private final Object owner = new Object();
    private final GpuPreviewService service;
    private final CompletableFuture<WorldStreamTransport.Session> opened = new CompletableFuture<>();
    private final CompletableFuture<Void> lifetime;
    private CompletableFuture<Void> closing;

    WorldStreamGpuOwner(GpuPreviewService service, String scopeSha256) {
        this(service, scopeSha256, IMAGE_DETAIL);
    }

    WorldStreamGpuOwner(GpuPreviewService service, String scopeSha256,
            com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail detail) {
        this.service = Objects.requireNonNull(service);
        lifetime = service.openStreaming(owner,
                spec(scopeSha256, detail),
                opened::complete,
                status -> {
                    if (status.state() == GpuPreviewService.State.FAILED) {
                        opened.completeExceptionally(new IOException(
                                "GPU World startup unavailable: " + status.message()));
                    }
                });
        lifetime.whenComplete((ignored, failure) -> {
                    // A queued owner can retire before any process exists. Never manufacture a
                    // child drain receipt or leave the controller waiting forever for its Session.
                    if (failure != null) {
                        opened.completeExceptionally(new IOException("GPU World owner ended", failure));
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
