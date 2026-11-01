package com.vibeminer.store;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** Successful small whole-read SHA proofs, scoped to a channel incarnation and immutable row. */
final class SessionHashes {
    static final int CAPACITY = 1_048_576;
    static final long MAX_LENGTH = 1L << 20;

    record Identity(
            Object incarnation,
            int pack,
            long offset,
            long length,
            ObjectKey key,
            long sha0,
            long sha1,
            long sha2,
            long sha3) {
        static Identity of(Object incarnation, StoreIndex.Row row) {
            ByteBuffer sha = ByteBuffer.wrap(row.sha());
            return new Identity(
                    incarnation,
                    row.pack(),
                    row.offset(),
                    row.length(),
                    row.key(),
                    sha.getLong(),
                    sha.getLong(),
                    sha.getLong(),
                    sha.getLong());
        }
    }

    record Check(Object generation, Identity identity) {}

    private final int capacity;
    private final Set<Identity> verified = new HashSet<>();
    private Object generation = new Object();

    SessionHashes() {
        this(CAPACITY);
    }

    SessionHashes(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Session SHA capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Null means an existing proof applies; misses hash outside this short metadata lock. */
    synchronized Check begin(Identity identity) {
        return verified.contains(identity) ? null : new Check(generation, identity);
    }

    synchronized void verified(Check check) {
        if (check.generation() != generation || verified.contains(check.identity())) {
            return;
        }
        if (verified.size() >= capacity) {
            clear();
        }
        verified.add(check.identity());
    }

    synchronized void clear() {
        verified.clear();
        generation = new Object();
    }

    synchronized int size() {
        return verified.size();
    }
}
