package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.store.StoreException;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** A full store work queue is transient (BUSY), never a reason to treat data as unusable. */
final class StoreCodeMappingTest {
    private static final List<UnaryOperator<Throwable>> MAPPERS =
            List.of(DiskGeneratedStore::map, PackGeneratedStore::map, SqliteGeneratedStore::map);

    private static String code(UnaryOperator<Throwable> mapper, String storeCode) {
        var mapped = mapper.apply(new StoreException(storeCode, "scripted"));
        return ((GeneratedStoreException) mapped).code();
    }

    @Test
    void queueFullAndBusyAreTransientWhileUnknownCodesStayUnusable() {
        for (var mapper : MAPPERS) {
            assertEquals(GeneratedTypes.BUSY, code(mapper, "OBJECT_STORE_QUEUE_FULL"));
            assertEquals(GeneratedTypes.BUSY, code(mapper, "OBJECT_STORE_BUSY"));
            assertEquals(GeneratedTypes.LIMIT, code(mapper, "OBJECT_STORE_LIMIT"));
            assertEquals(GeneratedTypes.UNUSABLE, code(mapper, "OBJECT_STORE_SOMETHING_ELSE"));
        }
    }
}
