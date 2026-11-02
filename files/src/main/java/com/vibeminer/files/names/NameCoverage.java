package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import java.util.Iterator;
import java.util.List;

/** Metadata-only merge of distinct locale-selected FileDataIDs and one immutable names snapshot. */
public final class NameCoverage {
    private NameCoverage() {}

    public record Result(long files, long named) {}

    public static Result measure(Iterator<Long> files, LoadedNames names, Cancellation token) {
        long total = 0, named = 0, previous = 0, from = 0;
        int at = 0;
        List<NamesTypes.NameEntry> page = List.of();
        boolean exhausted = false;
        while (files.hasNext()) {
            token.check();
            long id = files.next();
            if (id <= previous || id > 0xffffffffL) {
                throw new IllegalArgumentException("File IDs must ascend");
            }
            previous = id;
            total++;
            while (true) {
                if (at == page.size() && !exhausted) {
                    page = names.from(from, 256, token).join();
                    at = 0;
                    if (page.size() > 256) {
                        throw new IllegalArgumentException("Names page exceeds bound");
                    }
                    long last = from - 1;
                    for (var entry : page) {
                        if (entry.id() <= last
                                || entry.id() > 0xffffffffL
                                || entry.name() == null
                                || entry.name().isBlank()) {
                            throw new IllegalArgumentException("Invalid names snapshot page");
                        }
                        last = entry.id();
                    }
                    if (page.isEmpty()) {
                        exhausted = true;
                    } else {
                        if (last == 0xffffffffL) {
                            exhausted = true;
                        } else {
                            from = last + 1;
                        }
                    }
                }
                if (at == page.size()) {
                    break;
                }
                long candidate = page.get(at).id();
                if (candidate < id) {
                    at++;
                    continue;
                }
                if (candidate == id) {
                    named++;
                }
                break;
            }
        }
        token.check();
        if (names.isDamaged()) {
            throw new IllegalStateException("Names snapshot became unreadable");
        }
        return new Result(total, named);
    }
}
