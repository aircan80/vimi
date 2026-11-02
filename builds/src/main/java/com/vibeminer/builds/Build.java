package com.vibeminer.builds;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.builds.BuildTypes.BuildMetadata;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.builds.BuildTypes.FileResolution;

/** An immutable opened build; resolution/listing never fetches. */
public interface Build {
    BuildRef build();

    BuildMetadata metadata();

    CdnContext cdn();

    FileResolution resolve(long fileDataId);

    FileResolution resolve(String path);

    Iterable<Long> fileDataIds(String locale, long fromId);

    /** Published encoded variants across all locales, without payload I/O. */
    default java.util.List<BuildContent.Entry> encodedContentPage(int offset, int limit) {
        return BuildContent.page(this, offset, limit);
    }

    default Iterable<Long> fileDataIds(String locale) {
        return fileDataIds(locale, 0);
    }
}
