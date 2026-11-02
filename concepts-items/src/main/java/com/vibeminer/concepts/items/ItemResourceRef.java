package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemResourceRef(
        ItemResourceKind kind,
        int slot,
        Long resourceId,
        ItemResourceState state,
        boolean complete,
        List<ItemResourceFile> files,
        int matches,
        TableTypes.Entry table,
        List<String> keyNames,
        String code,
        String message) {
    public ItemResourceRef {
        files = List.copyOf(files);
        keyNames = keyNames == null ? null : List.copyOf(keyNames);
    }
}
