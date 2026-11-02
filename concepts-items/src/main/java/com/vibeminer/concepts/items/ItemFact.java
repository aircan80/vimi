package com.vibeminer.concepts.items;

import java.util.List;

public record ItemFact(
        String key, ItemFactState state, ItemFactAgreement agreement, List<ItemFactValue> values) {
    public ItemFact {
        values = List.copyOf(values);
    }
}
