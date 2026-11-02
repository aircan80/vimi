package com.vibeminer.concepts.items;

public record ItemQuery(String query, Long exactId, int limit, String after) {
    public ItemQuery() {
        this("", null, 50, null);
    }

    public ItemQuery(String query) {
        this(query, null, 50, null);
    }
}
