package com.vibeminer.formats.tables;

import com.vibeminer.formats.generated.GeneratedTableSource;
import java.util.HashSet;
import java.util.LinkedHashMap;

/** Service-wide bounded direct-read background work and remembered failures. */
final class BackgroundBuilds {
    static final int MAX_RUNNING = 8, MAX_FAILED = 64;
    private final HashSet<GeneratedTableSource> running = new HashSet<>();
    private final LinkedHashMap<GeneratedTableSource, Boolean> failed = new LinkedHashMap<>();

    synchronized boolean hasFailed(GeneratedTableSource source) {
        return failed.containsKey(source);
    }

    synchronized boolean tryStart(GeneratedTableSource source) {
        return running.size() < MAX_RUNNING && running.add(source);
    }

    synchronized void end(GeneratedTableSource source, boolean stored, boolean failure) {
        running.remove(source);
        if (stored) {
            failed.remove(source);
        } else if (failure && !failed.containsKey(source)) {
            failed.put(source, true);
            while (failed.size() > MAX_FAILED) {
                failed.remove(failed.keySet().iterator().next());
            }
        }
    }

    synchronized void stored(GeneratedTableSource source) {
        failed.remove(source);
    }

    synchronized int running() {
        return running.size();
    }
}
