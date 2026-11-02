package com.vibeminer.concepts.items;

import com.vibeminer.formats.models.BuildModelTypes;

public record ItemModelTexture(
        int ordinal, Long type, Long fileDataId, BuildModelTypes.ReferenceState state) {}
