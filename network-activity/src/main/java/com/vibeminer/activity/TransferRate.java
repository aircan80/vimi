package com.vibeminer.activity;

import java.util.ArrayDeque;
import java.util.OptionalDouble;

/** Pure bounded sampler. Call at most four times a second; reset after a hidden interval. */
public final class TransferRate {
    private record Sample(long time, long bytes) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public void reset() {
        samples.clear();
    }

    public OptionalDouble sample(NetworkActivity.Snapshot snapshot) {
        long now = snapshot.nanoTime();
        Sample last = samples.peekLast();
        if (last != null && (now <= last.time() || snapshot.receivedBytes() < last.bytes())) {
            reset();
        }
        while (!samples.isEmpty() && now - samples.getFirst().time() > 2_000_000_000L) {
            samples.removeFirst();
        }
        while (samples.size() >= 9) {
            samples.removeFirst();
        }
        samples.addLast(new Sample(now, snapshot.receivedBytes()));
        Sample first = samples.getFirst();
        long elapsed = now - first.time();
        return elapsed <= 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(
                        (snapshot.receivedBytes() - first.bytes()) * 1_000_000_000.0 / elapsed);
    }
}
