package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.generated.GeneratedTableSource;
import org.junit.jupiter.api.Test;

final class BackgroundBuildsTest {
    private static GeneratedTableSource source(int id) {
        return new GeneratedTableSource(
                null,
                String.format(java.util.Locale.ROOT, "%032x", id),
                "definition",
                new Db2Types.Limits(),
                null);
    }

    @Test
    void directReadBuildsAreBoundedAndFailuresRetireWhenSourceSucceeds() {
        var builds = new BackgroundBuilds();
        for (int i = 0; i < 8; i++) {
            assertTrue(builds.tryStart(source(i)));
        }
        assertFalse(builds.tryStart(source(9)));
        assertFalse(builds.tryStart(source(0)));
        builds.end(source(0), false, true);
        assertTrue(builds.hasFailed(source(0)));
        assertTrue(builds.tryStart(source(9)));
        builds.stored(source(0));
        assertFalse(builds.hasFailed(source(0)));
        for (int i = 10; i < 80; i++) {
            builds.end(source(i), false, true);
        }
        assertFalse(builds.hasFailed(source(10)));
        assertTrue(builds.hasFailed(source(79)));
    }
}
