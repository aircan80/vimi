package com.vibeminer.concepts.items;

import java.util.List;

public record ItemModelRefs(
        ItemModelsState state, List<ItemModelRef> models, String code, String message) {
    public ItemModelRefs {
        models = List.copyOf(models);
    }

    public static final ItemModelRefs NONE =
            new ItemModelRefs(ItemModelsState.COMPLETE, List.of(), null, null);
}
