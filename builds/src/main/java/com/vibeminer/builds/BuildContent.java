package com.vibeminer.builds;

import com.vibeminer.store.ObjectKey;
import java.util.ArrayList;
import java.util.List;

/** Local metadata export: all published ENCODING variants, not a selected-locale install. */
public final class BuildContent {
    private BuildContent() {}

    public record Entry(ObjectKey key, long publishedBytes) {}

    public static List<Entry> page(Build build, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 4096) {
            throw new IllegalArgumentException(
                    "Content pages require offset >= 0 and 1..4096 rows");
        }
        if (!(build instanceof LedgerSource source)) {
            throw new IllegalArgumentException("Build has no published object ledger");
        }
        var ledger = source.ledger();
        var result = new ArrayList<Entry>();
        for (int i = offset; i < Math.min((long) ledger.count(), (long) offset + limit); i++) {
            result.add(
                    new Entry(
                            new ObjectKey(ObjectKey.ENCODED, ledger.key(i).bytes()),
                            ledger.size(i)));
        }
        return List.copyOf(result);
    }
}
