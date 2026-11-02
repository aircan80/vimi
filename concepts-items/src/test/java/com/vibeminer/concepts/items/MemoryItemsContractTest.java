package com.vibeminer.concepts.items;

import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.List;

class MemoryItemsContractTest implements ItemsContract {
    public BuildRef build() {
        return ItemsWorld.BUILD;
    }

    public Items createItems() {
        return new MemoryItems()
                .add(
                        build(),
                        "enUS",
                        List.of(
                                new MemoryItems.FakeItem(
                                        0, new ItemName(ItemNameState.VALID, "Sword")),
                                new MemoryItems.FakeItem(2, new ItemName(ItemNameState.VALID, " ")),
                                new MemoryItems.FakeItem(
                                        0xffffffffL, new ItemName(ItemNameState.EMPTY, ""))));
    }
}
