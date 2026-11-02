package com.vibeminer.concepts.items;

import com.vibeminer.formats.models.BuildModelTypes;
import java.time.Instant;
import java.util.List;

public record ItemModelRef(
        long fileDataId,
        ItemModelRefState state,
        BuildModelTypes.ReadState read,
        List<ItemModelTexture> textures,
        int skinFileDataIds,
        String code,
        String message,
        Instant observedAt,
        List<String> keyNames) {
    public ItemModelRef {
        textures = textures == null ? null : List.copyOf(textures);
        keyNames = keyNames == null ? null : List.copyOf(keyNames);
    }
}
