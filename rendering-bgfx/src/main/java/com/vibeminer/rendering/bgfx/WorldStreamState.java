package com.vibeminer.rendering.bgfx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure owner-thread metadata model for the proposed World v2 mode; no renderer is wired to it. */
final class WorldStreamState {
    static final int SLOTS = 10, MAX_ACTIVE = 9, MAX_REFERENCES = 48;

    record Nonce(long high, long low) {
        Nonce {
            if ((high | low) == 0) {
                throw new IllegalArgumentException("Zero session nonce");
            }
        }
    }

    record Lease(int slot, long generation, long serial) {
        Lease {
            if (slot < 0 || slot >= SLOTS || generation <= 0 || serial <= 0) {
                throw new IllegalArgumentException("Invalid slot lease");
            }
        }
    }

    enum Phase {
        CREDIT,
        READING,
        UPLOADING,
        STAGED,
        COMMITTED_DRAIN,
        CANCEL_DRAIN,
        CLOSED
    }

    enum Result {
        OK,
        WRONG_SESSION,
        STALE,
        BAD_LEASE,
        BUSY,
        INVALID_TABLE,
        REVISION_MISMATCH,
        CLOSED
    }

    private final Nonce nonce;
    private final long[] generations = new long[SLOTS];
    private final int[] coordinates = new int[SLOTS];
    private List<Lease> active = List.of();
    private Lease incoming;
    private Phase phase;
    private long serial, highWater, offerTransaction, contentRevision;
    private boolean decoderOwned, inputConsumed, stopping;
    private int references;

    WorldStreamState(Nonce nonce) {
        this.nonce = Objects.requireNonNull(nonce);
        java.util.Arrays.fill(coordinates, -1);
        grant();
    }

    Lease credit() {
        return phase == Phase.CREDIT && !stopping ? incoming : null;
    }

    Phase phase() {
        return phase;
    }

    List<Lease> active() {
        return active;
    }

    long contentRevision() {
        return contentRevision;
    }

    long highWater() {
        return highWater;
    }

    int references() {
        return references;
    }

    boolean inputConsumed() {
        return inputConsumed;
    }

    boolean drained() {
        return phase == Phase.CLOSED;
    }

    Result offer(Nonce session, long tx, Lease lease, int tileX, int tileY) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (phase != Phase.CREDIT) {
            return Result.BUSY;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (tileX < 0 || tileX > 63 || tileY < 0 || tileY > 63) {
            return Result.INVALID_TABLE;
        }
        offerTransaction = tx;
        coordinates[lease.slot()] = tileY * 64 + tileX;
        decoderOwned = true;
        inputConsumed = false;
        phase = Phase.READING;
        return Result.OK;
    }

    // Invoked only after decoder closes the actual input and drops all raw/output aliases.
    Result decoded(Lease lease, boolean valid) {
        if (!Objects.equals(incoming, lease) || !decoderOwned) {
            return Result.BAD_LEASE;
        }
        decoderOwned = false;
        inputConsumed = true;
        if (stopping || phase == Phase.CANCEL_DRAIN || !valid) {
            phase = Phase.CANCEL_DRAIN;
            retireIfDrained();
        } else {
            phase = Phase.UPLOADING;
        }
        return Result.OK;
    }

    Result submittedReference(Lease lease) {
        if (stopping) {
            return Result.CLOSED;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (phase != Phase.UPLOADING || references == MAX_REFERENCES) {
            return Result.BUSY;
        }
        references++;
        return Result.OK;
    }

    // Owner consumes bounded callback credits; callbacks themselves never mutate this model.
    Result consumedReference(Lease lease) {
        if (!Objects.equals(incoming, lease) || references == 0) {
            return Result.BAD_LEASE;
        }
        references--;
        retireIfDrained();
        return Result.OK;
    }

    Result staged(Lease lease) {
        if (stopping) {
            return Result.CLOSED;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (phase != Phase.UPLOADING) {
            return Result.BUSY;
        }
        phase = Phase.STAGED;
        return Result.OK;
    }

    Result commit(Nonce session, long tx, long expected, long next, List<Lease> table) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (expected != contentRevision || expected == Long.MAX_VALUE || next != expected + 1) {
            return Result.REVISION_MISMATCH;
        }
        if (table == null || table.size() > MAX_ACTIVE) {
            return Result.INVALID_TABLE;
        }
        var seen = new HashSet<Lease>();
        var seenCoordinates = new HashSet<Integer>();
        boolean includesIncoming = false;
        for (var member : table) {
            if (member == null || !seen.add(member)) {
                return Result.INVALID_TABLE;
            }
            boolean staged = phase == Phase.STAGED && member.equals(incoming);
            if (!active.contains(member) && !staged) {
                return Result.INVALID_TABLE;
            }
            if (!seenCoordinates.add(coordinates[member.slot()])) {
                return Result.INVALID_TABLE;
            }
            includesIncoming |= staged;
        }
        // Retained order is explicitly the caller's table, never slot index or arrival order.
        active = List.copyOf(table);
        contentRevision = next;
        if (includesIncoming) {
            phase = Phase.COMMITTED_DRAIN;
            retireIfDrained();
        }
        return Result.OK;
    }

    Result cancel(Nonce session, long establishedOffer, Lease lease) {
        var result = session(session);
        if (result != Result.OK) {
            return result;
        }
        if (!Objects.equals(incoming, lease)
                || establishedOffer != offerTransaction
                || establishedOffer <= 0) {
            return Result.STALE;
        }
        if (phase == Phase.CANCEL_DRAIN) {
            return Result.OK;
        }
        if (phase != Phase.READING && phase != Phase.UPLOADING && phase != Phase.STAGED) {
            return Result.STALE;
        }
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
        return Result.OK;
    }

    // Parent contract: its unpublished writer has drained before this command is sent.
    Result cancelUnused(Nonce session, long tx, Lease lease) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (phase != Phase.CREDIT || !Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
        return Result.OK;
    }

    void close() {
        stopping = true;
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
    }

    private Result session(Nonce session) {
        if (stopping) {
            return Result.CLOSED;
        }
        return nonce.equals(session) ? Result.OK : Result.WRONG_SESSION;
    }

    private Result newTransaction(Nonce session, long tx) {
        var result = session(session);
        if (result != Result.OK) {
            return result;
        }
        if (tx <= highWater || tx <= 0) {
            return Result.STALE;
        }
        highWater = tx;
        return Result.OK;
    }

    private void retireIfDrained() {
        if (decoderOwned || references != 0) {
            return;
        }
        if (stopping) {
            active = List.of();
            incoming = null;
            phase = Phase.CLOSED;
        } else if (phase == Phase.CANCEL_DRAIN || phase == Phase.COMMITTED_DRAIN) {
            incoming = null;
            grant();
        }
    }

    private void grant() {
        if (serial == Long.MAX_VALUE) {
            throw new IllegalStateException("Session credit exhausted");
        }
        int free = 0;
        while (free < SLOTS) {
            int slot = free;
            if (active.stream().noneMatch(member -> member.slot() == slot)) {
                break;
            }
            free++;
        }
        if (free == SLOTS || generations[free] == Long.MAX_VALUE) {
            throw new IllegalStateException("Session slot exhausted");
        }
        incoming = new Lease(free, ++generations[free], ++serial);
        offerTransaction = 0;
        inputConsumed = false;
        phase = Phase.CREDIT;
    }
}
