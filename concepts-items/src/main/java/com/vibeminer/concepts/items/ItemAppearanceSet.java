package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemAppearanceSet(
        ItemAppearancesState state,
        List<ItemAppearanceEntry> rows,
        ItemDefaultAppearanceState defaultState,
        Integer defaultIndex,
        int matches,
        TableTypes.Entry table,
        ItemModelRefs models,
        String code,
        String message) {
    public ItemAppearanceSet {
        rows = List.copyOf(rows);
    }
}
