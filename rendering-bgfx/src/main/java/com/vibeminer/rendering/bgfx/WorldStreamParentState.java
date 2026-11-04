package com.vibeminer.rendering.bgfx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Fixed-size parent operation metadata. No source, file, callback, process or pixel owners. */
final class WorldStreamParentState {
    static final class Ticket {
        final Object owner;
        final WorldStreamState.Lease lease;
        final long transaction;
        final int x, y;
        boolean producerStarted, producerDrained, offered, inputConsumed, fileRetired = true;
        boolean cancelRequested, cancelSent, unusedSent, canceled, rejected, committed, credited;
        int outcome, coverage;
        Resident resident;

        private Ticket(Object owner, WorldStreamState.Lease lease, long transaction, int x, int y) {
            this.owner = owner;
            this.lease = lease;
            this.transaction = transaction;
            this.x = x;
            this.y = y;
        }
    }

    static final class Resident {
        final Object owner;
        final WorldStreamState.Lease lease;
        final int x, y, outcome, coverage;

        private Resident(Ticket ticket) {
            owner = ticket.owner;
            lease = ticket.lease;
            x = ticket.x;
            y = ticket.y;
            outcome = ticket.outcome;
            coverage = ticket.coverage;
        }
    }

    static final class Commit {
        final long transaction, expected, next, policy;
        final List<Resident> ordered;
        boolean inputConsumed, terminal, accepted, fileRetired;

        private Commit(long transaction, long expected, long policy, List<Resident> ordered) {
            this.transaction = transaction;
            this.expected = expected;
            next = expected + 1;
            this.policy = policy;
            this.ordered = List.copyOf(ordered);
        }
    }

    enum Change {
        IGNORED,
        SESSION,
        CREDIT,
        CAMERA,
        INPUT_TILE,
        INPUT_COMMIT,
        STAGED,
        COMMITTED,
        REJECTED,
        CANCELED,
        CLOSED
    }

    private final Object owner = new Object();
    private final WorldStreamEnvelope.Session session;
    private final long[] generations = new long[10];
    private WorldStreamState.Lease credit;
    private long serial, transaction, content;
    private boolean hello, closed, childGone;
    private Ticket ticket;
    private Commit commit;
    private List<Resident> active = List.of();

    WorldStreamParentState(WorldStreamEnvelope.Session session) {
        this.session = Objects.requireNonNull(session);
    }

    synchronized Ticket reserve(int x, int y) {
        if (x < 0 || x > 63 || y < 0 || y > 63) {
            throw new IllegalArgumentException("Invalid tile");
        }
        if (closed
                || !hello
                || credit == null
                || ticket != null && (!ticket.credited || !ticket.fileRetired)
                || commit != null && (!commit.terminal || !commit.fileRetired)) {
            return null;
        }
        ticket = new Ticket(owner, credit, nextTransaction(), x, y);
        credit = null;
        return ticket;
    }

    synchronized void beginProducer(Ticket value) {
        requireTicket(value);
        require(
                !value.producerStarted && !value.producerDrained && !value.cancelRequested,
                "Producer start");
        value.producerStarted = true;
        value.fileRetired = false; // Destination registered BEFORE any writer runs.
    }

    /** Includes the actual producer finally AND any scheduled cancellation callback finally. */
    synchronized void producerDrained(Ticket value) {
        requireIdentity(value);
        require(value.producerStarted && !value.producerDrained, "Producer drain");
        value.producerDrained = true;
    }

    synchronized WorldStreamControl.Offer offer(
            Ticket value, int length, String sha, int outcome, int coverage) {
        requireTicket(value);
        require(
                value.producerDrained
                        && !value.cancelRequested
                        && !value.offered
                        && !value.fileRetired,
                "Offer publication");
        require(
                outcome == 1 && coverage >= 1 && coverage <= 256 || outcome == 2 && coverage == 0,
                "Offer appearance");
        var command =
                new WorldStreamControl.Offer(
                        session.nonce(), value.transaction, value.lease, length, sha);
        value.outcome = outcome;
        value.coverage = coverage;
        value.offered = true;
        return command;
    }

