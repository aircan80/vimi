package com.vimi.gui;

import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Small application-arbiter adapter; readiness and actual ownership drain stay separate. */
final class WorldStreamGpuOwner {
    private final Object owner = new Object();
    private final GpuPreviewService service;
    private final CompletableFuture<WorldStreamTransport.Session> opened = new CompletableFuture<>();

    WorldStreamGpuOwner(GpuPreviewService service, String scopeSha256) {
        this.service = Objects.requireNonNull(service);
        service.openStreaming(owner,
                new WorldStreamTransport.Spec(scopeSha256, 1, WorldStreamTransport.Backend.DEFAULT),
                opened::complete,
                status -> {
                    if (status.state() == GpuPreviewService.State.FAILED) {
                        opened.completeExceptionally(new IOException("GPU World startup unavailable"));
                    }
                }).whenComplete((ignored, failure) -> {
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
        var actualDrain = service.closeOwner(owner);
        opened.completeExceptionally(new CancellationException("GPU World owner stopping"));
        return actualDrain;
    }
}
