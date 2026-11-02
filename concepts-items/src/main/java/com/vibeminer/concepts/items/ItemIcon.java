package com.vibeminer.concepts.items;

import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImageTypes;
import java.time.Instant;
import java.util.List;

public record ItemIcon(
        ItemIconState state,
        Long fileDataId,
        BlpImageTypes.State image,
        BlpImageTypes.Header header,
        Blp2Types.Encoding encoding,
        Integer mipCount,
        FileReadState fileState,
        List<String> holeKeys,
        String code,
        String message,
        Instant observedAt,
        String contentKey) {
    public ItemIcon {
        holeKeys = holeKeys == null ? null : List.copyOf(holeKeys);
    }
}