    synchronized WorldStreamControl.Command cancel(Ticket value) {
        require(value != null && value.owner == owner, "Foreign tile reservation");
        if (closed || value.credited) {
            return null;
        }
        requireTicket(value);
        value.cancelRequested = true;
        if (value.credited
                || value.canceled
                || value.rejected
                || value.committed
                || value.cancelSent
                || value.unusedSent) {
            return null;
        }
        if (value.offered) {
            value.cancelSent = true;
            return new WorldStreamControl.Cancel(
                    session.nonce(),
                    value.transaction,
                    value.lease.slot(),
                    value.lease.generation());
        }
        if (!value.producerStarted) {
            value.producerDrained = true;
        }
        if (value.producerDrained && value.fileRetired) {
            value.unusedSent = true;
            return new WorldStreamControl.CancelUnused(
                    session.nonce(), value.transaction, value.lease);
        }
        return null;
    }

    synchronized void retireTileFile(Ticket value) {
        requireIdentity(value);
        require(
                !value.fileRetired
                        && (value.offered
                                ? value.inputConsumed || childGone
                                : value.producerDrained),
                "Tile input retirement");
        value.fileRetired = true;
    }

    synchronized Commit prepareCommit(long policy, List<Resident> ordered) {
        Objects.requireNonNull(ordered);
        if (closed
                || !hello
                || commit != null && (!commit.terminal || !commit.fileRetired)
                || ticket != null && !ticket.offered && !ticket.unusedSent && !ticket.credited) {
            return null;
        }
        int count = ordered.size();
        require(
                policy >= session.policy() && content < Long.MAX_VALUE && count <= 9 && count >= 0,
                "Commit bounds");
        var snapshot = new Resident[count];
        for (int i = 0; i < count; i++) {
            snapshot[i] = ordered.get(i);
        }
        var stable = java.util.Arrays.asList(snapshot);
        var slots = new HashSet<Integer>();
        var coordinates = new HashSet<Integer>();
        for (var member : stable) {
            require(
                    member != null
                            && member.owner == owner
                            && generations[member.lease.slot()] == member.lease.generation()
                            && (active.contains(member)
                                    || ticket != null
                                            && ticket.resident == member
                                            && !ticket.cancelRequested
                                            && !ticket.canceled
                                            && !ticket.rejected
                                            && !ticket.credited)
                            && slots.add(member.lease.slot())
                            && coordinates.add(member.y * 64 + member.x),
                    "Commit membership");
        }
        commit = new Commit(nextTransaction(), content, policy, stable);
        return commit;
    }

    synchronized void retireCommitFile(Commit value) {
        require(
                value == commit && (value.inputConsumed || childGone) && !value.fileRetired,
                "Commit file retirement");
        value.fileRetired = true;
    }

