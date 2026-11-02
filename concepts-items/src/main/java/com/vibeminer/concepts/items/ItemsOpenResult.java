package com.vibeminer.concepts.items;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes.FilesOpenState;
import java.time.Instant;

public record ItemsOpenResult(
        BuildRef build,
        String locale,
        FilesOpenState state,
        BuildItems items,
        String code,
        String message,
        Instant observedAt) {}
