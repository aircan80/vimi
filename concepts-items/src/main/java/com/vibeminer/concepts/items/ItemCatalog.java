package com.vibeminer.concepts.items;

import java.util.List;

/** Complete readable Item inventory for one immutable build/locale workspace, without media. */
public record ItemCatalog(
        ItemsPageState state,
        List<ItemListLabels> entries,
        String identity,
        String message,
        boolean restored) {
    public ItemCatalog {
        entries = List.copyOf(entries);
    }
}
