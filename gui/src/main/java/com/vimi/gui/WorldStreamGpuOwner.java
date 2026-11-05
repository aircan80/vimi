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
     * Static creature models at declared spawns ride in the same late tile objects, so they need
     * exteriors on; {@code -Dvimi.world.creatures=false} turns only the creatures off.
     */
    static final boolean CREATURES =
            EXTERIORS && exteriorsEnabled(System.getProperty("vimi.world.creatures"));

    /** Only {@code false} (any letter case) selects the terrain-only session profile. */
    static boolean exteriorsEnabled(String property) {
        return !"false".equalsIgnoreCase(property);
    }

    /** The streaming session this owner opens; its profile follows {@link #EXTERIORS}. */
    static WorldStreamTransport.Spec spec(String scopeSha256) {
        return new WorldStreamTransport.Spec(
                scopeSha256, 1, WorldStreamTransport.Backend.DEFAULT, EXTERIORS);
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
