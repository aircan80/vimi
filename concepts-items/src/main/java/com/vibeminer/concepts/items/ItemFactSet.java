package com.vibeminer.concepts.items;

import java.util.List;

public record ItemFactSet(
        ItemFactsState state,
        ItemSparseState sparse,
        List<ItemFact> facts,
        String code,
        String message) {
    public ItemFactSet {
        facts = List.copyOf(facts);
    }
}
