package com.vibeminer.concepts.items;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;

class RealItemsContractTest implements ItemsContract {
    public BuildRef build() {
        return ItemsWorld.BUILD;
    }

    public Items createItems() {
        return new ItemsService(
                ItemsWorld.data(
                        List.of(
                                ItemsWorld.row(0, "0", "2", "7", "13", "0"),
                                ItemsWorld.row(2, "2", "2", "7", "13", "0"),
                                ItemsWorld.row(0xffffffffL, "4294967295", "2", "7", "13", "0")),
                        List.of(
                                ItemsWorld.row(0, "0", "Sword", "4", "500", "13"),
                                ItemsWorld.row(2, "2", " ", "4", "500", "13"),
                                ItemsWorld.row(0xffffffffL, "4294967295", "", "4", "500", "13"))),
                new MemoryFiles(),
                new BlpImages(new MemoryGeneratedStore()));
    }
}
