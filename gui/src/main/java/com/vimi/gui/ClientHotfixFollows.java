package com.vimi.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Session state for "Client updated to build N" prompts. Each move (old build to new build) is
 * offered at most once while a prompt or apply for it is open, never again after the user kept
 * the current import, and never again after it was applied. A different target build is a new
 * move and prompts again.
 */
final class ClientHotfixFollows {
    private final Set<String> pending = new HashSet<>(), declined = new HashSet<>(),
            applied = new HashSet<>();

    /**
     * Moves to offer for one refresh. Each returned move is pending until {@link #declined} or
     * {@link #finished}; offering the same move again returns nothing meanwhile.
     */
    synchronized List<ClientHotfixDetails.BuildFollow> offer(
            List<ClientHotfixStore.RefreshResult> results,
            List<BuildInfo> builds,
            List<PreparationHistory.Receipt> receipts) {
        var offered = new ArrayList<ClientHotfixDetails.BuildFollow>();
        for (var result : results) {
            var follow = ClientHotfixDetails.buildFollow(result, builds, receipts);
            if (follow.isEmpty()) continue;
            String key = follow.get().key();
            if (pending.contains(key) || declined.contains(key) || applied.contains(key)) continue;
            pending.add(key);
            offered.add(follow.get());
        }
        return List.copyOf(offered);
    }

    /** The user kept the current import; this move is not offered again this session. */
    synchronized void declined(ClientHotfixDetails.BuildFollow follow) {
        pending.remove(follow.key());
        declined.add(follow.key());
    }

    /**
     * The apply finished. A completed move is not offered again; a failed one that changed
     * nothing may be offered by a later refresh.
     */
    synchronized void finished(ClientHotfixDetails.BuildFollow follow, boolean completed) {
        pending.remove(follow.key());
        if (completed) applied.add(follow.key());
    }
}
