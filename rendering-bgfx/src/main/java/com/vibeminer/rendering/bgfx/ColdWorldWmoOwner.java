package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Exactly one attempted exterior per enclosing cold native context, including failed prefixes. */
final class ColdWorldWmoOwner implements AutoCloseable {
    /**
     * The proposal's additional cold WMO envelope. Its three native mesh rows (GPU, queued and
     * staging) are 21,504 bytes each since #375 widened the world-wmo vertex to 36 bytes.
     */
    static final long CHARGE_BYTES = 6_181_888;

    private enum Phase {
        NEW,
        ATTEMPTED,
        CLOSED,
        CONTEXT_DRAINED
    }

    private final Thread owner = Thread.currentThread();
    private final Consumer<WorldWmoStatus> listener;
    private Phase phase = Phase.NEW;
    private long charged;
    private BgfxWorldWmoPass pass;
    private WorldWmoStatus status;

    ColdWorldWmoOwner(Consumer<WorldWmoStatus> listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    void attempt(Path annex, GpuTerrainSceneFile.Decoded base, BooleanSupplier canceled) {
        requireOwner();
        if (phase != Phase.NEW) {
            throw new IllegalStateException("Cold WMO context attempt already spent");
        }
        phase = Phase.ATTEMPTED;
        charged = CHARGE_BYTES;
        emit(new WorldWmoStatus(WorldWmoStatus.State.LOADING, WorldWmoStatus.Code.NONE, 0, 0));
        try {
            pass =
                    BgfxWorldWmoPass.readBound(
                            annex,
                            base,
                            canceled,
                            count ->
                                    emit(
                                            new WorldWmoStatus(
                                                    WorldWmoStatus.State.LOADING,
                                                    WorldWmoStatus.Code.NONE,
                                                    count,
                                                    0)));
            emit(
                    new WorldWmoStatus(
                            WorldWmoStatus.State.INITIALIZED,
                            WorldWmoStatus.Code.NONE,
                            pass.imageCount(),
                            pass.batchCount()));
        } catch (IOException unavailable) {
            // Input/pairing and expected native initialization failures share one honest fixed
            // code.
            emit(
                    new WorldWmoStatus(
                            WorldWmoStatus.State.UNAVAILABLE, WorldWmoStatus.Code.LOAD, 0, 0));
        }
    }

    void draw(TerrainCamera camera, int width, int height) {
        requireOwner();
        if (phase != Phase.ATTEMPTED) {
            throw new IllegalStateException("Cold WMO owner not active");
        }
        if (pass != null) {
            pass.draw(camera, width, height);
        }
    }

    void publishedFirstFrame() {
        requireOwner();
        if (phase == Phase.ATTEMPTED && status.state() == WorldWmoStatus.State.INITIALIZED) {
            emit(
                    new WorldWmoStatus(
                            WorldWmoStatus.State.READY,
                            WorldWmoStatus.Code.NONE,
                            status.images(),
                            status.batches()));
        }
    }

    private void emit(WorldWmoStatus next) {
        status = next;
        listener.accept(next);
    }

    WorldWmoStatus status() {
        return status;
    }

    long chargedBytes() {
        return charged;
    }

    @Override
    public void close() {
        requireOwner();
        if (phase == Phase.CLOSED || phase == Phase.CONTEXT_DRAINED) {
            return;
        }
        phase = Phase.CLOSED;
        if (pass != null) {
            pass.close();
            pass = null;
        }
        // Charge survives close and expected failures until actual enclosing bgfx shutdown returns.
    }

    void afterContextShutdown() {
        requireOwner();
        if (phase != Phase.CLOSED && phase != Phase.CONTEXT_DRAINED) {
            throw new IllegalStateException("Cold WMO owners must close before context drain");
        }
        phase = Phase.CONTEXT_DRAINED;
        charged = 0;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Cold WMO belongs to native owner");
        }
    }
}