    synchronized Change status(WorldStreamStatus.Message message) {
        Objects.requireNonNull(message);
        if (!session.nonce().equals(message.nonce())) {
            return Change.IGNORED;
        }
        require(!closed, "Status after terminal");
        if (message instanceof WorldStreamStatus.Session) {
            require(!hello, "Duplicate session");
            hello = true;
            return Change.SESSION;
        }
        require(hello, "Status before session");
        if (message instanceof WorldStreamStatus.Credit value) {
            require(
                    credit == null
                            && value.lease().serial() == serial + 1
                            && value.lease().generation() == generations[value.lease().slot()] + 1,
                    "Credit order");
            int free = 0;
            while (free < 10) {
                int slot = free;
                if (active.stream().noneMatch(member -> member.lease.slot() == slot)) {
                    break;
                }
                free++;
            }
            require(value.lease().slot() == free, "Credit active slot");
            if (ticket != null) {
                require(
                        ticket.producerDrained
                                && (ticket.offered ? ticket.inputConsumed : ticket.fileRetired)
                                && (ticket.committed || ticket.canceled || ticket.rejected),
                        "Credit before old owners drain");
                ticket.credited = true;
            }
            credit = value.lease();
            serial = credit.serial();
            generations[credit.slot()] = credit.generation();
            return Change.CREDIT;
        }
        if (message instanceof WorldStreamStatus.CameraApplied) {
            return Change.CAMERA;
        }
        if (message instanceof WorldStreamStatus.InputConsumed value) {
            if (value.kind() == WorldStreamStatus.InputKind.TILE) {
                require(
                        ticket != null
                                && ticket.offered
                                && ticket.transaction == value.transaction()
                                && !ticket.inputConsumed,
                        "Unknown/duplicate tile input");
                ticket.inputConsumed = true;
                return Change.INPUT_TILE;
            }
            require(
                    commit != null
                            && commit.transaction == value.transaction()
                            && !commit.inputConsumed,
                    "Unknown/duplicate commit input");
            commit.inputConsumed = true;
            return Change.INPUT_COMMIT;
        }
        if (message instanceof WorldStreamStatus.Staged value) {
            require(
                    ticket != null
                            && ticket.transaction == value.transaction()
                            && ticket.lease.equals(value.lease())
                            && ticket.offered
                            && ticket.inputConsumed
                            && ticket.resident == null
                            && !ticket.rejected
                            && !ticket.canceled
                            && ticket.outcome == value.outcome()
                            && ticket.coverage == value.coverage(),
                    "Unmatched staged payload");
            ticket.resident = new Resident(ticket);
            return Change.STAGED;
        }
        if (message instanceof WorldStreamStatus.Committed value) {
            require(
                    commit != null
                            && !commit.terminal
                            && commit.inputConsumed
                            && commit.transaction == value.transaction()
                            && commit.expected == value.expected()
                            && commit.next == value.next(),
                    "Unmatched committed table");
            active = commit.ordered;
            content = commit.next;
            commit.terminal = commit.accepted = true;
            if (ticket != null && ticket.resident != null && active.contains(ticket.resident)) {
                ticket.committed = true;
            }
            return Change.COMMITTED;
        }
        if (message instanceof WorldStreamStatus.Rejected value) {
            require(value.transaction() != 0, "Session rejected");
            if (ticket != null
                    && ticket.transaction == value.transaction()
                    && ticket.offered
                    && ticket.inputConsumed
                    && !ticket.rejected
                    && ticket.resident == null
                    && value.reason() == WorldStreamStatus.Rejection.INVALID_PACKET) {
                ticket.rejected = true;
                return Change.REJECTED;
            }
            require(
                    commit != null
                            && !commit.terminal
                            && commit.transaction == value.transaction()
                            && commit.inputConsumed
                            && (value.reason() == WorldStreamStatus.Rejection.INVALID_COMMIT
                                    || value.reason() == WorldStreamStatus.Rejection.INVALID_TABLE
                                    || value.reason()
                                            == WorldStreamStatus.Rejection.REVISION_MISMATCH),
                    "Unmatched/fatal rejection");
            commit.terminal = true;
            return Change.REJECTED;
        }
        if (message instanceof WorldStreamStatus.Canceled value) {
            require(
                    ticket != null
                            && ticket.transaction == value.transaction()
                            && ticket.lease.equals(value.lease())
                            && !ticket.canceled
                            && (ticket.cancelSent && ticket.inputConsumed
                                    || ticket.unusedSent
                                            && ticket.producerDrained
                                            && ticket.fileRetired),
                    "Unmatched cancellation");
            ticket.canceled = true;
            return Change.CANCELED;
        }
        require(message instanceof WorldStreamStatus.Closed, "Unknown status");
        closed = true;
        return Change.CLOSED;
    }

    synchronized List<Resident> active() {
        return active;
    }

    synchronized long content() {
        return content;
    }

    synchronized void close() {
        closed = true;
        active = List.of();
    }

    /** Called only from actual owned Process.onExit; EOF/CLOSED alone cannot authorize cleanup. */
    synchronized void processExited() {
        childGone = true;
        close();
    }

    private long nextTransaction() {
        require(transaction < Long.MAX_VALUE - 1, "Parent transaction exhausted");
        return ++transaction;
    }

    private void requireIdentity(Ticket value) {
        require(
                value != null && value.owner == owner && value == ticket,
                "Expired tile reservation");
    }

    private void requireTicket(Ticket value) {
        require(
                !closed && value != null && value.owner == owner && value == ticket,
                "Expired tile reservation");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
