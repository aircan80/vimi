package com.vibeminer.formats.tables;

import java.util.LinkedHashMap;
import java.util.Map;

/** Service-wide LRU bounded by both table count and aggregate protected identity count. */
final class ProtectedIds {
    static final int MAX_TABLES = 32, MAX_IDS = 1 << 18;
    private final LinkedHashMap<String, Map<Long, String>> tables =
            new LinkedHashMap<>(32, .75f, true);
    private int held;

    synchronized Map<Long, String> get(String identity) {
        return tables.get(identity);
    }

    synchronized void put(String identity, Map<Long, String> ids) {
        if (ids.size() > MAX_IDS) {
            return;
        }
        var prior = tables.remove(identity);
        if (prior != null) {
            held -= prior.size();
        }
        tables.put(identity, Map.copyOf(ids));
        held += ids.size();
        while (tables.size() > MAX_TABLES || held > MAX_IDS) {
            String oldest = tables.keySet().iterator().next();
            held -= tables.remove(oldest).size();
        }
    }
}
