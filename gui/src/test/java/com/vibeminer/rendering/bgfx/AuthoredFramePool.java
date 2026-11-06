package com.vibeminer.rendering.bgfx;

/** Test access to a live session's parent frame-array pool; no production entry point. */
public final class AuthoredFramePool {
    private AuthoredFramePool() {}

    /** Pixel arrays the session's frame reader has allocated so far (reuses excluded). */
    public static long allocations(WorldStreamTransport.Session session) throws Exception {
        var field = WorldStreamTransport.Session.class.getDeclaredField("owner");
        field.setAccessible(true);
        var owner = (WorldStreamLaunchOwner.Handle) field.get(session);
        return owner.files().frames().pool().allocations();
    }
}
