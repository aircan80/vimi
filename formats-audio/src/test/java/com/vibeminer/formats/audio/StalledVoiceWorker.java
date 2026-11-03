package com.vibeminer.formats.audio;

/** Intentionally never consumes stdin, exercising blocked-pipe cancellation in the parent. */
public final class StalledVoiceWorker {
    private StalledVoiceWorker() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(30_000);
    }
}
